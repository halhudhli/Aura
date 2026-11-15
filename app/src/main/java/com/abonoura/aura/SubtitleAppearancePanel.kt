package com.abonoura.aura

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Fits inside the player's scrolling side panel; this composable owns no nested vertical scroll. */
@Composable
fun SubtitleAppearancePanel(
    style: SubtitleAppearance,
    onChange: (SubtitleAppearance) -> Unit,
    onReset: () -> Unit,
) {
    val current = style.normalized()
    val accent = LocalAura.current.accent
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("مظهر الترجمة", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        AppearanceToggle("خط عريض", current.bold) { onChange(current.copy(bold = it)) }

        AppearanceLabel("لون النص")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SubtitleTextColor.entries.forEach { option ->
                Column(
                    Modifier.width(48.dp).clip(RoundedCornerShape(10.dp))
                        .selectable(current.textColor == option, role = Role.RadioButton, onClick = { onChange(current.copy(textColor = option)) })
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(Color(android.graphics.Color.parseColor(option.rgbHex)))
                            .border(if (current.textColor == option) 3.dp else 1.dp, if (current.textColor == option) accent else Color.White.copy(alpha = 0.3f), CircleShape),
                    )
                    Text(option.label, color = Color.White, fontSize = 10.sp)
                }
            }
        }
        AppearanceSlider("وضوح النص", "${current.opacityPercent}%", current.opacityPercent, 20..100) {
            onChange(current.copy(opacityPercent = it))
        }

        AppearanceToggle("حدود حول الحروف", current.outlineEnabled) { onChange(current.copy(outlineEnabled = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubtitleOutlineColor.entries.forEach { option ->
                AppearanceOption(option.label, current.outlineColor == option, current.outlineEnabled) {
                    onChange(current.copy(outlineColor = option))
                }
            }
        }

        AppearanceToggle("ظل خلف الحروف", current.shadowEnabled) { onChange(current.copy(shadowEnabled = it)) }
        AppearanceSlider("قوة الظل", current.shadowStrength.toString(), current.shadowStrength, 0..4, current.shadowEnabled) {
            onChange(current.copy(shadowStrength = it))
        }

        AppearanceSlider("موضع الترجمة", if (current.position == 100) "أسفل الشاشة" else "${current.position}%", current.position, 50..100) {
            onChange(current.copy(position = it))
        }
        Text("قلّل الموضع لرفع الترجمة عن أسفل الشاشة.", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)

        AppearanceLabel("الخط")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubtitleFont.entries.forEach { font ->
                AppearanceOption(font.label, current.font == font) { onChange(current.copy(font = font)) }
            }
        }
        TextButton(onClick = onReset, modifier = Modifier.align(Alignment.Start)) {
            Text("استعادة المظهر الافتراضي", color = accent)
        }
    }
}

@Composable
private fun AppearanceLabel(label: String) {
    Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
}

@Composable
private fun AppearanceToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = LocalAura.current.accent,
                uncheckedThumbColor = Color.LightGray,
                uncheckedTrackColor = Color(0xFF404040),
            ),
        )
    }
}

@Composable
private fun AppearanceOption(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        color = if (enabled) Color.White else Color.Gray,
        fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f))
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 12.dp),
    )
}

@Composable
private fun AppearanceSlider(label: String, valueLabel: String, value: Int, range: IntRange, enabled: Boolean = true, onChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White.copy(alpha = if (enabled) 0.75f else 0.4f), fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(valueLabel, color = Color.White.copy(alpha = if (enabled) 1f else 0.4f), fontSize = 12.sp)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
            enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = LocalAura.current.accent, activeTrackColor = LocalAura.current.accent, inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
        )
    }
}
