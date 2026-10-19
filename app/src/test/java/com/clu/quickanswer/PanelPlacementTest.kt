package com.clu.quickanswer

import com.clu.quickanswer.ocr.PxRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the answer panel goes relative to the bubble. Pure JVM, no Android classes. */
class PanelPlacementTest {

    private val bubble = 150
    private val gap = 24

    // Landscape phone (the case from the bug report) and the same phone in portrait.
    private val landW = 2712
    private val landH = 1220
    private val portW = 1220
    private val portH = 2712

    private fun landscape(bx: Int, by: Int, pw: Int = 600, ph: Int = 200) =
        PanelPlacement.place(bx, by, bubble, pw, ph, landW, landH, gap)

    private fun portrait(bx: Int, by: Int, pw: Int = 600, ph: Int = 200) =
        PanelPlacement.place(bx, by, bubble, pw, ph, portW, portH, gap)

    // ------------------------------------------------------------ left / right

    @Test
    fun bubbleInLeftHalf_panelGoesToItsRight_verticallyCentred() {
        val p = landscape(bx = 20, by = 400)
        assertEquals(20 + bubble + gap, p.x)
        assertEquals(400 + bubble / 2 - 200 / 2, p.y)
    }

    @Test
    fun bubbleInRightHalf_panelGoesToItsLeft_verticallyCentred() {
        val p = landscape(bx = 2500, by = 400)
        assertEquals(2500 - gap - 600, p.x)
        assertEquals(400 + bubble / 2 - 200 / 2, p.y)
    }

    @Test
    fun bubbleCentreExactlyAtMiddle_countsAsRightHalf_panelGoesLeft() {
        val bx = (landW - bubble) / 2 // centre == landW / 2
        val p = landscape(bx = bx, by = 400)
        assertEquals(bx - gap - 600, p.x)
    }

    @Test
    fun bubbleJustLeftOfMiddle_panelGoesRight() {
        val bx = (landW - bubble) / 2 - 2
        val p = landscape(bx = bx, by = 400)
        assertEquals(bx + bubble + gap, p.x)
    }

    @Test
    fun portrait_leftAndRightPlacement() {
        val left = portrait(bx = 20, by = 1000, pw = 500)
        assertEquals(20 + bubble + gap, left.x)
        assertEquals(1000 + bubble / 2 - 200 / 2, left.y)

        val right = portrait(bx = 1000, by = 1000, pw = 500)
        assertEquals(1000 - gap - 500, right.x)
        assertEquals(1000 + bubble / 2 - 200 / 2, right.y)
    }

    @Test
    fun maxWidth_isAQuarterInLandscapeAndSixtyFivePercentInPortrait() {
        assertEquals(678, PanelPlacement.maxWidth(landW, landH))
        assertEquals(793, PanelPlacement.maxWidth(portW, portH))
    }

    @Test
    fun landscapeDefaultBubbleSpot_widestPanelStaysLeftOfTheOptionColumn() {
        // Default bubble: 8dp from the left (density 2.75), 56dp wide. The game's option column starts
        // about a third of the way across, so even the widest panel must end before that.
        val bx = 22
        val size = 154
        val pw = PanelPlacement.maxWidth(landW, landH)
        val p = PanelPlacement.place(bx, landH / 3, size, pw, 300, landW, landH, 22)
        assertTrue(p.x >= bx + size)
        assertTrue("panel ends at ${p.x + pw}", p.x + pw <= landW / 3)
    }

    @Test
    fun landscapeDefaultBubbleSpot_panelDoesNotCoverTheBubble() {
        // Default bubble spot: 8dp from the left, a third of the way down.
        val bx = 22
        val by = landH / 3
        val p = landscape(bx = bx, by = by, pw = 800, ph = 120)
        assertTrue(p.x >= bx + bubble) // fully to the right of the bubble
        assertTrue(p.x + 800 <= landW)
        assertTrue(p.y >= 0 && p.y + 120 <= landH)
    }

    // ------------------------------------------------------------ clamping beside the bubble

    @Test
    fun besideBubble_clampedAtTopEdge() {
        // Bubble at the very top: a centred panel would start above the screen.
        val p = landscape(bx = 20, by = 0)
        assertEquals(20 + bubble + gap, p.x)
        assertEquals(0, p.y)
    }

