package app.moye.ui.reader

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
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
import app.moye.core.model.ReadingMode
import app.moye.core.model.VerticalPage
import app.moye.core.model.WritingMode
import app.moye.core.settings.ReaderSettings
import app.moye.core.text.PlaybackTiming
import app.moye.core.text.ReadingProgress
import app.moye.ui.theme.readerPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val pageTurns = remember { PageTurnBridge() }
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
    LaunchedEffect(viewModel, pageTurns) {
        viewModel.pageTurns.collect { delta -> pageTurns.request(delta > 0) }
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
            .background(palette.background),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
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
                TextButton(onClick = { sheet = ReaderSheet.CHAPTERS }) {
                    Text(stringResource(R.string.chapters), color = palette.accent)
                }
            }
            IconButton(onClick = { sheet = ReaderSheet.SETTINGS }) {
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
                ReaderStatus.READY -> if (state.settings.readingMode == ReadingMode.SENTENCE) {
                    SentenceBody(state, palette.text, palette.muted, viewModel::previousUnit, viewModel::nextUnit)
                } else {
                    PagedBody(
                        state = state,
                        textColor = palette.text,
                        pageTurns = pageTurns,
                        onSeek = viewModel::seek,
                    )
                }
            }
        }
        ReaderBottomBar(
            state = state,
            palette = palette,
            onPrevious = {
                if (state.settings.readingMode == ReadingMode.SENTENCE) viewModel.previousUnit() else pageTurns.request(false)
            },
            onNext = {
                if (state.settings.readingMode == ReadingMode.SENTENCE) viewModel.nextUnit() else pageTurns.request(true)
            },
            onToggle = viewModel::togglePlayback,
        )
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
private fun SentenceBody(
    state: ReaderUiState,
    textColor: androidx.compose.ui.graphics.Color,
    muted: androidx.compose.ui.graphics.Color,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val index = ReadingProgress.unitIndexForOffset(state.units, state.offset)
    val unit = state.units.getOrNull(index)
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(index) {
                    detectTapGestures { position ->
                        if (position.x < size.width * 0.35f) onPrevious() else onNext()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = unit?.text?.trim().orEmpty().ifBlank { stringResource(R.string.empty_content) },
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
                color = textColor,
                fontFamily = FontFamily.Serif,
                fontSize = state.settings.fontSizeSp.sp,
                letterSpacing = state.settings.letterSpacingEm.em,
                lineHeight = (state.settings.fontSizeSp * state.settings.lineHeight).sp,
                textAlign = TextAlign.Start,
            )
        }
        Text(
            stringResource(R.string.sentence_position, (index + 1).coerceAtLeast(0), state.units.size),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            color = muted,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.tap_hint),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            color = muted,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun PagedBody(
    state: ReaderUiState,
    textColor: androidx.compose.ui.graphics.Color,
    pageTurns: PageTurnBridge,
    onSeek: (Int) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }.toInt()
        val heightPx = (with(density) { maxHeight.toPx() } - with(density) { 32.dp.toPx() }).toInt().coerceAtLeast(1)
        val fontPx = with(density) { state.settings.fontSizeSp.sp.toPx() }
        val paddingPx = with(density) { 8.dp.toPx() }
        if (state.writingMode == WritingMode.VERTICAL) {
            val pages = remember(state.text, widthPx, heightPx, fontPx, state.settings.letterSpacingEm, state.settings.lineHeight) {
                paginateVerticalFor(
                    state.text,
                    widthPx.toFloat(),
                    heightPx.toFloat(),
                    fontPx,
                    state.settings.letterSpacingEm,
                    state.settings.lineHeight,
                    paddingPx,
                )
            }
            val index = ReadingProgress.verticalPageIndex(pages, state.offset).coerceAtLeast(0)
            val page = pages.getOrNull(index) ?: VerticalPage(0, 0, emptyList())
            SimulatedPage(
                direction = state.settings.pageTurnDirection,
                bridge = pageTurns,
                onTurn = { forward ->
                    val target = pages.getOrNull(index + if (forward) 1 else -1) ?: return@SimulatedPage false
                    onSeek(target.start)
                    true
                },
            ) {
                Column(Modifier.fillMaxSize()) {
                    VerticalPageCanvas(
                        text = state.text,
                        page = page,
                        color = textColor,
                        fontSizePx = fontPx,
                        letterSpacingEm = state.settings.letterSpacingEm,
                        lineHeight = state.settings.lineHeight,
                        paddingPx = paddingPx,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.page_position, index + 1, pages.size.coerceAtLeast(1)),
                        color = textColor.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            var pagination by remember(state.text, widthPx, heightPx, fontPx, state.settings.letterSpacingEm, state.settings.lineHeight) {
                mutableStateOf<HorizontalPagination?>(null)
            }
            LaunchedEffect(state.text, widthPx, heightPx, fontPx, state.settings.letterSpacingEm, state.settings.lineHeight) {
                pagination = null
                pagination = withContext(Dispatchers.Default) {
                    buildHorizontalPagination(
                        state.text,
                        widthPx,
                        heightPx,
                        fontPx,
                        state.settings.letterSpacingEm,
                        state.settings.lineHeight,
                    )
                }
            }
            val ready = pagination
            if (ready == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = textColor)
            } else {
                val index = linePageIndex(ready.pages, state.offset)
                val page = ready.pages[index]
                SimulatedPage(
                    direction = state.settings.pageTurnDirection,
                    bridge = pageTurns,
                    onTurn = { forward ->
                        val target = ready.pages.getOrNull(index + if (forward) 1 else -1) ?: return@SimulatedPage false
                        onSeek(target.start)
                        true
                    },
                ) {
                    Column(Modifier.fillMaxSize()) {
                        HorizontalPageCanvas(
                            pagination = ready,
                            page = page,
                            color = textColor,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(R.string.page_position, index + 1, ready.pages.size),
                            color = textColor.copy(alpha = 0.7f),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(
    state: ReaderUiState,
    palette: app.moye.ui.theme.ReaderPalette,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggle: () -> Unit,
) {
    val sentence = state.settings.readingMode == ReadingMode.SENTENCE
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onPrevious) {
            Text(stringResource(if (sentence) R.string.previous_sentence else R.string.previous), color = palette.accent)
        }
        if (sentence) {
            IconButton(onClick = onToggle) {
                Icon(
                    if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (state.playing) R.string.pause else R.string.play),
                    tint = palette.accent,
                )
            }
            Text(stringResource(R.string.speed_value, state.settings.playbackSpeed), color = palette.muted)
        }
        TextButton(onClick = onNext) {
            Text(stringResource(if (sentence) R.string.next_sentence else R.string.next), color = palette.accent)
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
        Text(stringResource(R.string.mode_sentence), color = muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.readingMode == ReadingMode.SENTENCE,
                onClick = { onChange { it.copy(readingMode = ReadingMode.SENTENCE) } },
                label = { Text(stringResource(R.string.mode_sentence)) },
            )
            FilterChip(
                selected = settings.readingMode == ReadingMode.PAGED,
                onClick = { onChange { it.copy(readingMode = ReadingMode.PAGED) } },
                label = { Text(stringResource(R.string.mode_paged)) },
            )
        }
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
