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
        latinAdvance: Float = 0.5f,
    ): List<ReadingPage> {
        val width = charsPerLine.coerceAtLeast(1)
        val advance = latinAdvance.coerceIn(0.2f, 1f)
        val budget = linesPerPage.coerceAtLeast(1)
        val total = textLength.coerceAtLeast(0)
        if (units.isEmpty()) return listOf(ReadingPage(0, total, emptyList()))

        val groups = mutableListOf<List<ReadingUnit>>()
        var current = mutableListOf<ReadingUnit>()
        var used = 0
        var column = 0f
        fun flush() {
            if (current.isEmpty()) return
            groups += current.toList()
            current = mutableListOf()
            used = 0
            column = 0f
        }
        for (unit in units) {
            for (chunk in splitToBudget(unit, width, budget, advance)) {
                val continuing = current.isNotEmpty() && chunk.paragraphIndex == current.last().paragraphIndex
                val (added, nextColumn) = additionalLines(chunk.text, width, if (continuing) column else 0f, advance)
                if (current.isNotEmpty() && used + added > budget) {
                    flush()
                    val (freshAdded, freshColumn) = additionalLines(chunk.text, width, 0f, advance)
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

    internal fun estimateLines(text: String, charsPerLine: Int, latinAdvance: Float = 0.5f): Int {
        return additionalLines(text, charsPerLine, 0f, latinAdvance).first
    }

    /**
     * Lines added by placing [text] when [column] fullwidth cells are already
     * on the current line. A new paragraph passes 0, so it starts on the next
     * line and does not insert a blank line. [latinAdvance] is how many fullwidth
     * cells a Latin letter occupies in the current font.
     */
    internal fun additionalLines(
        text: String,
        charsPerLine: Int,
        column: Float,
        latinAdvance: Float = 0.5f,
    ): Pair<Int, Float> {
        val width = charsPerLine.coerceAtLeast(1).toFloat()
        var col = column.coerceIn(0f, width)
        if (col >= width - 0.001f) col = 0f
        var lines = 0
        var lineCounted = col > 0f
        if (text.isEmpty()) return (if (lineCounted) 0 else 1) to col
        for (ch in text) {
            if (ch == '\n') {
                if (!lineCounted) lines++
                col = 0f
                lineCounted = false
                continue
            }
            val advance = columnAdvance(ch, latinAdvance).coerceAtMost(width)
            if (col > 0f && col + advance > width + 0.001f) {
                if (!lineCounted) lines++
                col = 0f
                lineCounted = false
            }
            if (!lineCounted) {
                lines++
                lineCounted = true
            }
            col += advance
        }
        return lines to col
    }

    /** Fullwidth characters occupy one cell. Latin letters occupy [latinAdvance] cells. */
    internal fun columnAdvance(ch: Char, latinAdvance: Float = 0.5f): Float {
        val code = ch.code
        val fullWidth = code in 0x1100..0x115F ||
            code in 0x2E80..0xA4CF ||
            code in 0xAC00..0xD7A3 ||
            code in 0xF900..0xFAFF ||
            code in 0xFE10..0xFE19 ||
            code in 0xFE30..0xFE6F ||
            code in 0xFF01..0xFF60 ||
            code in 0xFFE0..0xFFE6
        return if (fullWidth) 1f else latinAdvance.coerceIn(0.2f, 1f)
    }

    private fun splitToBudget(
        unit: ReadingUnit,
        charsPerLine: Int,
        linesPerPage: Int,
        latinAdvance: Float,
    ): List<ReadingUnit> {
        val text = unit.text
        if (text.isEmpty() || estimateLines(text, charsPerLine, latinAdvance) <= linesPerPage) return listOf(unit)
        val parts = mutableListOf<ReadingUnit>()
        var cursor = 0
        while (cursor < text.length) {
            var low = cursor + 1
            var high = text.length
            var best = cursor + 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                if (estimateLines(text.substring(cursor, mid), charsPerLine, latinAdvance) <= linesPerPage) {
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
