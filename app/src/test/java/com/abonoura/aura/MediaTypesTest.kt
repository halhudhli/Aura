package com.abonoura.aura

import org.junit.Assert.*
import org.junit.Test

class MediaTypesTest {
    @Test fun playlistsAreNotMisclassifiedAsAudio() {
        assertEquals(MediaType.Playlist, MediaTypes.classify("قنواتي.M3U", "audio/x-mpegurl"))
        assertEquals(MediaType.Playlist, MediaTypes.classify("stream.m3u8?token=x", "application/vnd.apple.mpegurl"))
        assertEquals(MediaType.Playlist, MediaTypes.classify("list", "audio/mpegurl"))
    }
    @Test fun audioIsDetectedFromEitherMimeOrExtension() {
        assertEquals(MediaType.Audio, MediaTypes.classify("rain.FLAC"))
        assertEquals(MediaType.Audio, MediaTypes.classify("track", "audio/opus"))
        assertEquals(MediaType.Video, MediaTypes.classify("movie.mkv", "video/x-matroska"))
    }
    @Test fun unsupportedIntentSchemesAreRejected() {
        assertTrue(MediaTypes.playableScheme("content://provider/video/123"))
        assertTrue(MediaTypes.playableScheme("https://example.com/live"))
        assertFalse(MediaTypes.playableScheme("javascript:alert(1)"))
        assertFalse(MediaTypes.playableScheme("intent://otherapp"))
    }
}
