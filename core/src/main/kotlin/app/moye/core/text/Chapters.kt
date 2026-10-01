package app.moye.core.text

import app.moye.core.model.Chapter

object ChapterDetector {
    private val patterns = listOf(
        Regex("""^第[0-9０-９零〇一二三四五六七八九十百千万两]+[章节回部集卷篇]\s*.*"""),
        Regex("""(?i)^chapter\s+[0-9ivxlcdm]+\b.*"""),
        Regex("""(?i)^ch\.?\s*[0-9]+\b.*"""),
    )

    fun detect(text: String): List<Chapter> {
        if (text.isEmpty()) return emptyList()
        val chapters = mutableListOf<Chapter>()
        var index = 0
        while (index < text.length) {
            val newline = text.indexOf('\n', index)
            val lineEnd = if (newline == -1) text.length else newline
            val trimmed = text.substring(index, lineEnd).trim()
            if (trimmed.isNotEmpty() && patterns.any { it.matches(trimmed) }) {
                chapters += Chapter(title = trimmed, startOffset = index)
            }
            index = if (newline == -1) text.length else newline + 1
        }
        return chapters
    }
}

object ChapterNavigation {
    fun indexAt(chapters: List<Chapter>, offset: Int): Int {
        if (chapters.isEmpty()) return -1
        var index = 0
        for (i in chapters.indices) {
            if (chapters[i].startOffset <= offset) index = i else break
        }
        return index
    }

    fun previousTarget(chapters: List<Chapter>, offset: Int): Int? {
        val index = indexAt(chapters, offset)
        if (index < 0) return null
        val start = chapters[index].startOffset
        return when {
            offset > start -> start
            index > 0 -> chapters[index - 1].startOffset
            else -> null
        }
    }

    fun nextTarget(chapters: List<Chapter>, offset: Int): Int? {
        val index = indexAt(chapters, offset)
        val next = if (index < 0) 0 else index + 1
        return chapters.getOrNull(next)?.startOffset
    }
}
