package app.moye.ui.reader

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import app.moye.core.model.PageTurnDirection
import app.moye.core.model.VerticalPage
import app.moye.core.text.Paginator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

data class LinePage(
    val startLine: Int,
    val endLine: Int,
    val start: Int,
    val end: Int,
)

class HorizontalPagination(
    val layout: StaticLayout,
    val paint: TextPaint,
    val pages: List<LinePage>,
)

suspend fun buildHorizontalPagination(
    text: String,
    widthPx: Int,
    heightPx: Int,
    fontSizePx: Float,
    letterSpacingEm: Float,
    lineHeight: Float,
): HorizontalPagination = withContext(Dispatchers.Default) {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = fontSizePx
        letterSpacing = letterSpacingEm
        typeface = Typeface.SERIF
    }
    val safeWidth = widthPx.coerceAtLeast(1)
    val safeHeight = heightPx.coerceAtLeast(1)
    val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, safeWidth)
        .setLineSpacing(0f, lineHeight)
        .setIncludePad(false)
        .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .build()
    val pages = mutableListOf<LinePage>()
    if (layout.lineCount == 0) {
        pages += LinePage(0, 0, 0, 0)
    }
    var line = 0
    while (line < layout.lineCount) {
        val top = layout.getLineTop(line)
        var endLine = line
        while (endLine < layout.lineCount && layout.getLineBottom(endLine) - top <= safeHeight) {
            endLine++
        }
        if (endLine == line) endLine = line + 1
        pages += LinePage(
            startLine = line,
            endLine = endLine,
            start = layout.getLineStart(line),
            end = layout.getLineEnd(endLine - 1),
        )
        line = endLine
    }
    HorizontalPagination(layout, paint, pages)
}

fun linePageIndex(pages: List<LinePage>, offset: Int): Int {
    if (pages.isEmpty()) return 0
    val index = pages.indexOfFirst { offset < it.end || it.start == it.end }
    return if (index == -1) pages.lastIndex else index
}

fun verticalCapacity(
    widthPx: Float,
    heightPx: Float,
    fontPx: Float,
    letterSpacingEm: Float,
    lineHeight: Float,
    paddingPx: Float,
): Pair<Int, Int> {
    val innerW = (widthPx - paddingPx * 2f).coerceAtLeast(fontPx)
    val innerH = (heightPx - paddingPx * 2f).coerceAtLeast(fontPx)
    val charAdvance = fontPx * (1f + letterSpacingEm)
    val columnAdvance = fontPx * lineHeight.coerceAtLeast(1.1f)
    val chars = (innerH / charAdvance).toInt().coerceAtLeast(1)
    val columns = (innerW / columnAdvance).toInt().coerceAtLeast(1)
    return chars to columns
}

fun paginateVerticalFor(
    text: String,
    widthPx: Float,
    heightPx: Float,
    fontPx: Float,
    letterSpacingEm: Float,
    lineHeight: Float,
    paddingPx: Float,
): List<VerticalPage> {
    val (chars, columns) = verticalCapacity(widthPx, heightPx, fontPx, letterSpacingEm, lineHeight, paddingPx)
    return Paginator.paginateVertical(text, chars, columns)
}

class PageTurnBridge {
    var onPlay: ((forward: Boolean, moved: Boolean) -> Unit)? = null

    fun play(forward: Boolean, moved: Boolean) {
        onPlay?.invoke(forward, moved)
    }
}

