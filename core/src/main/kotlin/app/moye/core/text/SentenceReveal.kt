package app.moye.core.text

data class PageReveal(
    val revealedCount: Int,
    val typedChars: Int,
    val typing: Boolean,
    val anchorOffset: Int,
)

sealed class RevealStep {
    data class Updated(val reveal: PageReveal) : RevealStep()

    data object NextPage : RevealStep()

    data object PreviousPage : RevealStep()
}

/**
 * The right side advances one sentence. A new sentence types out, and a sentence
 * already reached opens in full. The left side returns one sentence, fully shown.
 * The first sentence asks for the previous page; the last asks for the next.
 * Auto Play still waits until the current sentence is fully visible.
 */
object SentenceReveal {
    fun pageIndexForOffset(pages: List<ReadingPage>, offset: Int): Int {
        if (pages.isEmpty()) return 0
        val index = pages.indexOfFirst { offset < it.end }
        return if (index == -1) pages.lastIndex else index
    }

    fun enter(page: ReadingPage, typewriterEnabled: Boolean): PageReveal {
        val first = page.sentences.firstOrNull()
            ?: return PageReveal(0, 0, false, page.start)
        if (!typewriterEnabled) return wholePage(page, first.startOffset)
        return revealSentence(1, first)
    }

    /**
     * A page the reader has already opened is shown in full.
     * The typewriter does not restart, and progress stays at the start of the page.
     */
    fun revisit(page: ReadingPage): PageReveal {
        val first = page.sentences.firstOrNull()
            ?: return PageReveal(0, 0, false, page.start)
        return wholePage(page, first.startOffset)
    }

    /**
     * Going backward opens the page in full.
     * Going forward does too once [furthestReachedOffset] has passed the page start.
     * A page that starts after that offset is new and keeps the progressive reveal.
     */
    fun opensInFull(forward: Boolean, pageStart: Int, furthestReachedOffset: Int): Boolean {
        if (!forward) return true
        return pageStart <= furthestReachedOffset
    }

    /** Last offset on a page the reader has left. The next page starts after it. */
    fun coveredOffset(page: ReadingPage): Int = (page.end - 1).coerceAtLeast(page.start)

    fun restore(page: ReadingPage, offset: Int, typewriterEnabled: Boolean): PageReveal {
        val positioned = positionedOnPage(page, offset)
        if (!typewriterEnabled && page.sentences.isNotEmpty()) {
            return wholePage(page, positioned.anchorOffset)
        }
        return positioned
    }

    fun onTap(
        page: ReadingPage,
        reveal: PageReveal,
        typewriterEnabled: Boolean,
        furthestReachedOffset: Int = -1,
    ): RevealStep {
        if (page.sentences.isEmpty()) return RevealStep.NextPage
        if (reveal.revealedCount >= page.sentences.size && reveal.revealedCount > 0) return RevealStep.NextPage
        val nextIndex = reveal.revealedCount.coerceAtLeast(0)
        if (nextIndex > page.sentences.lastIndex) return RevealStep.NextPage
        val sentence = page.sentences[nextIndex]
        val seen = sentence.startOffset <= furthestReachedOffset
        return RevealStep.Updated(showSentence(page, nextIndex, immediate = !typewriterEnabled || seen))
    }

    /** One sentence backward. The destination is fully shown and does not type out again. */
    fun onPrevious(page: ReadingPage, reveal: PageReveal): RevealStep {
        if (page.sentences.isEmpty() || reveal.revealedCount <= 1) return RevealStep.PreviousPage
        val current = (reveal.revealedCount - 1).coerceIn(0, page.sentences.lastIndex)
        if (current <= 0) return RevealStep.PreviousPage
        return RevealStep.Updated(showSentence(page, current - 1, immediate = true))
    }

