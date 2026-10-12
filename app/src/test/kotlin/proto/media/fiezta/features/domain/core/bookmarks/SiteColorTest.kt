package proto.media.fiezta.features.domain.core.bookmarks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteColorTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val transparent = 0x00FF0000
    private val youtubeRed = 0xFFFF0000.toInt()
    private val twitchPurple = 0xFF9146FF.toInt()
    private val facebookBlue = 0xFF1877F2.toInt()

    private fun icon(vararg areas: Pair<Int, Int>): IntArray =
        areas.flatMap { (color, pixels) -> List(pixels) { color } }.toIntArray()

    @Test
    fun everyAddressOfASiteSharesOneDomain() {
        assertEquals("youtube.com", SiteColor.domainOf("https://www.youtube.com/"))
        assertEquals("youtube.com", SiteColor.domainOf("https://m.youtube.com/watch?v=1"))
        assertEquals("youtube.com", SiteColor.domainOf("https://music.youtube.com/playlist?list=2"))
        assertNull(SiteColor.domainOf("not a url"))
    }

    @Test
    fun theFallbackIsTheSameForTheSameDomainAndStaysInThePalette() {
        val index = SiteColor.fallbackIndex("youtube.com", 5)
        assertEquals(index, SiteColor.fallbackIndex("youtube.com", 5))
        assertTrue(index in 0 until 5)
        assertTrue(SiteColor.fallbackIndex(null, 5) in 0 until 5)
    }

    @Test
    fun theAccentWinsOverWhiteBlackAndTransparentPixels() {
        assertEquals(youtubeRed, SiteColor.ofIcon(icon(transparent to 400, youtubeRed to 300, white to 250, black to 74)))
        assertEquals(twitchPurple, SiteColor.ofIcon(icon(white to 600, twitchPurple to 424)))
        assertEquals(facebookBlue, SiteColor.ofIcon(icon(facebookBlue to 800, white to 224)))
    }

    @Test
    fun theLargestAccentWinsWhenItDominatesTheOthers() {
        assertEquals(facebookBlue, SiteColor.ofIcon(icon(facebookBlue to 500, youtubeRed to 200, white to 324)))
    }

    @Test
    fun anIconOfManyColoursWithNoDominantOneIsWhite() {
        val googleBlue = 0xFF4285F4.toInt()
        val googleRed = 0xFFEA4335.toInt()
        val googleYellow = 0xFFFBBC05.toInt()
        val googleGreen = 0xFF34A853.toInt()
        val google = icon(white to 500, googleBlue to 170, googleRed to 130, googleYellow to 100, googleGreen to 124)
        assertEquals(white, SiteColor.ofIcon(google))
    }

    @Test
    fun onlyALightColourNeedsDarkInitials() {
        assertTrue(SiteColor.needsDarkInitials(white))
        assertFalse(SiteColor.needsDarkInitials(youtubeRed))
        assertFalse(SiteColor.needsDarkInitials(twitchPurple))
        assertFalse(SiteColor.needsDarkInitials(facebookBlue))
    }

    @Test
    fun anIconWithNoAccentHasNoColor() {
        assertNull(SiteColor.ofIcon(icon(white to 500, black to 524)))
        assertNull(SiteColor.ofIcon(icon(transparent to 1024)))
        assertNull(SiteColor.ofIcon(IntArray(0)))
    }

    @Test
    fun aBrightAccentIsDimmedSoWhiteInitialsStayReadable() {
        val color = SiteColor.ofIcon(icon(0xFFFFFF00.toInt() to 1024))!!
        val red = color shr 16 and 0xFF
        val green = color shr 8 and 0xFF
        val blue = color and 0xFF
        assertEquals(red, green)
        assertEquals(0, blue)
        assertTrue(red in 150..180)
    }
}
