package app.moye.core.library

import app.moye.core.model.RemovalChoice
import app.moye.core.model.RemovalResult

class BookRemoval(
    private val library: Library,
    private val deleteCopy: (relativePath: String) -> Boolean,
) {
    fun remove(id: String, choice: RemovalChoice): RemovalResult {
        if (choice == RemovalChoice.CANCEL) return RemovalResult.Cancelled
        val existing = library.get(id) ?: return RemovalResult.NotFound
        if (choice == RemovalChoice.DELETE_COPY) {
            val deleted = try {
                deleteCopy(existing.relativePath)
            } catch (_: Exception) {
                false
            }
            if (!deleted) return RemovalResult.Failed
        }
        library.remove(id) ?: return RemovalResult.Failed
        return if (choice == RemovalChoice.DELETE_COPY) {
            RemovalResult.RemovedDeletedCopy
        } else {
            RemovalResult.RemovedKeepCopy
        }
    }
}
