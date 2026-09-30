package app.moye.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SentenceSegmenterTest {
    @Test
    fun splitsChineseSentencesWithoutSpaces() {
        val units = SentenceSegmenter.segment("你好。世界！末尾没有句号")
        assertEquals(listOf("你好。", "世界！", "末尾没有句号"), units.map { it.text })
    }

    @Test
    fun splitsEnglishAndKeepsAbbreviationsAndDecimals() {
        val units = SentenceSegmenter.segment("Mr. Smith paid 3.14 dollars. Then he left.")
        assertEquals(listOf("Mr. Smith paid 3.14 dollars. ", "Then he left."), units.map { it.text })
    }

    @Test
    fun keepsInitialismsTogether() {
        val units = SentenceSegmenter.segment("U.S.A. is big. Really.")
        assertEquals(listOf("U.S.A. is big. ", "Really."), units.map { it.text })
    }

    @Test
    fun otherScriptsStayAsParagraphs() {
        val text = "Привет. Как дела?\nХорошо."
        val units = SentenceSegmenter.segment(text)
        assertEquals(listOf("Привет. Как дела?", "Хорошо."), units.map { it.text })
    }

    @Test
    fun doesNotDropBodyCharacters() {
        val text = "第一句。Second sentence!\n\n尾声……「好。」\nПривет мир"
        val units = SentenceSegmenter.segment(text)
        units.forEach { unit ->
            assertEquals(text.substring(unit.startOffset, unit.endOffset), unit.text)
        }
        assertTrue(units.zipWithNext().all { (left, right) -> left.endOffset <= right.startOffset })
        val visible = text.filterNot { it.isWhitespace() }
        assertEquals(visible, units.joinToString("") { it.text }.filterNot { it.isWhitespace() })
    }

    @Test
    fun emptyAndBlankInputDoesNotThrow() {
        assertEquals(emptyList(), SentenceSegmenter.segment(""))
        assertEquals(emptyList(), SentenceSegmenter.segment("\n\n  \n"))
    }
}