    @Test
    fun besideBubble_clampedAtBottomEdge() {
        val p = landscape(bx = 20, by = landH - bubble)
        assertEquals(20 + bubble + gap, p.x)
        assertEquals(landH - 200, p.y)
    }

    @Test
    fun besideBubble_bubbleDraggedAboveScreen_panelClampedToTop() {
        val p = landscape(bx = 20, by = -100)
        assertEquals(0, p.y)
    }

    @Test
    fun besideBubble_bubbleDraggedBelowScreen_panelClampedToBottom() {
        val p = landscape(bx = 2500, by = landH + 100)
        assertEquals(landH - 200, p.y)
        assertEquals(2500 - gap - 600, p.x)
    }

    @Test
    fun besideBubble_rightAndLeftSidesFitExactlyAtTheEdge() {
        // Right side: panel ends exactly at the right edge of the screen.
        val pwRight = landW - (20 + bubble + gap)
        val right = landscape(bx = 20, by = 400, pw = pwRight)
        assertEquals(20 + bubble + gap, right.x)

        // Left side: panel starts exactly at x = 0.
        val bxLeft = 700
        val pwLeft = bxLeft - gap
        val left = PanelPlacement.place(bxLeft, 400, bubble, pwLeft, 200, 1400, landH, gap)
        assertEquals(0, left.x)
    }

    // ------------------------------------------------------------ no room beside: below / above

    @Test
    fun noRoomBeside_placedBelowBubble_horizontallyCentred() {
        // Bubble centre 575 is in the left half, but 500 + 150 + 24 + 900 > 1220: no room to the right.
        val p = portrait(bx = 500, by = 1000, pw = 900)
        assertEquals(575 - 450, p.x)
        assertEquals(1000 + bubble + gap, p.y)
    }

    @Test
    fun noRoomBeside_nearLeftEdge_clampedToLeft() {
        val p = portrait(bx = 20, by = 1000, pw = 1100)
        assertEquals(0, p.x)
        assertEquals(1000 + bubble + gap, p.y)
    }

    @Test
    fun noRoomBeside_nearRightEdge_clampedToRight() {
        val p = portrait(bx = 1050, by = 1000, pw = 1100)
        assertEquals(portW - 1100, p.x)
        assertEquals(1000 + bubble + gap, p.y)
    }

    @Test
    fun noRoomBeside_noRoomBelow_placedAboveBubble() {
        val p = portrait(bx = 500, by = 2500, pw = 900)
        assertEquals(2500 - gap - 200, p.y)
        assertEquals(575 - 450, p.x)
    }

    @Test
    fun noRoomBeside_belowFitsExactlyAtBottomEdge() {
        val by = portH - 200 - gap - bubble
        val p = portrait(bx = 500, by = by, pw = 900)
        assertEquals(by + bubble + gap, p.y)
        assertEquals(portH - 200, p.y)
    }

    @Test
    fun noRoomBeside_noRoomAboveOrBelow_clampedOnScreen() {
        // Short, wide screen: neither above nor below the bubble can hold the panel.
        val p = PanelPlacement.place(100, 100, bubble, 1100, 300, 1220, 500, gap)
        assertTrue(p.x >= 0 && p.x + 1100 <= 1220)
        assertTrue(p.y >= 0 && p.y + 300 <= 500)
    }

    @Test
    fun panelLargerThanScreen_isPinnedToTopLeft() {
        val p = PanelPlacement.place(100, 100, bubble, 3000, 3000, landW, landH, gap)
        assertEquals(0, p.x)
        assertEquals(0, p.y)
    }

    // ------------------------------------------------------------ invariants

    @Test
    fun panelAlwaysStaysFullyOnScreen_whenItFits() {
        val screens = listOf(landW to landH, portW to portH)
        val panels = listOf(300 to 100, 800 to 200, 1100 to 400)
        for ((sw, sh) in screens) {
            for ((pw, ph) in panels) {
                var bx = -200
                while (bx <= sw + 200) {
                    var by = -200
                    while (by <= sh + 200) {
                        val p = PanelPlacement.place(bx, by, bubble, pw, ph, sw, sh, gap)
                        val msg = "screen ${sw}x$sh panel ${pw}x$ph bubble $bx,$by -> ${p.x},${p.y}"
                        assertTrue(msg, p.x >= 0 && p.x + pw <= sw)
                        assertTrue(msg, p.y >= 0 && p.y + ph <= sh)
                        by += 137
                    }
                    bx += 151
                }
            }
        }
    }

