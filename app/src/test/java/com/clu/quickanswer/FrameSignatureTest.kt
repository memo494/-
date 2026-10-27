package com.clu.quickanswer

import com.clu.quickanswer.FrameSignature.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSignatureTest {

    private val size = 32

    private fun grid(v: Int) = IntArray(size * size) { v }

    @Test
    fun diff_isTheMeanAbsoluteDifference() {
        assertEquals(0.0, FrameSignature.diff(grid(10), grid(10)), 0.0)
        assertEquals(30.0, FrameSignature.diff(grid(10), grid(40)), 0.0)
    }

    @Test
    fun diff_skipsCellsEitherSideIgnores() {
        val a = grid(100)
        val b = grid(100)
        // b differs a lot in one corner, but a overlay covered that corner in a.
        for (y in 0 until 8) for (x in 0 until 8) {
            a[y * size + x] = FrameSignature.IGNORED
            b[y * size + x] = 0
        }
        assertEquals(0.0, FrameSignature.diff(a, b), 0.0)
        assertEquals(0.0, FrameSignature.diff(b, a), 0.0)
    }

    @Test
    fun diff_allIgnored_isZero() {
        assertEquals(0.0, FrameSignature.diff(grid(FrameSignature.IGNORED), grid(50)), 0.0)
    }

    @Test
    fun ignoreRect_marksOnlyTouchedCells() {
        val sig = grid(7)
        // 3200x3200 image: each cell is 100px. Rect 150..350 touches cells 1..3 on both axes.
        FrameSignature.ignoreRect(sig, size, 3200, 3200, 150, 150, 350, 350)
        for (y in 0 until size) for (x in 0 until size) {
            val inside = x in 1..3 && y in 1..3
            assertEquals("cell $x,$y", if (inside) FrameSignature.IGNORED else 7, sig[y * size + x])
        }
    }

    @Test
    fun ignoreRect_rectEndingExactlyOnACellBorder_doesNotSpillIntoTheNextCell() {
        val sig = grid(7)
        FrameSignature.ignoreRect(sig, size, 3200, 3200, 0, 0, 200, 100)
        assertEquals(FrameSignature.IGNORED, sig[0])
        assertEquals(FrameSignature.IGNORED, sig[1])
        assertEquals(7, sig[2])
        assertEquals(7, sig[size])
    }

    @Test
    fun ignoreRect_offScreenRect_changesNothing() {
        val sig = grid(7)
        FrameSignature.ignoreRect(sig, size, 1000, 1000, -500, -500, -10, -10)
        FrameSignature.ignoreRect(sig, size, 1000, 1000, 1200, 200, 1500, 300)
        assertEquals(grid(7).toList(), sig.toList())
    }

    @Test
    fun ignoreRect_partlyOffScreen_isClippedToTheImage() {
        val sig = grid(7)
        FrameSignature.ignoreRect(sig, size, 3200, 3200, -500, 3100, 150, 4000)
        // Bottom-left corner: cells x 0..1, y 31.
        assertEquals(FrameSignature.IGNORED, sig[31 * size])
        assertEquals(FrameSignature.IGNORED, sig[31 * size + 1])
        assertEquals(7, sig[31 * size + 2])
        assertEquals(7, sig[30 * size])
    }

    @Test
    fun overlayAppearingOrDisappearing_isNotAChange() {
        // Same game frame. With the panel up, the cells under it hold dark panel pixels; without, game pixels.
        val without = grid(120)
        val marked = grid(120)
        FrameSignature.ignoreRect(marked, size, 2712, 1220, 200, 400, 1000, 600)
        val withPanel = IntArray(size * size) { if (marked[it] == FrameSignature.IGNORED) 0 else 120 }
        assertTrue(FrameSignature.diff(without, withPanel) > 6.0) // this is what used to look like a change
        FrameSignature.ignoreRect(withPanel, size, 2712, 1220, 200, 400, 1000, 600)
        assertEquals(0.0, FrameSignature.diff(without, withPanel), 0.0)
    }

    // ------------------------------------------------------------ sameMask

    @Test
    fun sameMask_comparesOnlyWhichCellsAreIgnored() {
        val a = grid(10)
        val b = grid(200)
        assertTrue(FrameSignature.sameMask(a, b))
        a[5] = FrameSignature.IGNORED
        assertFalse(FrameSignature.sameMask(a, b))
        b[5] = FrameSignature.IGNORED
        assertTrue(FrameSignature.sameMask(a, b))
        assertFalse(FrameSignature.sameMask(IntArray(4), IntArray(5)))
    }

    // ------------------------------------------------------------ judge

    private val change = 6.0
    private val stableLimit = 2.0

    private fun judge(sig: IntArray, prev: IntArray?, last: IntArray?, panel: Boolean = false) =
        FrameSignature.judge(sig, prev, last, panel, change, stableLimit)

    @Test
    fun judge_sameAsLastSent_isIdle_evenWithoutPrevious() {
        assertEquals(Verdict.IDLE, judge(grid(100), null, grid(100)))
        assertEquals(Verdict.IDLE, judge(grid(100), grid(100), grid(103), panel = true))
    }

    @Test
    fun judge_changeFirstSeen_waitsForAStablePicture() {
        assertEquals(Verdict.WAIT, judge(grid(100), null, grid(10)))
        assertEquals(Verdict.WAIT, judge(grid(100), grid(60), grid(10))) // still moving
        assertEquals(Verdict.SEND, judge(grid(100), grid(101), grid(10)))
    }

    @Test
    fun judge_nothingSentYet_sends_afterTwoTicks() {
        assertEquals(Verdict.WAIT, judge(grid(100), null, null))
        assertEquals(Verdict.SEND, judge(grid(100), grid(100), null))
    }

    @Test
    fun judge_changeWithPanelInFrame_neverSends_evenIfStable() {
        assertEquals(Verdict.HIDE_PANEL, judge(grid(100), grid(100), grid(10), panel = true))
        assertEquals(Verdict.HIDE_PANEL, judge(grid(100), null, grid(10), panel = true))
        assertEquals(Verdict.HIDE_PANEL, judge(grid(100), grid(100), null, panel = true))
    }

    @Test
    fun judge_differentIgnoredCellsBetweenTicks_isNotStableYet() {
        val prev = grid(100)
        prev[40] = FrameSignature.IGNORED // e.g. the panel was still showing a tick ago
        assertEquals(Verdict.WAIT, judge(grid(100), prev, grid(10)))
        assertEquals(Verdict.SEND, judge(grid(100), grid(100), grid(10)))
    }

    // ------------------------------------------------------------ whole auto-mode runs

    private val imgSize = 3200 // 100 px per cell

    /** Game screen: question banner on the top 4 rows, answer options in the middle, plain elsewhere. */
    private fun game(banner: Int, options: Int) = IntArray(size * size) {
        val row = it / size
        when {
            row < 4 -> banner
            row in 10..20 -> options
            else -> 100
        }
    }

    /** What a tick computes: the frame's cells, minus those under our bubble and (if up) the top panel. */
    private fun frameSig(screen: IntArray, panelUp: Boolean): IntArray {
        val sig = screen.copyOf()
        if (panelUp) FrameSignature.ignoreRect(sig, size, imgSize, imgSize, 300, 60, 2900, 360) // cells x3..28, y0..3
        FrameSignature.ignoreRect(sig, size, imgSize, imgSize, 100, 1000, 250, 1150) // bubble
        return sig
    }

    private val tickMs = FrameSignature.TICK_MS
    private val panelMs = 15_000L // the answer panel hides itself after this long (PanelPlacement.AUTO_HIDE_BASE_MS)

    /**
     * The service's autoTick, one call per [FrameSignature.TICK_MS] of simulated time (everything
     * below is counted in ticks, so the wall-clock figures follow that constant), with the answer
     * arriving at once after a SEND and showing the panel for [panelMs], and an answer hidden by
     * HIDE_PANEL coming back on IDLE if it still has time left.
     */
    private class AutoRun(val frameSig: (IntArray, Boolean) -> IntArray) {
        var prev: IntArray? = null
        var lastSent: IntArray? = null
        var panelUp = false
        var sends = 0
        var hides = 0
        var restores = 0
        var now = 0L // simulated ms
        var lastSendAt = -1L
        var panelDeadline = 0L
        val hidden = HiddenAnswer()
        val verdicts = mutableListOf<Verdict>()

        fun tick(screen: IntArray): Verdict {
            now += FrameSignature.TICK_MS
            // The panel's own 15 s timer fires between ticks, before this one looks at the frame.
            if (panelUp && now >= panelDeadline) panelUp = false
            val panelInFrame = panelUp
            val sig = frameSig(screen, panelInFrame)
            val v = FrameSignature.judge(sig, prev, lastSent, panelInFrame, 6.0, 2.0)
            prev = sig
            when (v) {
                Verdict.HIDE_PANEL -> { hidden.remember("answer", panelDeadline); panelUp = false; hides++ }
                Verdict.SEND -> {
                    hidden.clear()
                    sends++
                    lastSendAt = now
                    lastSent = sig
                    panelUp = true
                    panelDeadline = now + 15_000
                }
                Verdict.IDLE -> if (!panelUp && hidden.take(now, 1000) != null) { panelUp = true; restores++ }
                Verdict.WAIT -> {}
            }
            verdicts.add(v)
            return v
        }

        fun ticks(screen: IntArray, n: Int) = List(n) { tick(screen) }
    }

    @Test
    fun tickInterval_reactsWithinAboutASecond() {
        // Auto mode used to tick every 1.5 s (a new question took 1.5-3 s); it has to stay well under that.
        assertTrue(FrameSignature.TICK_MS in 250L..1000L)
    }

    /** A question that holds still is sent on the second tick: within 0.8 s of appearing. */
    @Test
    fun auto_aSettledQuestionIsSentWithinEightTenthsOfASecond() {
        val run = AutoRun(::frameSig)
        val verdicts = run.ticks(game(banner = 30, options = 180), 2)
        assertEquals(listOf(Verdict.WAIT, Verdict.SEND), verdicts)
        assertTrue("sent after ${verdicts.size * tickMs} ms", verdicts.size * tickMs <= 800L)
    }

    @Test
    fun auto_newQuestionUnderTheVisiblePanel_hidesThenSendsTheCleanFrameOnce() {
        val a = game(banner = 30, options = 180)
        val b = game(banner = 220, options = 40)
        val run = AutoRun(::frameSig)

        assertEquals(listOf(Verdict.WAIT, Verdict.SEND), run.ticks(a, 2))
        assertTrue(run.panelUp)
        assertEquals(List(3) { Verdict.IDLE }, run.ticks(a, 3)) // answer on screen: nothing changes

        // The next question appears while the panel is still up.
        assertEquals(Verdict.HIDE_PANEL, run.tick(b))
        assertFalse(run.panelUp)
        assertEquals(Verdict.WAIT, run.tick(b)) // first frame without the panel: not comparable yet
        assertEquals(Verdict.SEND, run.tick(b)) // sent without the panel in it
        assertEquals(List(5) { Verdict.IDLE }, run.ticks(b, 5))

        assertEquals(2, run.sends)
        assertEquals(1, run.hides)
    }

    @Test
    fun auto_changeOnlyUnderThePanel_isSeenOnceThePanelAutoHides_notMissed() {
        val a = game(banner = 30, options = 180)
        val bannerOnly = game(banner = 220, options = 180)
        val run = AutoRun(::frameSig)
        run.ticks(a, 2)
        assertTrue(run.panelUp)
        val firstSendAt = run.lastSendAt

        // Hidden behind the panel and small elsewhere: not visible while the panel is up ...
        assertTrue(FrameSignature.diff(frameSig(a, true), frameSig(bannerOnly, true)) < 6.0)
        var idleTicks = 0
        while (run.now + tickMs < run.panelDeadline) {
            assertEquals(Verdict.IDLE, run.tick(bannerOnly))
            idleTicks++
        }
        // Ticks strictly before the deadline (15 s need not be a whole number of ticks).
        val ticksToDeadline = (panelMs + tickMs - 1) / tickMs
        assertEquals("every tick until the panel's 15 s are up", (ticksToDeadline - 1).toInt(), idleTicks)

        // ... but the 15 s auto-hide uncovers it (the tick at the deadline sees a frame without the
        // panel, not comparable to the one before), and it is sent one tick later.
        assertEquals(listOf(Verdict.WAIT, Verdict.SEND), run.ticks(bannerOnly, 2))
        assertEquals(firstSendAt + (ticksToDeadline + 1) * tickMs, run.lastSendAt)
        assertEquals(2, run.sends)
        assertEquals(0, run.hides)
    }

    @Test
    fun auto_enabledWhileAnAnswerPanelIsUp_hidesItThenSends() {
        // Auto mode on (nothing sent yet) with a manual answer still showing.
        val a = game(banner = 30, options = 180)
        val run = AutoRun(::frameSig)
        run.panelUp = true
        run.panelDeadline = run.now + panelMs
        assertEquals(Verdict.HIDE_PANEL, run.tick(a))
        assertEquals(Verdict.WAIT, run.tick(a))
        assertEquals(Verdict.SEND, run.tick(a))
        assertEquals(1, run.hides)
        assertEquals(1, run.sends)
    }

    @Test
    fun auto_staticScreen_afterSending_staysIdleForever() {
        val a = game(banner = 30, options = 180)
        val run = AutoRun(::frameSig)
        run.ticks(a, 2)
        // 100 s on the same screen: the answer panel's 15 s run out in the middle of it.
        assertTrue(run.ticks(a, 40).all { it == Verdict.IDLE })
        assertFalse("the panel hid itself", run.panelUp)
        assertTrue(run.ticks(a, 160).all { it == Verdict.IDLE })
        assertEquals(1, run.sends)
        assertEquals(0, run.hides)
        assertEquals(0, run.restores)
    }

    @Test
    fun auto_newQuestion_isSentWithinTwoTicks_orThreeWhenThePanelIsUp() {
        val a = game(banner = 30, options = 180)
        val b = game(banner = 220, options = 40)
        val c = game(banner = 90, options = 120)
        val run = AutoRun(::frameSig)
        run.ticks(a, 2)

        // Worst case for the wait: the question appears just after a tick. The panel is still up,
        // so: hide, one tick to compare against, send.
        var appearedAt = run.now
        assertEquals(listOf(Verdict.HIDE_PANEL, Verdict.WAIT, Verdict.SEND), run.ticks(b, 3))
        assertEquals(3 * tickMs, run.lastSendAt - appearedAt)
        assertEquals(1_200L, run.lastSendAt - appearedAt) // at 400 ms ticks

        // Without a panel (it timed out): the first tick sees the change, the second finds it stable.
        while (run.panelUp) assertEquals(Verdict.IDLE, run.tick(b))
        assertEquals(Verdict.IDLE, run.tick(b))
        appearedAt = run.now
        assertEquals(listOf(Verdict.WAIT, Verdict.SEND), run.ticks(c, 2))
        assertEquals(800L, run.lastSendAt - appearedAt) // at 400 ms ticks

        // And exactly one send per question, however long the screen then stays the same.
        assertTrue(run.ticks(c, 100).all { it == Verdict.IDLE })
        assertEquals(3, run.sends)
        assertEquals(1, run.hides)
    }

    @Test
    fun auto_oneTickBlipWhileTheAnswerIsShown_hidesItThenBringsItBack() {
        val a = game(banner = 30, options = 180)
        val blip = game(banner = 220, options = 40) // e.g. a heads-up notification, a peek at the shade
        val run = AutoRun(::frameSig)
        run.ticks(a, 2)
        assertTrue(run.panelUp)

        assertEquals(Verdict.HIDE_PANEL, run.tick(blip))
        assertFalse(run.panelUp)
        assertEquals(Verdict.IDLE, run.tick(a)) // back to what was answered
        assertTrue("the answer should be back", run.panelUp)
        assertEquals(List(10) { Verdict.IDLE }, run.ticks(a, 10))
        assertEquals(1, run.sends)
        assertEquals(1, run.hides)
        assertEquals(1, run.restores)
    }

    @Test
    fun auto_blipOfTwoTicks_stillBringsTheAnswerBack_withoutSending() {
        val a = game(banner = 30, options = 180)
        val blip = game(banner = 220, options = 40)
        val run = AutoRun(::frameSig)
        run.ticks(a, 2)
        // A blip of 2 ticks (1 s) is the longest that still counts as one: the third tick would send.
        assertEquals(listOf(Verdict.HIDE_PANEL, Verdict.WAIT), run.ticks(blip, 2))
        assertEquals(Verdict.IDLE, run.tick(a))
        assertTrue(run.panelUp)
        assertEquals(1, run.sends)
    }

    @Test
    fun auto_answerThatRanOutOfTimeWhileHidden_isNotBroughtBack() {
        val a = game(banner = 30, options = 180)
        val blip = game(banner = 220, options = 40)
        val run = AutoRun(::frameSig)
        run.ticks(a, 2)
        assertEquals(Verdict.HIDE_PANEL, run.tick(blip))
        run.now += 20_000 // the 15 s would have passed anyway
        assertEquals(Verdict.IDLE, run.tick(a))
        assertFalse(run.panelUp)
        assertEquals(0, run.restores)
    }

    @Test
    fun auto_afterARealChange_theOldAnswerIsNeverBroughtBack() {
        val a = game(banner = 30, options = 180)
        val b = game(banner = 220, options = 40)
        val run = AutoRun(::frameSig)
        run.ticks(a, 2)
        // A change that holds for 3 ticks (1.5 s) is a new screen, not a blip: sent, never undone.
        assertEquals(listOf(Verdict.HIDE_PANEL, Verdict.WAIT, Verdict.SEND), run.ticks(b, 3))
        assertTrue(run.ticks(b, 40).all { it == Verdict.IDLE }) // 20 s: the new answer's 15 s auto-hide is in it
        assertFalse(run.panelUp)
        assertEquals(0, run.restores)
        assertEquals(2, run.sends)
    }

    @Test
    fun auto_manyQuestions_neverSendsWithThePanelAndNeverHidesInALoop_andNeverMissesOne() {
        val rnd = java.util.Random(42)
        val run = AutoRun(::frameSig)
        var prevOptions = -1000
        var questions = 0
        repeat(60) {
            var options: Int
            do {
                options = rnd.nextInt(256)
            } while (kotlin.math.abs(options - prevOptions) < 60)
            prevOptions = options
            val screen = game(banner = rnd.nextInt(256), options = options)
            questions++
            // 4 to 8 ticks (2 to 4 s), as short as a question can be and still be sent: with the
            // panel up that takes 3 ticks (hide, compare, send).
            val hold = 4 + rnd.nextInt(5)
            repeat(hold) {
                // Besides the real 15 s timer (in AutoRun), now and then it fires early between two ticks.
                if (run.panelUp && rnd.nextInt(8) == 0) run.panelUp = false
                val panelBefore = run.panelUp
                val before = run.verdicts.lastOrNull()
                val v = run.tick(screen)
                if (v == Verdict.SEND) assertFalse("sent with the panel in the frame", panelBefore)
                if (v == Verdict.HIDE_PANEL) assertTrue("hid a panel that was not there", panelBefore)
                if (before == Verdict.HIDE_PANEL) assertTrue("sent right after a hide", v != Verdict.SEND)
                if (before == Verdict.HIDE_PANEL) assertTrue("hide twice in a row", v != Verdict.HIDE_PANEL)
            }
            // Every question that stayed for 4+ ticks (2 s) was sent (the latest sent frame shows it).
            val sent = run.lastSent!!
            assertTrue("question $questions was missed", FrameSignature.diff(frameSig(screen, false), sent) <= 6.0)
        }
        assertEquals("one send per question", questions, run.sends)
        assertTrue(run.hides <= run.sends)
    }
}
