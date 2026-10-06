package app.moye.ui.shelf

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.lifecycle.viewmodel.compose.viewModel
import app.moye.MoYeApplication
import app.moye.ObserveResume
import app.moye.R
import app.moye.applyAppLanguage
import app.moye.core.library.BookRecord
import app.moye.core.library.filterBooks
import app.moye.core.model.BookFormat
import app.moye.core.model.ImportError
import app.moye.data.ImportReport
import app.moye.core.model.RemovalChoice
import app.moye.core.text.ReadingProgress
import app.moye.core.time.DurationParts

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfScreen(onOpenBook: (String) -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as MoYeApplication).container
    val viewModel: ShelfViewModel = viewModel(factory = ShelfViewModelFactory(container.repository, container.settings))
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val keptMessage = stringResource(R.string.removed_kept)
    val deletedMessage = stringResource(R.string.removed_deleted)
    val failedMessage = stringResource(R.string.remove_failed)
    val addedMessage = stringResource(R.string.import_added)
    val alreadyMessage = stringResource(R.string.import_already)
    val skippedMessage = stringResource(R.string.import_skipped)
    val failedCountMessage = stringResource(R.string.import_failed_count)
    val launcher = rememberLauncherForActivityResult(OpenMultipleBooks()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        for (uri in uris) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: SecurityException) {
                // The picker grant is enough to copy the file during this import.
            }
        }
        viewModel.importAll(uris)
    }

    LaunchedEffect(Unit) { viewModel.refresh() }
    ObserveResume(onResume = viewModel::refresh, onPause = {})

    LaunchedEffect(state.importReport) {
        val report = state.importReport ?: return@LaunchedEffect
        val message = importReportMessage(report, addedMessage, alreadyMessage, skippedMessage, failedCountMessage)
        if (message != null) snackbarHostState.showSnackbar(message)
        viewModel.dismissImportReport()
    }

    LaunchedEffect(state.notice) {
        val message = when (state.notice) {
            ShelfNotice.REMOVED_KEPT -> keptMessage
            ShelfNotice.REMOVED_DELETED -> deletedMessage
            ShelfNotice.REMOVE_FAILED -> failedMessage
            null -> null
        }
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.dismissNotice()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                title = {
                    Column {
                        Text(stringResource(R.string.app_name), fontFamily = FontFamily.Serif)
                        Text(stringResource(R.string.shelf_subtitle), style = MaterialTheme.typography.labelMedium)
                    }
                },
                actions = {
                    TextButton(onClick = {
                        val next = if (state.languageTag == "en") "zh" else "en"
                        viewModel.setLanguage(next)
                        applyAppLanguage(next)
                    }) {
                        Text(if (state.languageTag == "en") stringResource(R.string.language_zh) else stringResource(R.string.language_en))
                    }
                    IconButton(onClick = { viewModel.setPrivacyVisible(true) }) {
                        Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.privacy))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.search_hint)) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        TextButton(onClick = { viewModel.setQuery("") }) {
                            Text(stringResource(R.string.clear_search))
                        }
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { launcher.launch(OpenMultipleBooks.MIME_TYPES) },
                enabled = !state.importing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.importing) stringResource(R.string.importing) else stringResource(R.string.import_book))
            }
            Spacer(Modifier.height(16.dp))
            when {
                state.loading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                }
                state.loadFailed -> {
                    MessageBlock(
                        title = stringResource(R.string.shelf_load_failed),
                        body = null,
                        action = stringResource(R.string.retry),
                        onAction = viewModel::refresh,
                    )
                }
                else -> {
                    val visible = filterBooks(state.books, state.query)
                    if (state.books.isEmpty()) {
                        MessageBlock(
                            title = stringResource(R.string.empty_shelf),
                            body = stringResource(R.string.empty_shelf_hint),
                            action = null,
                            onAction = null,
                        )
                    } else if (visible.isEmpty()) {
                        MessageBlock(
                            title = stringResource(R.string.no_search_results),
                            body = null,
                            action = stringResource(R.string.clear_search),
                            onAction = { viewModel.setQuery("") },
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 28.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(visible, key = { it.id }) { book ->
                                BookCard(
                                    book = book,
                                    coverFile = if (book.format == BookFormat.EPUB) container.repository.coverFile(book) else null,
                                    coverRevision = state.coverRevision,
                                    onOpen = { onOpenBook(book.id) },
                                    onEdit = { viewModel.beginEdit(book) },
                                    onRemove = { viewModel.beginRemove(book) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    state.importError?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissImportError,
            confirmButton = {
                TextButton(onClick = viewModel::dismissImportError) { Text(stringResource(R.string.close)) }
            },
            title = { Text(stringResource(R.string.import_book)) },
            text = { Text(importErrorText(error)) },
        )
    }
    state.editing?.let { book -> EditBookDialog(book, viewModel::dismissEdit, viewModel::saveEdit) }
    state.removing?.let { book -> RemoveBookDialog(book, viewModel::dismissRemove, viewModel::confirmRemove) }
    if (state.showPrivacy) {
        AlertDialog(
            onDismissRequest = { viewModel.setPrivacyVisible(false) },
            confirmButton = {
                TextButton(onClick = { viewModel.setPrivacyVisible(false) }) { Text(stringResource(R.string.close)) }
            },
            title = { Text(stringResource(R.string.privacy)) },
            text = { Text(stringResource(R.string.privacy_body)) },
        )
    }
}

@Composable
private fun BookCard(
    book: BookRecord,
    coverFile: File?,
    coverRevision: Int,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val fraction = ReadingProgress.fraction(book.charOffset, book.totalChars)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.clickable(onClick = onOpen).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BookCoverArt(
                    title = book.title,
                    file = coverFile,
                    revision = coverRevision,
                    modifier = Modifier.size(width = 72.dp, height = 104.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Serif,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        book.author?.takeIf { it.isNotBlank() } ?: stringResource(R.string.author_unknown),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more_actions))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_book)) },
                        onClick = { menu = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remove_book)) },
                        onClick = { menu = false; onRemove() },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    R.string.progress_line,
                    ReadingProgress.percent(book.charOffset, book.totalChars),
                    formatDuration(book.readingDurationMs),
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MessageBlock(title: String, body: String?, action: String?, onAction: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
        if (body != null) {
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun EditBookDialog(book: BookRecord, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var title by remember(book.id) { mutableStateOf(book.title) }
    var author by remember(book.id) { mutableStateOf(book.author.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.field_title)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text(stringResource(R.string.field_author)) },
                    supportingText = { Text(stringResource(R.string.author_optional)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title, author) }, enabled = title.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RemoveBookDialog(
    book: BookRecord,
    onDismiss: () -> Unit,
    onChoice: (RemovalChoice) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remove_title, book.title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.remove_message))
                Button(onClick = { onChoice(RemovalChoice.KEEP_COPY) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.remove_keep_copy))
                }
                OutlinedButton(onClick = { onChoice(RemovalChoice.DELETE_COPY) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.remove_delete_copy))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { onChoice(RemovalChoice.CANCEL) }) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun BookCoverArt(title: String, file: File?, revision: Int, modifier: Modifier = Modifier) {
    val stamp = file?.let { "${it.path}:${it.lastModified()}:$revision" }
    var bitmap by remember(stamp) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(stamp) { mutableStateOf(false) }
    LaunchedEffect(stamp) {
        bitmap = null
        failed = false
        if (file == null) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) { decodeCover(file) }
        if (decoded == null) failed = true else bitmap = decoded
    }
    val shape = RoundedCornerShape(4.dp)
    val framed = modifier.clip(shape).border(1.dp, Color(0xFFC9C0B2), shape)
    val image = bitmap
    when {
        image != null -> Image(
            bitmap = image.asImageBitmap(),
            contentDescription = stringResource(R.string.cover),
            modifier = framed,
            contentScale = ContentScale.Crop,
        )
        file != null && !failed -> Box(framed.background(Color(0xFF241C16)))
        else -> GeneratedTitleCover(title, framed)
    }
}