    fun showSentence(page: ReadingPage, sentenceIndex: Int, immediate: Boolean): PageReveal {
        val sentence = page.sentences.getOrNull(sentenceIndex)
            ?: return PageReveal(0, 0, false, page.start)
        if (immediate) {
            return PageReveal(
                revealedCount = sentenceIndex + 1,
                typedChars = sentence.text.length,
                typing = false,
                anchorOffset = sentence.startOffset,
            )
        }
        return revealSentence(sentenceIndex + 1, sentence)
    }

    /**
     * Auto Play may show the next sentence only after the current one is fully visible.
     * A paragraph or page boundary does not skip that check.
     */
    fun onAutoPlay(page: ReadingPage, reveal: PageReveal, typewriterEnabled: Boolean): RevealStep {
        if (page.sentences.isEmpty()) return RevealStep.NextPage
        if (!typewriterEnabled) {
            if (reveal.revealedCount < page.sentences.size) {
                return RevealStep.Updated(wholePage(page, reveal.anchorOffset))
            }
            return RevealStep.NextPage
        }
        if (reveal.revealedCount <= 0) return RevealStep.Updated(enter(page, typewriterEnabled))
        val index = (reveal.revealedCount - 1).coerceIn(0, page.sentences.lastIndex)
        val current = page.sentences[index]
        if (reveal.typing || reveal.typedChars < current.text.length) {
            return RevealStep.Updated(reveal)
        }
        if (reveal.revealedCount < page.sentences.size) {
            val next = page.sentences[reveal.revealedCount]
            return RevealStep.Updated(revealSentence(reveal.revealedCount + 1, next))
        }
        return RevealStep.NextPage
    }

    fun tick(page: ReadingPage, reveal: PageReveal): PageReveal {
        if (!reveal.typing || reveal.revealedCount <= 0) return reveal.copy(typing = false)
        val text = page.sentences.getOrNull(reveal.revealedCount - 1)?.text.orEmpty()
        if (reveal.typedChars >= text.length) return reveal.copy(typedChars = text.length, typing = false)
        val next = nextCharBoundary(text, reveal.typedChars)
        return reveal.copy(typedChars = next, typing = next < text.length)
    }

    fun visiblePrefix(text: String, typedChars: Int): String {
        if (typedChars <= 0 || text.isEmpty()) return ""
        return text.substring(0, typedChars.coerceAtMost(text.length))
    }

    private fun positionedOnPage(page: ReadingPage, offset: Int): PageReveal {
        val first = page.sentences.firstOrNull()
            ?: return PageReveal(0, 0, false, page.start)
        val index = page.sentences.indexOfLast { it.startOffset <= offset }.coerceAtLeast(0)
        val sentence = page.sentences[index]
        val anchor = if (first.startOffset > offset && index == 0) first.startOffset else sentence.startOffset
        return PageReveal(
            revealedCount = index + 1,
            typedChars = sentence.text.length,
            typing = false,
            anchorOffset = anchor,
        )
    }

    private fun wholePage(page: ReadingPage, anchorOffset: Int): PageReveal {
        val last = page.sentences.last()
        return PageReveal(
            revealedCount = page.sentences.size,
            typedChars = last.text.length,
            typing = false,
            anchorOffset = anchorOffset,
        )
    }

    private fun revealSentence(
        revealedCount: Int,
        sentence: app.moye.core.model.ReadingUnit,
    ): PageReveal {
        val typed = if (sentence.text.isEmpty()) 0 else nextCharBoundary(sentence.text, 0)
        return PageReveal(
            revealedCount = revealedCount,
            typedChars = typed,
            typing = typed < sentence.text.length,
            anchorOffset = sentence.startOffset,
        )
    }

    private fun nextCharBoundary(text: String, typedChars: Int): Int {
        if (typedChars >= text.length) return text.length
        var next = typedChars + 1
        if (next < text.length && text[typedChars].isHighSurrogate() && text[next].isLowSurrogate()) next++
        return next.coerceAtMost(text.length)
    }
}
