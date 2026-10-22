package com.clu.quickanswer.ocr

import kotlin.math.max
import kotlin.math.min

/**
 * Tight text-line boxes inside a region, for text of either polarity: [ColorLayout] only knows
 * dark text on its cream and white boxes, but most games the app should learn are dark with light
 * text. The line cutting is the same kind as [ColorLayout]'s (Otsu, row projection into bands, merge
 * of bands split by a small gap, column extent per band, tight boxes) so the recogniser sees the
 * same kind of crop; only the choice of which side of the threshold is ink differs.
 *
 * Differences that matter for templates, whose rectangles are only as exact as a person drew them:
 * - the rows are projected over the box's columns only (a little inset, so a button's bevel is not
 *   a line) and the row threshold is relative to the densest row, so a bevel or a frame does not
 *   count as much as it used to; a line then grows over its own sparse rows (dots, ascenders);
 * - a band that is really two lines glued together by descenders is split at its deepest valley,
 *   but a decoration under a single line (a swoosh, a tail) is not cut off as a second line;
 * - the horizontal extent of a line is looked for a little beyond the box, so a box that is a bit
 *   too narrow still gives the whole line, and a line that continues past the box (its first or last
 *   word would be missing) is reported as [Result.clipped]. Frames, bars, picture edges and specks
 *   beside the box are not mistaken for more of the line.
 */
object TextLines {
    /** What [findDetailed] found: the [lines] (top to bottom) and whether any of them runs out of the box. */
    class Result(val lines: List<PxRect>, val clipped: Boolean)

    /** Horizontal padding added to a line as a fraction of its height (vertical padding is harmful). */
    private const val PAD_X = 0.25

    /** Share of the box's width ignored on each side for the row projection: borders, bevels, rounded corners. */
    private const val INSET_X = 0.015

    /** Share of the box's height ignored at the top and at the bottom. */
    private const val INSET_Y = 0.03

    /** How far (share of the box's width) beyond the box a line is followed to see whether it continues. */
    private const val EXPAND_X = 0.05

    /**
     * Ink columns further apart than this many line heights belong to different segments. Measured on
     * real captures: gaps between words reach 0.65 of the line height, while the things that sit beside
     * a question box (a frame, a neighbouring key or picture) start 0.85-1.15 of it away.
     */
    private const val SEGMENT_GAP = 0.8

    /** A column that is ink over more than this share of a band's rows is "solid": a stroke of a letter, or a fill. */
    private const val SOLID_SHARE = 0.85

    /** Ink columns closer than this (share of the line height) are one cluster; a cluster narrower than [SPECK_WIDTH] of it is a speck, not a letter. */
    private const val SPECK_GAP = 0.25
    private const val SPECK_WIDTH = 0.1

    /** A run of full-height columns at least this wide (px) is a bar, not a letter's stroke. */
    private const val MIN_BAR_PX = 5

    /** A run of solid columns outside the box at least this wide (share of the line height, 4 px at least) is a fill, not letters. */
    private const val FILL_WIDTH = 0.3

    /** A row is "on" when it has more than this share of the densest row's ink. */
    private const val ROW_PEAK_SHARE = 0.06

    /** A line grows over sparser rows next to it, down to this share of its own densest row, by at most [GROW_MAX] of its height. */
    private const val GROW_SHARE = 0.03
    private const val GROW_MAX = 0.8

    /** A band taller than this many times the (lower) median band height is more than one line. */
    private const val TALL_BAND = 1.6

    /** A valley is a row range whose smoothed ink count is below this share of the band's peak. */
    private const val VALLEY_SHARE = 0.30

    /** Each side of a valley reaches at least this share of the band's peak (a line, not a few dots). */
    private const val SIDE_PEAK_SHARE = 0.30

    /** Pieces of a split are at least this share of the tallest band. */
    private const val MIN_PIECE = 0.35

    /** When the band is the only line, both pieces are at least this share of the taller piece. */
    private const val SOLE_PIECE = 0.7

