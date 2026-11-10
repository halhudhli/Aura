package com.abonoura.aura

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Content for the player's scrollable side panel. Changes are applied by its host. */
@Composable
fun PlayerEffectsPanel(
    effects: PlayerEffects,
    onChange: (PlayerEffects) -> Unit,
    onReset: () -> Unit,
) {
    val current = effects.normalized()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("الصورة", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VideoPreset.entries.forEach { preset ->
                EffectsOption(
                    label = preset.label,
                    selected = current.matches(preset),
                    modifier = Modifier.weight(1f),
                    onClick = { onChange(current.withPreset(preset)) },
                )
            }
        }
        EffectsStepper("السطوع", current.brightness) { onChange(current.copy(brightness = it)) }
        EffectsStepper("التباين", current.contrast) { onChange(current.copy(contrast = it)) }
        EffectsStepper("تشبّع الألوان", current.saturation) { onChange(current.copy(saturation = it)) }
        EffectsStepper("غاما", current.gamma) { onChange(current.copy(gamma = it)) }

        Text("الصوت", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
        AudioMode.entries.forEach { mode ->
            EffectsOption(
                label = mode.label,
                selected = current.audioMode == mode,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onChange(current.copy(audioMode = mode)) },
            )
        }
        Text(
            when (current.audioMode) {
                AudioMode.Normal -> "تشغيل الصوت دون مؤثرات إضافية."
                AudioMode.Dialogue -> "تقليل الترددات المنخفضة والعالية للتركيز على الحوار."
                AudioMode.Night -> "تقليل الفرق بين الأصوات الهادئة والعالية."
            },
            color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp,
        )
        EffectsOption("إعادة ضبط الصورة والصوت", false, Modifier.fillMaxWidth(), onReset)
    }
}

@Composable
private fun EffectsStepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EffectsStepButton("−", enabled = value > -100) { onChange((value - 10).coerceAtLeast(-100)) }
            Text(
                text = if (value > 0) "+$value" else value.toString(),
                color = Color.White, fontSize = 15.sp,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            EffectsStepButton("+", enabled = value < 100) { onChange((value + 10).coerceAtMost(100)) }
        }
    }
}

@Composable
private fun EffectsStepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(40.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.12f else 0.04f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White.copy(alpha = if (enabled) 1f else 0.3f), fontSize = 20.sp)
    }
}

@Composable
private fun EffectsOption(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = LocalAura.current.accent
    Text(
        label,
        color = if (selected) accent else Color.White,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        fontSize = 14.sp,
        modifier = modifier.clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = if (selected) 0.14f else 0.06f))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 11.dp),
    )
}
