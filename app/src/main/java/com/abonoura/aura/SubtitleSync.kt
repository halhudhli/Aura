package com.abonoura.aura

const val MIN_SUBTITLE_DELAY_MS = -600_000
const val MAX_SUBTITLE_DELAY_MS = 600_000

/**
 * Two moments from the same line during uninterrupted playback. The listener marks when speech
 * starts and when its subtitle appears. Either mark can come first; no microphone is involved.
 */
data class EarSyncCapture(val heardMs: Long? = null, val sawMs: Long? = null) {
    init {
        require(heardMs == null || heardMs >= 0L)
        require(sawMs == null || sawMs >= 0L)
    }

    fun captureHeard(positionMs: Long): EarSyncCapture = copy(heardMs = positionMs)

    fun captureSaw(positionMs: Long): EarSyncCapture = copy(sawMs = positionMs)

    /** Positive values delay the subtitles; an already-applied delay remains part of the result. */
    fun resolveDelay(baseDelayMs: Int): Int? {
        val heard = heardMs ?: return null
        val saw = sawMs ?: return null
        val base = baseDelayMs.toLong()
        // Non-negative playback positions can be subtracted without overflow. Clamp the delta
        // before adding it, so even invalidly large timestamps cannot overflow the final value.
        val delta = (heard - saw).coerceIn(
            MIN_SUBTITLE_DELAY_MS.toLong() - base,
            MAX_SUBTITLE_DELAY_MS.toLong() - base,
        )
        return (base + delta).toInt()
    }

    fun reset(): EarSyncCapture = EarSyncCapture()
}
