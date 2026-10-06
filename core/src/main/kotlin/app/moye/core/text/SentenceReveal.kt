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
 * Sentence reveal inside one page. Typing a sentence is not the same as turning
 * the page: a tap completes the current sentence before the next sentence appears.
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
        return revealSentence(page, 1, typewriterEnabled, first)
    }

    fun restore(page: ReadingPage, offset: Int): PageReveal {
        val first = page.sentences.firstOrNull()
            ?: return PageReveal(0, 0, false, page.start)
        val index = page.sentences.indexOfLast { it.startOffset <= offset }.coerceAtLeast(0)
        val sentence = page.sentences[index]
        return PageReveal(
            revealedCount = index + 1,
            typedChars = sentence.text.length,
            typing = false,
            anchorOffset = sentence.startOffset,
        ).let { reveal ->
            if (first.startOffset > offset && index == 0) {
                reveal.copy(anchorOffset = first.startOffset)
            } else {
                reveal
            }
        }
    }

    fun onTap(page: ReadingPage, reveal: PageReveal, typewriterEnabled: Boolean): RevealStep {
        if (page.sentences.isEmpty()) return RevealStep.NextPage
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
            return RevealStep.Updated(revealSentence(page, nextIndex, typewriterEnabled, page.sentences[nextIndex - 1]))
        }
        return RevealStep.NextPage
    }

    /**
     * Auto Play moves to the next sentence when the sentence budget ends.
     * A sentence that is still typing is shown in full as the next one appears;
     * finishing the typewriter is not an extra step.
     */
    fun onAutoPlay(page: ReadingPage, reveal: PageReveal, typewriterEnabled: Boolean): RevealStep {
        if (page.sentences.isEmpty()) return RevealStep.NextPage
        if (reveal.revealedCount <= 0) return RevealStep.Updated(enter(page, typewriterEnabled))
        if (reveal.revealedCount < page.sentences.size) {
            val next = page.sentences[reveal.revealedCount]
            return RevealStep.Updated(revealSentence(page, reveal.revealedCount + 1, typewriterEnabled, next))
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

    private fun revealSentence(
        page: ReadingPage,
        revealedCount: Int,
        typewriterEnabled: Boolean,
        sentence: app.moye.core.model.ReadingUnit,
    ): PageReveal {
        val animate = typewriterEnabled && sentence.text.isNotEmpty()
        val typed = if (animate) nextCharBoundary(sentence.text, 0) else sentence.text.length
        return PageReveal(
            revealedCount = revealedCount,
            typedChars = typed,
            typing = animate && typed < sentence.text.length,
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
