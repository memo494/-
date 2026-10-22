package com.clu.quickanswer.ocr

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTemplateTest {
    private val fp = "0123456789abcdef".repeat(18)

    private val q = RelRect(0.1f, 0.1f, 0.9f, 0.25f)
    private val opt = listOf(
        RelRect(0.1f, 0.3f, 0.49f, 0.4f), RelRect(0.51f, 0.3f, 0.9f, 0.4f),
        RelRect(0.1f, 0.45f, 0.49f, 0.55f), RelRect(0.51f, 0.45f, 0.9f, 0.55f),
    )

    private fun grid() = LayoutTemplate(
        id = "g1", packageName = "com.example.game", fingerprint = fp, arrangement = Arrangement.GRID,
        question = q, options = opt, numericField = null,
        signatures = List(5) { RegionSignature(60 + it, 220 - it) }, language = "ar",
        state = TemplateState.TRUSTED, hits = 4, misses = 1, source = "manual", createdAt = 1_700_000_000_123L, lastUsedAt = 1_700_000_999_999L,
    )

    private fun numeric() = LayoutTemplate(
        id = "n1", packageName = null, fingerprint = fp.replace('3', '.'), arrangement = Arrangement.NUMERIC,
        question = q, options = emptyList(), numericField = RelRect(0.3f, 0.3f, 0.7f, 0.36f),
        signatures = listOf(RegionSignature(250, 20), RegionSignature(30, 200)), language = null,
        state = TemplateState.CANDIDATE, hits = 0, misses = 0, source = "model", createdAt = 5L, lastUsedAt = 5L,
    )

    @Test
    fun relRectConvertsToPixelsAndBack() {
        val r = RelRect(0.1f, 0.25f, 0.9f, 0.5f)
        assertEquals(PxRect(108, 600, 972, 1200), r.toPx(1080, 2400))
        assertEquals(PxRect(54, 300, 486, 600), r.toPx(540, 1200))
        val back = RelRect.fromPx(PxRect(108, 600, 972, 1200), 1080, 2400)
        assertEquals(0.1f, back.left, 1e-6f)
        assertEquals(0.5f, back.bottom, 1e-6f)
        assertEquals(PxRect(108, 600, 972, 1200), back.toPx(1080, 2400))
    }

    @Test
    fun relRectValidity() {
        assertTrue(RelRect(0f, 0f, 1f, 1f).isValid)
        assertFalse(RelRect(0.5f, 0.1f, 0.5f, 0.2f).isValid)
        assertFalse(RelRect(0.1f, 0.3f, 0.2f, 0.2f).isValid)
        assertFalse(RelRect(-0.1f, 0.1f, 0.5f, 0.2f).isValid)
        assertFalse(RelRect(0.1f, 0.1f, 1.1f, 0.2f).isValid)
        assertFalse(RelRect(Float.NaN, 0.1f, 0.5f, 0.2f).isValid)
    }

    @Test
    fun signaturePolarity() {
        assertTrue(RegionSignature(40, 220).lightOnDark)
        assertFalse(RegionSignature(240, 20).lightOnDark)
        assertFalse(RegionSignature(100, 100).lightOnDark)
    }

    @Test
    fun jsonRoundtrip() {
        for (t in listOf(grid(), numeric())) {
            assertTrue(t.isValid)
            // Through text, like the file on disk.
            val back = LayoutTemplate.fromJson(JSONObject(t.toJson().toString()))
            assertEquals(t, back)
        }
    }

    @Test
    fun validTemplates() {
        assertTrue(grid().isValid)
        assertTrue(numeric().isValid)
        assertTrue(grid().copy(arrangement = Arrangement.VERTICAL).isValid)
        assertTrue(grid().copy(options = opt.take(2), signatures = List(3) { RegionSignature(10, 200) }).isValid)
    }

    @Test
    fun invalidTemplates() {
        val g = grid()
        assertFalse(g.copy(id = "").isValid)
        assertFalse(g.copy(fingerprint = fp.drop(1)).isValid)
        assertFalse(g.copy(fingerprint = "z".repeat(288)).isValid)
        assertFalse(g.copy(options = opt.take(1), signatures = g.signatures.take(2)).isValid)
        assertFalse(g.copy(options = List(9) { opt[it % 4] }, signatures = List(10) { RegionSignature(1, 2) }).isValid)
        assertFalse(g.copy(numericField = RelRect(0.3f, 0.6f, 0.7f, 0.7f), signatures = g.signatures + RegionSignature(1, 2)).isValid)
        assertFalse(g.copy(signatures = g.signatures.drop(1)).isValid)
        assertFalse(g.copy(question = RelRect(0.5f, 0.1f, 0.4f, 0.2f)).isValid)
        assertFalse(g.copy(options = opt.take(3) + RelRect(0.5f, 0.5f, 1.5f, 0.6f), signatures = g.signatures).isValid)
        assertFalse(g.copy(signatures = g.signatures.take(4) + RegionSignature(300, 0)).isValid)
        assertFalse(g.copy(hits = -1).isValid)
        val n = numeric()
        assertFalse(n.copy(numericField = null, signatures = n.signatures.take(1)).isValid)
        assertFalse(n.copy(options = opt.take(2), signatures = List(4) { RegionSignature(1, 2) }).isValid)
    }

    @Test
    fun fromJsonIsNullForInvalidOrMissing() {
        assertNull(LayoutTemplate.fromJson(JSONObject()))
        assertNull(LayoutTemplate.fromJson(grid().copy(options = opt.take(1), signatures = grid().signatures.take(2)).toJson()))
        for (key in listOf("id", "fp", "arr", "q", "opts", "sig", "state", "hits", "misses", "src", "created", "used")) {
            val o = grid().toJson()
            o.remove(key)
            assertNull("without $key", LayoutTemplate.fromJson(o))
        }
        assertNull(LayoutTemplate.fromJson(grid().toJson().put("arr", "DIAGONAL")))
        assertNull(LayoutTemplate.fromJson(grid().toJson().put("state", "ROYAL")))
        assertNull(LayoutTemplate.fromJson(grid().toJson().put("q", JSONArray().put(0.1).put(0.1).put(0.9))))
        assertNull(LayoutTemplate.fromJson(grid().toJson().put("q", "not a rect")))
        assertNull(LayoutTemplate.fromJson(grid().toJson().put("hits", "many")))
        assertNull(LayoutTemplate.fromJson(grid().toJson().put("sig", JSONArray().put(JSONArray().put(1)))))
        assertNull(LayoutTemplate.fromJson(grid().toJson().put("fp", "short")))
    }

    @Test
    fun fromJsonKeepsNullOptionalFields() {
        val back = LayoutTemplate.fromJson(numeric().toJson())
        assertNotNull(back)
        assertNull(back!!.packageName)
        assertNull(back.language)
        assertEquals(Arrangement.NUMERIC, back.arrangement)
    }
}
