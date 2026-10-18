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
    fun paragraphsFollowEachOtherWithoutABlankLine() {
        val together = PageComposer.compose(
            SentenceSegmenter.segment("甲。乙。"),
            charsPerLine = 10,
            linesPerPage = 1,
            textLength = "甲。乙。".length,
        )
        val broken = PageComposer.compose(
            SentenceSegmenter.segment("甲。\n乙。"),
            charsPerLine = 10,
            linesPerPage = 1,
            textLength = "甲。\n乙。".length,
        )
        val roomForBothLines = PageComposer.compose(
            SentenceSegmenter.segment("甲。\n乙。"),
            charsPerLine = 10,
            linesPerPage = 2,
            textLength = "甲。\n乙。".length,
        )
        assertEquals(1, together.size)
        assertEquals(2, together.single().sentences.size)
        assertEquals(2, broken.size)
        assertEquals("乙。", broken.last().sentences.single().text)
        assertEquals(1, roomForBothLines.size)
    }

    @Test
    fun latinLettersTakeHalfACellSoThePageFills() {
        val text = "a".repeat(20)
        val pages = PageComposer.compose(
            SentenceSegmenter.segment(text),
            charsPerLine = 10,
            linesPerPage = 1,
            textLength = text.length,
        )
        assertEquals(1, pages.size)
        assertEquals(text, pages.single().sentences.joinToString("") { it.text })
    }

    @Test
    fun aNarrowerLatinAdvancePutsMoreTextOnThePage() {
        val text = "a".repeat(20)
        val units = SentenceSegmenter.segment(text)
        val wide = PageComposer.compose(units, charsPerLine = 10, linesPerPage = 1, textLength = text.length, latinAdvance = 1f)
        val narrow = PageComposer.compose(units, charsPerLine = 10, linesPerPage = 1, textLength = text.length, latinAdvance = 0.5f)
        assertTrue(wide.size > narrow.size)
        assertEquals(1, narrow.size)
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
    fun rightTapAdvancesOneSentenceWhileTheCurrentOneIsStillTyping() {
        val page = pageOf(sample, linesPerPage = 8)
        assertTrue(page.sentences.size > 1)
        val typing = SentenceReveal.enter(page, typewriterEnabled = true)
        assertTrue(typing.typing)
        assertEquals("这", SentenceReveal.visiblePrefix(page.sentences[0].text, typing.typedChars))

        val next = assertIs<RevealStep.Updated>(
            SentenceReveal.onTap(page, typing, typewriterEnabled = true),
        ).reveal
        assertEquals(2, next.revealedCount)
        assertEquals(page.sentences[1].startOffset, next.anchorOffset)
        assertEquals(page.sentences[1].paragraphIndex, page.sentences[next.revealedCount - 1].paragraphIndex)
        assertTrue(next.typing)
        assertEquals("窗", SentenceReveal.visiblePrefix(page.sentences[1].text, next.typedChars))
    }

    @Test
    fun leftTapMovesBackExactlyOneSentence() {
        val page = pageOf(sample, linesPerPage = 8)
        assertTrue(page.sentences.size >= 3)
        var reveal = SentenceReveal.enter(page, typewriterEnabled = true)
        reveal = assertIs<RevealStep.Updated>(SentenceReveal.onTap(page, reveal, typewriterEnabled = true)).reveal
        reveal = assertIs<RevealStep.Updated>(SentenceReveal.onTap(page, reveal, typewriterEnabled = true)).reveal
        assertEquals(3, reveal.revealedCount)

        val back = assertIs<RevealStep.Updated>(SentenceReveal.onPrevious(page, reveal)).reveal
        assertEquals(2, back.revealedCount)
        assertFalse(back.typing)
        assertEquals(page.sentences[1].text, SentenceReveal.visiblePrefix(page.sentences[1].text, back.typedChars))
        assertEquals(page.sentences[1].startOffset, back.anchorOffset)
        assertEquals(page.sentences[1].sentenceIndex, page.sentences[back.revealedCount - 1].sentenceIndex)

        val again = assertIs<RevealStep.Updated>(SentenceReveal.onPrevious(page, back)).reveal
        assertEquals(1, again.revealedCount)
        assertFalse(again.typing)
        assertIs<RevealStep.PreviousPage>(SentenceReveal.onPrevious(page, again))
    }

    @Test
    fun leftTapOnTheFirstSentenceAsksForThePreviousPage() {
        val pages = PageComposer.compose(
            SentenceSegmenter.segment(sample),
            charsPerLine = 6,
            linesPerPage = 2,
            textLength = sample.length,
        )
        assertTrue(pages.size >= 2)
        val origin = SentenceReveal.enter(pages[1], typewriterEnabled = true)
        assertIs<RevealStep.PreviousPage>(SentenceReveal.onPrevious(pages[1], origin))

        val previous = pages[0]
        val landed = SentenceReveal.showSentence(previous, previous.sentences.lastIndex, immediate = true)
        assertEquals(previous.sentences.size, landed.revealedCount)
        assertEquals(previous.sentences.last().text.length, landed.typedChars)
        assertEquals(previous.sentences.last().startOffset, landed.anchorOffset)
        assertFalse(landed.typing)
        assertEquals(landed, SentenceReveal.tick(previous, landed))
    }

    @Test
    fun aVisitedSentenceOpensInFullInsteadOfTypingAgain() {
        val page = pageOf(sample, linesPerPage = 8)
        val first = SentenceReveal.enter(page, typewriterEnabled = true)
        val second = assertIs<RevealStep.Updated>(
            SentenceReveal.onTap(page, first, typewriterEnabled = true),
        ).reveal
        val reached = page.sentences[1].endOffset - 1
        val back = assertIs<RevealStep.Updated>(SentenceReveal.onPrevious(page, second)).reveal
        val again = assertIs<RevealStep.Updated>(
            SentenceReveal.onTap(page, back, typewriterEnabled = true, furthestReachedOffset = reached),
        ).reveal
        assertEquals(2, again.revealedCount)
        assertFalse(again.typing)
        assertEquals(page.sentences[1].text.length, again.typedChars)
        assertEquals(page.sentences[1].startOffset, again.anchorOffset)
    }

    @Test
    fun tapAfterTheLastSentenceAsksForTheNextPage() {
        val page = pageOf(sample, linesPerPage = 8)
        var reveal = SentenceReveal.enter(page, typewriterEnabled = true)
        while (reveal.revealedCount < page.sentences.size || reveal.typing) {
            if (reveal.typing) {
                reveal = SentenceReveal.tick(page, reveal)
            } else {
                reveal = assertIs<RevealStep.Updated>(SentenceReveal.onTap(page, reveal, typewriterEnabled = true)).reveal
            }
        }
        assertEquals(page.sentences.size, reveal.revealedCount)
        assertIs<RevealStep.NextPage>(SentenceReveal.onTap(page, reveal, typewriterEnabled = true))
    }

    @Test
    fun returningToAVisitedPageShowsAllTextWithoutRestartingTheTypewriter() {
        val pages = PageComposer.compose(
            SentenceSegmenter.segment(sample),
            charsPerLine = 6,
            linesPerPage = 2,
            textLength = sample.length,
        )
        assertTrue(pages.size >= 2)
        val earlier = pages[0]
        val origin = pages[1]
        var reached = -1

        assertFalse(SentenceReveal.opensInFull(forward = true, origin.start, reached))
        val firstLook = SentenceReveal.enter(origin, typewriterEnabled = true)
        assertEquals(1, firstLook.revealedCount)
        assertTrue(firstLook.revealedCount < origin.sentences.size || firstLook.typing)

        reached = reached.coerceAtLeast(SentenceReveal.coveredOffset(origin))
        assertTrue(origin.start <= reached)
        assertTrue(SentenceReveal.coveredOffset(origin) < pages.getOrNull(2)?.start ?: Int.MAX_VALUE)
        assertFalse(SentenceReveal.opensInFull(forward = true, origin.end, reached))

        assertTrue(SentenceReveal.opensInFull(forward = false, earlier.start, reached))
        val back = SentenceReveal.revisit(earlier)
        assertEquals(earlier.sentences.size, back.revealedCount)
        assertEquals(earlier.sentences.last().text.length, back.typedChars)
        assertFalse(back.typing)
        assertEquals(earlier.sentences.first().startOffset, back.anchorOffset)
        assertEquals(back, SentenceReveal.tick(earlier, back))
        assertIs<RevealStep.NextPage>(SentenceReveal.onTap(earlier, back, typewriterEnabled = true))
        assertIs<RevealStep.NextPage>(SentenceReveal.onAutoPlay(earlier, back, typewriterEnabled = true))

        assertTrue(SentenceReveal.opensInFull(forward = true, origin.start, reached))
        val returned = SentenceReveal.revisit(origin)
        assertEquals(origin.sentences.size, returned.revealedCount)
        assertEquals(origin.sentences.last().text.length, returned.typedChars)
        assertFalse(returned.typing)
        assertEquals(returned, SentenceReveal.tick(origin, returned))
        assertIs<RevealStep.NextPage>(SentenceReveal.onTap(origin, returned, typewriterEnabled = true))
        assertIs<RevealStep.NextPage>(SentenceReveal.onAutoPlay(origin, returned, typewriterEnabled = true))

        val unread = pages.last()
        if (unread.start > reached) {
            assertFalse(SentenceReveal.opensInFull(forward = true, unread.start, reached))
            val fresh = SentenceReveal.enter(unread, typewriterEnabled = true)
            assertEquals(1, fresh.revealedCount)
        }
    }

    @Test
    fun disablingTypewriterShowsTheWholePageAtOnce() {
        val page = pageOf(sample, linesPerPage = 8)
        val shown = SentenceReveal.enter(page, typewriterEnabled = false)
        assertEquals(page.sentences.size, shown.revealedCount)
        assertFalse(shown.typing)
        assertEquals(page.sentences.last().text.length, shown.typedChars)
        assertIs<RevealStep.NextPage>(SentenceReveal.onTap(page, shown, typewriterEnabled = false))
        assertIs<RevealStep.NextPage>(SentenceReveal.onAutoPlay(page, shown, typewriterEnabled = false))
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
