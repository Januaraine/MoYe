package app.moye.ui.shelf

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.moye.core.library.BookRecord
import app.moye.core.model.ImportError
import app.moye.core.model.RemovalChoice
import app.moye.core.model.RemovalResult
import app.moye.core.settings.FileSettingsStore
import app.moye.data.BookRepository
import app.moye.data.ImportReport
import app.moye.data.ShelfSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ShelfNotice {
    REMOVED_KEPT,
    REMOVED_DELETED,
    REMOVE_FAILED,
}

data class ShelfUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val books: List<BookRecord> = emptyList(),
    val query: String = "",
    val importing: Boolean = false,
    val importError: ImportError? = null,
    val importReport: ImportReport? = null,
    val editing: BookRecord? = null,
    val removing: BookRecord? = null,
    val notice: ShelfNotice? = null,
    val showPrivacy: Boolean = false,
    val languageTag: String = "",
)

class ShelfViewModel(
    private val repository: BookRepository,
    private val settingsStore: FileSettingsStore,
) : ViewModel() {
    private val _state = MutableStateFlow(ShelfUiState())
    val state: StateFlow<ShelfUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(languageTag = settingsStore.load().languageTag) }
        when (val snapshot = repository.loadShelf()) {
            is ShelfSnapshot.Ready -> _state.update {
                it.copy(loading = false, loadFailed = false, books = snapshot.books)
            }
            ShelfSnapshot.Failed -> _state.update { it.copy(loading = false, loadFailed = true) }
        }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun importAll(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(importing = true, importError = null, importReport = null) }
            val report = repository.importAll(uris)
            refresh()
            val onlyFailures = report.imported == 0 && report.duplicates == 0 && report.firstError != null
            _state.update {
                it.copy(
                    importing = false,
                    importError = if (onlyFailures) report.firstError else null,
                    importReport = if (onlyFailures || report.imported + report.duplicates + report.failures == 0) {
                        null
                    } else {
                        report
                    },
                )
            }
        }
    }

    fun dismissImportError() {
        _state.update { it.copy(importError = null) }
    }

    fun dismissImportReport() {
        _state.update { it.copy(importReport = null) }
    }

    fun beginEdit(book: BookRecord) {
        _state.update { it.copy(editing = book) }
    }

    fun dismissEdit() {
        _state.update { it.copy(editing = null) }
    }

    fun saveEdit(title: String, author: String) {
        val book = _state.value.editing ?: return
        repository.updateMetadata(book.id, title, author)
        _state.update { it.copy(editing = null) }
        refresh()
    }

    fun beginRemove(book: BookRecord) {
        _state.update { it.copy(removing = book) }
    }

    fun dismissRemove() {
        _state.update { it.copy(removing = null) }
    }

    fun confirmRemove(choice: RemovalChoice) {
        val book = _state.value.removing ?: return
        val result = repository.remove(book.id, choice)
        val notice = when (result) {
            RemovalResult.RemovedKeepCopy -> ShelfNotice.REMOVED_KEPT
            RemovalResult.RemovedDeletedCopy -> ShelfNotice.REMOVED_DELETED
            RemovalResult.Failed -> ShelfNotice.REMOVE_FAILED
            RemovalResult.Cancelled, RemovalResult.NotFound -> null
        }
        _state.update { it.copy(removing = null, notice = notice) }
        refresh()
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun setPrivacyVisible(visible: Boolean) {
        _state.update { it.copy(showPrivacy = visible) }
    }

    fun setLanguage(tag: String) {
        settingsStore.update { it.copy(languageTag = tag) }
        _state.update { it.copy(languageTag = tag) }
    }
}

class ShelfViewModelFactory(
    private val repository: BookRepository,
    private val settingsStore: FileSettingsStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ShelfViewModel(repository, settingsStore) as T
    }
}
