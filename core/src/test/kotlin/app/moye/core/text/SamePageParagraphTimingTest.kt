package app.moye.core.text

import kotlin.test.Test

/**
 * Reproduction probe for the reported "extra wait before the next paragraph".
 *
 * It replays the auto-play loop from ReaderViewModel against the real core
 * classes (SentenceSegmenter / PageComposer / SentenceReveal / PlaybackTiming /
 * TypewriterTiming) with a fake clock, so the inter-sentence advance times can
 * be compared without an emulator.
 *
 * The loop's `delay(wait)` always covers the remaining typewriter time because
 * wait = max(interval, remaining); the typewriter finishes inside that wait, so
 * it is modeled as completing at `remaining` milliseconds. The printed numbers
 * are therefore the same advance times the running app schedules.
 */
class SamePageParagraphTimingTest {

    @Test
    fun printAdvanceTimingAcrossParagraphBoundary() {
        val text = "第一段第一句。第一段第二句。\n第二段第一句。第二段第二句。"
        val units = SentenceSegmenter.segment(text)
        val page = PageComposer.compose(units, charsPerLine = 40, linesPerPage = 8, textLength = text.length).single()

        val speed = 1f
        val typewriterSpeed = 1f
        val interval = PlaybackTiming.intervalMillis(speed)
        val charMs = TypewriterTiming.millisPerCharacter(typewriterSpeed)

        println("== sentence segmentation (watch for invisible chars) ==")
        units.forEach { u ->
            println(
                "para=${u.paragraphIndex} sent=${u.sentenceIndex} len=${u.text.length} " +
                    "start=${u.startOffset} end=${u.endOffset} text=${u.text.toEscaped()}",
            )
        }
        println("page sentence count=${page.sentences.size}")
        println("interval=$interval ms, charMs=$charMs ms")
        println()

        var reveal = SentenceReveal.enter(page, typewriterEnabled = true)
        var now = 0L
        var previousRevealAt = 0L
        var previousPara = -1

        while (true) {
            val index = reveal.revealedCount - 1
            val sentence = page.sentences[index]

            val remaining = if (reveal.typing) {
                (sentence.text.length - reveal.typedChars).coerceAtLeast(0) * charMs
            } else {
                0L
            }
            val wait = PlaybackTiming.sentenceAdvanceDelay(remaining, speed, typewriterEnabled = true)
            val typedDoneAt = now + remaining
            val boundary = if (previousPara >= 0 && sentence.paragraphIndex != previousPara) "  <-- paragraph break" else ""
            val gap = if (previousPara >= 0) now - previousRevealAt else -1L

            println(
                "reveal #${reveal.revealedCount} para=${sentence.paragraphIndex} sent=${sentence.sentenceIndex} " +
                    "len=${sentence.text.length} at=$now gap_since_prev=$gap typed_done_at=$typedDoneAt$boundary",
            )

            previousRevealAt = now
            previousPara = sentence.paragraphIndex
            now += wait
            reveal = reveal.copy(typedChars = sentence.text.length, typing = false)

            val step = SentenceReveal.onAutoPlay(page, reveal, typewriterEnabled = true)
            when (step) {
                is RevealStep.Updated -> reveal = step.reveal
                is RevealStep.NextPage -> break
                is RevealStep.PreviousPage -> break
            }
        }
    }

    private fun String.toEscaped(): String {
        return buildString {
            for (ch in this@toEscaped) {
                when {
                    ch == '\n' -> append("\\n")
                    ch == '\r' -> append("\\r")
                    ch == '\t' -> append("\\t")
                    ch == ' ' -> append("·")
                    ch.isWhitespace() -> append("\\u%04x".format(ch.code))
                    else -> append(ch)
                }
            }
        }
    }
}
