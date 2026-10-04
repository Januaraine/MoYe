package app.moye.ui.reader

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.moye.MoYeApplication
import app.moye.R
import app.moye.ReaderKeyBridge
import app.moye.core.model.ContentError
import app.moye.core.model.PageTurnDirection
import app.moye.core.model.ReaderTheme
import app.moye.core.model.TypewriterSpeed
import app.moye.core.model.WritingMode
import app.moye.core.settings.ReaderSettings
import app.moye.core.text.PlaybackTiming
import app.moye.core.text.SentenceReveal
import app.moye.ui.theme.readerPalette

private enum class ReaderSheet { NONE, SETTINGS, CHAPTERS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(bookId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as MoYeApplication).container
    val readerOwner = remember(bookId) {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(readerOwner) {
        onDispose { readerOwner.viewModelStore.clear() }
    }
    val viewModel: ReaderViewModel = viewModel(
        viewModelStoreOwner = readerOwner,
        key = bookId,
        factory = ReaderViewModelFactory(container.repository, container.settings, bookId),
    )
    val state by viewModel.state.collectAsState()
    var sheet by remember { mutableStateOf(ReaderSheet.NONE) }
    var chromeVisible by remember { mutableStateOf(false) }
    val palette = readerPalette(state.settings.theme)
    val view = LocalView.current

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(viewModel, lifecycle) {
        ReaderKeyBridge.handler = viewModel::onCommand
        viewModel.onScreenVisible(true, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onActivityResume()
                Lifecycle.Event.ON_PAUSE -> viewModel.onActivityPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            ReaderKeyBridge.handler = null
            viewModel.onScreenVisible(false, false)
        }
    }
    androidx.compose.runtime.SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = palette.background.luminance() > 0.5f
    }
    BackHandler {
        if (sheet != ReaderSheet.NONE) sheet = ReaderSheet.NONE else onBack()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val headerVisible = chromeVisible || state.status == ReaderStatus.ERROR
        Row(
            Modifier
                .fillMaxWidth()
                .alpha(if (headerVisible) 1f else 0f)
                .padding(start = 4.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, enabled = headerVisible) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = palette.text)
            }
            Text(
                state.title,
                modifier = Modifier.weight(1f),
                color = palette.text,
                fontFamily = FontFamily.Serif,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.chapters.isNotEmpty()) {
                TextButton(onClick = { sheet = ReaderSheet.CHAPTERS }, enabled = headerVisible) {
                    Text(stringResource(R.string.chapters), color = palette.accent)
                }
            }
            IconButton(onClick = { sheet = ReaderSheet.SETTINGS }, enabled = headerVisible) {
                Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings), tint = palette.text)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state.status) {
                ReaderStatus.LOADING -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = palette.accent)
                ReaderStatus.ERROR -> Text(
                    contentErrorText(state.error),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = palette.text,
                    textAlign = TextAlign.Center,
                )
                ReaderStatus.READY -> ReadingBody(
                    state = state,
                    textColor = palette.text,
                    onTap = viewModel::onReadingTap,
                    onCenterTap = { chromeVisible = !chromeVisible },
                    onTurn = { forward -> viewModel.turnPage(forward) },
                    onLayout = viewModel::bindLayout,
                )
            }
        }
        Column(Modifier.fillMaxWidth().alpha(if (chromeVisible) 1f else 0f)) {
            Text(
                stringResource(
                    R.string.page_position,
                    (state.pageIndex + 1).coerceAtLeast(1),
                    state.pages.size.coerceAtLeast(1),
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                color = palette.muted,
                textAlign = TextAlign.Center,
            )
            ReaderBottomBar(
                state = state,
                palette = palette,
                enabled = chromeVisible,
                onPrevious = { viewModel.turnPage(forward = false) },
                onNext = { viewModel.turnPage(forward = true) },
                onToggle = viewModel::togglePlayback,
            )
        }
    }

    if (sheet != ReaderSheet.NONE) {
        ModalBottomSheet(
            onDismissRequest = { sheet = ReaderSheet.NONE },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = palette.background,
            contentColor = palette.text,
        ) {
            if (sheet == ReaderSheet.SETTINGS) {
                SettingsSheet(state, palette.text, palette.muted, viewModel::updateSettings)
            } else {
                ChapterSheet(state, palette.text, palette.muted) { offset ->
                    viewModel.goToChapter(offset)
                    sheet = ReaderSheet.NONE
                }
            }
        }
    }
}