    @Test
    fun panelBesideBubble_neverOverlapsTheBubbleHorizontally() {
        var bx = 0
        while (bx <= landW - bubble) {
            val p = landscape(bx = bx, by = 500)
            val besideRight = p.x >= bx + bubble
            val besideLeft = p.x + 600 <= bx
            val below = p.y >= 500 + bubble
            val above = p.y + 200 <= 500
            assertTrue("bubble x=$bx panel=${p.x},${p.y}", besideRight || besideLeft || below || above)
            bx += 97
        }
    }

    // ------------------------------------------------------------ top of the screen

    private val margin = 17 // about 6dp

    @Test
    fun top_isCentredHorizontally_atTheTopMargin() {
        val p = PanelPlacement.top(800, 150, landW, landH, margin)
        assertEquals((landW - 800) / 2, p.x)
        assertEquals(margin, p.y)
        val q = PanelPlacement.top(900, 150, portW, portH, margin)
        assertEquals((portW - 900) / 2, q.x)
        assertEquals(margin, q.y)
    }

    @Test
    fun top_doesNotDependOnAnythingButSizes_centredPanelIsSymmetric() {
        val p = PanelPlacement.top(700, 120, 1400, 800, margin)
        assertEquals(350, p.x) // equal gap left and right
        assertEquals(1400 - 700 - p.x, p.x)
    }

    @Test
    fun top_panelAsWideAsTheScreen_startsAtZero() {
        assertEquals(0, PanelPlacement.top(landW, 100, landW, landH, margin).x)
    }

    @Test
    fun top_panelWiderOrTallerThanScreen_isPinnedToTopLeft() {
        val p = PanelPlacement.top(3000, 3000, landW, landH, margin)
        assertEquals(0, p.x)
        assertEquals(0, p.y)
    }

    @Test
    fun top_panelAlmostAsTallAsTheScreen_marginIsClampedOnScreen() {
        val p = PanelPlacement.top(500, landH - 5, landW, landH, margin)
        assertEquals(5, p.y)
    }

    @Test
    fun top_panelStaysOnScreenForAnySizeThatFits() {
        for ((sw, sh) in listOf(landW to landH, portW to portH)) {
            val maxW = PanelPlacement.topMaxWidth(sw, sh)
            for (pw in listOf(1, 300, maxW / 2, maxW - 1, maxW)) {
                for (ph in listOf(1, 120, 300, sh - margin, sh)) {
                    val p = PanelPlacement.top(pw, ph, sw, sh, margin)
                    val msg = "screen ${sw}x$sh panel ${pw}x$ph -> ${p.x},${p.y}"
                    assertTrue(msg, p.x >= 0 && p.x + pw <= sw)
                    assertTrue(msg, p.y >= 0 && p.y + ph <= sh)
                }
            }
        }
    }

    @Test
    fun topMaxWidth_isSixtyPercentInLandscapeAndNinetyInPortrait() {
        assertEquals(1627, PanelPlacement.topMaxWidth(landW, landH))
        assertEquals(1098, PanelPlacement.topMaxWidth(portW, portH))
    }

    @Test
    fun maxWidth_byMode() {
        assertEquals(
            PanelPlacement.topMaxWidth(landW, landH),
            PanelPlacement.maxWidth(PanelPlacement.Mode.TOP, landW, landH)
        )
        assertEquals(
            PanelPlacement.maxWidth(portW, portH),
            PanelPlacement.maxWidth(PanelPlacement.Mode.NEXT_TO_BUBBLE, portW, portH)
        )
    }

    // ------------------------------------------------------------ mode as saved in Prefs

    @Test
    fun mode_keysRoundTrip() {
        for (m in PanelPlacement.Mode.values()) assertEquals(m, PanelPlacement.Mode.fromKey(m.key))
        assertEquals("top", PanelPlacement.Mode.TOP.key)
        assertEquals("bubble", PanelPlacement.Mode.NEXT_TO_BUBBLE.key)
    }

