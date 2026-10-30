package com.werd.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.werd.app.ocr.Ocr
import com.werd.app.ocr.Preprocess
import com.werd.app.parse.WerdParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OcrPipelineTest {

    @Test fun marksRemovedDotsKept() {
        // 200×100 canvas: one big letter body, one solid 6×6 dot, one thin slanted 12×3 "fatha"
        val w = 200; val h = 100
        val ink = BooleanArray(w * h)
        for (y in 40..80) for (x in 20..120) ink[y * w + x] = true          // letter body
        for (y in 20..25) for (x in 60..65) ink[y * w + x] = true          // dot
        for (k in 0..11) for (t in 0..2) ink[(28 - k / 4 + t) * w + 140 + k] = true  // thin stroke
        val removed = Preprocess.removeMarks(ink, w, h)
        assertEquals(1, removed)
        assertTrue("dot kept", ink[22 * w + 62])
        assertFalse("stroke removed", ink[27 * w + 141])
        assertTrue("body kept", ink[60 * w + 60])
    }

    @Test fun adaptiveThresholdHandlesShadow() {
        // dark text pixel on a light area and on a shadowed area are both ink; paper in shadow is not
        val w = 300; val h = 60
        val gray = IntArray(w * h) { i -> val x = i % w; if (x < 150) 230 else 120 }
        gray[30 * w + 70] = 60; gray[30 * w + 220] = 20
        val ink = Preprocess.binarize(gray, w, h)
        assertTrue(ink[30 * w + 70]); assertTrue(ink[30 * w + 220])
        assertFalse(ink[10 * w + 220]); assertFalse(ink[10 * w + 70])
    }

    @Test fun androidBitmapPath() {
        val f = File("/tmp/claude-0/ocr/page.png")
        if (!f.exists()) return   // only on the dev machine that renders the sample page
        val bmp = BitmapFactory.decodeFile(f.path)
        val out = Ocr.prepare(bmp, ignoreHarakat = true)
        File("/tmp/claude-0/ocr/android_prepared.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertEquals(bmp.width, out.width)
    }

    @Test fun ocrMisreadsRepaired() {
        assertEquals("حسبي، تنصر", Ocr.fixLine("حسبي» تنصر"))
        assertEquals("طس، حم", Ocr.fixLine("طس ء حم"))
        assertEquals("العظيم (ثلاثاً)", Ocr.fixLine("العظيم (ثلا0)"))
        assertEquals("العظيم (ثلاثاً)", Ocr.fixLine("العظيم (ثلائاً)"))
        assertEquals("(صلى الله عليه وسلم)", Ocr.fixLine("(صلى الله عليه وسلم)"))   // real text untouched
        assertEquals("«قال» نعم", Ocr.fixLine("«قال» نعم"))
    }

    @Test fun ocrTextBecomesTally() {
        val raw = javaClass.classLoader!!.getResource("ocr_sample.txt").readText()
        val text = Ocr.tidy(raw)
        val r = WerdParser.parse(text)
        println("TIDY lines=${text.lines().size} items=${r.items.size} counted=${r.items.filter { it.detected }.map { it.count }}")
        r.items.forEach { println("  [${it.count}] ${it.text.take(60)}") }
        assertTrue(r.items.size >= 10)
        assertTrue(r.items.count { it.detected && it.count == 3 } >= 8)
    }
}
