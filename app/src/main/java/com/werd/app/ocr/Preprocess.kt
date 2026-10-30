package com.werd.app.ocr

/**
 * Image clean-up before OCR, on plain arrays so it is fast and testable off-device.
 *
 * 1. Adaptive threshold (handles shadows / uneven light in phone photos).
 * 2. Optional removal of tashkeel marks: Tesseract's Arabic model reads vocalised text poorly, but reads
 *    the bare letters well. Marks are small connected shapes that are thin or hollow (fatha, kasra, damma,
 *    shadda, sukun, tanween); letter dots are small too but solid and compact, so they are kept.
 */
object Preprocess {

    /** gray: 0..255 per pixel. Returns ink mask (true = dark text pixel). */
    fun binarize(gray: IntArray, w: Int, h: Int, block: Int = (w / 25) or 1, c: Int = 15): BooleanArray {
        // integral image for O(1) local means (Int is enough: 255 × MAX_PIXELS < 2^31)
        require(w.toLong() * h <= MAX_PIXELS) { "image too large" }
        val integ = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0
            for (x in 0 until w) {
                row += gray[y * w + x]
                integ[(y + 1) * (w + 1) + (x + 1)] = integ[y * (w + 1) + (x + 1)] + row
            }
        }
        val r = block / 2
        val ink = BooleanArray(w * h)
        for (y in 0 until h) {
            val y0 = maxOf(0, y - r); val y1 = minOf(h - 1, y + r)
            for (x in 0 until w) {
                val x0 = maxOf(0, x - r); val x1 = minOf(w - 1, x + r)
                val sum = integ[(y1 + 1) * (w + 1) + (x1 + 1)] - integ[y0 * (w + 1) + (x1 + 1)] -
                    integ[(y1 + 1) * (w + 1) + x0] + integ[y0 * (w + 1) + x0]
                val n = (y1 - y0 + 1) * (x1 - x0 + 1)
                ink[y * w + x] = gray[y * w + x].toLong() * n <= sum.toLong() - c.toLong() * n
            }
        }
        return ink
    }

    /** Erases tashkeel-like components from [ink] in place. Returns how many were removed. */
    fun removeMarks(ink: BooleanArray, w: Int, h: Int, fillMin: Float = 0.5f, areaFrac: Float = 0.15f): Int {
        val label = IntArray(w * h)
        val queue = IntArray(w * h)
        val areas = ArrayList<Int>()
        val boxes = ArrayList<IntArray>() // minX, minY, maxX, maxY
        var next = 0
        for (start in 0 until w * h) {
            if (!ink[start] || label[start] != 0) continue
            next++
            var head = 0; var tail = 0
            queue[tail++] = start; label[start] = next
            var minX = w; var minY = h; var maxX = 0; var maxY = 0; var area = 0
            while (head < tail) {
                val p = queue[head++]
                val px = p % w; val py = p / w
                area++
                if (px < minX) minX = px; if (px > maxX) maxX = px
                if (py < minY) minY = py; if (py > maxY) maxY = py
                for (dy in -1..1) {
                    val ny = py + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        val nx = px + dx
                        if (nx < 0 || nx >= w) continue
                        val q = ny * w + nx
                        if (ink[q] && label[q] == 0) { label[q] = next; queue[tail++] = q }
                    }
                }
            }
            areas += area; boxes += intArrayOf(minX, minY, maxX, maxY)
        }
        if (next == 0) return 0
        val sorted = areas.sorted()
        val big = sorted.subList(sorted.size - maxOf(1, sorted.size / 4), sorted.size)
        val ref = big[big.size / 2].toFloat()          // typical letter-body size
        val remove = BooleanArray(next + 1)
        var removed = 0
        for (i in 0 until next) {
            val a = areas[i]
            if (a > ref * areaFrac) continue
            val b = boxes[i]
            val bw = b[2] - b[0] + 1; val bh = b[3] - b[1] + 1
            val fill = a.toFloat() / (bw * bh)
            val aspect = maxOf(bw, bh).toFloat() / minOf(bw, bh)
            if (fill >= fillMin && aspect < 1.8f) continue   // a letter dot
            remove[i + 1] = true; removed++
        }
        for (p in 0 until w * h) if (label[p] != 0 && remove[label[p]]) ink[p] = false
        return removed
    }

    const val MAX_PIXELS = 8_000_000L

    /** Scale factor that brings a page to a width Tesseract handles well, within the pixel budget. */
    fun scaleFor(width: Int, height: Int = width): Float {
        var s = when {
            width < 1200 -> minOf(2f, 1600f / width)
            width > 3000 -> 2600f / width
            else -> 1f
        }
        val px = width.toLong() * height * s * s
        if (px > MAX_PIXELS) s *= kotlin.math.sqrt(MAX_PIXELS.toFloat() / px) * 0.98f
        return s
    }
}
