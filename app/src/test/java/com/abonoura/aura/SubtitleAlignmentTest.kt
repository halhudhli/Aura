package com.abonoura.aura
import org.junit.Assert.*
import org.junit.Test

class SubtitleAlignmentTest {
    private val cues=buildList {var position=5000L;for(i in 0 until 70){position+=2200+(i*439%3700);add(SubtitleCue(position,position+800+i*61%2200,"cue $i"))}}
    @Test fun findsPositiveAndNegativeOffset() {
        for(offset in listOf(7300L,-4200L)) {
            val ref=cues.map{it.copy(startMs=it.startMs+offset,endMs=it.endMs+offset)}
            val fit=SubtitleAligner.align(cues,ref)!!
            assertEquals(offset.toDouble(),fit.offsetMs.toDouble(),150.0);assertTrue(fit.confidence>.85)
        }
    }
    @Test fun correctsFrameRateDrift() {
        val ref=cues.map{it.copy(startMs=(it.startMs*25.0/24+3100).toLong(),endMs=(it.endMs*25.0/24+3100).toLong())}
        val fit=SubtitleAligner.align(cues,ref)!!
        assertEquals(25.0/24,fit.scale,.0003);assertEquals(3100.0,fit.offsetMs.toDouble(),150.0)
    }
    @Test fun refusesInsufficientDataAndSupportsCancellation() {
        assertNull(SubtitleAligner.align(cues.take(3),cues))
        assertTrue(runCatching{SubtitleAligner.align(cues,cues){true}}.exceptionOrNull() is java.util.concurrent.CancellationException)
    }
    @Test fun refinesNonStandardDrift() {
        val ref=cues.map{it.copy(startMs=(it.startMs*1.003+2700).toLong(),endMs=(it.endMs*1.003+2700).toLong())}
        val fit=SubtitleAligner.align(cues,ref)!!
        assertEquals(1.003,fit.scale,.0004);assertEquals(2700.0,fit.offsetMs.toDouble(),150.0)
    }
    @Test fun srtCanBeReadBack() {assertEquals(cues,SubtitleCues.parse(SubtitleAligner.srt(cues)))}
}