@Composable
private fun GeneratedTitleCover(title: String, modifier: Modifier = Modifier) {
    Box(
        modifier.background(Color(0xFF241C16)).padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .width(28.dp)
                    .height(2.dp)
                    .background(Color(0xFF8C3A3A)),
            )
            Spacer(Modifier.height(10.dp))
            val label = title.ifBlank { stringResource(R.string.app_name) }
            val compact = label.length > 8
            Text(
                label,
                color = Color(0xFFF4EFE4),
                fontFamily = FontFamily.Serif,
                fontSize = if (compact) 13.sp else 20.sp,
                lineHeight = if (compact) 17.sp else 24.sp,
                textAlign = TextAlign.Center,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun decodeCover(file: File): android.graphics.Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 800 || bounds.outHeight / sample > 1200) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Exception) {
        null
    }
}

private fun importReportMessage(
    report: ImportReport,
    added: String,
    already: String,
    skipped: String,
    failed: String,
): String? {
    if (report.imported == 0 && report.duplicates == 0 && report.failures == 0) return null
    if (report.imported == 0 && report.failures == 0) {
        return if (report.duplicates == 1) already else skipped.format(report.duplicates)
    }
    val parts = mutableListOf<String>()
    if (report.imported > 0) parts += added.format(report.imported)
    if (report.duplicates > 0) parts += skipped.format(report.duplicates)
    if (report.failures > 0) parts += failed.format(report.failures)
    return parts.joinToString(" ")
}

@Composable
private fun importErrorText(error: ImportError): String = when (error) {
    ImportError.UNSUPPORTED_FORMAT -> stringResource(R.string.import_unsupported)
    ImportError.UNREADABLE -> stringResource(R.string.import_unreadable)
    ImportError.EMPTY -> stringResource(R.string.import_empty)
    ImportError.CORRUPT -> stringResource(R.string.import_corrupt)
    ImportError.TOO_LARGE -> stringResource(R.string.import_too_large)
}

@Composable
fun formatDuration(durationMs: Long): String {
    val parts = DurationParts.from(durationMs)
    return when {
        parts.underOneMinute -> stringResource(R.string.duration_under_minute)
        parts.hours == 0L -> stringResource(R.string.duration_minutes, parts.minutes)
        parts.minutes == 0L -> stringResource(R.string.duration_hours, parts.hours)
        else -> stringResource(R.string.duration_hours_minutes, parts.hours, parts.minutes)
    }
}
