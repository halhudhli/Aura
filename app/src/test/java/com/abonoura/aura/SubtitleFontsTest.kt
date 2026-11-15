package com.abonoura.aura
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class SubtitleFontsTest {
    @Test fun readsActualBundledFontFamily() {assertEquals("Almarai",SubtitleFonts.family(File("src/main/assets/fonts/Almarai-Regular.ttf").readBytes()))}
    @Test fun rejectsTruncatedAndInvalidFontTables() {assertNull(SubtitleFonts.family(byteArrayOf(0,1,2)));assertNull(SubtitleFonts.family(ByteArray(80){0x7f}))}
}
