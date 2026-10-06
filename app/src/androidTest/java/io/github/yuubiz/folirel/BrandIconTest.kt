package io.github.yuubiz.folirel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The launcher icons are adaptive, the round one is the same symbol, and the themed-icon layer is white-only (the OS chooses the colour). */
@RunWith(AndroidJUnit4::class)
class BrandIconTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun adaptive(id: Int): AdaptiveIconDrawable =
        ContextCompat.getDrawable(context, id) as? AdaptiveIconDrawable ?: error("resource $id is not an adaptive icon")

    private fun render(d: Drawable, size: Int = 432): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(b))
        return b
    }

    @Test
    fun normalAndRoundAreAdaptiveWithTheirOwnMonochromeLayer() {
        for (id in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
            val icon = adaptive(id)
            assertNotNull(icon.monochrome)
            val mono = render(icon.monochrome!!)
            val fg = render(icon.foreground)
            assertTrue("the monochrome layer must not be the foreground", !mono.sameAs(fg))
        }
        // Same symbol in both icons: identical layers when drawn.
        assertTrue(render(adaptive(R.mipmap.ic_launcher).foreground).sameAs(render(adaptive(R.mipmap.ic_launcher_round).foreground)))
        assertTrue(render(adaptive(R.mipmap.ic_launcher).monochrome!!).sameAs(render(adaptive(R.mipmap.ic_launcher_round).monochrome!!)))
    }

    @Test
    fun theMonochromeLayerHasNoBrandColourBakedIn() {
        val b = render(adaptive(R.mipmap.ic_launcher).monochrome!!)
        var visible = 0
        for (y in 0 until b.height) for (x in 0 until b.width) {
            val p = b.getPixel(x, y)
            if (Color.alpha(p) > 0) {
                visible++
                // Premultiplied or not, a white layer has equal, high channels wherever it is drawn.
                assertEquals("red at $x,$y", 255, Color.red(p))
                assertEquals("green at $x,$y", 255, Color.green(p))
                assertEquals("blue at $x,$y", 255, Color.blue(p))
            }
        }
        assertTrue("the layer draws something", visible > 1000)
        // The leaves keep their weight: the left leaf is fainter than the right one, the third line fainter than the first two.
        val alphas = HashSet<Int>()
        for (y in 0 until b.height step 4) for (x in 0 until b.width step 4) Color.alpha(b.getPixel(x, y)).takeIf { it in 100..254 }?.let { alphas.add(it / 8) }
        assertTrue("translucent parts exist", alphas.isNotEmpty())
    }

    /**
     * The visible area is the central 72 dp of a 108 dp layer, and every mask keeps at least its central 66 dp circle (the safe zone).
     * The supplied symbol's leaf tip touches that circle's edge, so the check allows 1 % of the circle's radius (about 0.3 dp, far
     * inside the 72 dp circle of the smallest mask) and fails for anything that reaches further.
     */
    @Test
    fun theSymbolStaysInsideTheSafeZone() {
        for (layer in listOf(adaptive(R.mipmap.ic_launcher).foreground, adaptive(R.mipmap.ic_launcher).monochrome!!)) {
            val size = 432
            val b = render(layer, size)
            val centre = size / 2f
            val safeRadius = size * 66f / 108f / 2f
            var farthest = 0.0
            for (y in 0 until size) for (x in 0 until size) {
                if (Color.alpha(b.getPixel(x, y)) > 8) farthest = maxOf(farthest, Math.hypot((x - centre).toDouble(), (y - centre).toDouble()))
            }
            assertTrue("farthest symbol pixel $farthest px, safe radius $safeRadius px", farthest <= safeRadius * 1.01)
        }
    }

    /** Evidence only: the icon through a circle and a rounded square mask, saved to files/brand-masks.png. */
    @Test
    fun renderMaskPreview() {
        val size = 432
        val icon = adaptive(R.mipmap.ic_launcher)
        val out = Bitmap.createBitmap(size * 2, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        for ((i, radius) in listOf(size / 2f, size * 0.22f).withIndex()) {
            val save = c.save()
            c.translate(i * size.toFloat(), 0f)
            c.clipPath(android.graphics.Path().apply { addRoundRect(0f, 0f, size.toFloat(), size.toFloat(), radius, radius, android.graphics.Path.Direction.CW) })
            for (layer in listOf(icon.background, icon.foreground)) {
                layer.setBounds(-(size / 4), -(size / 4), size + size / 4, size + size / 4)
                layer.draw(c)
            }
            c.restoreToCount(save)
        }
        File(context.filesDir, "brand-masks.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Evidence only: the monochrome layer tinted the way a themed-icon launcher does (light and dark), saved to files/brand-themed-*.png. */
    @Test
    fun renderThemedPreview() {
        for ((name, plate, ink) in listOf(
            Triple("light", Color.parseColor("#D9E8DD"), Color.parseColor("#1F4A33")),
            Triple("dark", Color.parseColor("#2B3A31"), Color.parseColor("#CFE6D6")),
        )) {
            val size = 432
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = plate })
            val mono = adaptive(R.mipmap.ic_launcher).monochrome!!.mutate()
            mono.colorFilter = PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN)
            // A layer is 108 dp wide and the mask shows its central 72 dp, so the layer is 1.5 times the plate, centred.
            mono.setBounds(-(size / 4), -(size / 4), size + size / 4, size + size / 4)
            mono.draw(c)
            File(context.filesDir, "brand-themed-$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
