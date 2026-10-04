package io.github.yuubiz.folirel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuubiz.folirel.backend.local.icon.AndroidIconDecoder
import io.github.yuubiz.folirel.data.db.FeedIconEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * U08 (decoding on a real Android runtime): PNG, ICO with a PNG entry, ICO with a BMP entry, a wide logo, a large
 * image, and things that are not images (HTML, SVG) over synthetic fixtures (fixtures/icons, tools/make_icon_fixtures.py).
 */
@RunWith(AndroidJUnit4::class)
class IconDecoderTest {
    private val decoder = AndroidIconDecoder()

    private fun fixture(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open("icons/$name").use { it.readBytes() }

    /** The decoded PNG really has colour in it (a decoder returning a blank bitmap would fail this). */
    private fun hasColour(png: ByteArray): Boolean {
        val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return false
        var coloured = false
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val p = bitmap.getPixel(x, y)
            if ((p ushr 24) > 0 && (p and 0xFFFFFF) != 0xFFFFFF) coloured = true
        }
        return coloured
    }

    @Test
    fun pngDecodesToANormalizedPng() {
        val icon = decoder.decode(fixture("icon-32.png"))
        assertNotNull(icon)
        assertEquals(32, icon!!.width)
        assertEquals(32, icon.height)
        assertFalse(icon.isBanner)
        assertTrue(hasColour(icon.png))
    }

    @Test
    fun icoWithPngEntryDecodes() {
        val icon = decoder.decode(fixture("icon-png-entry.ico"))
        assertNotNull("Android decodes ICO with an embedded PNG", icon)
        assertEquals(32, icon!!.width)
        assertTrue(hasColour(icon.png))
    }

    @Test
    fun icoWithBmpEntryDecodes() {
        val icon = decoder.decode(fixture("icon-bmp.ico"))
        assertNotNull("Android decodes ICO with a BMP entry", icon)
        assertEquals(16, icon!!.width)
        assertEquals(16, icon.height)
        assertTrue(hasColour(icon.png))
    }

    @Test
    fun aWideLogoIsRecognizedAsABannerAndLargeImagesAreScaledDown() {
        val banner = decoder.decode(fixture("banner-300x50.png"))!!
        assertTrue(banner.isBanner)
        assertTrue(maxOf(banner.width, banner.height) <= FeedIconEntity.MAX_SIDE_PX)
        assertEquals(banner.width.toFloat() / banner.height, 6f, 0.3f)
        val big = decoder.decode(fixture("big-512.png"))!!
        assertTrue(maxOf(big.width, big.height) <= FeedIconEntity.MAX_SIDE_PX)
        assertEquals(big.width, big.height)
        assertTrue("stored icons stay small", big.png.size < 64 * 1024)
    }

    @Test
    fun nonImagesFallBackInsteadOfCrashing() {
        assertNull(decoder.decode(fixture("not-an-image.html")))
        assertNull("SVG is not decoded; the initial letter is used", decoder.decode(fixture("vector.svg")))
        assertNull(decoder.decode(ByteArray(0)))
        assertNull(decoder.decode(ByteArray(2048) { (it * 31).toByte() }))
        // A truncated PNG does not throw.
        val png = fixture("icon-32.png")
        decoder.decode(png.copyOf(png.size / 2))
        // Pixel format sanity: a normalized icon decodes to ARGB_8888 at the stated size.
        val icon = decoder.decode(png)!!
        val bitmap = BitmapFactory.decodeByteArray(icon.png, 0, icon.png.size)
        assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
        assertEquals(icon.width, bitmap.width)
    }
}
