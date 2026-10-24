package com.clu.quickanswer.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A readable screenshot is not necessarily an enabled answer screen. Before touching a choice,
 * require four separate visible button surfaces with real, high-contrast strokes on each one.
 * This does not decide which answer is correct; the caller still has to match its printed words.
 */
object ChoiceTouchGuard {
    private data class Surface(val light: Int, val red: Int, val green: Int, val blue: Int)

    data class Content(val question: PxRect, val picture: PxRect?)

    /** Stable content of the verified parchment fallback, excluding its category, HUD and timer. */
    fun stableContent(argb: IntArray, width: Int, height: Int, buttons: List<PxRect>): Content? {
        if (width < 320 || height < 200 || buttons.size != 4 ||
            argb.size.toLong() < width.toLong() * height) return null
        val left = (width * 0.33).toInt()
        val right = (width * 0.76).toInt()
        val bottom = (buttons.minOf { it.top } - height * 0.02).toInt().coerceIn(0, height)
        val top = (buttons.minOf { it.top } - height * 0.24).toInt().coerceIn(0, bottom)
        if (bottom - top < 8) return null
        data class Row(val y: Int, val left: Int, val right: Int, val count: Int)
        val rows = ArrayList<Row>()
        for (y in top until bottom) {
            var first = right; var last = left; var count = 0
            for (x in left until right) {
                val c = argb[y * width + x]
                val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
                if (b - r < 20 || g - r < 15 || luma(c) !in 30..180) continue
                first = minOf(first, x); last = maxOf(last, x); count++
            }
            if (count >= maxOf(3, width / 320)) rows.add(Row(y, first, last + 1, count))
        }
        val bands = ArrayList<MutableList<Row>>()
        for (row in rows) {
            if (bands.isEmpty() || row.y - bands.last().last().y > maxOf(3, height / 40)) bands.add(mutableListOf())
            bands.last().add(row)
        }
        val text = bands.filter { band ->
            band.maxOf { it.right } - band.minOf { it.left } >= width * 0.06 &&
                band.sumOf { it.count } >= maxOf(24, width * height / 12_000) &&
                band.last().y - band.first().y >= maxOf(4, height / 100)
        }.flatten()
        if (text.isEmpty()) return null
        val pad = maxOf(2, height / 200)
        val caption = PxRect(maxOf(left, text.minOf { it.left } - pad), maxOf(top, text.first().y - pad),
            minOf(right, text.maxOf { it.right } + pad), minOf(bottom, text.last().y + pad + 1))
        // Only this fallback adapter knows this photo mount; the general reader supplies its own geometry.
        val photo = PxRect((width * 0.095).toInt(), (height * 0.365).toInt(),
            (width * 0.313).toInt(), (height * 0.75).toInt())
        return Content(caption, photo.takeIf { PictureRegion.isShown(argb, width, height, PictureRegion.detectArea(it)) })
    }

    /** A trusted reader/template supplies all regions; no game-wide coordinates or four-choice assumption. */
    fun isReadyFromLayout(
        argb: IntArray, width: Int, height: Int, question: PxRect,
        buttons: List<PxRect>, words: List<PxRect>,
    ): Boolean {
        if (width < 200 || height < 200 || argb.size.toLong() < width.toLong() * height ||
            buttons.size !in 2..8 || words.size != buttons.size) return false
        fun valid(r: PxRect) = r.left >= 0 && r.top >= 0 && r.right <= width && r.bottom <= height &&
            r.width >= 8 && r.height >= 8
        if (!valid(question) || buttons.any { !valid(it) } || words.any { !valid(it) }) return false
        // An embedded landscape screenshot in a tall image viewer is not a portrait quiz UI.
        val top = minOf(question.top, buttons.minOf { it.top })
        val bottom = maxOf(question.bottom, buttons.maxOf { it.bottom })
        if (height > width && bottom - top < height * 0.55) return false
        for (i in buttons.indices) {
            val b = buttons[i]
            val word = words[i]
            if (b.width < 24 || b.height < 16 || b.width.toDouble() / b.height !in 1.0..40.0 ||
                word.left < b.left || word.top < b.top || word.right > b.right || word.bottom > b.bottom ||
                overlaps(question, b)) return false
            if (buttons.drop(i + 1).any { overlaps(b, it) }) return false
            if (inspect(argb, width, height, b, word, fromLayout = true) == null) return false
        }
        return true
    }

