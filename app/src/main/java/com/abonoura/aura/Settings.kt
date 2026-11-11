package com.abonoura.aura

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

@Composable
fun SettingsScreen(versionName: String, onBack: () -> Unit) {
    val colors = LocalAura.current
    val context = LocalContext.current
    val picturePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val name = "background-${System.currentTimeMillis()}.img"
            context.contentResolver.openInputStream(uri)?.use { input ->
                File(context.filesDir, name).outputStream().use { input.copyTo(it) }
            }
            Prefs.backgroundFile?.let { old -> File(context.filesDir, old).delete() }
            Prefs.putBackground(name)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TopIcon(Icons.AutoMirrored.Rounded.ArrowBack, "رجوع", onBack)
            Text("الإعدادات", color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Section("الشكل") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AuraLook.entries.forEach { look -> Chip(look.label, look == Prefs.look) { Prefs.putLook(look) } }
                }
                if (Prefs.look == AuraLook.Glass) {
                    Action("صورة الخلفية", if (Prefs.backgroundFile == null) "الافتراضية · يُضغط لاختيار صورة من المعرض" else "صورة من المعرض · يُضغط للتغيير") {
                        picturePicker.launch("image/*")
                    }
                    if (Prefs.backgroundFile != null) {
                        Action("إزالة صورة الخلفية", "العودة إلى الخلفية الافتراضية") {
                            Prefs.backgroundFile?.let { old -> File(context.filesDir, old).delete() }
                            Prefs.putBackground(null)
                        }
                        Action("تعتيم الخلفية", "${Prefs.backgroundDim}% · يُضغط للتغيير") {
                            val steps = listOf(0, 10, 20, 30, 45, 60, 80)
                            Prefs.putDim(steps.firstOrNull { it > Prefs.backgroundDim } ?: steps.first())
                        }
                    }
                }
            }

            Section("التشغيل") {
                Toggle("المتابعة من آخر موضع", "يبدأ الفيديو من حيث توقفت المشاهدة", Prefs.resume) { Prefs.putResume(it) }
                Toggle("الإيماءات", "السحب للتقديم والترجيع، والسطوع يساراً، والصوت يميناً، والضغط المطوّل للتسريع", Prefs.gestures) { Prefs.putGestures(it) }
                Toggle("صورة داخل صورة تلقائياً", "عند الخروج من التطبيق أثناء التشغيل يستمر الفيديو في نافذة صغيرة", Prefs.autoPip) { Prefs.putAutoPip(it) }
                Toggle("فك الترميز بالعتاد", "أسرع وأقل استهلاكاً للبطارية. يُوقف إذا ظهرت مشكلة في الصورة", Prefs.hardwareDecoding) { Prefs.putHardware(it) }
                Action("مدة القفز", "${Prefs.seekSeconds} ثوانٍ · يُضغط للتغيير") {
                    val steps = listOf(5, 10, 15, 30)
                    Prefs.putSeek(steps.firstOrNull { it > Prefs.seekSeconds } ?: steps.first())
                }
                Action("سرعة الضغط المطوّل", "${Prefs.holdSpeed}× · يُضغط للتغيير") {
                    val steps = listOf(1.5f, 2f, 2.5f, 3f)
                    Prefs.putHold(steps.firstOrNull { it > Prefs.holdSpeed } ?: steps.first())
                }
            }

            Section("السجل") {
                Action("مسح سجل المشاهدة", "تُحذف مواضع التوقف المحفوظة لكل الفيديوهات") { Prefs.clearHistory() }
            }

            Section("حول التطبيق") {
                Image(
                    painterResource(R.drawable.aura_wordmark), contentDescription = "AURA · أورا",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(106.dp).clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Color(0xFF080B15)),
                )
                Text("Aura · الإصدار $versionName", color = colors.text, fontSize = 14.sp)
                Text("مشغّل فيديو بلا إعلانات ولا تتبّع. بواسطة Abonoura.", color = colors.subText, fontSize = 13.sp)
                Text("يعتمد على مكتبة mpv مفتوحة المصدر لتشغيل الفيديو، وخط Almarai للترجمة.", color = colors.subText, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val colors = LocalAura.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 4.dp))
        Column(Modifier.fillMaxWidth().auraCard().padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
    }
}

@Composable
private fun Toggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = LocalAura.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = colors.text, fontSize = 15.sp)
            Text(description, color = colors.subText, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Action(title: String, description: String, onClick: () -> Unit) {
    val colors = LocalAura.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)) {
        Text(title, color = colors.text, fontSize = 15.sp)
        Text(description, color = colors.subText, fontSize = 12.sp)
    }
}
