package com.abonoura.aura

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

private data class SubtitleSearch(
    val query: String, val kind: SubtitleMediaKind, val language: SubtitleLanguage,
    val season: Int, val episode: Int, val media: SubtitleMedia?, val attempt: Long,
)

@Composable
fun OnlineSubtitlesDialog(videoTitle: String, onDismiss: () -> Unit, onDownloaded: (File, OnlineSubtitle) -> Unit) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val client = remember {
        SubtitleSearchClient(listOfNotNull(
            SubtitleAddon.fromManifest("subsense", "SubSense", BuildConfig.SUBSENSE_API_KEY),
            SubtitleAddon.fromManifest("opensubtitles-pro", "OpenSubtitles PRO", BuildConfig.OPENSUBTITLESPRO_API_KEY),
        ))
    }
    val hint = remember(videoTitle) { subtitleVideoHint(videoTitle) }
    var query by remember { mutableStateOf(hint.query) }
    var kind by remember { mutableStateOf(hint.kind) }
    var season by remember { mutableStateOf(hint.season.toString()) }
    var episode by remember { mutableStateOf(hint.episode.toString()) }
    var language by remember { mutableStateOf(subtitleLanguages.first { it.code == Prefs.subtitleLanguage }) }
    var languageMenu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<SubtitleMedia?>(null) }
    var submitted by remember { mutableStateOf<SubtitleSearch?>(null) }
    var titles by remember { mutableStateOf<List<SubtitleMedia>>(emptyList()) }
    var results by remember { mutableStateOf<List<OnlineSubtitle>>(emptyList()) }
    var warnings by remember { mutableStateOf<List<String>>(emptyList()) }
    var sourceFilter by remember { mutableStateOf<String?>(null) }
    var sourceMenu by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf<String?>(null) }
    val validEpisode = kind == SubtitleMediaKind.Movie ||
        (season.toIntOrNull()?.let { it >= 0 } == true && episode.toIntOrNull()?.let { it >= 1 } == true)

    fun clearSearch(clearMedia: Boolean = true) {
        submitted = null
        if (clearMedia) selected = null
        titles = emptyList()
        results = emptyList()
        warnings = emptyList()
        sourceFilter = null
        searching = false
        searched = false
        error = null
    }

    fun search() {
        if (query.isBlank() || downloading != null || searching || !client.configured || !validEpisode) return
        keyboard?.hide()
        submitted = SubtitleSearch(query.trim(), kind, language, season.toIntOrNull() ?: 1, episode.toIntOrNull() ?: 1, selected, (submitted?.attempt ?: 0L) + 1)
    }

    fun dismiss() { scope.cancel(); onDismiss() }

    LaunchedEffect(submitted) {
        val request = submitted ?: return@LaunchedEffect
        searching = true
        error = null
        searched = false
        results = emptyList()
        titles = emptyList()
        warnings = emptyList()
        sourceFilter = null
        try {
            if (request.media == null) {
                val matches = client.titles(request.query, request.kind)
                if (submitted != request) return@LaunchedEffect
                titles = matches
            } else {
                val response = client.subtitles(request.media, request.language, request.season, request.episode, videoTitle)
                if (submitted != request) return@LaunchedEffect
                results = response.subtitles
                warnings = response.warnings
            }
            searched = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (submitted != request) return@LaunchedEffect
            error = subtitleError(failure)
        }
        searching = false
    }

    Dialog(onDismissRequest = { dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB59CFF), surface = Color(0xFF18161F))) {
                Surface(Modifier.widthIn(max = 720.dp).fillMaxWidth(0.96f).fillMaxHeight(0.94f), shape = RoundedCornerShape(20.dp)) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("البحث عن ترجمة", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            IconButton(onClick = { dismiss() }) { Icon(Icons.Rounded.Close, contentDescription = "إغلاق") }
                        }
                        LazyColumn(
                            Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            item {
                                OutlinedTextField(
                                    value = query,
                                    onValueChange = { query = it; clearSearch() },
                                    label = { Text("اسم الفيلم أو المسلسل") }, singleLine = true,
                                    enabled = downloading == null && client.configured,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { search() }), modifier = Modifier.fillMaxWidth(),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SubtitleMediaKind.entries.forEach { option ->
                                        FilterChip(selected = option == kind, onClick = { kind = option; clearSearch() }, label = { Text(option.label) }, enabled = downloading == null)
                                    }
                                }
                                if (kind == SubtitleMediaKind.Series) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        OutlinedTextField(season, { if (it.length <= 3 && it.all(Char::isDigit)) { season = it; clearSearch(false) } },
                                            label = { Text("الموسم") }, singleLine = true, enabled = downloading == null,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                                        OutlinedTextField(episode, { if (it.length <= 4 && it.all(Char::isDigit)) { episode = it; clearSearch(false) } },
                                            label = { Text("الحلقة") }, singleLine = true, enabled = downloading == null,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        TextButton(onClick = { languageMenu = true }, enabled = downloading == null) { Text("اللغة: ${language.label} ▾") }
                                        DropdownMenu(languageMenu, onDismissRequest = { languageMenu = false }) {
                                            subtitleLanguages.forEach { option ->
                                                DropdownMenuItem(text = { Text(option.label) }, onClick = {
                                                    language = option; Prefs.putSubtitleLanguage(option.code)
                                                    languageMenu = false; clearSearch(false)
                                                })
                                            }
                                        }
                                    }
                                    Button(onClick = { search() }, enabled = query.isNotBlank() && !searching && downloading == null && client.configured && validEpisode) {
                                        Text(if (selected == null) "بحث" else "عرض الترجمات")
                                    }
                                }
                                selected?.let { media ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(listOf(media.name, media.year).filter { it.isNotBlank() }.joinToString(" · "), modifier = Modifier.weight(1f), maxLines = 2, fontWeight = FontWeight.SemiBold)
                                        TextButton(onClick = { clearSearch(); search() }, enabled = downloading == null) { Text("تغيير العمل") }
                                    }
                                }
                            }
                            if (!client.configured) {
                                item { Text("البحث عن الترجمة غير مفعّل في هذه النسخة بعد. يمكنك اختيار ترجمة من ملف على جهازك.") }
                            } else if (!searched && !searching && error == null) {
                                item { Text("ابحث بالاسم ثم اختر العمل المطابق للفيديو. بعدها اختر نسخة الترجمة المناسبة.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
                            }
                            error?.let { message -> item {
                                Text(message, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                                if (downloading == null) TextButton(onClick = { search() }) { Text("إعادة البحث") }
                            } }
                            warnings.forEach { warning -> item { Text(warning, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) } }
                            if (searched && selected == null) {
                                item { Text(if (titles.isEmpty()) "لم نعثر على عمل بهذا الاسم. جرّب الاسم الأصلي أو غيّر النوع بين فيلم ومسلسل." else "اختر العمل المطابق للفيديو:") }
                            }
                            items(titles, key = { it.id }) { media ->
                                Column(Modifier.fillMaxWidth().clickable(enabled = downloading == null && !searching) { selected = media; search() }.padding(vertical = 12.dp)) {
                                    Text(media.name, fontWeight = FontWeight.SemiBold)
                                    Text(listOf(media.kind.label, media.year).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    HorizontalDivider(Modifier.padding(top = 12.dp))
                                }
                            }
                            if (searched && selected != null && results.isEmpty()) {
                                item { Text("لم نعثر على ترجمة بهذه اللغة. جرّب لغة أخرى وتأكد أنها مفعّلة في إعداد الإضافة.") }
                            }
                            if (results.isNotEmpty()) {
                                item { Column {
                                    TextButton(onClick = { sourceMenu = true }, enabled = downloading == null) { Text("المصدر: ${sourceFilter ?: "الكل"} ▾") }
                                    DropdownMenu(sourceMenu, onDismissRequest = { sourceMenu = false }) {
                                        (listOf<String?>(null) + results.map { it.sourceLabel }.distinct()).forEach { source ->
                                            DropdownMenuItem(text = { Text(source ?: "الكل") }, onClick = { sourceFilter = source; sourceMenu = false })
                                        }
                                    }
                                } }
                            }
                            items(results.filter { sourceFilter == null || it.sourceLabel == sourceFilter }, key = { it.id }) { subtitle ->
                                Column {
                                    Row(
                                        Modifier.fillMaxWidth().clickable(enabled = downloading == null && !searching) {
                                            downloading = subtitle.id; error = null
                                            scope.launch {
                                                try {
                                                    val file = client.download(subtitle, File(context.cacheDir, "subtitles"))
                                                    onDownloaded(file, subtitle)
                                                } catch (cancelled: CancellationException) { throw cancelled
                                                } catch (failure: Exception) { error = subtitleError(failure)
                                                } finally { downloading = null }
                                            }
                                        }.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(subtitle.release, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                            Text("${language.label} · ${subtitle.sourceLabel}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            if (downloading == subtitle.id) Text("جارٍ تحميل الترجمة…", fontSize = 12.sp)
                                        }
                                        if (downloading == subtitle.id) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                        else Icon(Icons.Rounded.Download, contentDescription = "تحميل هذه الترجمة", tint = MaterialTheme.colorScheme.primary)
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                            if (searching) item {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.padding(12.dp).size(28.dp)) }
                            }
                        }
                        Text("المصادر: ${client.sourceNames.ifBlank { "غير مفعّلة" }}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp).align(Alignment.CenterHorizontally))
                    }
                }
            }
        }
    }
}

private val OnlineSubtitle.sourceLabel: String get() = if (source.equals(addonName, true)) addonName else "$source · $addonName"

private fun subtitleError(failure: Exception): String = when (failure) {
    is SubtitleServiceException -> failure.message.orEmpty()
    is java.net.SocketTimeoutException -> "انتهت مهلة الاتصال. تحقق من الإنترنت ثم أعد المحاولة."
    else -> "تعذّر البحث أو تحميل الترجمة. تحقق من اتصال الإنترنت ثم أعد المحاولة."
}
