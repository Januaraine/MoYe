package app.moye.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AutoPlayTimingTest {
    private val book = "一句。二句。三句。\n\n四句。五句。六句。"

    @Test
    fun documentIsOneSentenceSequenceWithParagraphMetadata() {
        val units = SentenceSegmenter.segment(book)
        assertEquals(listOf(0, 0, 0, 1, 1, 1), units.map { it.paragraphIndex })
        assertEquals(listOf(0, 1, 2, 0, 1, 2), units.map { it.sentenceIndex })
        assertEquals(listOf("一句。", "二句。", "三句。", "四句。", "五句。", "六句。"), units.map { it.text })
    }

    @Test
    fun unfinishedSentenceDoesNotRevealTheNextParagraph() {
        val page = pageOf(book)
        assertEquals(listOf(0, 0, 0, 1, 1, 1), page.sentences.map { it.paragraphIndex })
        val typing = SentenceReveal.enter(page, typewriterEnabled = true)
        assertTrue(typing.typing)
        val held = assertIs<RevealStep.Updated>(
            SentenceReveal.onAutoPlay(page, typing, typewriterEnabled = true),
        ).reveal
        assertEquals(1, held.revealedCount)
        assertEquals(0, page.sentences[held.revealedCount - 1].paragraphIndex)

        val lastOfParagraph = revealThrough(page, revealedCount = 3, typing = true)
        val stillThere = assertIs<RevealStep.Updated>(
            SentenceReveal.onAutoPlay(page, lastOfParagraph, typewriterEnabled = true),
        ).reveal
        assertEquals(3, stillThere.revealedCount)
        assertEquals(0, page.sentences[stillThere.revealedCount - 1].paragraphIndex)
    }

    @Test
    fun completedSentencesAdvanceInOrderAcrossParagraphs() {
        val page = pageOf(book)
        var reveal = SentenceReveal.enter(page, typewriterEnabled = true)
        val seen = mutableListOf(page.sentences[reveal.revealedCount - 1].text)
        while (reveal.revealedCount < page.sentences.size) {
            reveal = reveal.copy(typedChars = page.sentences[reveal.revealedCount - 1].text.length, typing = false)
            reveal = assertIs<RevealStep.Updated>(
                SentenceReveal.onAutoPlay(page, reveal, typewriterEnabled = true),
            ).reveal
            seen += page.sentences[reveal.revealedCount - 1].text
        }
        assertEquals(listOf("一句。", "二句。", "三句。", "四句。", "五句。", "六句。"), seen)
        reveal = reveal.copy(typedChars = page.sentences.last().text.length, typing = false)
        assertIs<RevealStep.NextPage>(SentenceReveal.onAutoPlay(page, reveal, typewriterEnabled = true))
    }

    @Test
    fun typewriterOffShowsEverySentenceAndTurnsThePage() {
        val page = pageOf(book)
        val shown = SentenceReveal.enter(page, typewriterEnabled = false)
        assertEquals(page.sentences.size, shown.revealedCount)
        assertFalse(shown.typing)
        assertIs<RevealStep.NextPage>(SentenceReveal.onAutoPlay(page, shown, typewriterEnabled = false))
    }

    @Test
    fun paragraphBoundaryUsesTheSameSentenceCycle() {
        val inside = PlaybackTiming.sentenceAdvanceDelay(1_200L, speed = 1f, typewriterEnabled = true)
        val across = PlaybackTiming.sentenceAdvanceDelay(1_200L, speed = 1f, typewriterEnabled = true)
        val typewriterOff = PlaybackTiming.sentenceAdvanceDelay(1_200L, speed = 1f, typewriterEnabled = false)
        assertEquals(2_000L, inside)
        assertEquals(inside, across)
        assertEquals(2_000L, typewriterOff)

        val longTypewriter = PlaybackTiming.sentenceAdvanceDelay(3_200L, speed = 1f, typewriterEnabled = true)
        assertEquals(3_200L, longTypewriter)
        assertTrue(longTypewriter < 3_200L + 2_000L)
    }

    @Test
    fun pauseResumeAndRestartKeepASingleDeadline() {
        val scheduler = AutoPlayScheduler()
        val first = scheduler.replace(0L, speed = 1f, typewriterDurationMillis = 3_200L, typewriterEnabled = true)
        assertEquals(3_200L, scheduler.deadlineMillis)
        assertEquals(1, scheduler.pendingCount())

        scheduler.clear()
        assertEquals(0, scheduler.pendingCount())
        assertFalse(scheduler.isCurrent(first))

        val resumed = scheduler.replace(500L, speed = 1f, typewriterDurationMillis = 800L, typewriterEnabled = true)
        assertEquals(2_500L, scheduler.deadlineMillis)
        assertTrue(scheduler.isCurrent(resumed))
        assertEquals(1, scheduler.pendingCount())

        val restarted = scheduler.replace(700L, speed = 1f, typewriterDurationMillis = 0L, typewriterEnabled = false)
        assertFalse(scheduler.isCurrent(resumed))
        assertTrue(scheduler.isCurrent(restarted))
        assertEquals(1, scheduler.pendingCount())
        assertEquals(2_700L, scheduler.deadlineMillis)
    }

    private fun pageOf(text: String) =
        PageComposer.compose(
            SentenceSegmenter.segment(text),
            charsPerLine = 20,
            linesPerPage = 12,
            textLength = text.length,
        ).single()

    private fun revealThrough(page: ReadingPage, revealedCount: Int, typing: Boolean): PageReveal {
        val sentence = page.sentences[revealedCount - 1]
        return PageReveal(
            revealedCount = revealedCount,
            typedChars = if (typing) 1 else sentence.text.length,
            typing = typing,
            anchorOffset = sentence.startOffset,
        )
    }
}
