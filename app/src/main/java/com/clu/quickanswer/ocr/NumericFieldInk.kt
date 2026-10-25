package com.clu.quickanswer.ocr

/** Pixel evidence for direct entry: unknown/occupied fields never authorize appending or erasing. */
object NumericFieldInk {
    enum class State { EMPTY, FILLED, UNKNOWN }
    data class Sample(val state: State, val pixels: IntArray = intArrayOf(), val width: Int = 0, val height: Int = 0)

    fun inspect(argb: IntArray, width: Int, height: Int, field: PxRect, ink: NumericFieldReader.Ink): Sample {
        val unknown = Sample(State.UNKNOWN)
        if (width <= 0 || height <= 0 || argb.size.toLong() < width.toLong() * height ||
            field.left < 0 || field.top < 0 || field.right > width || field.bottom > height ||
            field.width < 8 || field.height < 8 || ink == NumericFieldReader.Ink.AUTO) return unknown
        val w = field.width; val h = field.height
        val light = IntArray(w * h) { i ->
            val c = argb[(field.top + i / w) * width + field.left + i % w]
            ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000
        }
        val histogram = IntArray(256)
        for (v in light) histogram[v]++
        var seen = 0
        val background = (0..255).first { seen += histogram[it]; seen > light.size / 2 }
        if (ink == NumericFieldReader.Ink.GOLD && background !in 15..140 ||
            ink == NumericFieldReader.Ink.LIGHT && background < 220) return unknown
        val mask = BooleanArray(light.size) { i ->
            if (ink == NumericFieldReader.Ink.GOLD)
                NumericFieldReader.isGold(argb[(field.top + i / w) * width + field.left + i % w])
            else light[i] <= background - 18
        }
        if (ink == NumericFieldReader.Ink.GOLD) dropFlatStrokes(mask, w, h)
        if (ink == NumericFieldReader.Ink.LIGHT) {
            // Remove only thin components attached to the field boundary (its rounded frame).
            // A character clipped at the boundary or joined to that frame remains UNKNOWN.
            val visited = BooleanArray(mask.size)
            val queue = IntArray(mask.size)
            val edge = maxOf(3, h / 8)
            for (start in mask.indices) {
                if (!mask[start] || visited[start]) continue
                var head = 0; var tail = 1; var touches = false; var goesInside = false
                var left = w; var right = -1; var top = h; var bottom = -1
                queue[0] = start; visited[start] = true
                while (head < tail) {
                    val at = queue[head++]; val x = at % w; val y = at / w
                    left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
                    touches = touches || x == 0 || y == 0 || x == w - 1 || y == h - 1
                    goesInside = goesInside || minOf(x, y, w - 1 - x, h - 1 - y) > edge
                    for (dy in -1..1) for (dx in -1..1) {
                        val nx = x + dx; val ny = y + dy
                        if (nx !in 0 until w || ny !in 0 until h) continue
                        val next = ny * w + nx
                        if (mask[next] && !visited[next]) { visited[next] = true; queue[tail++] = next }
                    }
                }
                val frame = !goesInside && (right - left + 1 >= w * 0.6 || bottom - top + 1 >= h * 0.8)
                if (touches && !frame) return unknown
                if (frame || tail <= 2) for (i in 0 until tail) mask[queue[i]] = false
            }
        }
        var left = w; var right = -1; var top = h; var bottom = -1; var count = 0
        val rows = IntArray(h)
        for (i in mask.indices) if (mask[i]) {
            val x = i % w; val y = i / w
            left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
            rows[y]++; count++
        }
        if (count == 0) return Sample(State.EMPTY)
        val glyphHeight = bottom - top + 1
        if (count < 8 || glyphHeight < maxOf(4, h / 5) || left <= 0 || right >= w - 1 ||
            top <= 0 || bottom >= h - 1 || right - left + 1 > glyphHeight * 5 ||
            (top..bottom).any { rows[it] == 0 }) return unknown
        val pad = maxOf(2, glyphHeight / 4)
        val cropW = right - left + 1 + pad * 2; val cropH = glyphHeight + 4
        val clean = IntArray(cropW * cropH) { -1 }
        for (y in top..bottom) for (x in left..right) if (mask[y * w + x])
            clean[(y - top + 2) * cropW + x - left + pad] = 0xff101010.toInt()
        return Sample(State.FILLED, clean, cropW, cropH)
    }

    /**
     * Removes the gold of the field itself, keeping only what can be typed digits. The dark field
     * has a thin gold rim (25 Oct 2026, owner's phone: a line along its bottom in a shared JPEG, a
     * whole outline in a lossless capture), and taking it for ink made every empty field "unknown".
     * A typed number is centred: each digit is at least a fifth of the field tall, crosses its
     * middle row, sits away from its sides, and four digits span well under the field's width. A
     * stroke that is flat, hugs a side, misses the middle row, or spans (nearly) the whole field is
     * the frame and is dropped.
     */
    fun dropFlatStrokes(mask: BooleanArray, w: Int, h: Int) {
        val minHeight = maxOf(4, h / 5)
        val middle = h / 2
        val visited = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var head = 0; var tail = 1; var top = h; var bottom = -1; var left = w; var right = -1
            queue[0] = start; visited[start] = true
            while (head < tail) {
                val at = queue[head++]; val x = at % w; val y = at / w
                top = minOf(top, y); bottom = maxOf(bottom, y); left = minOf(left, x); right = maxOf(right, x)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val next = ny * w + nx
                    if (mask[next] && !visited[next]) { visited[next] = true; queue[tail++] = next }
                }
            }
            val height = bottom - top + 1
            val width = right - left + 1
            val centreX = (left + right) / 2f
            val frame = height < minHeight || middle !in top..bottom ||
                width >= w * FRAME_WIDTH || height >= h * FRAME_HEIGHT ||
                centreX < w * SIDE_MARGIN || centreX > w * (1 - SIDE_MARGIN)
            if (frame) for (i in 0 until tail) mask[queue[i]] = false
        }
    }

    /** A stroke this wide (share of the field) is its rim: four centred digits span about 40%. */
    private const val FRAME_WIDTH = 0.85f

    /** A stroke this tall is a side of the rim: digits leave a margin above and below. */
    private const val FRAME_HEIGHT = 0.9f

    /** A stroke centred this close to a side is the rim's end, not a digit. */
    private const val SIDE_MARGIN = 0.12f
}
