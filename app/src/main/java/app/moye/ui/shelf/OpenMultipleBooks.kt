package app.moye.ui.shelf

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Multi-file TXT/EPUB picker.
 *
 * OpenMultipleDocuments sets EXTRA_ALLOW_MULTIPLE, but a wildcard-only mime
 * list makes the emulator DocumentsUI open a single-file picker. Concrete
 * MIME types keep multiple selection and still limit the list to book files.
 */
class OpenMultipleBooks : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            setType("*/*")
            putExtra(Intent.EXTRA_MIME_TYPES, MIME_TYPES)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
    }

    companion object {
        val MIME_TYPES: Array<String> = arrayOf(
            "text/plain",
            "application/epub+zip",
            "application/zip",
            "application/octet-stream",
        )
    }
}