@Composable
private fun ReadingBody(
    state: ReaderUiState,
    textColor: Color,
    onTap: () -> Unit,
    onCenterTap: () -> Unit,
    onTurn: (Boolean) -> Unit,
    onLayout: (Int, Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val density = LocalDensity.current
            val fontPx = with(density) { state.settings.fontSizeSp.sp.toPx() }
            val paddingPx = with(density) { 8.dp.toPx() }
            val textWidth = (with(density) { maxWidth.toPx() } - with(density) { 56.dp.toPx() }).coerceAtLeast(fontPx)
            val textHeight = (with(density) { maxHeight.toPx() } - with(density) { 24.dp.toPx() }).coerceAtLeast(fontPx)
            val charsPerLine: Int
            val linesPerPage: Int
            if (state.writingMode == WritingMode.VERTICAL) {
                val (chars, columns) = verticalCapacity(
                    textWidth,
                    textHeight,
                    fontPx,
                    state.settings.letterSpacingEm,
                    state.settings.lineHeight,
                    paddingPx,
                )
                charsPerLine = chars
                linesPerPage = columns
            } else {
                val charWidth = fontPx * (1f + state.settings.letterSpacingEm)
                val lineHeightPx = fontPx * state.settings.lineHeight
                charsPerLine = (textWidth / charWidth).toInt().coerceAtLeast(1)
                val rawLines = (textHeight / lineHeightPx).toInt().coerceAtLeast(1)
                linesPerPage = if (rawLines > 2) rawLines - 1 else rawLines
            }
            LaunchedEffect(state.text, charsPerLine, linesPerPage, state.writingMode) {
                onLayout(charsPerLine, linesPerPage)
            }
            if (state.pages.isEmpty()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = textColor)
            } else {
                val page = state.pages.getOrNull(state.pageIndex)
                ReadingSurface(
                    direction = state.settings.pageTurnDirection,
                    onTap = onTap,
                    onCenterTap = onCenterTap,
                    onTurn = onTurn,
                ) {
                    if (page == null || page.sentences.isEmpty()) {
                        Text(
                            stringResource(R.string.empty_content),
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            color = textColor,
                            textAlign = TextAlign.Center,
                        )
                    } else if (state.writingMode == WritingMode.VERTICAL) {
                        val visible = visiblePageText(page.sentences, state.revealedCount, state.typedChars)
                        val verticalPage = remember(visible, textWidth, textHeight, fontPx, state.settings.letterSpacingEm, state.settings.lineHeight) {
                            paginateVerticalFor(
                                visible,
                                textWidth,
                                textHeight,
                                fontPx,
                                state.settings.letterSpacingEm,
                                state.settings.lineHeight,
                                paddingPx,
                            ).first()
                        }
                        VerticalPageCanvas(
                            text = visible,
                            page = verticalPage,
                            color = textColor,
                            fontSizePx = fontPx,
                            letterSpacingEm = state.settings.letterSpacingEm,
                            lineHeight = state.settings.lineHeight,
                            paddingPx = paddingPx,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    } else {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 28.dp, vertical = 18.dp),
                            verticalArrangement = Arrangement.spacedBy((state.settings.fontSizeSp * state.settings.lineHeight * 0.65f).dp),
                        ) {
                            val shownCount = state.revealedCount.coerceIn(0, page.sentences.size)
                            for (index in 0 until shownCount) {
                                val sentence = page.sentences[index]
                                val text = if (index == shownCount - 1) {
                                    SentenceReveal.visiblePrefix(sentence.text, state.typedChars)
                                } else {
                                    sentence.text
                                }
                                Text(
                                    text = text,
                                    color = textColor,
                                    fontFamily = FontFamily.Serif,
                                    fontSize = state.settings.fontSizeSp.sp,
                                    letterSpacing = state.settings.letterSpacingEm.em,
                                    lineHeight = (state.settings.fontSizeSp * state.settings.lineHeight).sp,
                                    textAlign = TextAlign.Start,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun visiblePageText(
    sentences: List<app.moye.core.model.ReadingUnit>,
    revealedCount: Int,
    typedChars: Int,
): String {
    val count = revealedCount.coerceIn(0, sentences.size)
    return buildString {
        for (index in 0 until count) {
            if (index > 0) append('\n')
            val sentence = sentences[index]
            append(
                if (index == count - 1) SentenceReveal.visiblePrefix(sentence.text, typedChars) else sentence.text,
            )
        }
    }
}

@Composable
private fun ReaderBottomBar(
    state: ReaderUiState,
    palette: app.moye.ui.theme.ReaderPalette,
    enabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onPrevious, enabled = enabled) {
            Text(stringResource(R.string.previous), color = palette.accent)
        }
        IconButton(onClick = onToggle, enabled = enabled) {
            Icon(
                if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(if (state.playing) R.string.pause else R.string.play),
                tint = palette.accent,
            )
        }
        Text(stringResource(R.string.speed_value, state.settings.playbackSpeed), color = palette.muted)
        TextButton(onClick = onNext, enabled = enabled) {
            Text(stringResource(R.string.next), color = palette.accent)
        }
    }
}

@Composable
private fun SettingsSheet(
    state: ReaderUiState,
    textColor: androidx.compose.ui.graphics.Color,
    muted: androidx.compose.ui.graphics.Color,
    onChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
) {
    val settings = state.settings
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.settings), color = textColor, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
        Text(stringResource(R.string.typewriter_speed), color = textColor)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TypewriterChip(TypewriterSpeed.SLOW, R.string.typewriter_slow, settings.typewriterSpeed, onChange)
            TypewriterChip(TypewriterSpeed.NORMAL, R.string.typewriter_normal, settings.typewriterSpeed, onChange)
            TypewriterChip(TypewriterSpeed.FAST, R.string.typewriter_fast, settings.typewriterSpeed, onChange)
        }
        FilterChip(
            selected = !settings.typewriterEnabled,
            onClick = { onChange { it.copy(typewriterEnabled = !it.typewriterEnabled) } },
            label = { Text(stringResource(R.string.typewriter_disable)) },
        )
        Text(stringResource(R.string.typewriter_hint), color = muted, style = MaterialTheme.typography.bodySmall)
        SettingSlider(stringResource(R.string.font_size), settings.fontSizeSp, 14f, 36f, textColor) { value ->
            onChange { it.copy(fontSizeSp = value) }
        }
        SettingSlider(stringResource(R.string.letter_spacing), settings.letterSpacingEm, 0f, 0.3f, textColor) { value ->
            onChange { it.copy(letterSpacingEm = value) }
        }
        SettingSlider(stringResource(R.string.line_spacing), settings.lineHeight, 1.1f, 2.2f, textColor) { value ->
            onChange { it.copy(lineHeight = value) }
        }
        Text(stringResource(R.string.theme), color = textColor)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeChip(ReaderTheme.PAPER, R.string.theme_paper, settings.theme, onChange)
            ThemeChip(ReaderTheme.LIGHT, R.string.theme_light, settings.theme, onChange)
            ThemeChip(ReaderTheme.DARK, R.string.theme_dark, settings.theme, onChange)
        }
        SettingSlider(
            stringResource(R.string.playback_speed),
            settings.playbackSpeed,
            PlaybackTiming.MIN_SPEED,
            PlaybackTiming.MAX_SPEED,
            textColor,
        ) { value ->
            onChange { it.copy(playbackSpeed = value) }
        }
        Text(stringResource(R.string.playback_speed_hint), color = muted, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.page_direction), color = textColor)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.pageTurnDirection == PageTurnDirection.HORIZONTAL,
                onClick = { onChange { it.copy(pageTurnDirection = PageTurnDirection.HORIZONTAL) } },
                label = { Text(stringResource(R.string.direction_horizontal)) },
            )
            FilterChip(
                selected = settings.pageTurnDirection == PageTurnDirection.VERTICAL,
                onClick = { onChange { it.copy(pageTurnDirection = PageTurnDirection.VERTICAL) } },
                label = { Text(stringResource(R.string.direction_vertical)) },
            )
        }
        if (state.writingModeLocked) {
            val label = if (state.writingMode == WritingMode.VERTICAL) {
                stringResource(R.string.text_vertical)
            } else {
                stringResource(R.string.text_horizontal)
            }
            Text(stringResource(R.string.epub_direction, label), color = muted)
        } else {
            Text(stringResource(R.string.text_direction), color = textColor)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.txtWritingMode == WritingMode.HORIZONTAL,
                    onClick = { onChange { it.copy(txtWritingMode = WritingMode.HORIZONTAL) } },
                    label = { Text(stringResource(R.string.text_horizontal)) },
                )
                FilterChip(
                    selected = settings.txtWritingMode == WritingMode.VERTICAL,
                    onClick = { onChange { it.copy(txtWritingMode = WritingMode.VERTICAL) } },
                    label = { Text(stringResource(R.string.text_vertical)) },
                )
            }
        }
    }
}

