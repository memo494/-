package com.clu.quickanswer.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * Contract for [PictureRegion]: is a picture pinned in an area, and a short fingerprint of it. Only
 * synthetic images (grey levels, smooth "photos" made of sine waves): no real screenshot is in the
 * repository (decision 5).
 */
class PictureRegionTest {

    private fun gray(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private class Img(val w: Int, val h: Int, val px: IntArray)

    private fun image(w: Int, h: Int, f: (x: Int, y: Int) -> Int) =
        Img(w, h, IntArray(w * h) { gray(f(it % w, it / w).coerceIn(0, 255)) })

    private fun Img.rect() = PxRect(0, 0, w, h)

    private fun Img.isShown(r: PxRect = rect()) = PictureRegion.isShown(px, w, h, r)

    private fun Img.hash(r: PxRect = rect()) = PictureRegion.hash(px, w, h, r)

    /** A smooth picture with bright and dark areas, as a function of the position inside it (0..1). */
    private fun photoA(w: Int, h: Int) =
        image(w, h) { x, y -> 128 + (90 * sin(8.0 * x / w) * cos(6.0 * y / h)).toInt() }

    /** Another smooth picture, unrelated to [photoA]. */
    private fun photoB(w: Int, h: Int) =
        image(w, h) { x, y -> 128 + (90 * cos(11.0 * x / w + 1) * sin(9.0 * y / h + 2)).toInt() }

    // ---- detectArea

    @Test
    fun detectArea_isTheRightHalfOfThePicture() {
        // The parchment game's picture on the 2712 x 1220 captures: the centre is at x = 553.
        assertEquals(PxRect(553, 445, 848, 915), PictureRegion.detectArea(PxRect(258, 445, 848, 915)))
    }

    @Test
    fun detectArea_staysInsideThePicture_evenWithAnOddWidth() {
        val p = PxRect(10, 20, 111, 70)
        val d = PictureRegion.detectArea(p)
        assertEquals(p.right, d.right)
        assertEquals(p.top, d.top)
        assertEquals(p.bottom, d.bottom)
        assertTrue(d.left > p.left && d.left < p.right)
        assertTrue("about half", d.width in p.width / 2..p.width / 2 + 1)
    }

    // ---- isShown

    @Test
    fun isShown_isFalseForPlainBackgroundWithNoise() {
        // Measured on the owner's captures: plain parchment is 4.4-11.4. Uniform noise of +-19 is 11.
        val rnd = Random(7)
        val plain = image(400, 300) { _, _ -> 150 + rnd.nextInt(39) - 19 }
        assertFalse(plain.isShown())
        assertFalse(image(400, 300) { _, _ -> 200 }.isShown())
    }

    @Test
    fun isShown_isTrueForAPicture() {
        assertTrue(photoA(400, 300).isShown())
        assertTrue(photoB(400, 300).isShown())
    }

    @Test
    fun isShown_isTrueForAMostlyWhiteDiagram() {
        // The weakest real picture measured (a white diagram) was 31.5: white with a few thin dark lines.
        val diagram = image(400, 300) { x, _ -> if (x % 100 < 2) 20 else 255 }
        assertTrue(diagram.isShown())
    }

    @Test
    fun isShown_thresholdIsMinStdInclusive() {
        // Half the area at one level and half at another: the standard deviation is half the difference.
        fun halves(diff: Int) = image(400, 300) { x, _ -> if (x < 200) 100 else 100 + diff }
        assertTrue(halves(44).isShown())
        assertTrue("exactly MIN_STD counts", halves((2 * PictureRegion.MIN_STD).toInt()).isShown())
        assertFalse(halves(36).isShown())
    }

    @Test
    fun isShown_onlyLooksInsideTheRect() {
        // Left half is a picture, right half is plain.
        val photo = photoA(200, 300)
        val img = image(400, 300) { x, y -> if (x < 200) photo.px[y * 200 + x] and 0xFF else 180 }
        assertTrue(img.isShown(PxRect(0, 0, 200, 300)))
        assertFalse(img.isShown(PxRect(200, 0, 400, 300)))
    }

    @Test
    fun isShown_failsClosedOnAnEmptyOrOutsideRect() {
        val img = photoA(400, 300)
        assertFalse(img.isShown(PxRect(50, 50, 50, 200)))
        assertFalse(img.isShown(PxRect(60, 60, 20, 20)))
        assertFalse(img.isShown(PxRect(500, 50, 700, 200)))
        assertFalse(img.isShown(PxRect(-300, -300, -10, -10)))
        assertFalse(PictureRegion.isShown(IntArray(10), 400, 300, img.rect()))
        assertFalse(PictureRegion.isShown(IntArray(0), 0, 0, PxRect(0, 0, 10, 10)))
    }

    @Test
    fun isShown_clampsARectThatReachesOutsideTheImage() {
        val img = photoA(400, 300)
        assertTrue(img.isShown(PxRect(-100, -100, 500, 400)))
    }

    // ---- hash

    @Test
    fun hash_is64LowercaseHexCharacters() {
        val h = photoA(320, 240).hash()
        assertEquals(PictureRegion.HASH_CHARS, h.length)
        assertEquals(64, h.length)
        assertTrue(h.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun hash_isDeterministic_andTheSamePictureIsAtDistanceZero() {
        val a = photoA(320, 240)
        assertEquals(a.hash(), photoA(320, 240).hash())
        assertEquals(0, PictureRegion.distance(a.hash(), photoA(320, 240).hash()))
    }

    @Test
    fun hash_ignoresBrightness_scaleAndNoise() {
        val a = photoA(320, 240)
        val h = a.hash()
        val brighter = image(320, 240) { x, y -> (a.px[y * 320 + x] and 0xFF) + 25 }
        assertEquals("a uniform brightness shift", 0, PictureRegion.distance(h, brighter.hash()))
        assertTrue("twice the size", PictureRegion.distance(h, photoA(640, 480).hash()) <= PictureRegion.SAME_PICTURE_MAX)
        assertTrue("a smaller one", PictureRegion.distance(h, photoA(200, 150).hash()) <= PictureRegion.SAME_PICTURE_MAX)
        val rnd = Random(3)
        val noisy = image(320, 240) { x, y -> (a.px[y * 320 + x] and 0xFF) + rnd.nextInt(9) - 4 }
        assertTrue("compression noise", PictureRegion.distance(h, noisy.hash()) <= PictureRegion.SAME_PICTURE_MAX)
    }

    @Test
    fun hash_tellsDifferentPicturesApart() {
        val a = photoA(320, 240)
        val b = photoB(320, 240)
        assertTrue(PictureRegion.distance(a.hash(), b.hash()) > PictureRegion.SAME_PICTURE_MAX)
        val inverted = image(320, 240) { x, y -> 255 - (a.px[y * 320 + x] and 0xFF) }
        assertTrue(PictureRegion.distance(a.hash(), inverted.hash()) > 200)
        // Left-to-right against top-to-bottom: half of the bits differ.
        val across = image(320, 240) { x, _ -> x * 255 / 319 }
        val down = image(320, 240) { _, y -> y * 255 / 239 }
        assertTrue(PictureRegion.distance(across.hash(), down.hash()) > 100)
    }

    @Test
    fun hash_dependsOnlyOnTheRect() {
        val a = photoA(300, 220)
        val big = image(500, 400) { x, y ->
            val px = x - 120
            val py = y - 90
            if (px in 0 until 300 && py in 0 until 220) a.px[py * 300 + px] and 0xFF else 20 + (x * 7 + y * 13) % 200
        }
        assertEquals(a.hash(), big.hash(PxRect(120, 90, 420, 310)))
    }

    @Test
    fun hash_ofAUniformAreaSetsNoBit() {
        // Nothing is brighter than the average, so no bit is set; such an area is not a picture anyway.
        val h = image(100, 100) { _, _ -> 77 }.hash()
        assertEquals("0".repeat(64), h)
    }

    @Test(expected = IllegalArgumentException::class)
    fun hash_ofAnEmptyRectIsAProgrammingError() {
        photoA(100, 100).hash(PxRect(10, 10, 10, 50))
    }

    // ---- distance

    @Test
    fun distance_countsDifferingBits() {
        val zero = "0".repeat(64)
        assertEquals(0, PictureRegion.distance(zero, zero))
        assertEquals(4, PictureRegion.distance(zero, "f" + "0".repeat(63)))
        assertEquals(1, PictureRegion.distance(zero, "0".repeat(63) + "1"))
        assertEquals(256, PictureRegion.distance(zero, "f".repeat(64)))
        assertEquals("symmetric", PictureRegion.distance("f".repeat(64), zero), PictureRegion.distance(zero, "f".repeat(64)))
        assertEquals("upper case is the same hex", 0, PictureRegion.distance("ABCDEF0123456789".repeat(4), "abcdef0123456789".repeat(4)))
    }

    @Test
    fun distance_isMaxForMalformedOrDifferentLengthInput() {
        val ok = "0123456789abcdef".repeat(4)
        assertEquals(Int.MAX_VALUE, PictureRegion.distance(ok, ok.drop(1)))
        assertEquals(Int.MAX_VALUE, PictureRegion.distance(ok, ok + "0"))
        assertEquals(Int.MAX_VALUE, PictureRegion.distance(ok, "g" + ok.drop(1)))
        assertEquals(Int.MAX_VALUE, PictureRegion.distance(ok, ""))
        // Two empty strings are not "the same picture".
        assertEquals(Int.MAX_VALUE, PictureRegion.distance("", ""))
        // Short strings are not hashes either, even when they are equal.
        assertEquals(Int.MAX_VALUE, PictureRegion.distance("abc", "abc"))
    }

    @Test
    fun samePictureMax_isAQuarterOfTheBitsAtMost() {
        assertEquals(24, PictureRegion.SAME_PICTURE_MAX)
        assertEquals(256, PictureRegion.HASH_BITS)
    }
}
