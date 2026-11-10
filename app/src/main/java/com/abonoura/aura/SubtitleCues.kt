package com.abonoura.aura

/** Plain text cue timings used by the subtitle synchronization controls. */
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/** Parses text subtitle timelines without modifying the source file or executing subtitle markup. */
object SubtitleCues {
    private const val MAX_INPUT_CHARS = 8 * 1024 * 1024
    private const val MAX_CUES = 20_000
    private const val MAX_CUE_TEXT_CHARS = 16_384
    private val timeLine = Regex(
        "^\\s*((?:\\d{1,6}:)?\\d{2}:\\d{2}[.,]\\d{1,3})\\s*-->\\s*((?:\\d{1,6}:)?\\d{2}:\\d{2}[.,]\\d{1,3})(?:\\s+.*)?$",
    )
    private val assTags = Regex("\\{[^}]*}")
    private val htmlTags = Regex("<[^>]*>")
    private val entity = Regex("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|nbsp|quot|apos);")

    fun parse(text: String): List<SubtitleCue> {
        if (text.length > MAX_INPUT_CHARS || text.isBlank()) return emptyList()
        val isAss = text.lineSequence().take(100).any {
            val line = it.trim().removePrefix("\uFEFF")
            line.equals("[Events]", ignoreCase = true) || line.equals("[Script Info]", ignoreCase = true)
        }
        return (if (isAss) parseAss(text) else parseTimedText(text)).sortedBy { it.startMs }
    }

    private fun parseTimedText(text: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        var start: Long? = null
        var end: Long? = null
        var ignoredBlock = false
        val body = StringBuilder()

        fun flush() {
            val from = start
            val to = end
            if (from != null && to != null && to > from && cues.size < MAX_CUES) {
                displayText(body.toString()).takeIf { it.isNotBlank() }?.let { cues += SubtitleCue(from, to, it) }
            }
            start = null
            end = null
            body.setLength(0)
        }

        for (raw in text.lineSequence()) {
            if (cues.size >= MAX_CUES) break
            val line = raw.removePrefix("\uFEFF")
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                flush()
                ignoredBlock = false
                continue
            }
            if (ignoredBlock) continue
            if (start == null && (trimmed == "NOTE" || trimmed.startsWith("NOTE ") || trimmed == "STYLE" || trimmed == "REGION")) {
                flush()
                ignoredBlock = true
                continue
            }
            if (trimmed.contains("-->")) {
                flush()
                val match = timeLine.matchEntire(trimmed) ?: continue
                start = timestamp(match.groupValues[1])
                end = timestamp(match.groupValues[2])
            } else if (start != null && body.length < MAX_CUE_TEXT_CHARS) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(line.take(MAX_CUE_TEXT_CHARS - body.length))
            }
        }
        flush()
        return cues
    }

    private fun parseAss(text: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        var inEvents = false
        var columns = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        for (raw in text.lineSequence()) {
            if (cues.size >= MAX_CUES) break
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.startsWith('[')) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue
            if (line.startsWith("Format:", ignoreCase = true)) {
                val format = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                columns = if (format.size in 3..32 && format.lastOrNull() == "text" && "start" in format && "end" in format) format else emptyList()
                continue
            }
            if (!line.startsWith("Dialogue:", ignoreCase = true) || columns.isEmpty()) continue
            val fields = line.substringAfter(':').split(',', limit = columns.size)
            if (fields.size != columns.size) continue
            val start = timestamp(fields[columns.indexOf("start")].trim()) ?: continue
            val end = timestamp(fields[columns.indexOf("end")].trim()) ?: continue
            if (end <= start) continue
            val body = displayText(fields.last().take(MAX_CUE_TEXT_CHARS))
            if (body.isNotBlank()) cues += SubtitleCue(start, end, body)
        }
        return cues
    }

    private fun timestamp(value: String): Long? {
        val pieces = value.replace(',', '.').split(':')
        if (pieces.size !in 2..3) return null
        val hours = if (pieces.size == 3) pieces[0].toLongOrNull() ?: return null else 0L
        val minutes = pieces[pieces.size - 2].toIntOrNull() ?: return null
        val seconds = pieces.last().split('.')
        if (seconds.size != 2 || seconds[1].length !in 1..3 || !seconds[1].all(Char::isDigit)) return null
        val wholeSeconds = seconds[0].toIntOrNull() ?: return null
        val milliseconds = seconds[1].padEnd(3, '0').toIntOrNull() ?: return null
        if (hours !in 0..999_999 || minutes !in 0..59 || wholeSeconds !in 0..59) return null
        return hours * 3_600_000L + minutes * 60_000L + wholeSeconds * 1_000L + milliseconds
    }

    private fun displayText(value: String): String = entity.replace(
        htmlTags.replace(assTags.replace(value, ""), "")
            .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " "),
    ) { match ->
        when (val name = match.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "nbsp" -> " "
            "quot" -> "\""
            "apos" -> "'"
            else -> {
                val number = if (name.startsWith("#x")) name.drop(2).toIntOrNull(16) else name.drop(1).toIntOrNull()
                if (number != null && Character.isValidCodePoint(number) && number !in 0xD800..0xDFFF && number >= 0x20) {
                    String(Character.toChars(number))
                } else match.value
            }
        }
    }.trim()
}

/** Positive delay moves a subtitle later, matching mpv's sub-delay convention. */
fun subtitleDelayForCue(playbackMs: Long, cue: SubtitleCue): Int {
    val playback = playbackMs.coerceAtLeast(0L)
    val start = cue.startMs.coerceAtLeast(0L)
    return if (playback >= start) (playback - start).coerceAtMost(600_000L).toInt()
    else -(start - playback).coerceAtMost(600_000L).toInt()
}
