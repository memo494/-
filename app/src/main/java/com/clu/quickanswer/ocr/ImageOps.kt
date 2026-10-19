package com.clu.quickanswer.ocr

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The few image operations the OCR needs, written in plain Kotlin on ARGB int arrays so they run
 * on a JVM without android.graphics. They follow the OpenCV functions the lab prototype used
 * (INTER_AREA / INTER_LINEAR resize, BGR2HSV, BGR2GRAY, rectangle close, connected components,
 * Otsu) closely enough that the layout heuristics, tuned with OpenCV, behave the same.
 */
internal object ImageOps {

    /** A connected blob of set pixels: bounding box ([left], [top], [width], [height]) and pixel [area]. */
    class Blob(val left: Int, val top: Int, val width: Int, val height: Int, val area: Int)

    /** Hue 0..179, saturation 0..255, value 0..255 per pixel (OpenCV's 8-bit convention). */
    class Hsv(val width: Int, val height: Int, val h: ByteArray, val s: ByteArray, val v: ByteArray)

    private const val HSV_SHIFT = 12
    private val SDIV = IntArray(256) { if (it == 0) 0 else Math.rint((255 shl HSV_SHIFT) / it.toDouble()).toInt() }
    private val HDIV = IntArray(256) { if (it == 0) 0 else Math.rint((180 shl HSV_SHIFT) / (6.0 * it)).toInt() }

    /** Box-filter ("area") downscale of the colour channels to [dw] x [dh], like cv2.INTER_AREA. Returns packed 0xRRGGBB. */
    fun areaResize(src: IntArray, w: Int, h: Int, dw: Int, dh: Int): IntArray {
        val sx = w.toDouble() / dw
        val sy = h.toDouble() / dh
        val out = IntArray(dw * dh)
        val acc = DoubleArray(w * 3)
        for (dy in 0 until dh) {
            val y0 = dy * sy
            val y1 = min((dy + 1) * sy, h.toDouble())
            java.util.Arrays.fill(acc, 0.0)
            var wySum = 0.0
            var iy = floor(y0).toInt()
            while (iy < h && iy < y1) {
                val wy = min(iy + 1.0, y1) - max(iy.toDouble(), y0)
                if (wy > 0) {
                    wySum += wy
                    val base = iy * w
                    for (x in 0 until w) {
                        val c = src[base + x]
                        val a = x * 3
                        acc[a] += wy * (c shr 16 and 0xFF)
                        acc[a + 1] += wy * (c shr 8 and 0xFF)
                        acc[a + 2] += wy * (c and 0xFF)
                    }
                }
                iy++
            }
            for (dx in 0 until dw) {
                val x0 = dx * sx
                val x1 = min((dx + 1) * sx, w.toDouble())
                var r = 0.0
                var g = 0.0
                var b = 0.0
                var wxSum = 0.0
                var ix = floor(x0).toInt()
                while (ix < w && ix < x1) {
                    val wx = min(ix + 1.0, x1) - max(ix.toDouble(), x0)
                    if (wx > 0) {
                        wxSum += wx
                        val a = ix * 3
                        r += wx * acc[a]
                        g += wx * acc[a + 1]
                        b += wx * acc[a + 2]
                    }
                    ix++
                }
                val norm = wxSum * wySum
                if (norm <= 0.0) continue
                out[dy * dw + dx] = (clamp8(r / norm) shl 16) or (clamp8(g / norm) shl 8) or clamp8(b / norm)
            }
        }
        return out
    }

    private fun clamp8(v: Double): Int = min(255, max(0, (v + 0.5).toInt()))

    /** HSV of packed 0xRRGGBB pixels, with the same fixed-point arithmetic as cv2.cvtColor(BGR2HSV). */
    fun toHsv(rgb: IntArray, w: Int, h: Int): Hsv {
        val hh = ByteArray(w * h)
        val ss = ByteArray(w * h)
        val vv = ByteArray(w * h)
        for (i in 0 until w * h) {
            val c = rgb[i]
            val r = c shr 16 and 0xFF
            val g = c shr 8 and 0xFF
            val b = c and 0xFF
            val v = max(r, max(g, b))
            val diff = v - min(r, min(g, b))
            val s = (diff * SDIV[v] + (1 shl (HSV_SHIFT - 1))) shr HSV_SHIFT
            var hue = when {
                v == r -> g - b
                v == g -> b - r + 2 * diff
                else -> r - g + 4 * diff
            }
            hue = (hue * HDIV[diff] + (1 shl (HSV_SHIFT - 1))) shr HSV_SHIFT
            if (hue < 0) hue += 180
            hh[i] = hue.toByte()
            ss[i] = s.toByte()
            vv[i] = v.toByte()
        }
        return Hsv(w, h, hh, ss, vv)
    }

