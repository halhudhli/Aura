package com.abonoura.aura

import org.junit.Assert.*
import org.junit.Test

class SubtitleCuesTest {
    @Test fun parsesArabicMultilineSrtAndRemovesDisplayMarkup() {
        val cues = SubtitleCues.parse("\uFEFF1\r\n00:00:01,250 --> 00:00:03,500\r\n<i>مرحبًا</i> &amp; أهلًا\r\nالسطر الثاني\r\n\r\n")
        assertEquals(listOf(SubtitleCue(1_250, 3_500, "مرحبًا & أهلًا\nالسطر الثاني")), cues)
    }

    @Test fun supportsVttShortTimesCueIdentifiersAndPositionSettings() {
        val cues = SubtitleCues.parse("""WEBVTT

            |cue-a
            |01:02.345 --> 01:04.000 align:start position:10%
            |<v Speaker>Test &lt;value&gt;</v>
            |
        """.trimMargin())
        assertEquals(listOf(SubtitleCue(62_345, 64_000, "Test <value>")), cues)
    }

    @Test fun ignoresVttNotesAndStyleBlocksIncludingTheirFakeTimes() {
        val cues = SubtitleCues.parse("""WEBVTT

            |NOTE ignored note
            |00:00.000 --> 00:01.000
            |Not a cue
            |
            |STYLE
            |::cue { color: white; }
            |
            |REGION
            |id:region1
            |
            |00:02.000 --> 00:03.000
            |Real cue
        """.trimMargin())
        assertEquals(listOf(SubtitleCue(2_000, 3_000, "Real cue")), cues)
    }

    @Test fun honorsAssEventFormatAndKeepsCommasInDialogue() {
        val cues = SubtitleCues.parse("""[Script Info]
            |Title: Test
            |[V4+ Styles]
            |Format: Name, Fontname
            |Style: Default,Arial
            |[Events]
            |Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            |Dialogue: 0,0:00:01.20,0:00:04.56,Default,,0,0,0,,{\i1}مرحبًا، يا صديقي{\i0}\NHello, friend
        """.trimMargin())
        assertEquals(listOf(SubtitleCue(1_200, 4_560, "مرحبًا، يا صديقي\nHello, friend")), cues)
    }

    @Test fun supportsAssReorderedTimingColumnsAndIgnoresComments() {
        val cues = SubtitleCues.parse("""[Events]
            |Format: End, Start, Text
            |Comment: 0:00:04.00,0:00:01.00,Ignored
            |Dialogue: 0:00:04.00,0:00:01.00,A, B, C
        """.trimMargin())
        assertEquals(listOf(SubtitleCue(1_000, 4_000, "A, B, C")), cues)
    }

    @Test fun skipsMalformedAndReversedTimingsAndContinuesParsing() {
        val cues = SubtitleCues.parse("""1
            |00:03:00,000 --> 00:02:00,000
            |Reversed
            |
            |2
            |00:70:00,000 --> 00:71:00,000
            |Invalid minute
            |
            |3
            |nonsense --> malformed
            |Invalid time
            |
            |4
            |00:00:05,000 --> 00:00:06,000
            |Valid
        """.trimMargin())
        assertEquals(listOf(SubtitleCue(5_000, 6_000, "Valid")), cues)
    }

    @Test fun returnsSortedCuesAndPreservesDuplicateStartOrder() {
        val cues = SubtitleCues.parse("""00:00:05,000 --> 00:00:06,000
            |Later
            |
            |00:00:01,000 --> 00:00:02,000
            |First duplicate
            |
            |00:00:01,000 --> 00:00:03,000
            |Second duplicate
        """.trimMargin())
        assertEquals(listOf("First duplicate", "Second duplicate", "Later"), cues.map { it.text })
    }

    @Test fun ignoresInvalidEntitiesAndDecodesValidNumericEntities() {
        val cues = SubtitleCues.parse("00:00:01,000 --> 00:00:02,000\n&#x645; &#1605; &#xD800; &#99999999;\n")
        assertEquals("م م &#xD800; &#99999999;", cues.single().text)
    }

    @Test fun boundsInputAndCueCounts() {
        assertTrue(SubtitleCues.parse("x".repeat(8 * 1024 * 1024 + 1)).isEmpty())
        val input = buildString { repeat(20_005) { append("00:00:01,000 --> 00:00:02,000\nCue $it\n\n") } }
        assertEquals(20_000, SubtitleCues.parse(input).size)
    }

    @Test fun computesDelayWithCorrectSignAndClampsWithoutOverflow() {
        val cue = SubtitleCue(5_000, 6_000, "Hello")
        assertEquals(2_000, subtitleDelayForCue(7_000, cue))
        assertEquals(-2_000, subtitleDelayForCue(3_000, cue))
        assertEquals(0, subtitleDelayForCue(5_000, cue))
        assertEquals(600_000, subtitleDelayForCue(Long.MAX_VALUE, cue))
        assertEquals(-600_000, subtitleDelayForCue(0, cue.copy(startMs = Long.MAX_VALUE)))
    }
}
