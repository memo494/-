package com.clu.quickanswer

import com.clu.quickanswer.ocr.LayoutTemplates
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract for the bank file that carries answers and layout templates. */
class BankFileTest {

    private fun memoryJson(): String = AnswerMemory().apply {
        put("ما هي عاصمة الأردن؟", listOf("عمان", "إربد", "الزرقاء", "العقبة"), false, "عمان")
        put("كم عدد أيام الأسبوع؟", emptyList(), true, "7")
    }.encode()

    private val emptyTemplates = LayoutTemplates().encode()

    @Test fun encodesBothPartsAsObjects() {
        val root = JSONObject(BankFile.encode(memoryJson(), emptyTemplates))
        assertEquals(BankFile.FORMAT, root.getString("format"))
        assertEquals(BankFile.VERSION, root.getInt("version"))
        assertNotNull("memory is an object, not a string", root.optJSONObject("memory"))
        assertNotNull("templates is an object, not a string", root.optJSONObject("templates"))
    }

    @Test fun roundTrip() {
        val parts = BankFile.decode(BankFile.encode(memoryJson(), emptyTemplates))!!
        val m = AnswerMemory()
        assertTrue(m.restore(parts.memoryJson!!))
        assertEquals(2, m.size)
        assertEquals("عمان", m.lookup("ما هي عاصمة الأردن؟", listOf("العقبة", "عمان", "إربد", "الزرقاء"), false)!!.answer)
        assertNotNull(parts.templatesJson)
        assertEquals(0, LayoutTemplates().apply { restore(parts.templatesJson!!) }.size)
    }

    @Test fun oldFilesStillImport() {
        val old = memoryJson()
        val parts = BankFile.decode(old)!!
        assertEquals(old, parts.memoryJson)
        assertNull(parts.templatesJson)
    }

    @Test fun aPartMayBeMissing() {
        val onlyMemory = JSONObject().put("format", BankFile.FORMAT).put("version", 2)
            .put("memory", JSONObject(memoryJson())).toString()
        val p = BankFile.decode(onlyMemory)!!
        assertNotNull(p.memoryJson)
        assertNull(p.templatesJson)

        val onlyTemplates = JSONObject().put("format", BankFile.FORMAT).put("version", 2)
            .put("templates", JSONObject(emptyTemplates)).put("memory", "not an object").toString()
        val q = BankFile.decode(onlyTemplates)!!
        assertNull(q.memoryJson)
        assertNotNull(q.templatesJson)

        assertNull(BankFile.decode(JSONObject().put("format", BankFile.FORMAT).put("version", 2).toString()))
    }

    @Test fun aNewerVersionIsStillRead() {
        val newer = JSONObject(BankFile.encode(memoryJson(), emptyTemplates)).put("version", 3).put("extra", 1).toString()
        assertNotNull(BankFile.decode(newer)!!.memoryJson)
    }

    @Test fun garbageIsNotABank() {
        for (s in listOf("", "hello", "[]", "{}", "{\"format\":\"other\",\"memory\":{}}", "{\"e\":5}", "[".repeat(100_000))) {
            assertNull(s.take(40), BankFile.decode(s))
        }
    }
}