    /** Capture size (long side) the pixel constants were tuned on, see [ColorLayout.REFERENCE_LONG_SIDE]. */
    private const val REFERENCE_LONG_SIDE = ColorLayout.REFERENCE_LONG_SIDE.toDouble()

    /** Ink is at least this far from the background in luma, so a faint gradient is not text. */
    private const val MIN_CONTRAST = 25

    /** Below this standard deviation of luma a region is flat: no text. */
    private const val MIN_STD = 12.0

    /** The text lines of [findDetailed], without the clipping flag. */
    fun find(argb: IntArray, width: Int, height: Int, box: PxRect): List<PxRect> =
        findDetailed(argb, width, height, box).lines

    /**
     * Text lines inside [box] (input-image pixels), top to bottom. Rows are looked at inside the
     * box shrunk by 3 % vertically, columns inside it shrunk by 1.5 % sideways; a line's horizontal
     * extent is looked for up to 5 % of the box's width beyond it. Empty for a flat region or one
     * without ink. [Result.clipped] is true when a line is still going at the end of that range (ink
     * within [SEGMENT_GAP] line heights of its last column, or the screen's edge): the text continues
     * outside the box and a word may be missing.
     */
    fun findDetailed(argb: IntArray, width: Int, height: Int, box: PxRect): Result {
        require(width > 0 && height > 0 && argb.size >= width * height) { "bad image size" }
        val none = Result(emptyList(), false)
        val unit = max(width, height) / REFERENCE_LONG_SIDE
        val insetX = (box.width * INSET_X).toInt()
        val rx0 = (box.left + insetX).coerceIn(0, width)
        val rx1 = (box.right - insetX).coerceIn(0, width)
        val ry0 = (box.top + (box.height * INSET_Y).toInt()).coerceIn(0, height)
        val ry1 = (box.bottom - (box.height * INSET_Y).toInt()).coerceIn(0, height)
        val w = rx1 - rx0
        val h = ry1 - ry0
        if (w <= 0 || h <= 0) return none
        // Columns: the box, [expand] beyond it (where a line may legitimately stick out), and [peek] more
        // (only to see whether a line continues past that: a word gap must not hide it).
        val expand = (box.width * EXPAND_X).toInt()
        val peek = Math.ceil(SEGMENT_GAP * h).toInt()
        val ex0 = (box.left - expand).coerceIn(0, width)
        val ex1 = (box.right + expand).coerceIn(0, width)
        val ox0 = (box.left - expand - peek).coerceIn(0, width)
        val ox1 = (box.right + expand + peek).coerceIn(0, width)
        val ew = ox1 - ox0

        val gray = ImageOps.grayRoi(argb, width, ox0, ry0, ox1, ry1) // the outer columns; the inner ones start at ix
        val ix = rx0 - ox0
        var sum = 0.0
        for (y in 0 until h) for (x in 0 until w) sum += gray[y * ew + ix + x]
        val mean = sum / (w * h)
        var sq = 0.0
        for (y in 0 until h) for (x in 0 until w) { val d = gray[y * ew + ix + x] - mean; sq += d * d }
        if (Math.sqrt(sq / (w * h)) < MIN_STD) return none

        // The background is what the edge of the region is made of; the ink is what lies on the
        // other side of the Otsu threshold and clearly differs from it.
        val inner = IntArray(w * h)
        for (y in 0 until h) System.arraycopy(gray, y * ew + ix, inner, y * w, w)
        val edge = ArrayList<Int>(2 * (w + h))
        for (x in 0 until w) { edge.add(inner[x]); edge.add(inner[(h - 1) * w + x]) }
        for (y in 1 until h - 1) { edge.add(inner[y * w]); edge.add(inner[y * w + w - 1]) }
        edge.sort()
        val border = edge[edge.size / 2]
        val threshold = ImageOps.otsu(inner)
        val ink = if (border > threshold) {
            BooleanArray(gray.size) { gray[it] <= threshold && border - gray[it] >= MIN_CONTRAST }
        } else {
            BooleanArray(gray.size) { gray[it] > threshold && gray[it] - border >= MIN_CONTRAST }
        }

        // Columns that are ink over nearly the whole height (a bar, a frame, a fill's edge) are no text; wide ones
        // are left out of the row profile too, or they would make every row look inked.
        val fullHeight = BooleanArray(ew) { x ->
            var n = 0
            for (y in 0 until h) if (ink[y * ew + x]) n++
            n > SOLID_SHARE * h
        }
        val bar = BooleanArray(ew)
        run {
            var x = 0
            while (x < ew) {
                if (!fullHeight[x]) { x++; continue }
                var end = x
                while (end < ew && fullHeight[end]) end++
                if (end - x >= MIN_BAR_PX) for (k in x until end) bar[k] = true
                x = end
            }
        }
        // Row profile over the box's own columns, bands where it is dense enough.
        val profile = IntArray(h)
        var peak = 0
        for (y in 0 until h) {
            var n = 0
            val base = y * ew + ix
            for (x in 0 until w) if (ink[base + x] && !bar[ix + x]) n++
            profile[y] = n
            if (n > peak) peak = n
        }
        val rowMin = max(max(2.0, 0.004 * w), ROW_PEAK_SHARE * peak)
        val bands = ArrayList<IntArray>()
        var start = -1
        for (y in 0..h) {
            val on = y < h && profile[y] > rowMin
            if (on && start < 0) {
                start = y
            } else if (!on && start >= 0) {
                bands.add(intArrayOf(start, y))
                start = -1
            }
        }
        // Merge bands split by a small gap (dots and diacritics above or below the line).
        val merged = ArrayList<IntArray>()
        for (b in bands) {
            val last = merged.lastOrNull()
            if (last != null && b[0] - last[1] < 0.35 * max(8.0 * unit, (last[1] - last[0]).toDouble())) {
                last[1] = b[1]
            } else {
                merged.add(b)
            }
        }
        if (merged.isEmpty()) return none
        splitMergedLines(merged, profile)
        // Stray marks (shorter than a third of the tallest band) are no lines; their rows may still belong to a
        // neighbouring line, which finds them again when it grows over its sparse rows.
        val tallestCore = merged.maxOf { it[1] - it[0] }
        merged.removeAll { it[1] - it[0] < MIN_PIECE * tallestCore }
        val cores = merged.map { it.copyOf() }
        extendToFaintRows(merged, profile, max(2.0, 0.004 * w))
        val tallest = merged.maxOf { it[1] - it[0] }

        val margin = max(2, Math.rint(2.0 * unit).toInt())
        val out = ArrayList<PxRect>()
        var clipped = false
        for ((i, ext) in merged.withIndex()) {
            if (ext[1] - ext[0] < 0.35 * tallest) continue // stray marks
            // Columns with ink in the line proper (not the faint rows around it), over the box and a little beyond it.
            val a = cores[i][0]
            val b = cores[i][1]
            val bandH = b - a
            val any = BooleanArray(ew)
            val solid = BooleanArray(ew)
            for (x in 0 until ew) {
                var n = 0
                for (y in a until b) if (ink[y * ew + x]) n++
                any[x] = n > 0
                solid[x] = n > SOLID_SHARE * bandH
            }
            // A bar (see above) is no text; a thinner full-height column is none either beyond the box.
            for (k in 0 until ew) if (bar[k] || (fullHeight[k] && (k < ix || k >= ix + w))) any[k] = false
            var x: Int
            // Beyond the box, a wide run of solid columns (a neighbouring fill, a picture's edge or
            // shadow) is not text. Thin ones are strokes of letters, which are as tall as the line.
            val minFill = max(4, (FILL_WIDTH * bandH).toInt())
            x = 0
            while (x < ew) {
                if (!solid[x]) { x++; continue }
                var end = x
                while (end < ew && solid[end]) end++
                if (end - x >= minFill) for (k in x until end) if (k < ix || k >= ix + w) any[k] = false
                x = end
            }
            // Isolated specks (a bevel's highlight, a frame's remnant) must not bridge the gap to something else.
            val speckGap = max(2, (SPECK_GAP * bandH).toInt())
            val speckMax = max(3, (SPECK_WIDTH * bandH).toInt())
            x = 0
            while (x < ew) {
                if (!any[x]) { x++; continue }
                var end = x + 1
                var last = x
                while (end < ew && end - last <= speckGap) { if (any[end]) last = end; end++ }
                if (last - x + 1 < speckMax) for (k in x..last) any[k] = false
                x = last + 1
            }
            // Segments of ink columns, words apart by less than SEGMENT_GAP line heights stay together.
            val maxGap = SEGMENT_GAP * bandH
            var segFirst = -1
            var segLast = -1
            var keepFirst = -1
            var keepLast = -1
            fun closeSegment() {
                if (segFirst >= 0 && segLast >= ix && segFirst < ix + w) {
                    if (keepFirst < 0) keepFirst = segFirst
                    keepLast = segLast
                }
                segFirst = -1
            }
            for (x in 0 until ew) {
                if (!any[x]) continue
                if (segFirst >= 0 && x - segLast > maxGap) closeSegment()
                if (segFirst < 0) segFirst = x
                segLast = x
            }
            closeSegment()
            if (keepFirst < 0) continue
            // The line continues past the search range (or off the screen): a word may be missing.
            if (ox0 + keepFirst < ex0 || ox0 + keepLast >= ex1 || ox0 + keepFirst <= 0 || ox0 + keepLast >= width - 1) clipped = true
            val px = (PAD_X * bandH).toInt() + margin
            out.add(
                PxRect(
                    max(ex0, ox0 + keepFirst - px),
                    max(ry0, ry0 + ext[0] - margin),
                    min(ex1, ox0 + keepLast + px),
                    min(ry1, ry0 + ext[1] + margin),
                )
            )
        }
        return Result(out, clipped)
    }

