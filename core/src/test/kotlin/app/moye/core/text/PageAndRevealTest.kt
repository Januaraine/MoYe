package app.moye.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PageAndRevealTest {
    private val sample = "这是一个很普通的夜晚。窗外下着小雨。她坐在窗边，看着远处的灯光。然后，她听到了敲门声。"

    @Test
    fun onePageCanHoldSeveralSentences() {
        val units = SentenceSegmenter.segment(sample)
        assertEquals(4, units.size)
        val pages = PageComposer.compose(units, charsPerLine = 20, linesPerPage = 8, textLength = sample.length)
        assertEquals(1, pages.size)
        assertEquals(units.map { it.text }, pages.single().sentences.map { it.text })
        assertEquals(sample, pages.single().sentences.joinToString("") { it.text })
    }

    @Test
    fun sentencesAreNotPagesWhenMoreThanOneFits() {
        val units = SentenceSegmenter.segment(sample)
        val pages = PageComposer.compose(units, charsPerLine = 20, linesPerPage = 3, textLength = sample.length)
        assertTrue(pages.size < units.size)
        assertTrue(pages.any { it.sentences.size > 1 })
        assertEquals(sample, pages.flatMap { it.sentences }.joinToString("") { it.text })
    }

    @Test
    fun longSentenceIsSplitWithoutDroppingCharacters() {
        val text = "甲".repeat(20)
        val units = SentenceSegmenter.segment(text)
        val pages = PageComposer.compose(units, charsPerLine = 4, linesPerPage = 2, textLength = text.length)
        val joined = pages.flatMap { it.sentences }.joinToString("") { it.text }
        assertEquals(text, joined)
        assertTrue(pages.size > 1)
        pages.flatMap { it.sentences }.forEach { unit ->
            assertEquals(text.substring(unit.startOffset, unit.endOffset), unit.text)
        }
    }

    @Test
    fun tapCompletesTypingBeforeRevealingTheNextSentence() {
        val page = pageOf(sample, linesPerPage = 8)
        var reveal = SentenceReveal.enter(page, typewriterEnabled = true)
        assertEquals(1, reveal.revealedCount)
        assertTrue(reveal.typing)
        assertEquals("这", SentenceReveal.visiblePrefix(page.sentences[0].text, reveal.typedChars))

        reveal = SentenceReveal.tick(page, reveal)
        assertEquals("这是", SentenceReveal.visiblePrefix(page.sentences[0].text, reveal.typedChars))

        val completed = assertIs<RevealStep.Updated>(SentenceReveal.onTap(page, reveal, typewriterEnabled = true)).reveal
        assertEquals(1, completed.revealedCount)
        assertFalse(completed.typing)
        assertEquals(page.sentences[0].text, SentenceReveal.visiblePrefix(page.sentences[0].text, completed.typedChars))

        val next = assertIs<RevealStep.Updated>(SentenceReveal.onTap(page, completed, typewriterEnabled = true)).reveal
        assertEquals(2, next.revealedCount)
        assertTrue(next.typing)
        assertEquals("窗", SentenceReveal.visiblePrefix(page.sentences[1].text, next.typedChars))
    }

    @Test
    fun tapAfterTheLastSentenceAsksForTheNextPage() {
        val page = pageOf(sample, linesPerPage = 8)
        var reveal = SentenceReveal.enter(page, typewriterEnabled = false)
        while (reveal.revealedCount < page.sentences.size) {
            reveal = assertIs<RevealStep.Updated>(SentenceReveal.onTap(page, reveal, typewriterEnabled = false)).reveal
            assertFalse(reveal.typing)
        }
        assertEquals(page.sentences.size, reveal.revealedCount)
        assertIs<RevealStep.NextPage>(SentenceReveal.onTap(page, reveal, typewriterEnabled = false))
    }

    @Test
    fun disablingTypewriterStillRevealsOneSentenceAtATime() {
        val page = pageOf(sample, linesPerPage = 8)
        val first = SentenceReveal.enter(page, typewriterEnabled = false)
        assertEquals(1, first.revealedCount)
        assertFalse(first.typing)
        assertEquals(page.sentences[0].text.length, first.typedChars)

        val second = assertIs<RevealStep.Updated>(SentenceReveal.onTap(page, first, typewriterEnabled = false)).reveal
        assertEquals(2, second.revealedCount)
        assertFalse(second.typing)
        assertEquals(page.sentences[1].text.length, second.typedChars)
        assertTrue(second.revealedCount < page.sentences.size)
    }

    @Test
    fun typewriterRevealsTheSampleSentenceOneCharacterAtATime() {
        val page = pageOf(sample, linesPerPage = 8)
        val sentence = page.sentences.first().text
        assertTrue(page.sentences.size > 1)
        var reveal = SentenceReveal.enter(page, typewriterEnabled = true)
        val seen = mutableListOf(SentenceReveal.visiblePrefix(sentence, reveal.typedChars))
        while (reveal.typing) {
            reveal = SentenceReveal.tick(page, reveal)
            seen += SentenceReveal.visiblePrefix(sentence, reveal.typedChars)
        }
        val expected = sentence.indices.map { index -> sentence.substring(0, index + 1) }
        assertEquals(expected, seen)
        assertEquals(1, reveal.revealedCount)
        assertFalse(reveal.typing)
    }

    @Test
    fun typewriterAdvancesByCharacterAndKeepsSurrogatePairs() {
        val text = "A\uD83D\uDE00B"
        var count = 0
        val seen = mutableListOf<String>()
        while (count < text.length) {
            count = nextBoundary(text, count)
            seen += text.substring(0, count)
        }
        assertEquals(listOf("A", "A\uD83D\uDE00", "A\uD83D\uDE00B"), seen)
        val slow = TypewriterTiming.millisPerCharacter(0.5f)
        val normal = TypewriterTiming.millisPerCharacter(1f)
        val mid = TypewriterTiming.millisPerCharacter(1.2f)
        val fast = TypewriterTiming.millisPerCharacter(3f)
        assertTrue(slow > normal)
        assertTrue(normal > mid)
        assertTrue(mid > fast)
    }

    private fun pageOf(text: String, linesPerPage: Int) =
        PageComposer.compose(SentenceSegmenter.segment(text), 20, linesPerPage, text.length).single()

    private fun nextBoundary(text: String, typed: Int): Int {
        val page = PageComposer.compose(
            SentenceSegmenter.segment(text),
            charsPerLine = 20,
            linesPerPage = 4,
            textLength = text.length,
        ).single()
        val reveal = SentenceReveal.tick(
            page,
            app.moye.core.text.PageReveal(1, typed, true, 0),
        )
        return reveal.typedChars
    }
}
