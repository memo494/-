package com.clu.quickanswer.interaction

import com.clu.quickanswer.ocr.*
import org.junit.Assert.*
import org.junit.Test

class PrintedChoiceTouchTest {
    private val buttons = (0..3).map { PxRect(430, 205 + it * 90, 860, 280 + it * 90) }
    private val words = buttons.map { PxRect(550, it.top + 20, 740, it.top + 50) }
    private val layout = ScreenLayout(PxRect(180, 10, 1100, 185), buttons, null,
        listOf(PxRect(350, 60, 900, 110)), words.map(::listOf))
    private fun printed(values: List<String>) = values.mapIndexed { i, v -> WordAnswerMatcher.Candidate(v, words[i], 0.95f) }

    @Test fun mixedQuestionReadingCanFailWhileIndependentlyReadStackOptionsStillAuthorizeTheExactAnswer() {
        for ((values, answer, at) in listOf(
            Triple(listOf("زرافة", "فيل", "ذئب", "دب"), "فيل", 1),
            Triple(listOf("سورة القصص", "سورة آل عمران", "سورة الإسراء", "سورة مريم"), "سورة آل عمران", 1),
        )) {
            val request = PrintedChoiceTouch.request(layout, printed(values), answer)!!
            val plan = AnswerInteraction.plan(request, AnswerInteraction.Controls(1280, 576))!!
            assertEquals(listOf(words[at]), plan.taps)
            assertEquals(layout.questionLines, request.question.questionTextRects)
        }
    }

    @Test fun unreadableDuplicateMissingAndOutOfButtonWordsCannotAuthorizeTheFallback() {
        val good = printed(listOf("زرافة", "فيل", "ذئب", "دب"))
        assertNull(PrintedChoiceTouch.request(layout, good.dropLast(1), "فيل"))
        assertNull(PrintedChoiceTouch.request(layout, good.mapIndexed { i, c -> if (i == 0) c.copy(confidence = 0.5f) else c }, "فيل"))
        assertNull(PrintedChoiceTouch.request(layout, good.mapIndexed { i, c -> if (i == 0) c.copy(text = "فيل") else c }, "فيل"))
        assertNull(PrintedChoiceTouch.request(layout, good.mapIndexed { i, c -> if (i == 0) c.copy(rect = PxRect(0, 0, 50, 30)) else c }, "فيل"))
        assertNull(PrintedChoiceTouch.request(layout, good, "2"))
        assertNull(PrintedChoiceTouch.request(layout, good, "فول"))
    }

    @Test fun theWhiteStackExampleHasIndependentColorGeometryBeforeReadingItsWords() {
        val w = 1280; val h = 576
        val c = Canvas(w, h, 0xff385a60.toInt())
        c.fill(180, 10, 1100, 185, Canvas.CREAM)
        c.ink(400, 65, 900, 88)
        for (button in buttons) {
            c.fill(button.left, button.top, button.right, button.bottom, Canvas.WHITE)
            for (y in button.top + 20 until button.top + 50) for (x in 550 until 740)
                if (x % 12 < 3 || y >= button.top + 47) c.px[y * w + x] = 0xff7e3721.toInt()
        }
        val found = ColorLayout().inspect(c.px, w, h)
        assertNotNull(found.rejection, found.layout)
        val visible = found.layout!!
        assertEquals(4, visible.buttons.size)
        // Deliberately constant coloured ink: the old <Otsu cutter loses it, the independent <= cutter must not.
        val regions = PrintedChoiceTouch.regions(visible, c.px, w, h)!!
        assertTrue(ChoiceTouchGuard.isReadyFromLayout(c.px, w, h, visible.banner, visible.buttons, regions))
        val p = printed(listOf("زرافة", "فيل", "ذئب", "دب")).mapIndexed { i, it -> it.copy(rect = regions[i]) }
        val request = PrintedChoiceTouch.request(visible, p, "فيل")!!
        assertEquals(listOf(regions[1]), AnswerInteraction.plan(request, AnswerInteraction.Controls(w, h))!!.taps)
        // A changed result surface fails readiness when all four regions are checked.
        c.fill(buttons[2].left, buttons[2].top, buttons[2].right, buttons[2].bottom, 0xff21a800.toInt())
        assertFalse(ChoiceTouchGuard.isReadyFromLayout(c.px, w, h, visible.banner, visible.buttons, regions))
    }
}