    /** Include the pinned photo as well as the caption/options; keep the animated timer below it out. */
    fun contentArea(width: Int, height: Int, banner: PxRect?, buttons: List<PxRect>): PxRect {
        require(width > 0 && height > 0 && buttons.size == 4)
        val grid = abs(buttons[0].centerY - buttons[1].centerY) < buttons[0].height / 2
        // Below a wide white banner the animated players flank a narrow stack. The caller
        // fingerprints the four buttons separately; a bounding union would include those avatars.
        if (!grid && banner != null) return banner
        val left = if (grid) (width * 0.05f).toInt() else minOf(banner?.left ?: width, buttons.minOf { it.left })
        val top = minOf(banner?.top ?: (height * 0.30f).toInt(), buttons.minOf { it.top })
        val right = maxOf(banner?.right ?: (width * 0.76f).toInt(), buttons.maxOf { it.right })
        val bottom = maxOf(buttons.maxOf { it.bottom } + 4, if (grid) (height * 0.80f).toInt() else 0)
        return PxRect(left.coerceAtLeast(0), top.coerceAtLeast(0), right.coerceAtMost(width), bottom.coerceAtMost(height))
    }

    fun isReady(
        argb: IntArray, width: Int, height: Int,
        buttons: List<PxRect>, words: List<PxRect>,
    ): Boolean {
        // In particular, never touch a portrait photo viewer containing a landscape game image.
        if (width < 320 || height < 200 || width.toDouble() / height !in 1.7..2.65 ||
            argb.size.toLong() < width.toLong() * height || buttons.size != 4 || words.size != 4
        ) return false
        fun valid(r: PxRect): Boolean = r.left >= 0 && r.top >= 0 &&
            r.right <= width && r.bottom <= height && r.width >= 6 && r.height >= 4
        if (buttons.any { !valid(it) } || words.any { !valid(it) }) return false
        for (i in buttons.indices) {
            val b = buttons[i]
            val word = words[i]
            if (b.width < max(24, width / 18) || b.height < max(12, height / 40) ||
                b.width.toDouble() / b.height !in 1.8..24.0 ||
                word.left < b.left || word.top < b.top || word.right > b.right || word.bottom > b.bottom
            ) return false
            for (j in i + 1 until buttons.size) {
                if (overlaps(b, buttons[j]) || overlaps(word, words[j])) return false
            }
        }
        val surfaces = buttons.indices.map { index ->
            inspect(argb, width, height, buttons[index], words[index]) ?: return false
        }
        // A recoloured/disabled choice among three active ones is not an enabled set of choices.
        return surfaces.maxOf { it.light } - surfaces.minOf { it.light } <= 50
    }

