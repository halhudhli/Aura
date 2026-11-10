package com.abonoura.aura

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun VisualSubtitleSyncDialog(
    path: String,
    capturedMs: Long?,
    paused: Boolean,
    onCapture: () -> Unit,
    onTogglePlayback: () -> Unit,
    onChoose: (SubtitleCue) -> Unit,
    onDismiss: () -> Unit,
) {
    var cues by remember(path) { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    var loading by remember(path) { mutableStateOf(true) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(path) {
        try {
            cues = withContext(Dispatchers.IO) {
                val file = File(path)
                require(file.isFile && file.length() in 1..MAX_SUBTITLE_BYTES.toLong())
                SubtitleCues.parse(SubtitleText.decode(file.readBytes()))
            }
            if (cues.isEmpty()) error = "لم نعثر على جمل في ملف الترجمة. جرّب ملفًا آخر."
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { error = "تعذّر قراءة جمل الترجمة. اختر ترجمة من ملف أو من البحث أولًا." }
        loading = false
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB59CFF))) {
                Surface(Modifier.widthIn(max = 720.dp).fillMaxWidth(0.96f).fillMaxHeight(0.94f), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text("المزامنة باختيار جملة", modifier = Modifier.weight(1f), fontSize = 18.sp)
                            TextButton(onClick = onDismiss) { Text("إغلاق") }
                        }
                        Text("عند سماع بداية جملة اضغط «التقط اللحظة»، ثم اختر ترجمتها من القائمة لضبط التوقيت.", fontSize = 13.sp)
                        Row {
                            TextButton(onClick = onTogglePlayback) { Text(if (paused) "تشغيل" else "إيقاف مؤقت") }
                            TextButton(onClick = onCapture, enabled = !loading && error == null) { Text("التقط اللحظة") }
                            capturedMs?.let { Text(formatDuration(it), modifier = Modifier.padding(12.dp)) }
                        }
                        OutlinedTextField(query, { query = it }, label = { Text("ابحث داخل جمل الترجمة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (loading) CircularProgressIndicator()
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        val filtered = remember(cues, query) { if (query.isBlank()) cues else cues.filter { it.text.contains(query.trim(), true) } }
                        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                            if (!loading && error == null && filtered.isEmpty()) item { Text("لا توجد جمل تطابق البحث.") }
                            items(filtered.size, key = { it }) { index ->
                                val cue = filtered[index]
                                Column(Modifier.fillMaxWidth().clickable(enabled = capturedMs != null) { onChoose(cue) }.padding(vertical = 10.dp)) {
                                    Text(formatDuration(cue.startMs), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                                    Text(cue.text, fontSize = 15.sp)
                                    HorizontalDivider(Modifier.padding(top = 10.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
