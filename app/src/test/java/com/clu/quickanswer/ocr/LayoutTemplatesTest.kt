package com.clu.quickanswer.ocr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LayoutTemplatesTest {
    /** A fingerprint that is [level] everywhere: the distance between two of them is the difference of the levels. */
    private fun fp(level: Int) = Integer.toHexString(level).repeat(288)

    /** A GRID template whose layout is shifted down by [shift] (templates 0.05 apart are different layouts). */
    private fun tpl(
        id: String,
        fp: String = fp(8),
        pkg: String? = "com.example.game",
        state: TemplateState = TemplateState.CANDIDATE,
        hits: Int = 0,
        shift: Float = 0f,
        used: Long = 0L,
        options: Int = 4,
        arrangement: Arrangement = Arrangement.GRID,
    ): LayoutTemplate {
        val opts = if (arrangement == Arrangement.NUMERIC) emptyList() else List(options) {
            RelRect(0.1f, 0.3f + shift + it * 0.06f, 0.9f, 0.35f + shift + it * 0.06f)
        }
        val field = if (arrangement == Arrangement.NUMERIC) RelRect(0.3f, 0.3f + shift, 0.7f, 0.35f + shift) else null
        return LayoutTemplate(
            id = id, packageName = pkg, fingerprint = fp, arrangement = arrangement,
            question = RelRect(0.1f, 0.1f + shift, 0.9f, 0.25f + shift), options = opts, numericField = field,
            signatures = List(1 + opts.size + (if (field != null) 1 else 0)) { RegionSignature(60, 220) },
            language = "en", state = state, hits = hits, misses = 0, source = "model", createdAt = used, lastUsedAt = used,
        )
    }

    @Test
    fun findOrdersTrustedFirstThenDistanceThenHits() {
        val m = LayoutTemplates()
        m.add(tpl("cand", fp(8), shift = 0.00f))
        m.add(tpl("far", fp(9), state = TemplateState.TRUSTED, hits = 9, shift = 0.05f))
        m.add(tpl("few", fp(8), state = TemplateState.TRUSTED, hits = 1, shift = 0.10f))
        m.add(tpl("many", fp(8), state = TemplateState.TRUSTED, hits = 5, shift = 0.15f))
        // Too different a game, and a different package.
        m.add(tpl("other-game", fp(10), state = TemplateState.TRUSTED, shift = 0.20f))
        m.add(tpl("other-pkg", fp(8), pkg = "com.other", state = TemplateState.TRUSTED, shift = 0.25f))

        val found = m.find(fp(8), "com.example.game", trustedOnly = false)
        assertEquals(listOf("many", "few", "far"), found.map { it.id })

        m.remove("many")
        assertEquals(listOf("few", "far", "cand"), m.find(fp(8), "com.example.game", false).map { it.id })
        assertEquals(listOf("few", "far"), m.find(fp(8), "com.example.game", trustedOnly = true).map { it.id })
    }

    @Test
    fun findPackageRuleOnlyAppliesWhenBothAreKnown() {
        val m = LayoutTemplates()
        m.add(tpl("a", pkg = "com.a", shift = 0.0f))
        m.add(tpl("b", pkg = "com.b", shift = 0.1f))
        m.add(tpl("none", pkg = null, shift = 0.2f))
        assertEquals(setOf("a", "none"), m.find(fp(8), "com.a", false).map { it.id }.toSet())
        assertEquals(setOf("a", "b", "none"), m.find(fp(8), null, false).map { it.id }.toSet())
    }

    @Test
    fun findNeedsAFingerprintOfTheSameLengthWithEnoughKnownCells() {
        val m = LayoutTemplates()
        m.add(tpl("a"))
        assertTrue(m.find("8".repeat(100), null, false).isEmpty())
        assertTrue(m.find(".".repeat(288), null, false).isEmpty())
        assertEquals(1, m.find(".".repeat(100) + "8".repeat(188), null, false).size)
    }

    @Test
    fun findLimitsToThree() {
        val m = LayoutTemplates()
        for (i in 0 until 6) m.add(tpl("t$i", shift = i * 0.08f))
        assertEquals(3, m.find(fp(8), null, false).size)
    }

    @Test
    fun addStoresAndReturnsTheTemplate() {
        val m = LayoutTemplates()
        val t = tpl("a")
        assertSame(t, m.add(t))
        assertEquals(1, m.size)
        assertEquals(t, m.get("a"))
        assertNull(m.get("zzz"))
        assertEquals(listOf(t), m.all())
    }

    @Test
    fun addRejectsAnInvalidTemplate() {
        val m = LayoutTemplates()
        try {
            m.add(tpl("a").copy(fingerprint = "short"))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, m.size)
    }

    @Test
    fun addReturnsTheExistingTemplateForTheSameLayout() {
        val m = LayoutTemplates()
        val first = m.add(tpl("a", fp(8)))
        // Same game (distance 1), same arrangement and count, rectangles 0.02 off.
        val again = m.add(tpl("b", fp(9), shift = 0.02f))
        assertSame(first, again)
        assertEquals(1, m.size)

        // 0.05 off: a different layout of the same game.
        assertEquals("c", m.add(tpl("c", shift = 0.05f)).id)
        // Another option count, another arrangement, another game, another package.
        assertEquals("d", m.add(tpl("d", options = 3)).id)
        assertEquals("e", m.add(tpl("e", arrangement = Arrangement.NUMERIC)).id)
        assertEquals("f", m.add(tpl("f", fp(11))).id)
        assertEquals("g", m.add(tpl("g", pkg = "com.other")).id)
        assertEquals(6, m.size)
    }

    @Test
    fun addWithAnExistingIdReplaces() {
        val m = LayoutTemplates()
        m.add(tpl("a", shift = 0f))
        m.add(tpl("a", shift = 0.2f, hits = 2))
        assertEquals(1, m.size)
        assertEquals(2, m.get("a")!!.hits)
    }

    @Test
    fun promotedAfterThreeSuccesses() {
        val m = LayoutTemplates()
        m.add(tpl("a"))
        m.recordFailure("a", 1L)
        m.recordSuccess("a", 100L)
        m.recordSuccess("a", 200L)
        var t = m.get("a")!!
        assertEquals(TemplateState.CANDIDATE, t.state)
        assertEquals(2, t.hits)
        assertEquals(0, t.misses)
        assertEquals(200L, t.lastUsedAt)
        m.recordSuccess("a", 300L)
        t = m.get("a")!!
        assertEquals(TemplateState.TRUSTED, t.state)
        assertEquals(3, t.hits)
        assertEquals(300L, t.lastUsedAt)
        m.recordSuccess("a", 400L)
        assertEquals(TemplateState.TRUSTED, m.get("a")!!.state)
    }

    @Test
    fun trustedIsDemotedAfterFiveFailuresInARow() {
        val m = LayoutTemplates()
        m.add(tpl("a", state = TemplateState.TRUSTED, hits = 7))
        repeat(4) { m.recordFailure("a", it.toLong()) }
        assertEquals(TemplateState.TRUSTED, m.get("a")!!.state)
        assertEquals(4, m.get("a")!!.misses)
        // A success in between starts the count again.
        m.recordSuccess("a", 10L)
        repeat(4) { m.recordFailure("a", 20L + it) }
        assertEquals(TemplateState.TRUSTED, m.get("a")!!.state)
        m.recordFailure("a", 30L)
        val t = m.get("a")!!
        assertEquals(TemplateState.CANDIDATE, t.state)
        assertEquals(0, t.hits)
        assertEquals(0, t.misses)
    }

    @Test
    fun candidateIsRemovedAfterFiveFailuresInARow() {
        val m = LayoutTemplates()
        m.add(tpl("a", hits = 2))
        repeat(4) { m.recordFailure("a", it.toLong()) }
        assertNotNull(m.get("a"))
        m.recordFailure("a", 9L)
        assertNull(m.get("a"))
        assertEquals(0, m.size)
    }

    @Test
    fun recordingForAnUnknownIdDoesNothing() {
        val m = LayoutTemplates()
        m.recordSuccess("nope", 1L)
        m.recordFailure("nope", 1L)
        assertEquals(0, m.size)
    }

    @Test
    fun evictsTheLeastRecentlyUsedCandidateFirst() {
        val m = LayoutTemplates(capacity = 3)
        m.add(tpl("old-trusted", state = TemplateState.TRUSTED, used = 1, shift = 0.0f))
        m.add(tpl("cand-recent", used = 50, shift = 0.1f))
        m.add(tpl("cand-old", used = 5, shift = 0.2f))
        m.add(tpl("new", used = 60, shift = 0.3f))
        assertEquals(setOf("old-trusted", "cand-recent", "new"), m.all().map { it.id }.toSet())
        m.add(tpl("newer", used = 70, shift = 0.4f))
        assertEquals(setOf("old-trusted", "new", "newer"), m.all().map { it.id }.toSet())
    }

    @Test
    fun evictsTheLeastRecentlyUsedTrustedWhenThereIsNoCandidate() {
        val m = LayoutTemplates(capacity = 2)
        m.add(tpl("t-recent", state = TemplateState.TRUSTED, used = 50, shift = 0.0f))
        m.add(tpl("t-old", state = TemplateState.TRUSTED, used = 3, shift = 0.1f))
        // The newcomer is not what goes: it was just learned.
        m.add(tpl("new", used = 60, shift = 0.2f))
        assertEquals(setOf("t-recent", "new"), m.all().map { it.id }.toSet())
    }

    @Test
    fun encodeAndRestoreRoundtrip() {
        val m = LayoutTemplates()
        m.add(tpl("a", state = TemplateState.TRUSTED, hits = 4, used = 11, shift = 0.0f))
        m.add(tpl("b", pkg = null, used = 12, shift = 0.1f, arrangement = Arrangement.NUMERIC))
        val json = m.encode()
        assertEquals(1, JSONObject(json).getInt("version"))
        assertEquals(2, JSONObject(json).getJSONArray("templates").length())

        val other = LayoutTemplates()
        other.restore(json)
        assertEquals(m.all(), other.all())
        assertEquals(json, other.encode())
    }

    @Test
    fun restoreOfGarbageGivesAnEmptyStore() {
        val m = LayoutTemplates()
        m.add(tpl("a"))
        for (garbage in listOf("garbage", "", "[]", "{}", "{\"version\":1}", "{\"version\":1,\"templates\":5}", "{\"version\":2,\"templates\":[]}", "null", "{{{")) {
            m.add(tpl("a"))
            m.restore(garbage)
            assertEquals(garbage, 0, m.size)
        }
    }

    @Test
    fun restoreSkipsInvalidEntries() {
        val m = LayoutTemplates()
        m.add(tpl("good", shift = 0f))
        m.add(tpl("good2", shift = 0.2f))
        val root = JSONObject(m.encode())
        val arr = root.getJSONArray("templates")
        arr.put(JSONObject().put("id", "broken"))
        arr.put("a string")
        arr.put(tpl("bad").toJson().put("fp", "x"))
        val other = LayoutTemplates()
        other.restore(root.toString())
        assertEquals(listOf("good", "good2"), other.all().map { it.id })
    }

    @Test
    fun restoreKeepsAtMostTheCapacity() {
        val big = LayoutTemplates()
        for (i in 0 until 5) big.add(tpl("t$i", shift = i * 0.1f, used = i.toLong()))
        val small = LayoutTemplates(capacity = 3)
        small.restore(big.encode())
        assertEquals(3, small.size)
        assertEquals(setOf("t2", "t3", "t4"), small.all().map { it.id }.toSet())
    }

    @Test
    fun onChangeFiresAfterEveryChange() {
        val m = LayoutTemplates()
        var calls = 0
        m.onChange = { calls++ }
        m.add(tpl("a"))
        assertEquals(1, calls)
        m.add(tpl("dup", fp(9), shift = 0.01f)) // returns the existing one: nothing changed
        assertEquals(1, calls)
        m.recordSuccess("a", 1L)
        assertEquals(2, calls)
        m.recordFailure("a", 2L)
        assertEquals(3, calls)
        m.recordSuccess("nope", 3L)
        m.recordFailure("nope", 3L)
        m.remove("nope")
        assertEquals(3, calls)
        m.remove("a")
        assertEquals(4, calls)
        m.clear() // already empty
        assertEquals(4, calls)
        m.add(tpl("b"))
        m.clear()
        assertEquals(6, calls)
        m.restore(LayoutTemplates().encode()) // restore does not notify
        assertEquals(6, calls)
    }
}