    /**
     * A band is found with a threshold relative to the densest row of the whole box, which is right
     * for telling lines apart but leaves out the rows next to a line that are sparse: the dots and
     * ascenders of Arabic letters have a handful of ink pixels per row, and a short line under a long
     * one has fewer than the long one everywhere. Each band grows over the rows next to it (across a gap
     * of a few rows) that have more than [GROW_SHARE] of the band's *own* densest row (and at least
     * [faintMin] pixels), by at most [GROW_MAX] of its height and never further than halfway to its neighbour.
     */
    private fun extendToFaintRows(bands: MutableList<IntArray>, profile: IntArray, faintMin: Double) {
        val before = bands.map { it.copyOf() }
        for ((i, band) in bands.withIndex()) {
            val h = before[i][1] - before[i][0]
            var own = 0
            for (y in before[i][0] until before[i][1]) if (profile[y] > own) own = profile[y]
            val minInk = max(faintMin, GROW_SHARE * own)
            val maxGrow = (GROW_MAX * h).toInt()
            val gapOk = max(2, (0.1 * h).toInt())
            val floor = if (i == 0) 0 else (before[i - 1][1] + before[i][0]) / 2
            val ceil = if (i == bands.size - 1) profile.size else (before[i][1] + before[i + 1][0]) / 2
            var top = before[i][0]
            var y = top - 1
            var empty = 0
            while (y >= floor && before[i][0] - y <= maxGrow && empty < gapOk) {
                if (profile[y] > minInk) { top = y; empty = 0 } else empty++
                y--
            }
            var bottom = before[i][1]
            y = bottom
            empty = 0
            while (y < ceil && y - before[i][1] < maxGrow && empty < gapOk) {
                if (profile[y] > minInk) { bottom = y + 1; empty = 0 } else empty++
                y++
            }
            band[0] = top
            band[1] = bottom
        }
    }

