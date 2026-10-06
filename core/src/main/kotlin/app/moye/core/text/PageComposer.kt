package app.moye.core.text

import app.moye.core.model.ReadingUnit
import kotlin.math.max

data class ReadingPage(
    val start: Int,
    val end: Int,
    val sentences: List<ReadingUnit>,
)

/**
 * Packs sentences into pages. A page is the navigation unit and can hold more
 * than one sentence. A sentence that is longer than the line budget is split
 * into page-sized pieces without dropping characters.
 */
object PageComposer {
    fun compose(
        units: List<ReadingUnit>,
        charsPerLine: Int,
        linesPerPage: Int,
        textLength: Int,
    ): List<ReadingPage> {
        val width = charsPerLine.coerceAtLeast(1)
        val budget = linesPerPage.coerceAtLeast(1)
        val total = textLength.coerceAtLeast(0)
        if (units.isEmpty()) return listOf(ReadingPage(0, total, emptyList()))

        val groups = mutableListOf<List<ReadingUnit>>()
        var current = mutableListOf<ReadingUnit>()
        var used = 0
        var column = 0
        fun flush() {
            if (current.isEmpty()) return
            groups += current.toList()
            current = mutableListOf()
            used = 0
            column = 0
        }
        for (unit in units) {
            for (chunk in splitToBudget(unit, width, budget)) {
                val continuing = current.isNotEmpty() && chunk.paragraphIndex == current.last().paragraphIndex
                val (added, nextColumn) = additionalLines(chunk.text, width, if (continuing) column else 0)
                if (current.isNotEmpty() && used + added > budget) {
                    flush()
                    val (freshAdded, freshColumn) = additionalLines(chunk.text, width, 0)
                    current += chunk
                    used = freshAdded
                    column = freshColumn
                } else {
                    current += chunk
                    used += added
                    column = nextColumn
                }
            }
        }
        flush()
        if (groups.isEmpty()) return listOf(ReadingPage(0, total, emptyList()))
        return groups.mapIndexed { index, sentences ->
            val start = sentences.first().startOffset
            val naturalEnd = sentences.last().endOffset
            val end = if (index == groups.lastIndex) {
                max(naturalEnd, total)
            } else {
                max(naturalEnd, groups[index + 1].first().startOffset)
            }
            ReadingPage(start, end.coerceAtLeast(start), sentences)
        }
    }

    internal fun estimateLines(text: String, charsPerLine: Int): Int {
        return additionalLines(text, charsPerLine, 0).first
    }

    /**
     * Lines added by placing [text] when [column] characters are already on the
     * current line. A new paragraph passes column 0, so it starts on the next
     * line and does not insert a blank line.
     */
    internal fun additionalLines(text: String, charsPerLine: Int, column: Int): Pair<Int, Int> {
        val width = charsPerLine.coerceAtLeast(1)
        var col = column.coerceIn(0, width)
        if (col == width) col = 0
        val freshLine = col == 0
        var lines = 0
        for (ch in text) {
            if (ch == '\n') {
                lines++
                col = 0
                continue
            }
            if (col == width) {
                lines++
                col = 0
            }
            col++
        }
        if (freshLine && (col > 0 || text.isEmpty())) lines++
        return lines to col
    }

    private fun splitToBudget(unit: ReadingUnit, charsPerLine: Int, linesPerPage: Int): List<ReadingUnit> {
        val text = unit.text
        if (text.isEmpty() || estimateLines(text, charsPerLine) <= linesPerPage) return listOf(unit)
        val parts = mutableListOf<ReadingUnit>()
        var cursor = 0
        while (cursor < text.length) {
            var low = cursor + 1
            var high = text.length
            var best = cursor + 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                if (estimateLines(text.substring(cursor, mid), charsPerLine) <= linesPerPage) {
                    best = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            if (best < text.length && best > cursor && text[best - 1].isHighSurrogate()) best--
            if (best <= cursor) best = (cursor + 1).coerceAtMost(text.length)
            parts += unit.copy(
                text = text.substring(cursor, best),
                startOffset = unit.startOffset + cursor,
                endOffset = unit.startOffset + best,
            )
            cursor = best
        }
        return parts
    }
}
