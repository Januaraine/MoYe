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
}

/**
 * With the typewriter on, a page reveals one sentence at a time and a tap
 * finishes the current sentence before the next one appears.
 * With the typewriter off, the whole page is visible at once and the next
 * action turns the page.
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

    fun restore(page: ReadingPage, offset: Int, typewriterEnabled: Boolean): PageReveal {
        val positioned = positionedOnPage(page, offset)
        if (!typewriterEnabled && page.sentences.isNotEmpty()) {
            return wholePage(page, positioned.anchorOffset)
        }
        return positioned
    }

    fun onTap(page: ReadingPage, reveal: PageReveal, typewriterEnabled: Boolean): RevealStep {
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
        if (reveal.typing && reveal.typedChars < current.text.length) {
            return RevealStep.Updated(
                reveal.copy(typedChars = current.text.length, typing = false, anchorOffset = current.startOffset),
            )
        }
        if (reveal.revealedCount < page.sentences.size) {
            val nextIndex = reveal.revealedCount + 1
            return RevealStep.Updated(revealSentence(nextIndex, page.sentences[nextIndex - 1]))
        }
        return RevealStep.NextPage
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
