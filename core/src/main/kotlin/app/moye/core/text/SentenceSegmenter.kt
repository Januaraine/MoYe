package app.moye.core.text

import app.moye.core.model.ReadingUnit

/**
 * Splits body text into reading units without dropping characters.
 * Chinese and English use sentence boundaries. Other scripts stay as paragraphs.
 */
object SentenceSegmenter {
    private val closers = setOf(
        '」', '』', '”', '’', '"', '\'', '）', ')', '］', ']', '】',
    )
    private val abbreviations = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "eg", "ie", "fig", "no",
    )

    fun segment(text: String): List<ReadingUnit> {
        if (text.isEmpty()) return emptyList()
        val units = mutableListOf<ReadingUnit>()
        var index = 0
        var paragraphIndex = 0
        while (index < text.length) {
            val newline = text.indexOf('\n', index)
            val lineEnd = if (newline == -1) text.length else newline
            if (lineEnd > index && !text.substring(index, lineEnd).isBlank()) {
                val sentences = splitParagraph(text, index, lineEnd)
                sentences.forEachIndexed { sentenceIndex, unit ->
                    units += unit.copy(paragraphIndex = paragraphIndex, sentenceIndex = sentenceIndex)
                }
                paragraphIndex++
            }
            index = if (newline == -1) text.length else newline + 1
        }
        return units
    }

    private fun splitParagraph(text: String, start: Int, end: Int): List<ReadingUnit> {
        val slice = text.substring(start, end)
        if (detectMode(slice) == Mode.PARAGRAPH) {
            return listOf(ReadingUnit(slice, start, end))
        }
        val relativeEnds = sentenceEnds(slice)
        if (relativeEnds.isEmpty()) {
            return listOf(ReadingUnit(slice, start, end))
        }
        val units = mutableListOf<ReadingUnit>()
        var cursor = 0
        for (boundary in relativeEnds) {
            if (boundary <= cursor) continue
            val unitText = slice.substring(cursor, boundary)
            if (unitText.isNotEmpty()) {
                units += ReadingUnit(unitText, start + cursor, start + boundary)
            }
            cursor = boundary
        }
        if (cursor < slice.length) {
            val unitText = slice.substring(cursor)
            if (unitText.isNotEmpty()) {
                units += ReadingUnit(unitText, start + cursor, end)
            }
        }
        return if (units.isEmpty()) listOf(ReadingUnit(slice, start, end)) else units
    }

    private fun sentenceEnds(slice: String): List<Int> {
        val ends = mutableListOf<Int>()
        var index = 0
        while (index < slice.length) {
            val ch = slice[index]
            val cjk = ch == '。' || ch == '！' || ch == '？' || ch == '…'
            val ascii = ch == '.' || ch == '!' || ch == '?'
            if (cjk || ascii) {
                if (ch == '.' && (isAbbreviation(slice, index) || isDecimal(slice, index) || isInitialism(slice, index))) {
                    index++
                    continue
                }
                var next = index + 1
                if (ch == '.' || ch == '…') {
                    while (next < slice.length && (slice[next] == '.' || slice[next] == '…')) next++
                }
                while (next < slice.length && (isCjkEnder(slice[next]) || slice[next] in closers)) next++
                val boundary = cjk || endsAsciiSentence(slice, next)
                if (!boundary) {
                    index++
                    continue
                }
                while (next < slice.length && slice[next].isWhitespace() && slice[next] != '\n') next++
                ends += next
                index = next
                continue
            }
            index++
        }
        return ends
    }

    private fun isCjkEnder(ch: Char): Boolean = ch == '。' || ch == '！' || ch == '？' || ch == '…' || ch == '!' || ch == '?'

    private fun endsAsciiSentence(slice: String, indexAfter: Int): Boolean {
        if (indexAfter >= slice.length) return true
        val next = slice[indexAfter]
        return next.isWhitespace() || next in closers
    }

    private fun isDecimal(slice: String, dotIndex: Int): Boolean {
        val before = slice.getOrNull(dotIndex - 1) ?: return false
        val after = slice.getOrNull(dotIndex + 1) ?: return false
        return before.isDigit() && after.isDigit()
    }

    private fun isAbbreviation(slice: String, dotIndex: Int): Boolean {
        var cursor = dotIndex - 1
        while (cursor >= 0 && slice[cursor].isLetter()) cursor--
        val word = slice.substring(cursor + 1, dotIndex)
        if (word.isEmpty()) return false
        return word.lowercase() in abbreviations
    }

    private fun isInitialism(slice: String, dotIndex: Int): Boolean {
        val letter = slice.getOrNull(dotIndex - 1) ?: return false
        if (!letter.isLetter()) return false
        val beforeLetter = dotIndex - 2
        if (beforeLetter >= 0 && slice[beforeLetter].isLetter()) return false
        return beforeLetter >= 0 && slice[beforeLetter] == '.'
    }

    private fun detectMode(slice: String): Mode {
        var cjk = 0
        var latin = 0
        var other = 0
        for (ch in slice) {
            when {
                ch.isWhitespace() || !ch.isLetter() -> Unit
                isCjkLetter(ch) -> cjk++
                ch in 'A'..'Z' || ch in 'a'..'z' -> latin++
                else -> other++
            }
        }
        if (slice.any { it == '。' || it == '！' || it == '？' }) return Mode.SENTENCE
        val letters = cjk + latin + other
        if (letters == 0) {
            return if (slice.any { it == '.' || it == '!' || it == '?' || it == '…' }) Mode.SENTENCE else Mode.PARAGRAPH
        }
        if (other > cjk && other > latin) return Mode.PARAGRAPH
        return Mode.SENTENCE
    }

    private fun isCjkLetter(ch: Char): Boolean {
        val block = Character.UnicodeBlock.of(ch)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES
    }

    private enum class Mode {
        SENTENCE,
        PARAGRAPH,
    }
}
