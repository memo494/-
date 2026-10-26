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
        if (ink == NumericFieldReader.Ink.GOLD &&
            !removeRoundedCorners(argb, width, height, field, mask, background)) return unknown
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
     * A rectangular crop of the dark capsule includes parchment at its rounded corners. Remove
     * only a solid, tapering corner that continues outside both crop edges and borders the dark
     * field. A clipped character on an edge, or joined to that corner, must remain UNKNOWN.
     */
    private fun removeRoundedCorners(
        argb: IntArray, imageWidth: Int, imageHeight: Int, field: PxRect,
        mask: BooleanArray, background: Int,
    ): Boolean {
        val w = field.width; val h = field.height
        val visited = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        fun pixel(x: Int, y: Int): Int? {
            val px = field.left + x; val py = field.top + y
            return if (px in 0 until imageWidth && py in 0 until imageHeight) argb[py * imageWidth + px] else null
        }
        fun continues(a: Int?, b: Int?): Boolean {
            if (a == null || b == null || !NumericFieldReader.isGold(b)) return false
            return listOf(16, 8, 0).all { shift ->
                kotlin.math.abs((a shr shift and 255) - (b shr shift and 255)) <= 24
            }
        }
        fun dark(x: Int, y: Int): Boolean {
            val c = pixel(x, y) ?: return false
            val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
            val luma = (299 * r + 587 * g + 114 * b) / 1000
            return luma in 15..140 && kotlin.math.abs(luma - background) <= 40 &&
                maxOf(r, g, b) - minOf(r, g, b) <= 45
        }
        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var head = 0; var tail = 1
            var left = w; var right = -1; var top = h; var bottom = -1
            queue[0] = start; visited[start] = true
            while (head < tail) {
                val at = queue[head++]; val x = at % w; val y = at / w
                left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val next = ny * w + nx
                    if (mask[next] && !visited[next]) { visited[next] = true; queue[tail++] = next }
                }
            }
            val onLeft = left == 0; val onRight = right == w - 1
            val onTop = top == 0; val onBottom = bottom == h - 1
            if (!onLeft && !onRight && !onTop && !onBottom) continue
            if (onLeft == onRight || onTop == onBottom) return false
            val cornerWidth = right - left + 1; val cornerHeight = bottom - top + 1
            // A capsule corner occupies less than half the field's height in either direction;
            // never discard a strip through the middle where a clipped digit can lie.
            val limit = minOf(h / 2, w / 4)
            if (cornerWidth !in 3..limit || cornerHeight !in 3..limit) return false
            fun x(u: Int) = if (onLeft) u else w - 1 - u
            fun y(v: Int) = if (onTop) v else h - 1 - v
            var previous = cornerWidth
            for (v in 0 until cornerHeight) {
                var run = 0
                while (run < cornerWidth && mask[y(v) * w + x(run)]) run++
                if (run == 0 || run > previous || (run until cornerWidth).any { mask[y(v) * w + x(it)] }) return false
                if (!continues(pixel(x(0), y(v)), pixel(x(-1), y(v)))) return false
                // Look beyond the anti-aliased boundary, on the capsule's interior side.
                if (!dark(x(run + 2), y(v))) return false
                previous = run
            }
            if (previous > cornerWidth - maxOf(2, cornerWidth / 4)) return false
            for (u in 0 until cornerWidth) {
                if (!continues(pixel(x(u), y(0)), pixel(x(u), y(-1)))) return false
            }
            for (i in 0 until tail) mask[queue[i]] = false
        }
        return true
    }
}