@Composable
private fun TypewriterChip(
    speed: TypewriterSpeed,
    label: Int,
    selected: TypewriterSpeed,
    onChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
) {
    FilterChip(
        selected = selected == speed,
        onClick = { onChange { it.copy(typewriterSpeed = speed) } },
        label = { Text(stringResource(label)) },
    )
}

@Composable
private fun ThemeChip(
    theme: ReaderTheme,
    label: Int,
    selected: ReaderTheme,
    onChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
) {
    FilterChip(
        selected = selected == theme,
        onClick = { onChange { it.copy(theme = theme) } },
        label = { Text(stringResource(label)) },
    )
}

@Composable
private fun SettingSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    color: androidx.compose.ui.graphics.Color,
    onChange: (Float) -> Unit,
) {
    Text("$label  ${"%.1f".format(value)}", color = color)
    Slider(value = value, onValueChange = onChange, valueRange = min..max)
}

@Composable
private fun ChapterSheet(
    state: ReaderUiState,
    textColor: androidx.compose.ui.graphics.Color,
    muted: androidx.compose.ui.graphics.Color,
    onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.chapters),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = textColor,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = FontFamily.Serif,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = {
                val target = app.moye.core.text.ChapterNavigation.previousTarget(state.chapters, state.offset)
                if (target != null) onSelect(target)
            }) { Text(stringResource(R.string.previous_chapter)) }
            TextButton(onClick = {
                val target = app.moye.core.text.ChapterNavigation.nextTarget(state.chapters, state.offset)
                if (target != null) onSelect(target)
            }) { Text(stringResource(R.string.next_chapter)) }
        }
        if (state.chapters.isEmpty()) {
            Text(stringResource(R.string.no_chapters), color = muted, modifier = Modifier.padding(12.dp))
        } else {
            LazyColumn {
                items(state.chapters) { chapter ->
                    TextButton(onClick = { onSelect(chapter.startOffset) }, modifier = Modifier.fillMaxWidth()) {
                        Text(chapter.title, color = textColor, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                    }
                }
            }
        }
    }
}

@Composable
private fun contentErrorText(error: ContentError?): String = when (error) {
    ContentError.EMPTY -> stringResource(R.string.empty_content)
    ContentError.CORRUPT -> stringResource(R.string.content_corrupt)
    ContentError.MISSING, null -> stringResource(R.string.content_missing)
}
