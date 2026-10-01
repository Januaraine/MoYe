package app.moye.core.text

import app.moye.core.model.TextSpan
import app.moye.core.model.VerticalColumn
import app.moye.core.model.VerticalPage

object Paginator {
    fun paginateByGrid(text: String, charsPerLine: Int, linesPerPage: Int): List<TextSpan> {
        val lineSize = charsPerLine.coerceAtLeast(1)
        val pageLines = linesPerPage.coerceAtLeast(1)
        if (text.isEmpty()) return listOf(TextSpan(0, 0))
        val pages = mutableListOf<TextSpan>()
        var index = 0
        while (index < text.length) {
            var line = 0
            var cursor = index
            while (line < pageLines && cursor < text.length) {
                val newline = text.indexOf('\n', cursor)
                val lineLimit = (cursor + lineSize).coerceAtMost(text.length)
                val lineEnd = if (newline == -1) lineLimit else minOf(newline, lineLimit)
                cursor = if (lineEnd == newline) lineEnd + 1 else lineEnd
                line++
            }
            if (cursor <= index) cursor = (index + 1).coerceAtMost(text.length)
            pages += TextSpan(index, cursor)
            index = cursor
        }
        return pages
    }

    fun paginateVertical(text: String, charsPerColumn: Int, columnsPerPage: Int): List<VerticalPage> {
        val columnSize = charsPerColumn.coerceAtLeast(1)
        val pageColumns = columnsPerPage.coerceAtLeast(1)
        if (text.isEmpty()) {
            return listOf(VerticalPage(0, 0, listOf(VerticalColumn(0, 0))))
        }
        val pages = mutableListOf<VerticalPage>()
        var index = 0
        while (index < text.length) {
            val columns = mutableListOf<VerticalColumn>()
            var cursor = index
            while (columns.size < pageColumns && cursor < text.length) {
                val columnStart = cursor
                var filled = 0
                while (filled < columnSize && cursor < text.length) {
                    if (text[cursor] == '\n') {
                        cursor++
                        break
                    }
                    cursor++
                    filled++
                }
                columns += VerticalColumn(columnStart, cursor)
            }
            if (cursor <= index) cursor = (index + 1).coerceAtMost(text.length)
            pages += VerticalPage(index, cursor, columns)
            index = cursor
        }
        return pages
    }
}
