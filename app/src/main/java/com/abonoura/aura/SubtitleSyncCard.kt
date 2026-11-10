package com.abonoura.aura

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/** A small overlay: the video stays visible and audible while the user marks the same line. */
@Composable
fun SubtitleSyncCard(
    delayMs: Int,
    capture: EarSyncCapture,
    onHeard: () -> Unit,
    onSaw: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    captureEnabled: Boolean = true,
    unavailableMessage: String? = null,
    modifier: Modifier = Modifier,
) {
    val accent = Color(0xFFB59CFF)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            modifier.widthIn(max = 380.dp).fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xED17151D))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(18.dp))
                .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("مزامنة بالسمع والرؤية", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "إغلاق المزامنة", tint = Color.White)
                }
            }
            Text(
                "اضغط «سمعت» عند سماع بداية جملة، و«رأيت» عند ظهور ترجمتها. استخدم الجملة نفسها، ويمكن الضغط بأي ترتيب.",
                color = Color.White.copy(alpha = 0.82f), fontSize = 12.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SyncCaptureButton("سمعت", Icons.Rounded.Hearing, capture.heardMs != null, captureEnabled, onHeard, Modifier.weight(1f))
                SyncCaptureButton("رأيت", Icons.Rounded.Visibility, capture.sawMs != null, captureEnabled, onSaw, Modifier.weight(1f))
            }
            val instruction = when {
                !captureEnabled -> unavailableMessage ?: "شغّل الفيديو وحدد ترجمة لبدء المزامنة."
                capture.heardMs != null && capture.sawMs == null -> "تم تحديد السمع. اضغط «رأيت» عند ظهور ترجمة الجملة نفسها."
                capture.sawMs != null && capture.heardMs == null -> "تم تحديد الرؤية. اضغط «سمعت» عند سماع الجملة نفسها."
                else -> "تُطبّق الإزاحة بعد تحديد اللحظتين."
            }
            Text(instruction, color = if (captureEnabled) accent else Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("الإزاحة الحالية: ${syncDelayLabel(delayMs)}", color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onReset) { Text("إعادة التحديد", color = accent, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun SyncCaptureButton(
    label: String,
    icon: ImageVector,
    captured: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val foreground = if (captured) Color(0xFF160E27) else Color.White.copy(alpha = if (enabled) 1f else 0.4f)
    Row(
        modifier.clip(shape).background(if (captured) Color(0xFFB59CFF) else Color.White.copy(alpha = 0.12f))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(21.dp))
        Text(label, color = foreground, fontSize = 15.sp)
    }
}

private fun syncDelayLabel(delayMs: Int): String {
    val magnitude = abs(delayMs.toLong())
    val sign = if (delayMs < 0) "−" else "+"
    return "$sign${magnitude / 1000}.${(magnitude % 1000).toString().padStart(3, '0')} ث"
}
