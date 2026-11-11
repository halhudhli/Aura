package com.abonoura.aura
import org.junit.Assert.*
import org.junit.Test
class HttpRangesTest {
    @Test fun resolvesNormalOpenAndSuffixRanges() {
        assertEquals(HttpByteRange(0,999,false),HttpRanges.resolve(null,1000))
        assertEquals(HttpByteRange(200,400,true),HttpRanges.resolve("bytes=200-400",1000))
        assertEquals(HttpByteRange(900,999,true),HttpRanges.resolve("bytes=900-",1000))
        assertEquals(HttpByteRange(900,999,true),HttpRanges.resolve("bytes=-100",1000))
        assertEquals(HttpByteRange(0,999,true),HttpRanges.resolve("bytes=-2000",1000))
    }
    @Test fun clampsEndAndRejectsInvalidOrMultipleRanges() {
        assertEquals(HttpByteRange(900,999,true),HttpRanges.resolve("bytes=900-2000",1000))
        listOf("bytes=1000-","bytes=40-20","bytes=0-2,4-6","bytes=-0","bytes=-","bytes=99999999999999999999999-").forEach{assertNull(HttpRanges.resolve(it,1000))}
        assertNull(HttpRanges.resolve("bytes=0-",0))
        assertEquals(0L,HttpRanges.resolve(null,0)!!.length)
    }
}
