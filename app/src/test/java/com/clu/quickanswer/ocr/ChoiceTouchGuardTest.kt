package com.clu.quickanswer.ocr

import org.junit.Assert.*
import org.junit.Test

class ChoiceTouchGuardTest {
    private val width = 1280
    private val height = 576
    private val parchment = 0xffead6b5.toInt()
    private val dark = 0xff606060.toInt()
    private val white = 0xfff7f7f7.toInt()

    @Test fun guardedGridContentContainsTheEntirePinnedPhotoAndEveryOption() {
        val scene = grid(dx = 0, dy = -50)
        val area = ChoiceTouchGuard.contentArea(width, height, null, scene.buttons)
        val photo = PxRect(100, 190, 425, 452)
        for (region in scene.buttons + photo) {
            assertTrue(area.left <= region.left && area.top <= region.top)
            assertTrue(area.right >= region.right && area.bottom >= region.bottom)
        }
        assertTrue("leave the animated timer below the content guard", area.bottom < height * 0.82)
    }

    @Test fun verticalQuestionGuardDoesNotIncludeTheAnimatedPlayersBesideItsButtons() {
        val scene = vertical()
        val banner = PxRect(100, 20, 1180, 220)
        assertEquals(banner, ChoiceTouchGuard.contentArea(width, height, banner, scene.buttons))
    }

    private class Scene(
        val pixels: IntArray, val buttons: List<PxRect>, val words: List<PxRect>,
    )

