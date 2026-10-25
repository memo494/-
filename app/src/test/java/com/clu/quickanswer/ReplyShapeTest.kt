package com.clu.quickanswer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract for [ReplyShape]: is a reply one answer, a list, several options or "unsure"? */
class ReplyShapeTest {

    @Test fun oneAnswerIsSingle() {
        assertTrue(ReplyShape.isSingle("القلب"))
        assertTrue(ReplyShape.isSingle("42"))
        assertTrue(ReplyShape.isSingle("5-3"))
    }

    @Test fun oneAnswerPerLineIsAList() {
        assertTrue(ReplyShape.isList("1. أ\n2. ج"))
        assertFalse(ReplyShape.isList("1. أ\n\n"))
        assertFalse(ReplyShape.isSingle("1. أ\n2. ج"))
    }

    @Test fun severalOptionsAndPairsAreCompound() {
        assertTrue(ReplyShape.isCompound("باريس + لندن"))
        assertTrue(ReplyShape.isCompound("1-ب، 2-أ، 3-ج"))
        assertFalse(ReplyShape.isCompound("5-3"))
        assertFalse(ReplyShape.isSingle("باريس + لندن"))
        assertFalse(ReplyShape.isSingle("1-ب، 2-أ، 3-ج"))
    }

    @Test fun unsureIsNotSingle_evenWithAFullStop() {
        assertTrue(ReplyShape.isUnsure("غير متأكد."))
        assertFalse(ReplyShape.isSingle("غير متأكد."))
    }

    @Test fun noQuestionAndBlankAreNotSingle() {
        assertFalse(ReplyShape.isSingle(ScreenImage.NO_QUESTION))
        assertFalse(ReplyShape.isSingle(""))
        assertFalse(ReplyShape.isSingle("  \n "))
    }
}