    /**
     * Cuts bands that are several lines glued together (descenders of one line touching the next
     * line's ascenders, so the rows between them never get empty) at the deepest valley of the row
     * [profile]. A band is cut when it is taller than [TALL_BAND] times the lower median height of
     * the real lines (bands shorter than [MIN_PIECE] of the tallest are stray marks), or, when it is
     * the only real line, when its profile has a valley. Repeats while a band
     * qualifies; a cut never makes a piece smaller than [MIN_PIECE] of the tallest band ([SOLE_PIECE] of the taller piece for a lone band).
     */
    private fun splitMergedLines(bands: MutableList<IntArray>, profile: IntArray) {
        var guard = 0
        while (guard++ < 16) {
            // Stray marks (a bevel, a dot of its own) do not count when judging what a normal line is.
            val tallestBand = bands.maxOf { it[1] - it[0] }
            val significant = bands.filter { it[1] - it[0] >= MIN_PIECE * tallestBand }
            val heights = significant.map { it[1] - it[0] }.sorted()
            val median = heights[(heights.size - 1) / 2]
            var did = false
            for ((i, band) in bands.withIndex()) {
                if (band !in significant) continue
                val tall = significant.size > 1 && band[1] - band[0] > TALL_BAND * median
                if (!tall && significant.size > 1) continue
                val cut = valleyOf(band, profile, requireDeep = !tall) ?: continue
                val first = intArrayOf(band[0], cut)
                val second = intArrayOf(cut, band[1])
                val others = bands.filter { it !== band }.maxOfOrNull { it[1] - it[0] } ?: 0
                val pieceMax = max(first[1] - first[0], second[1] - second[0])
                // Lines of one font are about as tall as each other; a decoration under the text (a swoosh,
                // a bevel, the tail of a letter) is not, so a lone band is only cut into two near-equal pieces.
                val need = if (tall) MIN_PIECE * max(pieceMax, others) else SOLE_PIECE * pieceMax
                if (first[1] - first[0] < need || second[1] - second[0] < need) continue
                bands[i] = first
                bands.add(i + 1, second)
                did = true
                break
            }
            if (!did) return
        }
    }

