package com.abonoura.aura

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

data class SubtitleAlignment(val scale:Double,val offsetMs:Long,val confidence:Double,val matchedCues:Int) {
    fun apply(cues:List<SubtitleCue>)=cues.mapNotNull { c ->
        val start=(c.startMs*scale+offsetMs).toLong().coerceAtLeast(0)
        val end=(c.endMs*scale+offsetMs).toLong()
        if(end>start)SubtitleCue(start,end,c.text)else null
    }
}

/** Fits cue boundaries, independent of language. A weak or ambiguous fit is never applied silently. */
object SubtitleAligner {
    fun align(external:List<SubtitleCue>,reference:List<SubtitleCue>,cancelled:()->Boolean={false}):SubtitleAlignment? {
        if(external.size<12 || reference.size<12)return null
        fun sample(input:List<SubtitleCue>):List<SubtitleCue> {
            val sorted=input.sortedBy{it.startMs}
            if(sorted.size<=260)return sorted
            return (0 until 260).map{sorted[(it*(sorted.size-1)/259.0).roundToInt()]}
        }
        val source=sample(external);val target=reference.sortedBy{it.startMs}
        val seed=sample(reference)
        val starts=target.map{it.startMs}.toLongArray()
        fun nearest(value:Long):Int {
            val index=starts.binarySearch(value)
            if(index>=0)return index
            val insertion=-index-1
            if(insertion==0)return 0
            if(insertion==starts.size)return starts.lastIndex
            return if(abs(starts[insertion]-value)<abs(starts[insertion-1]-value))insertion else insertion-1
        }
        data class Candidate(val scale:Double,val offset:Long,val score:Double,val matches:Int)
        fun score(scale:Double,offset:Long):Candidate {
            var sum=0.0;var matches=0;val used=hashSetOf<Int>()
            source.forEach { c ->
                val from=(c.startMs*scale+offset).toLong();val index=nearest(from);val t=target[index]
                val startError=abs(t.startMs-from).toDouble();val endError=abs(t.endMs-(c.endMs*scale+offset))
                if(startError<750 && used.add(index))matches++
                if(used.contains(index))sum+=exp(-startError/450.0)*(.75+.25*exp(-endError/900.0))
            }
            return Candidate(scale,offset,sum/source.size,matches)
        }
        val scales=listOf(1.0,1000.0/1001,1001.0/1000,24.0/25,25.0/24,23.976/25,25/23.976,.98,.99,1.01,1.02)
        val candidates=mutableListOf<Candidate>()
        for(scale in scales) {
            if(cancelled())throw java.util.concurrent.CancellationException()
            val histogram=hashMapOf<Long,Int>()
            source.forEach { a ->seed.forEach { b ->
                val difference=b.startMs-(a.startMs*scale).toLong()
                if(abs(difference)<=600000) {val bin=(difference/250.0).roundToInt().toLong();histogram[bin]=(histogram[bin]?:0)+1}
            } }
            histogram.entries.sortedByDescending{it.value}.take(16).forEach {bin->candidates+=score(scale,bin.key*250)}
        }
        var best=candidates.maxByOrNull{it.score} ?: return null
        // Refine drift and offset together around the strongest coarse candidate.
        repeat(3) { pass ->
            val step=if(pass==0).001 else if(pass==1).0001 else .00002
            val origin=best
            val pivot=source[source.size/2].startMs
            for(ds in -5..5)for(dt in -4..4) {
                if(cancelled())throw java.util.concurrent.CancellationException()
                val candidate=score(origin.scale+ds*step,origin.offset-(ds*step*pivot).toLong()+dt*(if(pass==0)100 else 25))
                if(candidate.score>best.score)best=candidate
            }
        }
        val competitor=candidates.filter{abs(it.offset-best.offset)>3000 && abs(it.scale-best.scale)<.003}.maxOfOrNull{it.score} ?: 0.0
        val ambiguity=if(best.score>0 && competitor/best.score>.95).5 else 1.0
        val confidence=(best.score*(best.matches.toDouble()/source.size).coerceIn(0.0,1.0)*ambiguity).coerceIn(0.0,1.0)
        return SubtitleAlignment(best.scale,best.offset,confidence,best.matches)
    }
    fun srt(cues:List<SubtitleCue>):String {
        fun time(ms:Long):String {val s=ms/1000;return java.lang.String.format(java.util.Locale.ROOT,"%02d:%02d:%02d,%03d",s/3600,s/60%60,s%60,ms%1000)}
        return cues.mapIndexed{i,c->"${i+1}\n${time(c.startMs)} --> ${time(c.endMs)}\n${c.text}\n"}.joinToString("\n")
    }
}
