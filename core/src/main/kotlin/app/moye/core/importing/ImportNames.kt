package app.moye.core.importing

internal object ImportNames {
    fun title(displayName: String?, extension: String): String? {
        val base = displayName?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty()
        if (base.isEmpty()) return null
        val suffix = ".$extension"
        val stripped = if (base.lowercase().endsWith(suffix.lowercase())) base.dropLast(suffix.length) else base
        return stripped.trim().ifBlank { null }
    }
}
