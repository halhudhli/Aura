package com.abonoura.aura

/** Independent picture and audio adjustments for the current playback session. */
data class PlayerEffects(
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    val gamma: Int = 0,
    val audioMode: AudioMode = AudioMode.Normal,
) {
    fun normalized(): PlayerEffects = copy(
        brightness = brightness.coerceIn(-100, 100),
        contrast = contrast.coerceIn(-100, 100),
        saturation = saturation.coerceIn(-100, 100),
        gamma = gamma.coerceIn(-100, 100),
    )

    /** Choosing a picture preset leaves the user's sound selection alone. */
    fun withPreset(preset: VideoPreset): PlayerEffects = copy(
        brightness = preset.brightness,
        contrast = preset.contrast,
        saturation = preset.saturation,
        gamma = preset.gamma,
    )

    fun matches(preset: VideoPreset): Boolean =
        brightness == preset.brightness && contrast == preset.contrast &&
            saturation == preset.saturation && gamma == preset.gamma
}

enum class VideoPreset(
    val label: String,
    val brightness: Int,
    val contrast: Int,
    val saturation: Int,
    val gamma: Int,
) {
    Natural("طبيعي", 0, 0, 0, 0),
    Cinema("سينمائي", -4, 10, -10, 0),
    Vivid("حيوي", 0, 15, 15, 0),
}

/** mpv's lavfi audio filters; Normal clears Aura's filter chain. */
enum class AudioMode(val label: String, val mpvFilter: String?) {
    Normal("الصوت الأصلي", null),
    Dialogue("وضوح الحوار", "lavfi=[highpass=f=100,lowpass=f=8000]"),
    Night("وضع ليلي", "lavfi=[acompressor=threshold=0.125:ratio=4:attack=20:release=250:makeup=1.5]"),
}
