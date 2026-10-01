package app.moye.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.moye.core.model.Chapter
import app.moye.core.model.ContentError
import app.moye.core.model.ReaderCommand
import app.moye.core.model.ReadingMode
import app.moye.core.model.ReadingUnit
import app.moye.core.model.WritingMode
import app.moye.core.model.effectiveWritingMode
import app.moye.core.settings.FileSettingsStore
import app.moye.core.settings.ReaderSettings
import app.moye.core.text.ChapterNavigation
import app.moye.core.text.PlaybackTiming
import app.moye.core.text.ReadingProgress
import app.moye.core.text.SentenceSegmenter
import app.moye.core.time.ReadingTimer
import app.moye.data.BookRepository
import app.moye.data.ContentLoad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    private val _state = MutableStateFlow(ReaderUiState(settings = settingsStore.load()))
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val _pageTurns = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val pageTurns: SharedFlow<Int> = _pageTurns.asSharedFlow()

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
        } else {
            pausePlayback()
            syncTimer()
        }
    }

    fun onActivityResume() {
        activityResumed = true
        syncTimer()
    }

    fun onActivityPause() {
        activityResumed = false
        pausePlayback()
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
                if (_state.value.settings.readingMode == ReadingMode.SENTENCE) previousUnit() else _pageTurns.tryEmit(-1)
                true
            }
            ReaderCommand.NEXT -> {
                if (_state.value.settings.readingMode == ReadingMode.SENTENCE) nextUnit() else _pageTurns.tryEmit(1)
                true
            }
            ReaderCommand.TOGGLE_PLAYBACK -> {
                if (_state.value.settings.readingMode == ReadingMode.SENTENCE) togglePlayback()
                true
            }
        }
    }

    fun previousUnit() {
        if (moveUnit(-1) && _state.value.playing) restartPlayback()
    }

    fun nextUnit() {
        if (moveUnit(1)) {
            if (_state.value.playing) restartPlayback()
        } else if (_state.value.playing) {
            pausePlayback()
        }
    }

    fun togglePlayback() {
        if (_state.value.playing) pausePlayback() else startPlayback()
    }

    fun seek(offset: Int) {
        val total = _state.value.text.length
        _state.update { it.copy(offset = offset.coerceIn(0, total)) }
        persistOffset()
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
        val wasPlaying = _state.value.playing
        _state.update { current ->
            current.copy(
                settings = updated,
                writingMode = if (current.writingModeLocked) current.writingMode else updated.txtWritingMode,
                playing = wasPlaying && updated.readingMode == ReadingMode.SENTENCE,
            )
        }
        if (_state.value.playing) restartPlayback() else pausePlayback()
    }

    private fun startPlayback() {
        if (_state.value.units.isEmpty()) return
        _state.update { it.copy(playing = true) }
        restartPlayback()
    }

    private fun pausePlayback() {
        _state.update { it.copy(playing = false) }
        playJob?.cancel()
        playJob = null
    }

    private fun restartPlayback() {
        playJob?.cancel()
        playJob = viewModelScope.launch {
            while (isActive && _state.value.playing) {
                val snapshot = _state.value
                val index = ReadingProgress.unitIndexForOffset(snapshot.units, snapshot.offset)
                val unit = snapshot.units.getOrNull(index) ?: break
                delay(PlaybackTiming.dwellMillis(unit.text, snapshot.settings.playbackSpeed))
                if (!_state.value.playing) break
                if (!moveUnit(1)) {
                    _state.update { it.copy(playing = false) }
                    break
                }
            }
        }
    }

    private fun moveUnit(delta: Int): Boolean {
        val snapshot = _state.value
        if (snapshot.units.isEmpty()) return false
        val index = ReadingProgress.unitIndexForOffset(snapshot.units, snapshot.offset)
        val target = index + delta
        if (target !in snapshot.units.indices) {
            if (delta > 0) {
                _state.update { it.copy(offset = snapshot.text.length) }
                persistOffset()
            }
            return false
        }
        _state.update { it.copy(offset = snapshot.units[target].startOffset) }
        persistOffset()
        return true
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
        if (timer.isRunning) timer.pause(now())
        flushBlocking()
        super.onCleared()
    }
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
