package app.moye.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AutoPlayTimingTest {
    private val sample = "这是一个很普通的夜晚。窗外下着小雨。她坐在窗边，看着远处的灯光。然后，她听到了敲门声。"

    @Test
    fun sentenceIntervalStaysFixedWhenTypewriterIsOn() {
        val speed = 1f
        val budget = PlaybackTiming.intervalMillis(speed)
        assertEquals(2_000L, budget)
        val sentence = "这".repeat(40)
        val typewriter = TypewriterTiming.millisPerCharacter(speed) * sentence.length
        assertTrue(typewriter > 1_000L)

        val withTypewriter = PlaybackTiming.sentenceAdvanceDelay(typewriter, speed, typewriterEnabled = true)
        val withoutTypewriter = PlaybackTiming.sentenceAdvanceDelay(0L, speed, typewriterEnabled = false)
        assertEquals(budget, withTypewriter)
        assertEquals(budget, withoutTypewriter)
        assertTrue(withTypewriter < typewriter + budget)
    }

    @Test
    fun typewriterOnAndOffAdvanceTheSameSentence() {
        val page = pageOf(sample)
        val typing = SentenceReveal.enter(page, typewriterEnabled = true)
        assertTrue(typing.typing)
        assertTrue(typing.typedChars < page.sentences.first().text.length)

        val fromTyping = assertIs<RevealStep.Updated>(
            SentenceReveal.onAutoPlay(page, typing, typewriterEnabled = true),
        ).reveal
        val shownWhole = SentenceReveal.enter(page, typewriterEnabled = false)
        val fromWhole = assertIs<RevealStep.Updated>(
            SentenceReveal.onAutoPlay(page, shownWhole, typewriterEnabled = false),
        ).reveal

        assertEquals(2, fromTyping.revealedCount)
        assertEquals(fromWhole.revealedCount, fromTyping.revealedCount)
        assertEquals(page.sentences[1].startOffset, fromTyping.anchorOffset)
        assertEquals(page.sentences[1].startOffset, fromWhole.anchorOffset)
        assertTrue(fromTyping.typing)
        assertFalse(fromWhole.typing)
    }

    @Test
    fun lastSentenceAdvancesToTheNextPageWithoutAnExtraWaitStep() {
        val page = pageOf(sample)
        var reveal = SentenceReveal.enter(page, typewriterEnabled = true)
        while (reveal.revealedCount < page.sentences.size) {
            reveal = assertIs<RevealStep.Updated>(
                SentenceReveal.onAutoPlay(page, reveal, typewriterEnabled = true),
            ).reveal
        }
        assertTrue(reveal.typing || reveal.typedChars > 0)
        assertIs<RevealStep.NextPage>(SentenceReveal.onAutoPlay(page, reveal, typewriterEnabled = true))
        assertIs<RevealStep.NextPage>(SentenceReveal.onAutoPlay(page, reveal.copy(typing = false), typewriterEnabled = false))
    }

    @Test
    fun manualRescheduleKeepsASingleDeadline() {
        val scheduler = AutoPlayScheduler()
        val first = scheduler.replace(nowMillis = 0L, speed = 1f, typewriterDurationMillis = 4_000L, typewriterEnabled = true)
        assertEquals(2_000L, scheduler.deadlineMillis)
        assertEquals(1, scheduler.pendingCount())

        val second = scheduler.replace(nowMillis = 300L, speed = 1f, typewriterDurationMillis = 4_000L, typewriterEnabled = true)
        assertFalse(scheduler.isCurrent(first))
        assertTrue(scheduler.isCurrent(second))
        assertEquals(1, scheduler.pendingCount())
        assertEquals(2_300L, scheduler.deadlineMillis)
        assertEquals(2_000L, scheduler.delayMillis(300L))

        scheduler.clear()
        assertEquals(0, scheduler.pendingCount())
        assertFalse(scheduler.isCurrent(second))
    }

    private fun pageOf(text: String) =
        PageComposer.compose(SentenceSegmenter.segment(text), charsPerLine = 20, linesPerPage = 8, textLength = text.length).single()
}