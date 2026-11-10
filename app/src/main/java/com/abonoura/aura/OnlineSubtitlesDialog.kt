package com.abonoura.aura

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

private data class SubtitleSearch(val query: String, val language: String, val page: Int, val attempt: Long)

@Composable
fun OnlineSubtitlesDialog(
    videoTitle: String,
    onDismiss: () -> Unit,
    onDownloaded: (File, OnlineSubtitle) -> Unit,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val client = remember { OpenSubtitlesClient(BuildConfig.OPENSUBTITLES_API_KEY, "Aura v${BuildConfig.VERSION_NAME}") }
    var query by remember(videoTitle) { mutableStateOf(subtitleQuery(videoTitle)) }
    var language by remember { mutableStateOf(subtitleLanguages.first { it.code == Prefs.subtitleLanguage }) }
    var languageMenu by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf<SubtitleSearch?>(null) }
    var results by remember { mutableStateOf<List<OnlineSubtitle>>(emptyList()) }
    var nextPage by remember { mutableStateOf<Int?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf<Int?>(null) }

    fun clearSearch() {
        submitted = null
        results = emptyList()
        nextPage = null
        searching = false
        searched = false
        error = null
    }

    fun search(page: Int = 1) {
        if (query.isBlank() || downloading != null || searching || !client.configured) return
        keyboard?.hide()
        submitted = SubtitleSearch(query.trim(), language.code, page, (submitted?.attempt ?: 0L) + 1)
    }

    // Changing a query or dismissing the dialog cancels the old request, including pending downloads.
    LaunchedEffect(submitted) {
        val request = submitted ?: return@LaunchedEffect
        searching = true
        error = null
        if (request.page == 1) {
            results = emptyList()
            nextPage = null
            searched = false
        }
        try {
            val page = client.search(request.query, request.language, request.page)
            results = ((if (request.page == 1) emptyList() else results) + page.subtitles).distinctBy { it.fileId }
            nextPage = page.nextPage
            searched = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = subtitleError(failure)
        }
        searching = false
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB59CFF), surface = Color(0xFF18161F))) {
                Surface(
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(0.96f).fillMaxHeight(0.94f),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("البحث عن ترجمة", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "إغلاق") }
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
                                    label = { Text("اسم الفيلم أو المسلسل") },
                                    singleLine = true,
                                    enabled = downloading == null && client.configured,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { search() }),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        TextButton(onClick = { languageMenu = true }, enabled = downloading == null && client.configured) {
                                            Text("اللغة: ${language.label} ▾")
                                        }
                                        DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                                            subtitleLanguages.forEach { option ->
                                                DropdownMenuItem(text = { Text(option.label) }, onClick = {
                                                    language = option
                                                    Prefs.putSubtitleLanguage(option.code)
                                                    languageMenu = false
                                                    clearSearch()
                                                })
                                            }
                                        }
                                    }
                                    Button(onClick = { search() }, enabled = query.isNotBlank() && !searching && downloading == null && client.configured) {
                                        Text("بحث")
                                    }
                                }
                            }
                            if (!client.configured) {
                                item { Text("البحث عن الترجمة غير مفعّل في هذه النسخة بعد. يمكنك اختيار ترجمة من ملف على جهازك.") }
                            } else if (!searched && !searching && error == null) {
                                item { Text("عدّل الاسم عند الحاجة، ثم اضغط بحث. ستظهر نسخ الترجمة لتختار الأنسب للفيديو.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
                            }
                            error?.let { message ->
                                item {
                                    Text(message, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                                    if (!searched && downloading == null) TextButton(onClick = { search() }) { Text("إعادة المحاولة") }
                                }
                            }
                            if (searched && results.isEmpty()) {
                                item { Text("لم نعثر على ترجمة بهذه اللغة. جرّب اسمًا آخر أو غيّر اللغة.") }
                            }
                            items(results, key = { it.fileId }) { subtitle ->
                                Column {
                                    Row(
                                        Modifier.fillMaxWidth().clickable(enabled = downloading == null && !searching) {
                                            downloading = subtitle.fileId
                                            error = null
                                            scope.launch {
                                                try {
                                                    val file = client.download(subtitle, File(context.cacheDir, "subtitles"))
                                                    onDownloaded(file, subtitle)
                                                } catch (cancelled: CancellationException) {
                                                    throw cancelled
                                                } catch (failure: Exception) {
                                                    error = subtitleError(failure)
                                                } finally {
                                                    downloading = null
                                                }
                                            }
                                        }.padding(vertical = 12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(subtitle.release, maxLines = 3, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                            if (subtitle.fileName != subtitle.release) Text(subtitle.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            val label = subtitleLanguages.firstOrNull { it.code == subtitle.language }?.label ?: subtitle.language
                                            Text(
                                                listOfNotNull(label, "${subtitle.downloads} تحميل", "لضعاف السمع".takeIf { subtitle.hearingImpaired }, "المقاطع الأجنبية فقط".takeIf { subtitle.foreignPartsOnly }).joinToString(" · "),
                                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            if (downloading == subtitle.fileId) Text("جارٍ تحميل الترجمة…", fontSize = 12.sp)
                                        }
                                        if (downloading == subtitle.fileId) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                        else Icon(Icons.Rounded.Download, contentDescription = "تحميل هذه الترجمة", tint = MaterialTheme.colorScheme.primary)
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                            if (searching) {
                                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.padding(12.dp).size(28.dp)) } }
                            } else if (nextPage != null) {
                                item { TextButton(onClick = { nextPage?.let { search(it) } }, enabled = downloading == null, modifier = Modifier.fillMaxWidth()) { Text("عرض المزيد") } }
                            }
                        }
                        TextButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.opensubtitles.com"))) }
                        }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text("ترجمات من OpenSubtitles", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun subtitleError(failure: Exception): String = when (failure) {
    is SubtitleServiceException -> failure.message.orEmpty()
    is java.net.SocketTimeoutException -> "انتهت مهلة الاتصال. تحقق من الإنترنت ثم أعد المحاولة."
    else -> "تعذّر البحث أو تحميل الترجمة. تحقق من اتصال الإنترنت ثم أعد المحاولة."
}
