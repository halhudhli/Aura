package com.abonoura.aura

import org.junit.Assert.*
import org.junit.Test

class PlaylistParserTest {
    @Test fun parsesGroupsAndNamesContainingCommas() {
        val result=PlaylistParser.parse("\uFEFF#EXTM3U url-tvg=\"epg.xml\"\n#EXTINF:-1 tvg-id=\"one\" tvg-logo=\"/logo.png\" group-title=\"أخبار, عالم\",قناة، الأولى\n#EXTVLCOPT:http-user-agent=Client\nlive/one.m3u8", "https://example.com/list.m3u")
        assertFalse(result.isHls);assertEquals("https://example.com/epg.xml",result.epgUrl)
        val item=result.entries.single();assertEquals("أخبار, عالم",item.group);assertEquals("قناة، الأولى",item.title)
        assertEquals("https://example.com/live/one.m3u8",item.source);assertEquals("Client",item.headers["User-Agent"])
    }
    @Test fun hlsSegmentsAreNeverImportedAsChannels() {
        val result=PlaylistParser.parse("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nsegment.ts", "https://example.com/live.m3u8")
        assertTrue(result.isHls);assertTrue(result.entries.isEmpty())
    }
    @Test fun headersAndMetadataDoNotLeakToNextEntry() {
        val result=PlaylistParser.parse("#EXTINF:-1 group-title=\"Music\",One\n#EXTVLCOPT:http-referrer=https://example.com\nhttps://example.com/1\nhttps://other.example/2", "https://example.com/list")
        assertEquals(2,result.entries.size);assertTrue(result.entries[1].headers.isEmpty());assertEquals("",result.entries[1].group)
    }
    @Test fun invalidSchemesAreIgnoredAndLocalPathsResolve() {
        val result=PlaylistParser.parse("javascript:alert(1)\ntrack one.mp3", "file:///music/list.m3u")
        assertEquals("file:/music/track%20one.mp3",result.entries.single().source)
    }
    @Test fun readsPlsEntriesInNumericOrder() {
        val result=PlaylistParser.parse("[playlist]\nFile2=two.mp3\nTitle2=Two\nFile1=one.mp3\nTitle1=One\nLength1=123", "https://example.com/list.pls")
        assertEquals(listOf("One","Two"),result.entries.map { it.title });assertEquals(123L,result.entries.first().durationSeconds)
    }
}
