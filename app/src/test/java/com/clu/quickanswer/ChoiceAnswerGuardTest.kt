package com.clu.quickanswer

import org.junit.Assert.*
import org.junit.Test

class ChoiceAnswerGuardTest {
    private val names = listOf("صلاح الدين الأيوبي", "عمرو بن العاص", "طارق بن زياد", "نور الدين زنكي")

    @Test fun textIndexIsAcceptedOnlyWhenRequestedAndConsistentWithCurrentOptions() {
        assertTrue(ChoiceAnswerGuard.matches("الخيار 1", names[0], names, true))
        assertFalse(ChoiceAnswerGuard.matches("1", names[0], names, false))
        assertFalse(ChoiceAnswerGuard.matches("2", names[0], names, true))
        assertTrue(ChoiceAnswerGuard.matches("صلاح الدين الايوبي", names[0], names, false))
    }

    @Test fun fuzzyDisplayResolutionMustNotAuthorizeADifferentPrintedNameOrYear() {
        val options = listOf("محمد ولي", "أحمد", "عمر", "خالد")
        assertFalse(ChoiceAnswerGuard.matches("محمد علي", "محمد ولي", options, false))
        assertFalse(ChoiceAnswerGuard.matches("محمد علي", "محمد ولي", options, true))
        val years = listOf("عام 1999", "عام 2000", "عام 2001", "عام 2002")
        assertFalse(ChoiceAnswerGuard.matches("عام 1998", years[0], years, false))
    }

    @Test fun rejectedFuzzyReplyCannotBecomeTrustedThroughTheMemoryOnTheNextAttempt() {
        val memory = AnswerMemory()
        val options = listOf("محمد ولي", "أحمد", "عمر", "خالد")
        val question = "من هو صاحب الصورة؟"
        assertFalse(ChoiceAnswerGuard.rememberChoice(memory, question, "محمد علي", options[0], options, false))
        assertNull(memory.lookup(question, options, false))
        assertTrue(ChoiceAnswerGuard.rememberChoice(memory, question, options[0], options[0], options, false))
        assertEquals(options[0], memory.lookup(question, options, false)?.answer)
    }

    @Test fun exactAnswersFollowAllSuppliedChoiceArrangements() {
        for ((options, answer) in listOf(
            names to "صلاح الدين الأيوبي",
            listOf("الكويت", "تركيا", "المانيا", "إيطاليا") to "إيطاليا",
            listOf("المحرم", "شوال", "جمادى الآخرة", "رمضان") to "المحرم",
            listOf("سورة البقرة", "سورة طه", "سورة الإخلاص", "سورة النور") to "سورة طه",
        )) assertTrue(ChoiceAnswerGuard.matches(answer, answer, options, false))
        assertFalse(ChoiceAnswerGuard.matches(names[0], names[0], names.dropLast(1), false))
        assertFalse(ChoiceAnswerGuard.matches(names[0], names[0], listOf(names[0], names[0], names[2], names[3]), false))
    }
}