    @Test
    fun mode_missingOrUnknownKeyMeansTop() {
        assertEquals(PanelPlacement.Mode.TOP, PanelPlacement.Mode.fromKey(null))
        assertEquals(PanelPlacement.Mode.TOP, PanelPlacement.Mode.fromKey(""))
        assertEquals(PanelPlacement.Mode.TOP, PanelPlacement.Mode.fromKey("TOP"))
        assertEquals(PanelPlacement.Mode.TOP, PanelPlacement.Mode.fromKey("left"))
    }

    // ------------------------------------------------------------ bubble after a rotation

    @Test
    fun clampInto_landscapeToPortrait_pullsTheBubbleBackOnScreen() {
        // Bubble parked near the right edge in landscape: x is far outside the narrower portrait screen.
        val p = PanelPlacement.clampInto(2500, 900, bubble, bubble, portW, portH)
        assertEquals(portW - bubble, p.x)
        assertEquals(900, p.y)
    }

    @Test
    fun clampInto_portraitToLandscape_pullsTheBubbleBackOnScreen() {
        val p = PanelPlacement.clampInto(300, 2000, bubble, bubble, landW, landH)
        assertEquals(300, p.x)
        assertEquals(landH - bubble, p.y)
    }

    @Test
    fun clampInto_bubbleAlreadyInside_isUnchanged() {
        val p = PanelPlacement.clampInto(300, 400, bubble, bubble, portW, portH)
        assertEquals(300, p.x)
        assertEquals(400, p.y)
    }

    @Test
    fun clampInto_negativeCoordinatesGoToZero_andTinyScreenPinsToTopLeft() {
        val p = PanelPlacement.clampInto(-60, -10, bubble, bubble, portW, portH)
        assertEquals(0, p.x)
        assertEquals(0, p.y)
        val q = PanelPlacement.clampInto(80, 80, bubble, bubble, 100, 100)
        assertEquals(0, q.x)
        assertEquals(0, q.y)
    }

    // ------------------------------------------------------------ keeping clear of the green box

    @Test
    fun overlaps_countsSharedAreaOnly() {
        val a = PxRect(0, 0, 100, 100)
        assertTrue(PanelPlacement.overlaps(a, PxRect(99, 99, 200, 200)))
        assertFalse(PanelPlacement.overlaps(a, PxRect(100, 0, 200, 100))) // touching edges
        assertFalse(PanelPlacement.overlaps(a, PxRect(0, 100, 100, 200)))
        assertFalse(PanelPlacement.overlaps(a, PxRect(300, 300, 400, 400)))
    }

    @Test
    fun moveOffBox_picksTheNearestFreePlace_andNeverOverlapsTheBox() {
        // Portrait: a wide panel next to the bubble covers the top part of the box (an option button).
        val box = PxRect(300, 1200, 900, 1400)
        val panel = PxRect(200, 1100, 800, 1300)
        val to = PanelPlacement.moveOffBox(panel, box, portW, portH, gap)!!
        val moved = PxRect(to.x, to.y, to.x + panel.width, to.y + panel.height)
        assertFalse(PanelPlacement.overlaps(moved, box))
        assertTrue(moved.left >= 0 && moved.top >= 0 && moved.right <= portW && moved.bottom <= portH)
        // Above the box is the shortest way: 1200 - gap - 200 = 976.
        assertEquals(PanelPlacement.Pos(200, 976), to)
    }

    @Test
    fun moveOffBox_goesBelowOrBesideWhenThereIsNoRoomAbove() {
        val box = PxRect(300, 100, 900, 300)
        val panel = PxRect(200, 150, 800, 350)
        assertEquals(PanelPlacement.Pos(200, 300 + gap), PanelPlacement.moveOffBox(panel, box, portW, portH, gap))
        // A box as wide as the screen with a panel as tall as the rest: only a side could do, none fits.
        assertNull(PanelPlacement.moveOffBox(PxRect(0, 0, 1000, 2600), PxRect(0, 600, 1220, 800), portW, portH, gap))
    }
}