    private fun inspect(
        argb: IntArray, width: Int, height: Int, button: PxRect, word: PxRect,
        fromLayout: Boolean = false,
    ): Surface? {
        // Inset enough to tolerate approximate button edges and avoid counting a dark bevel as
        // Arabic ink on a white button. Whole-button OCR fallback boxes are intentionally allowed.
        val padX = max(1, min(button.width * 0.06, button.height * 0.24).toInt())
        val padY = max(1, (button.height * 0.24).toInt())
        val interior = PxRect(button.left + padX, button.top + padY,
            button.right - padX, button.bottom - padY)
        val area = interior.width * interior.height
        if (area < 24) return null
        val step = max(1, sqrt(area / 2_048.0).toInt())
        val light = IntArray(256)
        val red = IntArray(256)
        val green = IntArray(256)
        val blue = IntArray(256)
        var samples = 0
        for (y in interior.top until interior.bottom step step) {
            // Vary the horizontal phase so regular text stems cannot alias the sampling grid.
            val phase = ((y - interior.top) / step) % step
            for (x in interior.left + phase until interior.right step step) {
                val pixel = argb[y * width + x]
                light[luma(pixel)]++
                red[(pixel shr 16) and 255]++
                green[(pixel shr 8) and 255]++
                blue[pixel and 255]++
                samples++
            }
        }
        val surface = Surface(quantile(light, samples, 50), quantile(red, samples, 50),
            quantile(green, samples, 50), quantile(blue, samples, 50))
        val chroma = max(surface.red, max(surface.green, surface.blue)) -
            min(surface.red, min(surface.green, surface.blue))
        // A filled green result must not become a new answer target. Other colours are allowed
        // only on the reader-supplied path, with the same edge/contrast tests below.
        if (fromLayout && surface.green >= 90 && surface.green - surface.red >= 40 &&
            surface.green - surface.blue >= 30) return null
        val dark = if (fromLayout) surface.light in 15..175 else surface.light in 20..175 && chroma <= 45
        val white = if (fromLayout) surface.light > 175 else surface.light >= 220 && chroma <= 35
        if (!dark && !white) return null
        // Keep the trimmed range on the background side of the known text polarity. Long names
        // can cover 20–30% of the interior in ink; a symmetric 20–80% range would reject them.
        // Explicit foreground counts below still require real strokes even in a sparse OCR box.
        val backgroundLow = quantile(light, samples, if (dark) 10 else 40)
        val backgroundHigh = quantile(light, samples, if (dark) 60 else 90)
        if (backgroundHigh - backgroundLow > 45) return null
        var surfacePixels = 0
        for (value in max(0, surface.light - 28)..min(255, surface.light + 28)) surfacePixels += light[value]
        if (surfacePixels < samples * 0.65) return null
        if (!hasBoundary(argb, width, height, button, surface)) return null

        val inkArea = PxRect(max(interior.left, word.left), max(interior.top, word.top),
            min(interior.right, word.right), min(interior.bottom, word.bottom))
        if (inkArea.width < 6 || inkArea.height < 4) return null
        val inkStep = max(1, sqrt(inkArea.width.toDouble() * inkArea.height / 8_192).toInt())
        var ink = 0
        var checked = 0
        var firstX = inkArea.right
        var lastX = inkArea.left
        var firstY = inkArea.bottom
        var lastY = inkArea.top
        val occupiedRows = BooleanArray((inkArea.height - 1) / inkStep + 1)
        val occupiedColumns = BooleanArray((inkArea.width - 1) / inkStep + 1)
        for (y in inkArea.top until inkArea.bottom step inkStep) {
            for (x in inkArea.left until inkArea.right step inkStep) {
                checked++
                val pixel = argb[y * width + x]
                val value = luma(pixel)
                val isInk = if (dark) {
                    val r = (pixel shr 16) and 255
                    val g = (pixel shr 8) and 255
                    val b = pixel and 255
                    value >= max(if (fromLayout) 170 else 205, surface.light + 75) &&
                        (fromLayout || max(r, max(g, b)) - min(r, min(g, b)) <= 70)
                } else value <= min(if (fromLayout) 170 else 150, surface.light - 75)
                if (!isInk) continue
                ink++
                firstX = min(firstX, x); lastX = max(lastX, x)
                firstY = min(firstY, y); lastY = max(lastY, y)
                occupiedRows[(y - inkArea.top) / inkStep] = true
                occupiedColumns[(x - inkArea.left) / inkStep] = true
            }
        }
        if (ink < max(8, (checked * 0.015).toInt()) || ink > checked * 0.78 ||
            lastX - firstX < max(5, (inkArea.width * 0.035).toInt()) ||
            lastY - firstY < max(3, (inkArea.height * 0.15).toInt()) ||
            occupiedRows.count { it } < max(3, (occupiedRows.size * 0.10).toInt()) ||
            occupiedColumns.count { it } < max(4, (occupiedColumns.size * 0.04).toInt())
        ) return null
        return surface
    }

    /** Search just outside approximate edges; a word painted on flat parchment is not a button. */
    private fun hasBoundary(
        argb: IntArray, width: Int, height: Int, button: PxRect, surface: Surface,
    ): Boolean {
        val reach = max(3, (button.height * 0.40).toInt())
        val increment = max(1, reach / 8)
        fun changed(pixel: Int): Boolean = abs(luma(pixel) - surface.light) >= 16 ||
            abs(((pixel shr 16) and 255) - surface.red) >= 28 ||
            abs(((pixel shr 8) and 255) - surface.green) >= 28 ||
            abs((pixel and 255) - surface.blue) >= 28
        fun side(horizontal: Boolean, before: Boolean): Boolean {
            for (offset in 1..reach step increment) {
                var distinct = 0
                var sampled = 0
                for (part in 2..8) {
                    val x = if (horizontal) button.left + button.width * part / 10
                        else if (before) button.left - offset else button.right - 1 + offset
                    val y = if (!horizontal) button.top + button.height * part / 10
                        else if (before) button.top - offset else button.bottom - 1 + offset
                    if (x !in 0 until width || y !in 0 until height) continue
                    sampled++
                    if (changed(argb[y * width + x])) distinct++
                }
                if (sampled >= 5 && distinct >= sampled * 0.55) return true
            }
            return false
        }
        return listOf(side(true, true), side(true, false), side(false, true), side(false, false))
            .count { it } >= 2
    }

    private fun overlaps(a: PxRect, b: PxRect): Boolean =
        a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

    private fun luma(pixel: Int): Int =
        (((pixel shr 16) and 255) * 299 + ((pixel shr 8) and 255) * 587 + (pixel and 255) * 114) / 1_000

    private fun quantile(histogram: IntArray, count: Int, percent: Int): Int {
        val target = max(1, (count * percent + 99) / 100)
        var accumulated = 0
        for (value in histogram.indices) {
            accumulated += histogram[value]
            if (accumulated >= target) return value
        }
        return 255
    }
}
