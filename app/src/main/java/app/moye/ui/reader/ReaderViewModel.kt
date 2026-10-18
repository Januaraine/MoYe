package app.moye.ui.reader

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.moye.core.model.Chapter
import app.moye.core.model.ContentError
import app.moye.core.model.Delivery
import app.moye.core.model.ReaderCommand
import app.moye.core.model.ReadingUnit
import app.moye.core.model.WritingMode
import app.moye.core.model.effectiveWritingMode
import app.moye.core.settings.FileSettingsStore
import app.moye.core.settings.ReaderSettings
import app.moye.core.text.AutoPlayGate
import app.moye.core.text.AutoPlayScheduler
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
    val controlsVisible: Boolean = false,
    val paged: Boolean = false,
    val pageCount: Int = 0,
    val pageImage: ImageBitmap? = null,
    val pageFailed: Boolean = false,
)

class ReaderViewModel(
    private val repository: BookRepository,
    private val settingsStore: FileSettingsStore,
    private val bookId: String,
    private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : ViewModel() {
    private val timer = ReadingTimer()
    private val persistMutex = Mutex()
    private var appliedLatin = -1f
    private var screenVisible = false
    private var activityResumed = false
    private var playJob: Job? = null
    private var typeJob: Job? = null
    private var autoPlay = AutoPlayGate()
    private val autoPlaySchedule = AutoPlayScheduler()
    private var typeGeneration = 0
    private var pageEpoch = 0
    private var furthestReachedOffset = -1
    private var layoutGeneration = 0
    private var pageGeneration = 0
    private var boundWidth = 0
    private var boundHeight = 0

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
                    if (loaded.book.delivery == Delivery.PAGES) {
                        val settings = settingsStore.load()
                        val count = loaded.book.pageCount.coerceAtLeast(0)
                        val index = loaded.record.charOffset
                            .coerceIn(0, (count - 1).coerceAtLeast(0).toLong())
                            .toInt()
                        _state.update {
                            it.copy(
                                status = ReaderStatus.READY,
                                title = loaded.record.title,
                                settings = settings,
                                paged = true,
                                pageCount = count,
                                pageIndex = index,
                                offset = index,
                                text = "",
                                units = emptyList(),
                                chapters = emptyList(),
                                pages = emptyList(),
                            )
                        }
                        syncTimer()
                        return@launch
                    }
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

    fun bindLayout(charsPerLine: Int, linesPerPage: Int, latinAdvance: Float = 0.5f) {
        val snapshot = _state.value
        if (snapshot.paged || snapshot.status != ReaderStatus.READY) return
        val chars = charsPerLine.coerceAtLeast(1)
        val lines = linesPerPage.coerceAtLeast(1)
        val advance = latinAdvance.coerceIn(0.2f, 1f)
        if (
            snapshot.layoutChars == chars &&
            snapshot.layoutLines == lines &&
            appliedLatin == advance &&
            snapshot.pages.isNotEmpty()
        ) {
            return
        }
        val generation = ++layoutGeneration
        val opening = snapshot.pages.isEmpty()
        val units = snapshot.units
        val textLength = snapshot.text.length
        viewModelScope.launch {
            val pages = withContext(Dispatchers.Default) {
                PageComposer.compose(units, chars, lines, textLength, advance)
            }
            if (generation != layoutGeneration || _state.value.status != ReaderStatus.READY) return@launch
            val latest = _state.value
            val offset = latest.offset
            furthestReachedOffset = furthestReachedOffset.coerceAtLeast(offset)
            val index = SentenceReveal.pageIndexForOffset(pages, offset).coerceIn(0, pages.lastIndex)
            val page = pages[index]
            val restored = SentenceReveal.restore(page, offset, latest.settings.typewriterEnabled)
            val sentence = page.sentences.getOrNull(restored.revealedCount - 1)
            val previous = latest.pages.getOrNull(latest.pageIndex)
            val keepFull = previous != null && latest.isFullyRevealed() && latest.offset <= previous.start
            val typeFirstSentence = !keepFull &&
                opening &&
                restored.revealedCount <= 1 &&
                latest.settings.typewriterEnabled &&
                sentence != null &&
                sentence.text.isNotEmpty()
            val reveal = when {
                keepFull -> SentenceReveal.revisit(page)
                typeFirstSentence -> SentenceReveal.enter(page, true)
                else -> restored.copy(typing = false)
            }
            pageEpoch++
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
            appliedLatin = advance
            restartTyping()
            persistOffset()
            startPlaybackLoop()
        }
    }

    fun onPageBounds(widthPx: Int, heightPx: Int) {
        val snapshot = _state.value
        if (!snapshot.paged || snapshot.status != ReaderStatus.READY) return
        val width = widthPx.coerceIn(1, 2048)
        val height = heightPx.coerceIn(1, 2048)
        if (width == boundWidth && height == boundHeight && snapshot.pageImage != null) return
        boundWidth = width
        boundHeight = height
        showPage(snapshot.pageIndex, width, height)
    }

    fun onReadingTap() {
        val snapshot = _state.value
        if (snapshot.status != ReaderStatus.READY) return
        if (snapshot.paged) {
            turnPage(forward = true)
            return
        }
        val page = snapshot.pages.getOrNull(snapshot.pageIndex) ?: return
        when (val step = SentenceReveal.onTap(page, snapshot.toReveal(), snapshot.settings.typewriterEnabled, furthestReachedOffset)) {
            is RevealStep.Updated -> {
                coverCurrentSentence(page, snapshot.toReveal())
                pageEpoch++
                applyReveal(step.reveal)
                interruptAutoPlay()
            }
            RevealStep.NextPage -> {
                val target = snapshot.pageIndex + 1
                if (target !in snapshot.pages.indices) {
                    interruptAutoPlay()
                    return
                }
                coverCurrentSentence(page, snapshot.toReveal())
                openFirstSentence(target)
                interruptAutoPlay()
            }
            RevealStep.PreviousPage -> interruptAutoPlay()
        }
    }

    fun onPreviousSentence() {
        val snapshot = _state.value
        if (snapshot.status != ReaderStatus.READY) return
        if (snapshot.paged) {
            turnPage(forward = false)
            return
        }
        val page = snapshot.pages.getOrNull(snapshot.pageIndex) ?: return
        when (val step = SentenceReveal.onPrevious(page, snapshot.toReveal())) {
            is RevealStep.Updated -> {
                coverCurrentSentence(page, snapshot.toReveal())
                pageEpoch++
                applyReveal(step.reveal, allowRetreat = true)
                interruptAutoPlay()
            }
            RevealStep.PreviousPage -> {
                val target = snapshot.pageIndex - 1
                if (target !in snapshot.pages.indices) {
                    interruptAutoPlay()
                    return
                }
                coverCurrentSentence(page, snapshot.toReveal())
                val destination = snapshot.pages[target]
                val last = (destination.sentences.size - 1).coerceAtLeast(0)
                publishReveal(target, SentenceReveal.showSentence(destination, last, immediate = true))
                interruptAutoPlay()
            }
            RevealStep.NextPage -> interruptAutoPlay()
        }
    }

    fun toggleControls() {
        autoPlay = if (autoPlay.controlsOpen) autoPlay.hideControls() else autoPlay.showControls()
        publishAutoPlay()
        startPlaybackLoop()
    }

    fun hideControls() {
        if (!autoPlay.controlsOpen) return
        autoPlay = autoPlay.hideControls()
        publishAutoPlay()
        startPlaybackLoop()
    }

    fun turnPage(forward: Boolean): Boolean {
        val moved = movePage(forward)
        interruptAutoPlay()
        return moved
    }

    fun togglePlayback() {
        if (_state.value.paged) return
        if (autoPlay.armed) pausePlayback() else startPlayback()
    }

    fun seek(offset: Int) {
        val snapshot = _state.value
        val clamped = offset.coerceIn(0, snapshot.text.length)
        furthestReachedOffset = furthestReachedOffset.coerceAtLeast(clamped)
        val pages = snapshot.pages
        if (pages.isEmpty()) {
            _state.update { it.copy(offset = clamped, typing = false) }
            typeJob?.cancel()
            persistOffset()
            return
        }
        val index = SentenceReveal.pageIndexForOffset(pages, clamped)
        val reveal = SentenceReveal.restore(pages[index], clamped, snapshot.settings.typewriterEnabled)
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
        interruptAutoPlay()
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
            val showWholePage = !updated.typewriterEnabled && page != null && page.sentences.isNotEmpty()
            current.copy(
                settings = updated,
                writingMode = if (current.writingModeLocked) current.writingMode else updated.txtWritingMode,
                revealedCount = if (showWholePage) page.sentences.size else current.revealedCount,
                typedChars = if (showWholePage) page.sentences.last().text.length else current.typedChars,
                typing = if (showWholePage) false else current.typing,
            )
        }
        restartTyping()
        interruptAutoPlay()
    }

    private fun startPlayback() {
        if (_state.value.pages.isEmpty()) return
        autoPlay = autoPlay.start()
        publishAutoPlay()
        startPlaybackLoop()
    }

    private fun pausePlayback() {
        autoPlay = autoPlay.pause()
        publishAutoPlay()
        startPlaybackLoop()
    }

    private fun interruptAutoPlay() {
        autoPlay = autoPlay.interrupt()
        startPlaybackLoop()
    }

    private fun publishAutoPlay() {
        _state.update { it.copy(playing = autoPlay.armed, controlsVisible = autoPlay.controlsOpen) }
    }

    private fun startPlaybackLoop() {
        val gateTicket = autoPlay.token
        val previous = playJob
        playJob = null
        if (!autoPlay.isCurrent(gateTicket)) {
            autoPlaySchedule.clear()
            previous?.cancel()
            return
        }
        val next = viewModelScope.launch {
            while (isActive && autoPlay.isCurrent(gateTicket)) {
                val snapshot = _state.value
                val startedAt = now()
                val scheduleTicket = autoPlaySchedule.replace(
                    nowMillis = startedAt,
                    speed = snapshot.settings.playbackSpeed,
                    typewriterDurationMillis = remainingTypewriterMillis(snapshot),
                    typewriterEnabled = snapshot.settings.typewriterEnabled,
                )
                val wait = autoPlaySchedule.delayMillis(startedAt)
                if (wait > 0L) delay(wait)
                if (!isActive || !autoPlay.isCurrent(gateTicket) || !autoPlaySchedule.isCurrent(scheduleTicket)) return@launch
                while (isActive && autoPlay.isCurrent(gateTicket) && autoPlaySchedule.isCurrent(scheduleTicket) && _state.value.typing) {
                    delay(16)
                }
                if (!isActive || !autoPlay.isCurrent(gateTicket) || !autoPlaySchedule.isCurrent(scheduleTicket)) return@launch
                if (!advanceOneSentence()) return@launch
            }
        }
        playJob = next
        if (previous != null && previous != next) previous.cancel()
    }

    private fun advanceOneSentence(): Boolean {
        if (!autoPlay.isCurrent(autoPlay.token)) return false
        val epoch = pageEpoch
        val snapshot = _state.value
        val page = snapshot.pages.getOrNull(snapshot.pageIndex)
        if (snapshot.status != ReaderStatus.READY || page == null) {
            disarmPlayback()
            return false
        }
        when (val step = SentenceReveal.onAutoPlay(page, snapshot.toReveal(), snapshot.settings.typewriterEnabled)) {
            is RevealStep.Updated -> {
                if (epoch != pageEpoch || _state.value.pageIndex != snapshot.pageIndex) return true
                val sameSentence = step.reveal.revealedCount == snapshot.revealedCount &&
                    step.reveal.anchorOffset == snapshot.offset
                if (sameSentence) return true
                applyReveal(step.reveal)
                return true
            }
            RevealStep.NextPage -> {
                if (epoch != pageEpoch || _state.value.pageIndex != snapshot.pageIndex) return true
                if (!movePage(forward = true)) {
                    disarmPlayback()
                    return false
                }
                return true
            }
            RevealStep.PreviousPage -> return true
        }
    }

    private fun disarmPlayback() {
        autoPlay = autoPlay.pause()
        publishAutoPlay()
        autoPlaySchedule.clear()
    }

    private fun remainingTypewriterMillis(snapshot: ReaderUiState): Long {
        if (!snapshot.settings.typewriterEnabled || !snapshot.typing) return 0L
        val page = snapshot.pages.getOrNull(snapshot.pageIndex) ?: return 0L
        val sentence = page.sentences.getOrNull((snapshot.revealedCount - 1).coerceAtLeast(0)) ?: return 0L
        val unread = (sentence.text.length - snapshot.typedChars).coerceAtLeast(0)
        return unread * TypewriterTiming.millisPerCharacter(snapshot.settings.typewriterSpeed)
    }

    private fun movePage(forward: Boolean): Boolean {
        val snapshot = _state.value
        if (snapshot.paged) {
            if (snapshot.status != ReaderStatus.READY || snapshot.pageCount <= 0) return false
            val target = snapshot.pageIndex + if (forward) 1 else -1
            if (target !in 0 until snapshot.pageCount) return false
            _state.update {
                it.copy(pageIndex = target, offset = target, pageImage = null, pageFailed = false)
            }
            persistOffset()
            if (boundWidth > 0 && boundHeight > 0) showPage(target, boundWidth, boundHeight)
            return true
        }
        if (snapshot.status != ReaderStatus.READY || snapshot.pages.isEmpty()) return false
        val target = snapshot.pageIndex + if (forward) 1 else -1
        if (target !in snapshot.pages.indices) return false
        val leaving = snapshot.pages[snapshot.pageIndex]
        furthestReachedOffset = furthestReachedOffset.coerceAtLeast(SentenceReveal.coveredOffset(leaving))
        val targetPage = snapshot.pages[target]
        enterPage(target, SentenceReveal.opensInFull(forward, targetPage.start, furthestReachedOffset))
        return true
    }

    private fun restartTyping() {
        val generation = ++typeGeneration
        val epoch = pageEpoch
        typeJob?.cancel()
        val snapshot = _state.value
        if (!snapshot.typing || !snapshot.settings.typewriterEnabled) return
        val delayMs = TypewriterTiming.millisPerCharacter(snapshot.settings.typewriterSpeed)
        val pageIndex = snapshot.pageIndex
        val revealedCount = snapshot.revealedCount
        typeJob = viewModelScope.launch {
            while (isActive && generation == typeGeneration && epoch == pageEpoch) {
                delay(delayMs)
                if (generation != typeGeneration || epoch != pageEpoch) break
                val current = _state.value
                if (
                    !current.typing ||
                    current.pageIndex != pageIndex ||
                    current.revealedCount != revealedCount ||
                    current.isFullyRevealed()
                ) {
                    break
                }
                val page = current.pages.getOrNull(current.pageIndex) ?: break
                val next = SentenceReveal.tick(page, current.toReveal())
                if (generation != typeGeneration || epoch != pageEpoch) break
                _state.update { state ->
                    if (
                        generation != typeGeneration ||
                        epoch != pageEpoch ||
                        state.pageIndex != pageIndex ||
                        state.revealedCount != revealedCount ||
                        !state.typing ||
                        !state.settings.typewriterEnabled ||
                        state.isFullyRevealed()
                    ) {
                        state
                    } else {
                        state.copy(typedChars = next.typedChars, typing = next.typing)
                    }
                }
                if (!next.typing) break
            }
        }
    }

    private fun showPage(index: Int, width: Int, height: Int) {
        val generation = ++pageGeneration
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                repository.renderPage(bookId, index, width, height)
            }
            if (generation != pageGeneration) {
                bitmap?.recycle()
                return@launch
            }
            val image = bitmap?.asImageBitmap()
            _state.update { state ->
                if (state.pageIndex != index) state else state.copy(pageImage = image, pageFailed = image == null)
            }
        }
    }

    private fun enterPage(index: Int, openInFull: Boolean) {
        val page = _state.value.pages[index]
        val reveal = if (openInFull) {
            SentenceReveal.revisit(page)
        } else {
            SentenceReveal.enter(page, _state.value.settings.typewriterEnabled)
        }
        publishReveal(index, reveal)
    }

    private fun openFirstSentence(index: Int): Boolean {
        val page = _state.value.pages.getOrNull(index) ?: return false
        val first = page.sentences.firstOrNull()
        val seen = first != null && first.startOffset <= furthestReachedOffset
        val reveal = when {
            first == null -> SentenceReveal.enter(page, false)
            seen || !_state.value.settings.typewriterEnabled -> SentenceReveal.showSentence(page, 0, immediate = true)
            else -> SentenceReveal.enter(page, true)
        }
        publishReveal(index, reveal)
        return true
    }

    private fun publishReveal(pageIndex: Int, reveal: PageReveal) {
        pageEpoch++
        _state.value.pages.getOrNull(pageIndex)?.let { noteReveal(it, reveal) }
        _state.update {
            it.copy(
                pageIndex = pageIndex,
                revealedCount = reveal.revealedCount,
                typedChars = reveal.typedChars,
                typing = reveal.typing,
                offset = reveal.anchorOffset,
            )
        }
        persistOffset()
        restartTyping()
    }

    private fun applyReveal(reveal: PageReveal, allowRetreat: Boolean = false) {
        val epoch = pageEpoch
        val pageIndex = _state.value.pageIndex
        if (epoch != pageEpoch) return
        if (!allowRetreat && _state.value.wouldHideFullPage(reveal)) return
        val page = _state.value.pages.getOrNull(pageIndex)
        _state.update { state ->
            if (epoch != pageEpoch || state.pageIndex != pageIndex) {
                state
            } else if (!allowRetreat && state.wouldHideFullPage(reveal)) {
                state
            } else {
                state.copy(
                    revealedCount = reveal.revealedCount,
                    typedChars = reveal.typedChars,
                    typing = reveal.typing,
                    offset = reveal.anchorOffset,
                )
            }
        }
        if (epoch != pageEpoch || _state.value.pageIndex != pageIndex) return
        if (!allowRetreat && _state.value.wouldHideFullPage(reveal)) return
        if (page != null) noteReveal(page, reveal)
        persistOffset()
        restartTyping()
    }

    private fun coverCurrentSentence(page: ReadingPage, reveal: PageReveal) {
        if (reveal.revealedCount <= 0) return
        val sentence = page.sentences.getOrNull(reveal.revealedCount - 1) ?: return
        furthestReachedOffset = furthestReachedOffset.coerceAtLeast(
            (sentence.endOffset - 1).coerceAtLeast(sentence.startOffset),
        )
    }

    private fun noteReveal(page: ReadingPage, reveal: PageReveal) {
        if (reveal.revealedCount <= 0) return
        val sentence = page.sentences.getOrNull(reveal.revealedCount - 1) ?: return
        val covered = if (!reveal.typing && reveal.typedChars >= sentence.text.length) {
            (sentence.endOffset - 1).coerceAtLeast(sentence.startOffset)
        } else {
            sentence.startOffset
        }
        furthestReachedOffset = furthestReachedOffset.coerceAtLeast(covered)
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
                val position = if (snapshot.paged) snapshot.pageIndex.toLong() else snapshot.offset.toLong()
                val total = if (snapshot.paged) snapshot.pageCount.toLong() else snapshot.text.length.toLong()
                repository.updateProgress(bookId, position, total)
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

private fun ReaderUiState.isFullyRevealed(): Boolean {
    val page = pages.getOrNull(pageIndex) ?: return false
    val last = page.sentences.lastOrNull() ?: return false
    return revealedCount >= page.sentences.size && !typing && typedChars >= last.text.length
}

private fun ReaderUiState.wouldHideFullPage(reveal: PageReveal): Boolean {
    if (!isFullyRevealed()) return false
    return reveal.typing || reveal.revealedCount < revealedCount || reveal.typedChars < typedChars
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