    /**
     * The row at which to cut [band]: the lowest point of the smoothed [profile] in its middle 60 %.
     * With [requireDeep] it must also be a real valley: below [VALLEY_SHARE] of the band's peak, with
     * a line's worth of ink ([SIDE_PEAK_SHARE] of the peak) on both sides. Null when there is none.
     */
    private fun valleyOf(band: IntArray, profile: IntArray, requireDeep: Boolean): Int? {
        val a = band[0]
        val b = band[1]
        val n = b - a
        if (n < 8) return null
        val s = DoubleArray(n) { i ->
            var sum = 0.0
            var c = 0
            for (k in max(0, i - 1)..min(n - 1, i + 1)) { sum += profile[a + k]; c++ }
            sum / c
        }
        var peak = 0.0
        for (v in s) if (v > peak) peak = v
        if (peak <= 0) return null
        val lo = (0.2 * n).toInt()
        val hi = (0.8 * n).toInt()
        if (hi <= lo) return null
        var best = -1
        for (i in lo until hi) {
            if (best < 0 || s[i] < s[best] || (s[i] == s[best] && Math.abs(i - n / 2) < Math.abs(best - n / 2))) best = i
        }
        if (best < 0) return null
        if (requireDeep) {
            if (s[best] >= VALLEY_SHARE * peak) return null
            var left = 0.0
            for (i in 0 until best) if (s[i] > left) left = s[i]
            var right = 0.0
            for (i in best + 1 until n) if (s[i] > right) right = s[i]
            if (left < SIDE_PEAK_SHARE * peak || right < SIDE_PEAK_SHARE * peak) return null
        }
        return a + best
    }
}
