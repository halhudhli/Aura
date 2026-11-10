package com.abonoura.aura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerEffectsTest {
    @Test fun picturePresetPreservesTheIndependentAudioSelection() {
        val custom = PlayerEffects(brightness = 32, contrast = -13, saturation = 40, gamma = -5, audioMode = AudioMode.Night)
        val cinematic = custom.withPreset(VideoPreset.Cinema)
        assertTrue(cinematic.matches(VideoPreset.Cinema))
        assertEquals(AudioMode.Night, cinematic.audioMode)
        assertEquals(AudioMode.Night, cinematic.withPreset(VideoPreset.Natural).audioMode)
        assertEquals(32, custom.brightness)
    }

    @Test fun invalidPictureValuesAreBoundedWithoutResettingValidValuesOrAudio() {
        val invalid = PlayerEffects(Int.MAX_VALUE, Int.MIN_VALUE, 45, -30, AudioMode.Dialogue)
        assertEquals(PlayerEffects(100, -100, 45, -30, AudioMode.Dialogue), invalid.normalized())
    }
}
