package com.abonoura.aura

import java.util.Locale
import kotlin.math.roundToInt

enum class SubtitleTextColor(val label: String, val rgbHex: String) {
    White("أبيض", "#FFFFFF"),
    Yellow("أصفر", "#FFE45C"),
    Cyan("سماوي", "#75E9FF"),
    Green("أخضر", "#93F5A4"),
    Pink("وردي", "#FFB5D8"),
}

enum class SubtitleOutlineColor(val label: String, val rgbHex: String) {
    Black("أسود", "#000000"),
    White("أبيض", "#FFFFFF"),
}

enum class SubtitleFont(val label: String, val mpvName: String) {
    Almarai("المراعي", "Almarai"),
    SansSerif("خط الجهاز", "sans-serif"),
}

/** Player appearance only. The existing subtitle scale and per-video timing stay independent. */
data class SubtitleAppearance(
    val bold: Boolean = false,
    val textColor: SubtitleTextColor = SubtitleTextColor.White,
    val opacityPercent: Int = 100,
    val outlineEnabled: Boolean = true,
    val outlineColor: SubtitleOutlineColor = SubtitleOutlineColor.Black,
    val shadowEnabled: Boolean = true,
    val shadowStrength: Int = 1,
    val position: Int = 95,
    val font: SubtitleFont = SubtitleFont.Almarai,
) {
    fun normalized(): SubtitleAppearance = copy(
        opacityPercent = opacityPercent.coerceIn(20, 100),
        shadowStrength = shadowStrength.coerceIn(0, 4),
        position = position.coerceIn(50, 100),
    )

    // mpv accepts #AARRGGBB. Keep the same alpha on text and outline when changing opacity.
    val mpvTextColor: String get() = colorWithOpacity(textColor.rgbHex)
    val mpvOutlineColor: String get() = colorWithOpacity(outlineColor.rgbHex)
    val mpvBorderSize: Double get() = if (outlineEnabled) 2.0 else 0.0
    val mpvShadowOffset: Double get() = if (shadowEnabled) shadowStrength.coerceIn(0, 4).toDouble() else 0.0

    private fun colorWithOpacity(rgb: String): String {
        val alpha = (opacityPercent.coerceIn(20, 100) * 255 / 100.0).roundToInt()
        return String.format(Locale.ROOT, "#%02X%s", alpha, rgb.removePrefix("#"))
    }

    internal fun toSettings(): Map<String, String> = normalized().let { style ->
        mapOf(
            "bold" to style.bold.toString(),
            "text_color" to style.textColor.name,
            "opacity" to style.opacityPercent.toString(),
            "outline" to style.outlineEnabled.toString(),
            "outline_color" to style.outlineColor.name,
            "shadow" to style.shadowEnabled.toString(),
            "shadow_strength" to style.shadowStrength.toString(),
            "position" to style.position.toString(),
            "font" to style.font.name,
        )
    }

    companion object {
        /** Restore each field separately so an unknown setting does not discard valid preferences. */
        internal fun fromSettings(settings: Map<String, *>): SubtitleAppearance {
            val defaults = SubtitleAppearance()
            fun value(key: String): String? = settings[key] as? String
            fun flag(key: String, fallback: Boolean): Boolean = value(key)?.toBooleanStrictOrNull() ?: fallback
            fun number(key: String, fallback: Int): Int = value(key)?.toIntOrNull() ?: fallback
            return SubtitleAppearance(
                bold = flag("bold", defaults.bold),
                textColor = SubtitleTextColor.entries.firstOrNull { it.name == value("text_color") } ?: defaults.textColor,
                opacityPercent = number("opacity", defaults.opacityPercent),
                outlineEnabled = flag("outline", defaults.outlineEnabled),
                outlineColor = SubtitleOutlineColor.entries.firstOrNull { it.name == value("outline_color") } ?: defaults.outlineColor,
                shadowEnabled = flag("shadow", defaults.shadowEnabled),
                shadowStrength = number("shadow_strength", defaults.shadowStrength),
                position = number("position", defaults.position),
                font = SubtitleFont.entries.firstOrNull { it.name == value("font") } ?: defaults.font,
            ).normalized()
        }
    }
}
