package com.abonoura.aura
import org.junit.Assert.*
import org.junit.Test

class XmltvTest {
    @Test fun timezoneOffsetsRepresentSameInstant() {
        assertEquals(Xmltv.timestamp("20261111120000 +0300"),Xmltv.timestamp("20261111090000 +0000"))
        assertNull(Xmltv.timestamp("broken"))
    }
    @Test fun includesCurrentAndFutureButDropsOldProgrammes() {
        val now=Xmltv.timestamp("20261111090000 +0000")!!
        val xml="""<tv><programme channel="one" start="20261111083000 +0000" stop="20261111100000 +0000"><title>خبر &amp; تحليل</title><desc>وصف</desc></programme><programme channel="one" start="20200101000000 +0000" stop="20200101010000 +0000"><title>قديم</title></programme></tv>"""
        val parsed=Xmltv.parse(xml.toByteArray(),now)
        assertEquals(1,parsed.size);assertEquals("خبر & تحليل",parsed[0].title);assertEquals("وصف",parsed[0].description)
    }
    @Test fun rejectsExternalEntities() {
        val xml="""<!DOCTYPE tv [<!ENTITY leak SYSTEM "file:///etc/passwd">]><tv><programme channel="x"><title>&leak;</title></programme></tv>"""
        assertTrue(runCatching{Xmltv.parse(xml.toByteArray())}.isFailure)
    }
}
