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
            assertTrue("measured on the game's text areas", t.bounded)
        }
        assertNotNull("the 2x2 screen also has its greyed look", store.get("builtin-parchment-grid")!!.altSignatures)
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

    @Test fun theGridScreenKnowsWhereItsPictureIs() {
        val store = LayoutTemplates().apply { merge(json) }
        val grid = store.get("builtin-parchment-grid")!!
        val pic = grid.picture
        assertNotNull("the 2x2 screen pins a picture at the left", pic)
        assertTrue(pic!!.isValid)
        // Measured on the 2712 x 1220 captures (see RealScreenCalibration).
        assertEquals(PxRect(258, 445, 848, 915), pic.toPx(2712, 1220))
        // It survives the file round trip the store makes.
        assertEquals(pic, LayoutTemplate.fromJson(JSONObject(grid.toJson().toString()))!!.picture)
    }

    @Test fun aPictureNeverOverlapsTheTextAreas() {
        val store = LayoutTemplates().apply { merge(json) }
        for (t in store.all()) {
            val p = t.picture?.toPx(2712, 1220) ?: continue
            for (r in t.regions) {
                val q = r.toPx(2712, 1220)
                val overlaps = p.left < q.right && q.left < p.right && p.top < q.bottom && q.top < p.bottom
                assertTrue("${t.id}: the picture overlaps a region", !overlaps)
            }
        }
    }

    @Test fun phonesThatAlreadyMergedAnOlderFileMergeThisOne() {
        // The picture rect arrived in version 3; raising the version is what ships it to existing installs.
        assertTrue(BuiltinTemplates.VERSION >= 3)
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
