package app.moye.core.text

import app.moye.core.model.ReadingUnit
import app.moye.core.model.TextSpan
import app.moye.core.model.VerticalPage

object ReadingProgress {
    fun fraction(offset: Long, total: Long): Float {
        if (total <= 0L) return 0f
        return (offset.coerceIn(0L, total).toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    fun percent(offset: Long, total: Long): Int {
        if (total <= 0L) return 0
        return ((offset.coerceIn(0L, total) * 100L) / total).toInt()
    }

    fun unitIndexForOffset(units: List<ReadingUnit>, offset: Int): Int {
        if (units.isEmpty()) return -1
        val index = units.indexOfFirst { offset < it.endOffset }
        return when {
            index >= 0 -> index
            else -> units.lastIndex
        }
    }

    fun spanIndexForOffset(spans: List<TextSpan>, offset: Int): Int {
        if (spans.isEmpty()) return -1
        val index = spans.indexOfFirst { offset < it.end || it.start == it.end }
        if (index == -1) return spans.lastIndex
        if (spans[index].start == spans[index].end && offset > spans[index].start) {
            return spans.lastIndex
        }
        return index
    }

    fun verticalPageIndex(pages: List<VerticalPage>, offset: Int): Int {
        if (pages.isEmpty()) return -1
        val index = pages.indexOfFirst { offset < it.end || it.start == it.end }
        return if (index == -1) pages.lastIndex else index
    }
}