@Composable
fun SimulatedPage(
    direction: PageTurnDirection,
    bridge: PageTurnBridge,
    onTap: () -> Unit,
    onTurn: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val latestTurn by rememberUpdatedState(onTurn)
    val latestTap by rememberUpdatedState(onTap)

    fun animateTurn(forward: Boolean, moved: Boolean) {
        scope.launch {
            val sign = if (forward) 1f else -1f
            if (!moved) {
                offset.animateTo(sign * 0.08f, tween(70))
                offset.animateTo(0f, tween(90))
                return@launch
            }
            offset.snapTo(-sign * 0.45f)
            offset.animateTo(0f, tween(200, easing = FastOutSlowInEasing))
        }
    }

    DisposableEffect(bridge) {
        bridge.onPlay = { forward, moved -> animateTurn(forward, moved) }
        onDispose { bridge.onPlay = null }
    }

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(direction) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var drag = 0f
                    var pastSlop = false
                    val pointerId = down.id
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                        val delta = change.positionChange()
                        drag += if (direction == PageTurnDirection.HORIZONTAL) delta.x else delta.y
                        if (!pastSlop && abs(drag) > slop) pastSlop = true
                        if (pastSlop) {
                            change.consume()
                            val limit = if (direction == PageTurnDirection.HORIZONTAL) size.width.toFloat() else size.height.toFloat()
                            if (limit > 0f) {
                                scope.launch { offset.snapTo((drag / limit).coerceIn(-1f, 1f)) }
                            }
                        }
                        if (!change.pressed) {
                            if (!pastSlop) {
                                scope.launch { offset.snapTo(0f) }
                                latestTap()
                            } else {
                                val limit = if (direction == PageTurnDirection.HORIZONTAL) size.width.toFloat() else size.height.toFloat()
                                val threshold = limit * 0.18f
                                when {
                                    drag <= -threshold -> latestTurn(true)
                                    drag >= threshold -> latestTurn(false)
                                    else -> scope.launch { offset.animateTo(0f) }
                                }
                            }
                            break
                        }
                    }
                }
            }
            .graphicsLayer {
                cameraDistance = 18f * density
                if (direction == PageTurnDirection.HORIZONTAL) {
                    rotationY = -offset.value * 32f
                    translationX = offset.value * size.width * 0.06f
                } else {
                    rotationX = offset.value * 28f
                    translationY = offset.value * size.height * 0.06f
                }
            },
    ) {
        content()
        Canvas(Modifier.fillMaxSize()) {
            val strength = abs(offset.value)
            if (strength < 0.02f) return@Canvas
            val shadow = Color.Black.copy(alpha = 0.28f * strength)
            if (direction == PageTurnDirection.HORIZONTAL) {
                val fromLeft = offset.value > 0f
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            if (fromLeft) shadow else Color.Transparent,
                            if (fromLeft) Color.Transparent else shadow,
                        ),
                    ),
                )
            } else {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(shadow, Color.Transparent),
                        startY = 0f,
                        endY = size.height * 0.4f,
                    ),
                )
            }
        }
    }
}

@Composable
fun HorizontalPageCanvas(
    pagination: HorizontalPagination,
    page: LinePage,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.fillMaxSize()) {
        pagination.paint.color = color.toArgb()
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.clipRect(0f, 0f, size.width, size.height)
            val top = if (page.startLine >= pagination.layout.lineCount) 0 else pagination.layout.getLineTop(page.startLine)
            native.translate(0f, -top.toFloat())
            pagination.layout.draw(native)
            native.restore()
        }
    }
}

@Composable
fun VerticalPageCanvas(
    text: String,
    page: VerticalPage,
    color: Color,
    fontSizePx: Float,
    letterSpacingEm: Float,
    lineHeight: Float,
    paddingPx: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.fillMaxSize()) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSizePx
            this.color = color.toArgb()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.SERIF
        }
        val charAdvance = fontSizePx * (1f + letterSpacingEm)
        val columnAdvance = fontSizePx * lineHeight.coerceAtLeast(1.1f)
        page.columns.forEachIndexed { index, column ->
            val x = size.width - paddingPx - columnAdvance * index - columnAdvance / 2f
            var y = paddingPx + fontSizePx
            var cursor = column.start
            while (cursor < column.end && cursor < text.length) {
                val ch = text[cursor]
                if (ch != '\n') {
                    val rotate = ch == '，' || ch == '。' || ch == '、'
                    drawContext.canvas.nativeCanvas.save()
                    if (rotate) drawContext.canvas.nativeCanvas.rotate(90f, x, y)
                    drawContext.canvas.nativeCanvas.drawText(ch.toString(), x, y, paint)
                    drawContext.canvas.nativeCanvas.restore()
                    y += charAdvance
                }
                cursor++
            }
        }
    }
}
