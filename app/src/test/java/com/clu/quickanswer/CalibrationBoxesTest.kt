package com.clu.quickanswer

import com.clu.quickanswer.CalibrationBoxes.Mode
import com.clu.quickanswer.ocr.PxRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The boxes the user has drawn so far on the calibration screen, and how the picture is fitted to the view. */
class CalibrationBoxesTest {
    private fun r(i: Int) = PxRect(10 * i, 10 * i, 10 * i + 5, 10 * i + 5)

    @Test fun keysAreDrawnZeroToNine_thenTheConfirmButton() {
        val b = CalibrationBoxes()
        assertEquals('0', b.nextKey)
        repeat(10) { assertTrue(b.add(Mode.KEY, r(it))) }
        assertNull(b.nextKey)
        assertFalse(b.add(Mode.KEY, r(11)))
        assertEquals(r(7), b.keys[7])
        assertTrue(b.add(Mode.SUBMIT, r(12)))
        assertTrue(b.add(Mode.SUBMIT, r(13)))
        assertEquals(r(13), b.submit)
        assertTrue(b.undo())
        assertEquals(r(12), b.submit)
        assertTrue(b.undo())
        assertNull(b.submit)
        assertTrue(b.undo())
        assertEquals('9', b.nextKey)
        b.clear()
        assertTrue(b.isEmpty)
        assertTrue(b.keys.isEmpty())
    }

    @Test fun aNewQuestionOrFieldReplacesTheOldOne() {
        val b = CalibrationBoxes()
        assertTrue(b.add(Mode.QUESTION, r(1)))
        assertTrue(b.add(Mode.QUESTION, r(2)))
        assertEquals(r(2), b.question)
        assertTrue(b.add(Mode.FIELD, r(3)))
        assertTrue(b.add(Mode.FIELD, r(4)))
        assertEquals(r(4), b.numericField)
        assertTrue(b.options.isEmpty())
    }

    @Test fun optionsAddUpToTheLimit() {
        val b = CalibrationBoxes()
        repeat(8) { assertTrue(b.add(Mode.OPTION, r(it))) }
        assertFalse(b.add(Mode.OPTION, r(9)))
        assertEquals(8, b.options.size)
        assertEquals(r(7), b.options.last())
    }

    @Test fun undoRemovesTheLastBoxDrawn_whateverItIs() {
        val b = CalibrationBoxes()
        b.add(Mode.QUESTION, r(1))
        b.add(Mode.OPTION, r(2))
        b.add(Mode.OPTION, r(3))
        b.add(Mode.FIELD, r(4))
        assertTrue(b.undo())
        assertNull(b.numericField)
        assertTrue(b.undo())
        assertEquals(listOf(r(2)), b.options)
        assertTrue(b.undo())
        assertTrue(b.options.isEmpty())
        assertEquals(r(1), b.question)
        assertTrue(b.undo())
        assertNull(b.question)
        assertFalse(b.undo())
    }

    @Test fun undoingAReplacementBringsTheOldBoxBack() {
        val b = CalibrationBoxes()
        b.add(Mode.QUESTION, r(1))
        b.add(Mode.QUESTION, r(2))
        assertTrue(b.undo())
        assertEquals(r(1), b.question)
        assertTrue(b.undo())
        assertNull(b.question)
    }

    @Test fun clearForgetsEverythingIncludingTheUndoHistory() {
        val b = CalibrationBoxes()
        b.add(Mode.QUESTION, r(1))
        b.add(Mode.OPTION, r(2))
        b.clear()
        assertTrue(b.isEmpty)
        assertNull(b.question)
        assertFalse(b.undo())
        // Drawing again after a clear works as on a fresh screen.
        assertTrue(b.add(Mode.OPTION, r(3)))
        assertEquals(listOf(r(3)), b.options)
    }

    @Test fun readingOrderIsTheOrderTheTemplateWillUse() {
        val b = CalibrationBoxes()
        val bl = PxRect(0, 200, 90, 280)
        val tr = PxRect(110, 100, 200, 180)
        val br = PxRect(110, 200, 200, 280)
        val tl = PxRect(0, 100, 90, 180)
        for (o in listOf(bl, tr, br, tl)) b.add(Mode.OPTION, o)
        assertEquals(listOf(bl, tr, br, tl), b.options) // drawn order is kept
        assertEquals(listOf(tr, tl, br, bl), b.readingOrder()) // rows top to bottom, right to left
    }

    // ---------- the picture inside the view ----------

    @Test fun aWideViewLeavesBarsAtTheSides() {
        val fit = ImageFit(viewW = 1000f, viewH = 500f, imageW = 100, imageH = 100)
        assertEquals(5f, fit.scale, 1e-6f)
        assertEquals(250f, fit.offsetX, 1e-4f)
        assertEquals(0f, fit.offsetY, 1e-4f)
        assertEquals(250f, fit.viewX(0), 1e-4f)
        assertEquals(750f, fit.viewX(100), 1e-4f)
        assertEquals(500f, fit.viewY(100), 1e-4f)
    }

    @Test fun aTallViewLeavesBarsAboveAndBelow() {
        val fit = ImageFit(viewW = 400f, viewH = 1000f, imageW = 200, imageH = 100)
        assertEquals(2f, fit.scale, 1e-6f)
        assertEquals(0f, fit.offsetX, 1e-4f)
        assertEquals(400f, fit.offsetY, 1e-4f)
    }

    @Test fun viewPointsMapBackToImagePixels_clampedToThePicture() {
        val fit = ImageFit(viewW = 1000f, viewH = 500f, imageW = 100, imageH = 100)
        assertEquals(50, fit.imageX(500f))
        assertEquals(50, fit.imageY(250f))
        assertEquals(0, fit.imageX(10f)) // in the left bar
        assertEquals(100, fit.imageX(990f)) // in the right bar
        assertEquals(0, fit.imageY(-30f))
        assertEquals(100, fit.imageY(900f))
        for (x in listOf(0, 1, 37, 99, 100)) assertEquals(x, fit.imageX(fit.viewX(x)))
    }

    @Test fun aDragBecomesARectWhateverTheDirection() {
        val fit = ImageFit(viewW = 1000f, viewH = 500f, imageW = 100, imageH = 100)
        val a = fit.rectBetween(650f, 450f, 350f, 50f) // bottom right to top left
        assertEquals(PxRect(20, 10, 80, 90), a)
        val b = fit.rectBetween(350f, 50f, 650f, 450f)
        assertEquals(a, b)
        // Dragged past the picture: stops at its edge.
        assertEquals(PxRect(0, 0, 100, 100), fit.rectBetween(-100f, -100f, 2000f, 2000f))
    }
}
