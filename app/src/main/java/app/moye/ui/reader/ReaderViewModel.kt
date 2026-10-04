package app.moye.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.moye.core.model.Chapter
import app.moye.core.model.ContentError
import app.moye.core.model.ReaderCommand
import app.moye.core.model.ReadingUnit
import app.moye.core.model.WritingMode
import app.moye.core.model.effectiveWritingMode
import app.moye.core.settings.FileSettingsStore
import app.moye.core.settings.ReaderSettings
import app.moye.core.text.ChapterNavigation
import app.moye.core.text.PageComposer
import app.moye.core.text.PageReveal
import app.moye.core.text.PlaybackTiming
import app.moye.core.text.ReadingPage
import app.moye.core.text.RevealStep
import app.moye.core.text.SentenceReveal
import app.moye.core.text.SentenceSegmenter
import app.moye.core.text.TypewriterTiming
import app.moye.core.time.ReadingTimer
import app.moye.data.BookRepository
import app.moye.data.ContentLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ReaderStatus {
    LOADING,
    READY,
    ERROR,
}

data class ReaderUiState(
    val status: ReaderStatus = ReaderStatus.LOADING,
    val error: ContentError? = null,
    val title: String = "",
    val settings: ReaderSettings = ReaderSettings(),
    val writingMode: WritingMode = WritingMode.HORIZONTAL,
    val writingModeLocked: Boolean = false,
    val text: String = "",
    val units: List<ReadingUnit> = emptyList(),
    val pages: List<ReadingPage> = emptyList(),
    val pageIndex: Int = 0,
    val revealedCount: Int = 0,
    val typedChars: Int = 0,
    val typing: Boolean = false,
    val layoutChars: Int = -1,
    val layoutLines: Int = -1,
    val chapters: List<Chapter> = emptyList(),
    val offset: Int = 0,
    val playing: Boolean = false,
)

