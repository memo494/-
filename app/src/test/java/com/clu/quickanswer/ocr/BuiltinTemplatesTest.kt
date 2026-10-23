package com.clu.quickanswer.ocr

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contract for assets/templates/builtin.json, the layouts the app ships with (built from real
 * screenshots by [RealScreenCalibration], which is not run in CI). Only geometry, 24x12 fingerprints
 * and border signatures are in it: nothing that shows a player.
 */
class BuiltinTemplatesTest {

    private val json: String by lazy {
        val f = listOf("src/main/assets/templates/builtin.json", "app/src/main/assets/templates/builtin.json")
            .map { File(it) }.first { it.isFile }
        f.readText(Charsets.UTF_8)
    }

    @Test fun everyTemplateIsValidTrustedAndAddedToAnEmptyStore() {
        val store = LayoutTemplates()
        val r = store.merge(json)!!
        assertEquals("every built-in template is added", 5, r.added)
        assertEquals(0, r.skipped)
        for (t in store.all()) {
            assertTrue(t.id, t.id.startsWith("builtin-"))
            assertEquals(t.id, TemplateState.TRUSTED, t.state)
            assertEquals(t.id, BuiltinTemplates.SOURCE, t.source)
            assertNull("unknown language: read Arabic, then English", t.language)
        }
    }

    @Test fun theGamesAreToldApart() {
        val store = LayoutTemplates().apply { merge(json) }
        val grid = store.get("builtin-parchment-grid")!!
        val wood = store.get("builtin-wood-numeric")!!
        val parchmentNumeric = store.get("builtin-parchment-numeric")!!
        assertTrue(ScreenFingerprint.distance(grid.fingerprint, wood.fingerprint) > ScreenFingerprint.SAME_GAME_MAX)
        assertTrue(ScreenFingerprint.distance(grid.fingerprint, parchmentNumeric.fingerprint) <= ScreenFingerprint.SAME_GAME_MAX)
        // Same game, other screen type: the region signatures are what tells them apart.
        assertEquals(Arrangement.GRID, grid.arrangement)
        assertEquals(4, grid.options.size)
        assertNotNull(wood.numericField)
    }

    @Test fun yourOwnTemplatesWinOverTheBuiltInOnes() {
        val store = LayoutTemplates()
        store.merge(json)
        val again = store.merge(json)!!
        assertEquals("nothing is added twice", 0, again.added)
        assertEquals(5, store.size)
    }

    @Test fun theVersionIsReadFromTheFile() {
        assertEquals(BuiltinTemplates.VERSION, JSONObject(json).getInt("builtin"))
    }
}
