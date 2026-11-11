package com.abonoura.aura

import org.junit.Assert.*
import org.junit.Test

class SubtitleSyncTest {
    @Test fun waitsUntilBothMomentsAreCaptured() {
        assertNull(EarSyncCapture().resolveDelay(0))
        assertNull(EarSyncCapture().captureHeard(12_000).resolveDelay(0))
        assertNull(EarSyncCapture().captureSaw(12_000).resolveDelay(0))
    }

    @Test fun delaysAnEarlySubtitleToMatchLaterSpeech() {
        val capture = EarSyncCapture().captureSaw(10_000).captureHeard(12_000)
        assertEquals(2_000, capture.resolveDelay(0))
    }

    @Test fun advancesALateSubtitleToMatchEarlierSpeech() {
        val capture = EarSyncCapture().captureHeard(10_000).captureSaw(12_000)
        assertEquals(-2_000, capture.resolveDelay(0))
    }

    @Test fun captureOrderDoesNotChangeTheResult() {
        val heardFirst = EarSyncCapture().captureHeard(9_500).captureSaw(12_000)
        val sawFirst = EarSyncCapture().captureSaw(12_000).captureHeard(9_500)
        assertEquals(heardFirst, sawFirst)
        assertEquals(heardFirst.resolveDelay(750), sawFirst.resolveDelay(750))
    }

    @Test fun preservesExistingDelayRatherThanReplacingItWithTheDifference() {
        assertEquals(3_000, EarSyncCapture(heardMs = 12_000, sawMs = 10_000).resolveDelay(1_000))
        assertEquals(-1_500, EarSyncCapture(heardMs = 10_000, sawMs = 12_000).resolveDelay(500))
        assertEquals(500, EarSyncCapture(heardMs = 12_000, sawMs = 12_000).resolveDelay(500))
    }

    @Test fun recapturingAMomentUsesTheNewPosition() {
        val capture = EarSyncCapture().captureHeard(12_000).captureHeard(12_500).captureSaw(10_000)
        assertEquals(2_500, capture.resolveDelay(0))
    }

    @Test fun resetDiscardsBothMoments() {
        val reset = EarSyncCapture(heardMs = 12_000, sawMs = 10_000).reset()
        assertEquals(EarSyncCapture(), reset)
        assertNull(reset.resolveDelay(500))
    }

    @Test fun limitsExtremeCorrectionsWithoutIntegerOverflow() {
        assertEquals(MAX_SUBTITLE_DELAY_MS, EarSyncCapture(Long.MAX_VALUE, 0).resolveDelay(1_000))
        assertEquals(MIN_SUBTITLE_DELAY_MS, EarSyncCapture(0, Long.MAX_VALUE).resolveDelay(-1_000))
        assertEquals(MAX_SUBTITLE_DELAY_MS, EarSyncCapture(2_000, 0).resolveDelay(MAX_SUBTITLE_DELAY_MS - 1))
        assertEquals(MIN_SUBTITLE_DELAY_MS, EarSyncCapture(0, 2_000).resolveDelay(MIN_SUBTITLE_DELAY_MS + 1))
    }

    @Test fun largeAbsolutePlaybackPositionsRetainMillisecondPrecision() {
        assertEquals(1, EarSyncCapture(Long.MAX_VALUE, Long.MAX_VALUE - 1).resolveDelay(0))
    }

    @Test fun rejectsInvalidPlaybackPositions() {
        assertThrows(IllegalArgumentException::class.java) { EarSyncCapture().captureHeard(-1) }
        assertThrows(IllegalArgumentException::class.java) { EarSyncCapture().captureSaw(-1) }
    }
}