    private fun fill(pixels: IntArray, rect: PxRect, color: Int, imageWidth: Int = width) {
        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
            pixels[y * imageWidth + x] = color
        }
    }

    private fun print(pixels: IntArray, rect: PxRect, color: Int, imageWidth: Int = width) {
        // Separate stems with a joining baseline resemble Arabic text; no real screenshots.
        for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
            val dx = x - rect.left
            val dy = y - rect.top
            if (dx % 12 in 0..3 || dy in rect.height - 6 until rect.height - 3) {
                pixels[y * imageWidth + x] = color
            }
        }
    }

    private fun grid(dx: Int = 0, dy: Int = 0, background: Int = dark, ink: Int = white): Scene {
        val pixels = IntArray(width * height) { parchment }
        val buttons = listOf(
            PxRect(720 + dx, 315 + dy, 1040 + dx, 385 + dy),
            PxRect(360 + dx, 315 + dy, 680 + dx, 385 + dy),
            PxRect(720 + dx, 420 + dy, 1040 + dx, 490 + dy),
            PxRect(360 + dx, 420 + dy, 680 + dx, 490 + dy),
        )
        val words = buttons.mapIndexed { index, b ->
            val wordWidth = 90 + index * 12
            val left = b.left + (b.width - wordWidth) / 2
            PxRect(left, b.top + 23, left + wordWidth, b.top + 48)
        }
        for (i in buttons.indices) {
            fill(pixels, buttons[i], background)
            print(pixels, words[i], ink)
        }
        return Scene(pixels, buttons, words)
    }

    private fun vertical(): Scene {
        val pixels = IntArray(width * height) { parchment }
        val buttons = (0..3).map { i -> PxRect(320, 255 + i * 75, 960, 313 + i * 75) }
        val words = buttons.map { b -> PxRect(570, b.top + 16, 710, b.top + 41) }
        for (i in buttons.indices) {
            fill(pixels, buttons[i], white)
            print(pixels, words[i], 0xff30384c.toInt())
        }
        return Scene(pixels, buttons, words)
    }

    private fun ready(scene: Scene, buttons: List<PxRect> = scene.buttons, words: List<PxRect> = scene.words) =
        ChoiceTouchGuard.isReady(scene.pixels, width, height, buttons, words)

    @Test fun activeDarkChoicesWithOrWithoutAQuestionPictureAreReady() {
        val plain = grid()
        assertTrue(ready(plain))
        val pictured = grid()
        fill(pictured.pixels, PxRect(520, 100, 760, 280), 0xff265e92.toInt())
        assertTrue(ready(pictured))
    }

    @Test fun shiftedHudlessButtonsAreIndependentOfTheUsualGameCoordinates() {
        assertTrue(ready(grid(dx = -110, dy = -95)))
        assertTrue(ready(grid(dx = 75, dy = 45)))
    }

    @Test fun approximateEdgesCanBeOffWithoutLosingTheVisibleSurfaces() {
        val scene = grid()
        val approximate = scene.buttons.map { b ->
            PxRect(b.left + 8, b.top + 12, b.right - 8, b.bottom + 12)
        }
        assertTrue(ready(scene, buttons = approximate))
    }

    @Test fun fourWhiteVerticalButtonsSupportDarkArabicInk() {
        assertTrue(ready(vertical()))
    }

    @Test fun sparseInkIsStillFoundWhenOcrReturnsTheWholeButtonBox() {
        val scene = vertical()
        assertTrue(ready(scene, words = scene.buttons))
    }

    @Test fun denseLongArabicNamesDoNotLookLikeUnevenButtonBackgrounds() {
        val scene = grid()
        val denseWords = scene.buttons.map { b ->
            PxRect(b.left + 25, b.top + 20, b.right - 25, b.top + 49)
        }
        for (i in scene.buttons.indices) {
            fill(scene.pixels, scene.buttons[i], dark)
            print(scene.pixels, denseWords[i], white)
        }
        // The long synthetic joining stems fill more than the old 80th percentile, but leave
        // a clear majority of intact button surface, as a long Arabic answer does.
        val button = scene.buttons.first()
        val interior = PxRect(button.left + 16, button.top + 16, button.right - 16, button.bottom - 16)
        var ink = 0
        for (y in interior.top until interior.bottom) for (x in interior.left until interior.right) {
            if (scene.pixels[y * width + x] == white) ink++
        }
        assertTrue(ink.toDouble() / (interior.width * interior.height) in 0.20..0.30)
        assertTrue(ready(scene, words = denseWords))
    }

    @Test fun denseDarkArabicNamesOnWhiteButtonsUseTheLightBackgroundQuantiles() {
        val scene = vertical()
        val inkColor = 0xff30384c.toInt()
        val denseWords = scene.buttons.map { b ->
            PxRect(b.left + 70, b.top + 15, b.right - 70, b.top + 43)
        }
        for (i in scene.buttons.indices) {
            fill(scene.pixels, scene.buttons[i], white)
            print(scene.pixels, denseWords[i], inkColor)
        }
        val button = scene.buttons.first()
        val interior = PxRect(button.left + 13, button.top + 13, button.right - 13, button.bottom - 13)
        var ink = 0
        for (y in interior.top until interior.bottom) for (x in interior.left until interior.right) {
            if (scene.pixels[y * width + x] == inkColor) ink++
        }
        assertTrue(ink.toDouble() / (interior.width * interior.height) in 0.20..0.30)
        assertTrue(ready(scene, words = denseWords))
    }

    @Test fun disabledTranslucentChoicesCannotAuthorizeATap() {
        val waiting = grid(background = 0xffb0a898.toInt(), ink = 0xffd0cabc.toInt())
        assertFalse(ready(waiting))
        assertFalse(ready(grid(background = 0xff8e8e8e.toInt(), ink = 0xffbbbbbb.toInt())))
    }

    @Test fun oneDisabledOrCoveredChoiceRejectsTheEntireSet() {
        val disabled = grid()
        fill(disabled.pixels, disabled.buttons[2], 0xff9b9b9b.toInt())
        print(disabled.pixels, disabled.words[2], 0xffc4c4c4.toInt())
        assertFalse(ready(disabled))
        val covered = grid()
        fill(covered.pixels, covered.words[1], dark)
        assertFalse(ready(covered))
    }

    @Test fun aMissingTextLineOrAWhiteBlobIsNotReadableInk() {
        val missing = grid()
        fill(missing.pixels, missing.buttons[3], dark)
        assertFalse(ready(missing))
        val blob = grid()
        fill(blob.pixels, blob.words[0], white)
        assertFalse(ready(blob))
    }

    @Test fun brightMarksOnParchmentAreNotDarkAnswerButtons() {
        assertFalse(ready(grid(background = parchment, ink = white)))
        assertFalse(ready(grid(background = parchment, ink = 0xff282828.toInt())))
    }

    @Test fun buttonBordersAloneDoNotCountAsDarkText() {
        val scene = vertical()
        for (button in scene.buttons) {
            fill(scene.pixels, button, 0xff303030.toInt())
            fill(scene.pixels, PxRect(button.left + 3, button.top + 3,
                button.right - 3, button.bottom - 3), white)
        }
        assertFalse(ready(scene, words = scene.buttons))
    }

    @Test fun fourWordRegionsOnAFlatPanelDoNotCreateFourButtons() {
        val scene = grid()
        val pixels = IntArray(width * height) { dark }
        scene.words.forEach { print(pixels, it, white) }
        assertFalse(ChoiceTouchGuard.isReady(pixels, width, height, scene.buttons, scene.words))
    }

    @Test fun wrongContainmentMissingChoicesAndOverlappingChoicesAreRejected() {
        val scene = grid()
        assertFalse(ready(scene, words = scene.words.reversed()))
        assertFalse(ready(scene, words = scene.words.dropLast(1)))
        assertFalse(ready(scene, buttons = scene.buttons.dropLast(1)))
        assertFalse(ready(scene, buttons = listOf(scene.buttons[0]) + scene.buttons.drop(1).map { scene.buttons[0] }))
        assertFalse(ready(scene, words = scene.words + scene.words[0]))
    }

    @Test fun portraitGalleryAndMalformedFramesAreRejectedEvenWhenTheImageLooksReadable() {
        val scene = grid()
        assertFalse(ChoiceTouchGuard.isReady(scene.pixels, height, width, scene.buttons, scene.words))
        assertFalse(ChoiceTouchGuard.isReady(IntArray(5), width, height, scene.buttons, scene.words))
        assertFalse(ChoiceTouchGuard.isReady(scene.pixels, 0, height, scene.buttons, scene.words))
        assertFalse(ChoiceTouchGuard.isReady(scene.pixels, Int.MAX_VALUE, 576, scene.buttons, scene.words))
        assertFalse(ready(scene, buttons = listOf(PxRect(-1, 315, 1040, 385)) + scene.buttons.drop(1)))
    }

    @Test fun resizedCapturesUseRelativeSurfacesAndInkCounts() {
        val scene = grid()
        val smallWidth = width / 2
        val smallHeight = height / 2
        val small = IntArray(smallWidth * smallHeight) { index ->
            scene.pixels[(index / smallWidth * 2) * width + index % smallWidth * 2]
        }
        fun shrink(r: PxRect) = PxRect(r.left / 2, r.top / 2, r.right / 2, r.bottom / 2)
        assertTrue(ChoiceTouchGuard.isReady(small, smallWidth, smallHeight,
            scene.buttons.map(::shrink), scene.words.map(::shrink)))
    }
}