class ReaderViewModel(
    private val repository: BookRepository,
    private val settingsStore: FileSettingsStore,
    private val bookId: String,
    private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : ViewModel() {
    private val timer = ReadingTimer()
    private val persistMutex = Mutex()
    private var screenVisible = false
    private var activityResumed = false
    private var playJob: Job? = null
    private var typeJob: Job? = null
    private var typeGeneration = 0
    private var layoutGeneration = 0
    private var playbackGeneration = 0

    private val _state = MutableStateFlow(ReaderUiState(settings = settingsStore.load()))
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { repository.loadContent(bookId) }
            when (loaded) {
                is ContentLoad.Failed -> _state.update {
                    it.copy(status = ReaderStatus.ERROR, error = loaded.error)
                }
                is ContentLoad.Ready -> {
                    val settings = settingsStore.load()
                    val text = loaded.book.text
                    _state.update {
                        it.copy(
                            status = ReaderStatus.READY,
                            title = loaded.record.title,
                            settings = settings,
                            writingMode = effectiveWritingMode(loaded.record.declaredWritingMode, settings.txtWritingMode),
                            writingModeLocked = loaded.record.declaredWritingMode != null,
                            text = text,
                            units = SentenceSegmenter.segment(text),
                            chapters = loaded.book.chapters,
                            offset = loaded.record.charOffset.coerceIn(0, text.length.toLong()).toInt(),
                            layoutChars = -1,
                            layoutLines = -1,
                            pages = emptyList(),
                        )
                    }
                    syncTimer()
                }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(15_000)
                flush()
            }
        }
    }

    fun onScreenVisible(visible: Boolean, activityAlreadyResumed: Boolean) {
        screenVisible = visible
        if (visible) {
            activityResumed = activityAlreadyResumed
            refreshTitle()
            syncTimer()
            if (_state.value.typing) restartTyping()
        } else {
            pausePlayback()
            typeJob?.cancel()
            syncTimer()
        }
    }

    fun onActivityResume() {
        activityResumed = true
        syncTimer()
        if (_state.value.typing) restartTyping()
    }

    fun onActivityPause() {
        activityResumed = false
        pausePlayback()
        typeJob?.cancel()
        syncTimer()
        flushBlocking()
    }

    private fun refreshTitle() {
        if (_state.value.status != ReaderStatus.READY) return
        viewModelScope.launch {
            val record = withContext(Dispatchers.IO) { repository.find(bookId) } ?: return@launch
            _state.update { it.copy(title = record.title) }
        }
    }

    fun onCommand(command: ReaderCommand): Boolean {
        if (_state.value.status != ReaderStatus.READY) return false
        return when (command) {
            ReaderCommand.PREVIOUS -> {
                turnPage(forward = false)
                true
            }
            ReaderCommand.NEXT -> {
                turnPage(forward = true)
                true
            }
            ReaderCommand.TOGGLE_PLAYBACK -> {
                togglePlayback()
                true
            }
        }
    }

    fun bindLayout(charsPerLine: Int, linesPerPage: Int) {
        val snapshot = _state.value
        if (snapshot.status != ReaderStatus.READY) return
        val chars = charsPerLine.coerceAtLeast(1)
        val lines = linesPerPage.coerceAtLeast(1)
        if (snapshot.layoutChars == chars && snapshot.layoutLines == lines && snapshot.pages.isNotEmpty()) return
        val generation = ++layoutGeneration
        val opening = snapshot.pages.isEmpty()
        val units = snapshot.units
        val textLength = snapshot.text.length
        viewModelScope.launch {
            val pages = withContext(Dispatchers.Default) {
                PageComposer.compose(units, chars, lines, textLength)
            }
            if (generation != layoutGeneration || _state.value.status != ReaderStatus.READY) return@launch
            val offset = _state.value.offset
            val index = SentenceReveal.pageIndexForOffset(pages, offset).coerceIn(0, pages.lastIndex)
            val restored = SentenceReveal.restore(pages[index], offset)
            val sentence = pages[index].sentences.getOrNull(restored.revealedCount - 1)
            val typeCurrent = opening && _state.value.settings.typewriterEnabled && sentence != null && sentence.text.isNotEmpty()
            val reveal = if (typeCurrent) {
                restored.copy(typedChars = 0, typing = true)
            } else {
                restored.copy(typing = false)
            }
            _state.update {
                it.copy(
                    pages = pages,
                    pageIndex = index,
                    revealedCount = reveal.revealedCount,
                    typedChars = reveal.typedChars,
                    typing = reveal.typing,
                    layoutChars = chars,
                    layoutLines = lines,
                    offset = reveal.anchorOffset,
                )
            }
            restartTyping()
            persistOffset()
            if (_state.value.playing) restartPlayback()
        }
    }

    fun onReadingTap() {
        val snapshot = _state.value
        if (snapshot.status != ReaderStatus.READY) return
        val page = snapshot.pages.getOrNull(snapshot.pageIndex) ?: return
        val playing = snapshot.playing
        when (val step = SentenceReveal.onTap(page, snapshot.toReveal(), snapshot.settings.typewriterEnabled)) {
            is RevealStep.Updated -> applyReveal(step.reveal)
            RevealStep.NextPage -> advancePage(forward = true)
        }
        if (playing) restartPlayback()
    }

    fun turnPage(forward: Boolean): Boolean {
        val moved = advancePage(forward)
        if (_state.value.playing) restartPlayback()
        return moved
    }

    private fun advancePage(forward: Boolean): Boolean {
        val snapshot = _state.value
        if (snapshot.status != ReaderStatus.READY || snapshot.pages.isEmpty()) return false
        val target = snapshot.pageIndex + if (forward) 1 else -1
        if (target !in snapshot.pages.indices) return false
        enterPage(target)
        return true
    }

    fun togglePlayback() {
        if (_state.value.playing) pausePlayback() else startPlayback()
    }

    fun seek(offset: Int) {
        val snapshot = _state.value
        val clamped = offset.coerceIn(0, snapshot.text.length)
        val pages = snapshot.pages
        if (pages.isEmpty()) {
            _state.update { it.copy(offset = clamped, typing = false) }
            typeJob?.cancel()
            persistOffset()
            return
        }
        val index = SentenceReveal.pageIndexForOffset(pages, clamped)
        val reveal = SentenceReveal.restore(pages[index], clamped)
        _state.update {
            it.copy(
                offset = reveal.anchorOffset,
                pageIndex = index,
                revealedCount = reveal.revealedCount,
                typedChars = reveal.typedChars,
                typing = false,
            )
        }
        typeJob?.cancel()
        persistOffset()
        if (_state.value.playing) restartPlayback()
    }

    fun previousChapter() {
        val target = ChapterNavigation.previousTarget(_state.value.chapters, _state.value.offset) ?: return
        seek(target)
    }

    fun nextChapter() {
        val target = ChapterNavigation.nextTarget(_state.value.chapters, _state.value.offset) ?: return
        seek(target)
    }

    fun goToChapter(startOffset: Int) {
        seek(startOffset)
    }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        val updated = settingsStore.update(transform)
        _state.update { current ->
            val page = current.pages.getOrNull(current.pageIndex)
            val sentence = page?.sentences?.getOrNull(current.revealedCount - 1)
            val finishTyping = !updated.typewriterEnabled && current.typing && sentence != null
            current.copy(
                settings = updated,
                writingMode = if (current.writingModeLocked) current.writingMode else updated.txtWritingMode,
                typedChars = if (finishTyping) sentence.text.length else current.typedChars,
                typing = if (finishTyping) false else current.typing,
            )
        }
        restartTyping()
        if (_state.value.playing) restartPlayback() else playJob?.cancel()
    }

    private fun startPlayback() {
        if (_state.value.pages.isEmpty()) return
        _state.update { it.copy(playing = true) }
        restartPlayback()
    }

    private fun pausePlayback() {
        playbackGeneration++
        _state.update { it.copy(playing = false) }
        playJob?.cancel()
        playJob = null
    }

    private fun restartPlayback() {
        val generation = ++playbackGeneration
        playJob?.cancel()
        if (!_state.value.playing) {
            playJob = null
            return
        }
        playJob = viewModelScope.launch {
            while (isActive && generation == playbackGeneration && _state.value.playing) {
                if (_state.value.typing) {
                    delay(30)
                    continue
                }
                val snapshot = _state.value
                val page = snapshot.pages.getOrNull(snapshot.pageIndex) ?: break
                if (page.sentences.isEmpty()) {
                    if (generation == playbackGeneration) _state.update { it.copy(playing = false) }
                    break
                }
                val sentence = page.sentences.getOrNull((snapshot.revealedCount - 1).coerceAtLeast(0))
                val marker = Triple(snapshot.pageIndex, snapshot.revealedCount, snapshot.typedChars)
                delay(PlaybackTiming.dwellMillis(sentence?.text.orEmpty(), snapshot.settings.playbackSpeed))
                if (!isActive || generation != playbackGeneration || !_state.value.playing) break
                val now = _state.value
                if (now.typing) continue
                if (Triple(now.pageIndex, now.revealedCount, now.typedChars) != marker) continue
                val nowPage = now.pages.getOrNull(now.pageIndex) ?: break
                when (val step = SentenceReveal.onTap(nowPage, now.toReveal(), now.settings.typewriterEnabled)) {
                    is RevealStep.Updated -> applyReveal(step.reveal)
                    RevealStep.NextPage -> {
                        if (!advancePage(forward = true) && generation == playbackGeneration) {
                            _state.update { it.copy(playing = false) }
                            break
                        }
                    }
                }
            }
        }
    }

    private fun restartTyping() {
        val generation = ++typeGeneration
        typeJob?.cancel()
        val snapshot = _state.value
        if (!snapshot.typing || !snapshot.settings.typewriterEnabled) return
        val delayMs = TypewriterTiming.millisPerCharacter(snapshot.settings.typewriterSpeed)
        val pageIndex = snapshot.pageIndex
        val revealedCount = snapshot.revealedCount
        typeJob = viewModelScope.launch {
            while (isActive && generation == typeGeneration) {
                delay(delayMs)
                if (generation != typeGeneration) break
                val current = _state.value
                if (!current.typing || current.pageIndex != pageIndex || current.revealedCount != revealedCount) break
                val page = current.pages.getOrNull(current.pageIndex) ?: break
                val next = SentenceReveal.tick(page, current.toReveal())
                if (generation != typeGeneration) break
                _state.update { state ->
                    if (state.pageIndex != pageIndex || state.revealedCount != revealedCount || !state.typing) {
                        state
                    } else {
                        state.copy(typedChars = next.typedChars, typing = next.typing)
                    }
                }
                if (!next.typing) break
            }
        }
    }

    private fun enterPage(index: Int) {
        val page = _state.value.pages[index]
        val reveal = SentenceReveal.enter(page, _state.value.settings.typewriterEnabled)
        _state.update {
            it.copy(
                pageIndex = index,
                revealedCount = reveal.revealedCount,
                typedChars = reveal.typedChars,
                typing = reveal.typing,
                offset = reveal.anchorOffset,
            )
        }
        persistOffset()
        restartTyping()
    }

    private fun applyReveal(reveal: PageReveal) {
        _state.update {
            it.copy(
                revealedCount = reveal.revealedCount,
                typedChars = reveal.typedChars,
                typing = reveal.typing,
                offset = reveal.anchorOffset,
            )
        }
        persistOffset()
        restartTyping()
    }

    private fun syncTimer() {
        val shouldRun = screenVisible && activityResumed && _state.value.status == ReaderStatus.READY
        if (shouldRun) timer.resume(now()) else if (timer.isRunning) timer.pause(now())
    }

    private fun persistOffset() {
        viewModelScope.launch { flush() }
    }

    private suspend fun flush() {
        persistMutex.withLock {
            val delta = timer.takeDelta(now())
            val snapshot = _state.value
            if (snapshot.status != ReaderStatus.READY) return@withLock
            withContext(Dispatchers.IO) {
                if (delta > 0L) repository.addReadingTime(bookId, delta)
                repository.updateProgress(bookId, snapshot.offset.toLong(), snapshot.text.length.toLong())
            }
        }
    }

    private fun flushBlocking() {
        runBlocking { flush() }
    }

    override fun onCleared() {
        playJob?.cancel()
        typeJob?.cancel()
        if (timer.isRunning) timer.pause(now())
        flushBlocking()
        super.onCleared()
    }
}

private fun ReaderUiState.toReveal(): PageReveal {
    return PageReveal(
        revealedCount = revealedCount,
        typedChars = typedChars,
        typing = typing,
        anchorOffset = offset,
    )
}

class ReaderViewModelFactory(
    private val repository: BookRepository,
    private val settingsStore: FileSettingsStore,
    private val bookId: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ReaderViewModel(repository, settingsStore, bookId) as T
    }
}
