package app.moye.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrossPageContinuationTest {

    // 1. 同一段落三句话：依次正常显现。
    @Test
    fun threeSentencesInOneParagraphAdvanceInOrder() {
        val page = pageOf("第一句。第二句。第三句。")
        assertEquals(3, page.sentences.size)
        assertEquals(listOf(0, 0, 0), page.sentences.map { it.paragraphIndex })

        var reveal = SentenceReveal.enter(page, typewriterEnabled = true)
        val seen = mutableListOf(page.sentences[0].text)
        while (reveal.revealedCount < page.sentences.size) {
            reveal = reveal.copy(
                typedChars = page.sentences[reveal.revealedCount - 1].text.length,
                typing = false,
            )
            reveal = (SentenceReveal.onAutoPlay(page, reveal, true) as RevealStep.Updated).reveal
            seen += page.sentences[reveal.revealedCount - 1].text
        }
        assertEquals(listOf("第一句。", "第二句。", "第三句。"), seen)
    }

    // 2. 两个段落各两句：跨段落的句间节奏与段内一致（同一推进序列）。
    @Test
    fun twoParagraphsAdvanceAsOneFlatSequence() {
        val page = pageOf("甲。乙。\n丙。丁。")
        assertEquals(listOf(0, 0, 1, 1), page.sentences.map { it.paragraphIndex })

        var reveal = SentenceReveal.enter(page, typewriterEnabled = true)
        val seen = mutableListOf(page.sentences[0].text)
        while (reveal.revealedCount < page.sentences.size) {
            reveal = reveal.copy(
                typedChars = page.sentences[reveal.revealedCount - 1].text.length,
                typing = false,
            )
            reveal = (SentenceReveal.onAutoPlay(page, reveal, true) as RevealStep.Updated).reveal
            seen += page.sentences[reveal.revealedCount - 1].text
        }
        assertEquals(listOf("甲。", "乙。", "丙。", "丁。"), seen)
    }

    // 3. 段落之间有空行：不产生额外句子，也不增加一次推进等待。
    @Test
    fun blankLineBetweenParagraphsAddsNoSentence() {
        val units = SentenceSegmenter.segment("甲。\n\n乙。")
        assertEquals(listOf("甲。", "乙。"), units.map { it.text })
        assertEquals(listOf(0, 1), units.map { it.paragraphIndex })
        // 空行只体现在段落间偏移差上，不成为可播放的句子。
        assertEquals(2, units[1].startOffset - units[0].endOffset)
    }

    // 4. 一句话跨越两页：前半部分所在页被识别为“延续到下一页”。
    @Test
    fun sentenceSplitAcrossPagesIsDetectedAsContinuation() {
        val text = "甲".repeat(20)
        val pages = PageComposer.compose(
            SentenceSegmenter.segment(text),
            charsPerLine = 4,
            linesPerPage = 2,
            textLength = text.length,
        )
        assertTrue(pages.size >= 3)
        // 不丢字，切块连续（不重复、不遗漏）。
        assertEquals(text, pages.flatMap { it.sentences }.joinToString("") { it.text })
        assertTrue(SentenceReveal.continuesOnNextPage(pages[0], pages[1]))
        assertTrue(SentenceReveal.continuesOnNextPage(pages[1], pages[2]))
        assertFalse(SentenceReveal.continuesOnNextPage(pages[2], null))
    }

    // 5. 句末恰好位于页末：翻页后进入新句，不当作延续句。
    @Test
    fun sentenceEndingAtPageBoundaryIsNotAContinuation() {
        val text = "甲。乙。"
        val pages = PageComposer.compose(
            SentenceSegmenter.segment(text),
            charsPerLine = 2,
            linesPerPage = 1,
            textLength = text.length,
        )
        assertTrue(pages.size >= 2)
        assertFalse(SentenceReveal.continuesOnNextPage(pages[0], pages[1]))
    }

    private fun pageOf(text: String): ReadingPage =
        PageComposer.compose(
            SentenceSegmenter.segment(text),
            charsPerLine = 20,
            linesPerPage = 12,
            textLength = text.length,
        ).single()
}
