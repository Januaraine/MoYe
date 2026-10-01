package app.moye.data

import android.content.Context
import app.moye.core.library.BookFiles
import app.moye.core.library.FileLibraryStore
import app.moye.core.library.Library
import app.moye.core.settings.FileSettingsStore
import java.io.File

class AppContainer(context: Context) {
    private val root = File(context.filesDir, "moye")
    val settings = FileSettingsStore(File(root, "settings.json"))
    val library = Library(FileLibraryStore(File(root, "library.json")))
    val repository = BookRepository(
        context = context.applicationContext,
        library = library,
        bookFiles = BookFiles(File(root, "books")),
    )
}
