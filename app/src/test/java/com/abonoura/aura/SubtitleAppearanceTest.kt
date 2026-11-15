package com.abonoura.aura

import org.junit.Assert.*
import org.junit.Test

class SubtitleAppearanceTest {
    @Test fun allAppearanceOptionsSurviveSettingsRoundTrip() {
        for (color in SubtitleTextColor.entries) {
            val original = SubtitleAppearance(
                bold = true, textColor = color, opacityPercent = 67,
                outlineEnabled = false, outlineColor = SubtitleOutlineColor.White,
                shadowEnabled = false, shadowStrength = 3, position = 82, font = SubtitleFont.SansSerif,
            )
            assertEquals(original, SubtitleAppearance.fromSettings(original.toSettings()))
        }
    }

    @Test fun corruptAndFutureSettingsDoNotDiscardValidAppearanceChoices() {
        val restored = SubtitleAppearance.fromSettings(mapOf(
            "bold" to "true", "text_color" to "A_FUTURE_COLOR", "opacity" to "not-a-number",
            "outline" to "false", "outline_color" to "White", "shadow" to true,
            "shadow_strength" to "4", "font" to "MissingFont", "position" to "88",
            "unknown_future_setting" to "ignored",
        ))
        assertTrue(restored.bold)
        assertFalse(restored.outlineEnabled)
        assertEquals(SubtitleOutlineColor.White, restored.outlineColor)
        assertEquals(4, restored.shadowStrength)
        assertEquals(88, restored.position)
        assertEquals(SubtitleTextColor.White, restored.textColor)
        assertEquals(100, restored.opacityPercent)
        assertEquals(SubtitleFont.Almarai, restored.font)
    }

    @Test fun restoredUnboundedValuesCannotMoveSubtitlesOffscreenOrMakeThemInvisible() {
        val restored = SubtitleAppearance.fromSettings(mapOf("opacity" to "-10", "shadow_strength" to "900", "position" to "-300"))
        assertEquals(20, restored.opacityPercent)
        assertEquals(4, restored.shadowStrength)
        assertEquals(50, restored.position)
        val saved = SubtitleAppearance(opacityPercent = 500, shadowStrength = -5, position = 500).toSettings()
        assertEquals(SubtitleAppearance(opacityPercent = 100, shadowStrength = 0, position = 100), SubtitleAppearance.fromSettings(saved))
    }

    @Test fun togglingEffectsOffKeepsTheirChosenAppearanceForReenabling() {
        val saved = SubtitleAppearance(outlineEnabled = false, outlineColor = SubtitleOutlineColor.White, shadowEnabled = false, shadowStrength = 4)
        assertEquals(0.0, saved.mpvBorderSize, 0.0)
        assertEquals(0.0, saved.mpvShadowOffset, 0.0)
        val restored = SubtitleAppearance.fromSettings(saved.toSettings()).copy(outlineEnabled = true, shadowEnabled = true)
        assertEquals(SubtitleOutlineColor.White, restored.outlineColor)
        assertEquals(2.0, restored.mpvBorderSize, 0.0)
        assertEquals(4.0, restored.mpvShadowOffset, 0.0)
    }

    @Test fun transparencyUsesTheSameAlphaForTextAndOutline() {
        val style = SubtitleAppearance(opacityPercent = 20, textColor = SubtitleTextColor.Yellow)
        assertEquals("#33FFE45C", style.mpvTextColor)
        assertEquals("#33000000", style.mpvOutlineColor)
        assertEquals("#FFFFFFFF", SubtitleAppearance().mpvTextColor)
    }
}
