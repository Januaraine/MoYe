package app.moye.core.text

import app.moye.core.model.Chapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChapterAndPageTest {
    @Test
    fun detectsChineseAndEnglishChapterTitles() {
        val text = "序\n第一章 启程\n正文。\nChapter 2 Away\nMore.\n"
        val titles = ChapterDetector.detect(text).map { it.title }
        assertEquals(listOf("第一章 启程", "Chapter 2 Away"), titles)
        assertEquals(text.indexOf("第一章"), ChapterDetector.detect(text)[0].startOffset)
    }

    @Test
    fun chapterJumpsMoveToChapterStarts() {
        val chapters = listOf(Chapter("第一章", 0), Chapter("第二章", 10), Chapter("第三章", 30))
        assertEquals(1, ChapterNavigation.indexAt(chapters, 15))
        assertEquals(10, ChapterNavigation.previousTarget(chapters, 15))
        assertEquals(0, ChapterNavigation.previousTarget(chapters, 10))
        assertNull(ChapterNavigation.previousTarget(chapters, 0))
        assertEquals(30, ChapterNavigation.nextTarget(chapters, 15))
        assertNull(ChapterNavigation.nextTarget(chapters, 30))
    }

    @Test
    fun gridPagesCoverTheWholeText() {
        val text = "abcdefghij\nklm"
        val pages = Paginator.paginateByGrid(text, charsPerLine = 4, linesPerPage = 2)
        assertEquals(0, pages.first().start)
        assertEquals(text.length, pages.last().end)
        assertEquals(text, pages.joinToString("") { text.substring(it.start, it.end) })
        assertEquals(0, ReadingProgress.spanIndexForOffset(pages, 0))
    }

    @Test
    fun verticalPagesKeepColumnsInOrder() {
        val text = "甲乙丙丁戊\n己庚"
        val pages = Paginator.paginateVertical(text, charsPerColumn = 3, columnsPerPage = 2)
        assertEquals(text, pages.joinToString("") { page ->
            page.columns.joinToString("") { text.substring(it.start, it.end) }
        })
        assertEquals(0, ReadingProgress.verticalPageIndex(pages, 0))
        assertEquals(pages.lastIndex, ReadingProgress.verticalPageIndex(pages, text.length))
    }

    @Test
    fun longerSentencesAndHigherSpeedChangeDwellTime() {
        val short = PlaybackTiming.dwellMillis("Hi.", 1f)
        val longer = PlaybackTiming.dwellMillis("This sentence is much longer than the other one.", 1f)
        val faster = PlaybackTiming.dwellMillis("This sentence is much longer than the other one.", 2f)
        assertTrue(longer > short)
        assertTrue(faster < longer)
    }
}
