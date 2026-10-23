package com.clu.quickanswer.ocr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Contract for [LayoutTemplates.merge] (importing templates from another phone). */
class LayoutTemplatesMergeTest {

    private fun fp(level: Int) = Integer.toHexString(level).repeat(288)

    /** A GRID template; templates whose [shift] differs by 0.05 or more are different layouts. */
    private fun tpl(
        id: String,
        shift: Float = 0f,
        state: TemplateState = TemplateState.CANDIDATE,
        hits: Int = 0,
        misses: Int = 0,
        used: Long = 0L,
    ): LayoutTemplate {
        val opts = List(4) { RelRect(0.1f, 0.3f + shift + it * 0.06f, 0.9f, 0.35f + shift + it * 0.06f) }
        return LayoutTemplate(
            id = id, packageName = null, fingerprint = fp(8), arrangement = Arrangement.GRID,
            question = RelRect(0.1f, 0.1f + shift, 0.9f, 0.25f + shift), options = opts, numericField = null,
            signatures = List(5) { RegionSignature(60, 0.9f) }, language = "ar", state = state,
            hits = hits, misses = misses, source = "model", createdAt = used, lastUsedAt = used,
        )
    }

    private fun exported(vararg t: LayoutTemplate): String = LayoutTemplates(1000).apply { t.forEach { add(it) } }.encode()

    @Test fun addsNewTemplates_yoursWin() {
        val mine = LayoutTemplates()
        mine.add(tpl("a", shift = 0f, hits = 1))
        val incoming = JSONObject(
            exported(
                tpl("a", shift = 0.20f, hits = 9), // same id as yours -> skipped
                tpl("b", shift = 0.10f), // new -> added
            )
        )
        // Same layout as your "a" under another id -> skipped; and a broken entry -> skipped.
        val arr = incoming.getJSONArray("templates")
        arr.put(JSONObject(exported(tpl("c", shift = 0f))).getJSONArray("templates").getJSONObject(0))
        arr.put(JSONObject().put("bad", 1))

        val r = mine.merge(incoming.toString())!!
        assertEquals(1, r.added)
        assertEquals(3, r.skipped)
        assertEquals(setOf("a", "b"), mine.all().map { it.id }.toSet())
        assertEquals("your own template is unchanged", 1, mine.get("a")!!.hits)
    }

    @Test fun keepsStateAndHits_resetsMisses() {
        val mine = LayoutTemplates()
        mine.merge(exported(tpl("t", state = TemplateState.TRUSTED, hits = 5, misses = 3)))
        val t = mine.get("t")!!
        assertEquals(TemplateState.TRUSTED, t.state)
        assertEquals(5, t.hits)
        assertEquals(0, t.misses)
    }

    @Test fun aFullStoreKeepsYourOwn() {
        val mine = LayoutTemplates(capacity = 2)
        mine.add(tpl("x", shift = 0f))
        mine.add(tpl("y", shift = 0.1f))
        val r = mine.merge(exported(tpl("p", shift = 0.2f), tpl("q", shift = 0.3f)))!!
        assertEquals(0, r.added)
        assertEquals(2, r.skipped)
        assertEquals(setOf("x", "y"), mine.all().map { it.id }.toSet())
    }

    @Test fun fillsTheRoomThatIsLeft() {
        val mine = LayoutTemplates(capacity = 2)
        mine.add(tpl("x", shift = 0f))
        val r = mine.merge(exported(tpl("p", shift = 0.2f), tpl("q", shift = 0.3f)))!!
        assertEquals(1, r.added)
        assertEquals(1, r.skipped)
        assertEquals(2, mine.size)
        assertNotNull(mine.get("x"))
    }

    @Test fun unreadableIsNull_andChangesNothing() {
        val mine = LayoutTemplates()
        mine.add(tpl("a"))
        for (s in listOf("", "nope", "{}", "{\"version\":99,\"templates\":[]}", "{\"version\":1}")) {
            assertNull(s, mine.merge(s))
        }
        assertEquals(1, mine.size)
    }

    @Test fun onChangeFiresOnceOnlyWhenSomethingWasAdded() {
        val mine = LayoutTemplates()
        var calls = 0
        mine.onChange = { calls++ }
        mine.merge(exported(tpl("a", shift = 0f), tpl("b", shift = 0.1f), tpl("c", shift = 0.2f)))
        assertEquals(1, calls)
        mine.merge(exported(tpl("a", shift = 0f)))
        assertEquals("nothing new: no save", 1, calls)
    }
}