    /**
     * Morphological close with a [k] x [k] square (dilate, then erode). Pixels outside the image
     * count as neutral, as in OpenCV, so a blob touching the border is not eaten away.
     */
    fun close(mask: BooleanArray, w: Int, h: Int, k: Int): BooleanArray {
        val r = k / 2
        return erode(dilate(mask, w, h, r), w, h, r)
    }

    private fun dilate(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val tmp = BooleanArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var any = false
                var xx = max(0, x - r)
                val end = min(w - 1, x + r)
                while (xx <= end) {
                    if (m[row + xx]) { any = true; break }
                    xx++
                }
                tmp[row + x] = any
            }
        }
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r)
            val y1 = min(h - 1, y + r)
            for (x in 0 until w) {
                var any = false
                var yy = y0
                while (yy <= y1) {
                    if (tmp[yy * w + x]) { any = true; break }
                    yy++
                }
                out[y * w + x] = any
            }
        }
        return out
    }

    private fun erode(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val tmp = BooleanArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var all = true
                var xx = max(0, x - r)
                val end = min(w - 1, x + r)
                while (xx <= end) {
                    if (!m[row + xx]) { all = false; break }
                    xx++
                }
                tmp[row + x] = all
            }
        }
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r)
            val y1 = min(h - 1, y + r)
            for (x in 0 until w) {
                var all = true
                var yy = y0
                while (yy <= y1) {
                    if (!tmp[yy * w + x]) { all = false; break }
                    yy++
                }
                out[y * w + x] = all
            }
        }
        return out
    }

    /**
     * 8-connected blobs of set pixels, numbered in raster order of their first pixel (the same
     * order OpenCV labels them in, so "first largest" picks the same blob).
     */
    fun blobs(mask: BooleanArray, w: Int, h: Int): List<Blob> {
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val out = ArrayList<Blob>()
        for (start in 0 until w * h) {
            if (!mask[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = -1
            var maxY = -1
            var area = 0
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % w
                val y = p / w
                area++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                for (ny in max(0, y - 1)..min(h - 1, y + 1)) {
                    for (nx in max(0, x - 1)..min(w - 1, x + 1)) {
                        val q = ny * w + nx
                        if (mask[q] && !seen[q]) {
                            seen[q] = true
                            stack[sp++] = q
                        }
                    }
                }
            }
            out.add(Blob(minX, minY, maxX - minX + 1, maxY - minY + 1, area))
        }
        return out
    }

    /** 8-bit luma like cv2.cvtColor(BGR2GRAY) of the sub-rectangle, row-major, values 0..255. */
    fun grayRoi(argb: IntArray, imgW: Int, x0: Int, y0: Int, x1: Int, y1: Int): IntArray {
        val w = x1 - x0
        val h = y1 - y0
        val out = IntArray(max(0, w) * max(0, h))
        var o = 0
        for (y in y0 until y1) {
            val row = y * imgW
            for (x in x0 until x1) {
                val c = argb[row + x]
                out[o++] = ((c shr 16 and 0xFF) * 4899 + (c shr 8 and 0xFF) * 9617 + (c and 0xFF) * 1868 + 8192) shr 14
            }
        }
        return out
    }

    /** Otsu threshold of 8-bit values (pixels above it are "foreground"), as in cv2.threshold(THRESH_OTSU). */
    fun otsu(values: IntArray): Int {
        val hist = IntArray(256)
        for (v in values) hist[v]++
        val scale = 1.0 / values.size
        var mu = 0.0
        for (i in 0 until 256) mu += i * hist[i] * scale
        var mu1 = 0.0
        var q1 = 0.0
        var maxSigma = -1.0
        var best = 0
        val eps = 1.1920929E-7
        for (i in 0 until 256) {
            val p = hist[i] * scale
            mu1 *= q1
            q1 += p
            val q2 = 1.0 - q1
            if (min(q1, q2) < eps || max(q1, q2) > 1.0 - eps) continue
            mu1 = (mu1 + i * p) / q1
            val mu2 = (mu - q1 * mu1) / q2
            val sigma = q1 * q2 * (mu1 - mu2) * (mu1 - mu2)
            if (sigma > maxSigma) {
                maxSigma = sigma
                best = i
            }
        }
        return best
    }
}
