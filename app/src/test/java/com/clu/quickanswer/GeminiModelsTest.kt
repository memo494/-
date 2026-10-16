package com.clu.quickanswer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prefs-free logic only: nothing here touches Android. */
class GeminiModelsTest {

    private fun resource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("gemini/$name")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private val realList: String by lazy { resource("models.json") }

    // ------------------------------------------------------------ ListModels filter

    @Test
    fun filter_realListModels_keepsOnlyAnswerCapableModels() {
        val ids = GeminiModels.filterListModels(realList)
        assertEquals(
            listOf(
                "gemma-4-26b-a4b-it", "gemma-4-31b-it", "gemini-flash-latest", "gemini-flash-lite-latest",
                "gemini-pro-latest", "gemini-3-flash-preview", "gemini-3.1-pro-preview",
                "gemini-3.1-flash-lite-preview", "gemini-3.1-flash-lite", "gemini-3.5-flash",
                "gemini-3.5-flash-lite", "gemini-omni-flash-preview", "gemini-omni-1.1-flash",
                "gemini-3.6-flash", "gemini-3.7-flash", "gemini-3.8-flash", "gemini-4-argon"
            ),
            ids
        )
    }

    @Test
    fun filter_realListModels_dropsEverythingElse() {
        val ids = GeminiModels.filterListModels(realList)
        val dropped = listOf(
            "gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.5-flash-lite",       // 2.x
            "gemini-2.5-flash-preview-tts", "gemini-3.8-flash-tts",              // tts
            "gemini-2.5-flash-image", "gemini-3.1-flash-lite-image",             // image
            "nano-banana-pro-preview", "gemini-nano-banana-2.1",                 // not gemini-/gemma- or nano-banana
            "gemini-3.1-pro-preview-customtools", "gemini-3.5-transcribe",
            "gemini-robotics-er-2-preview", "gemini-2.5-computer-use-preview-10-2025",
            "antigravity-preview-latest", "deep-research-preview-04-2026", "lyria-3.5",
            "gemini-embedding-001", "aqa", "veo-3.1-generate-preview",
            "gemini-3.1-flash-live-preview", "gemini-3.8-live",                  // bidi only / live
            "gemini-3.5-live-translate-preview"
        )
        for (d in dropped) assertFalse("$d should be dropped", d in ids)
        // No prefix left over from the API's "models/" names.
        assertTrue(ids.none { it.startsWith("models/") })
    }

    @Test
    fun filter_requiresGenerateContent_andDropTerms_andLegacyPrefixes() {
        val json = """
            {"models":[
              {"name":"models/gemini-9-flash","supportedGenerationMethods":["generateContent","countTokens"]},
              {"name":"models/gemini-9-embed","supportedGenerationMethods":["embedContent"]},
              {"name":"models/gemini-9-live","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-2.0-flash","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-1.5-pro","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemma-9","supportedGenerationMethods":["generateContent"]},
              {"name":"models/palm-bison","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-9-flash","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-9-nomethods"}
            ]}
        """.trimIndent()
        assertEquals(listOf("gemini-9-flash", "gemma-9"), GeminiModels.filterListModels(json))
    }

    @Test
    fun filter_garbageGivesEmptyList() {
        assertTrue(GeminiModels.filterListModels("").isEmpty())
        assertTrue(GeminiModels.filterListModels("not json").isEmpty())
        assertTrue(GeminiModels.filterListModels("{}").isEmpty())
        assertTrue(GeminiModels.filterListModels("""{"error":{"code":403}}""").isEmpty())
    }

    @Test
    fun nextPageToken_readsTokenOrNull() {
        assertEquals("abc", GeminiModels.nextPageToken("""{"models":[],"nextPageToken":"abc"}"""))
        assertNull(GeminiModels.nextPageToken("""{"models":[]}"""))
        assertNull(GeminiModels.nextPageToken("nope"))
    }

    // ------------------------------------------------------------ merge with catalog

    @Test
    fun merge_realList_isCatalogOrder_freeFirst_paidLast() {
        val entries = GeminiModels.mergeWithCatalog(GeminiModels.filterListModels(realList))
        val expected = GeminiModels.CATALOG.map { it.id }
        assertEquals(expected, entries.map { it.id })
        assertEquals("gemini-3-flash-preview", entries.first().id)
        // Once a paid-only model shows up, no free one follows.
        val firstPaid = entries.indexOfFirst { !it.freeTier }
        assertTrue(firstPaid > 0)
        assertTrue(entries.drop(firstPaid).none { it.freeTier })
        assertEquals("gemini-4-argon", entries.last().id)
        assertTrue(entries.all { it.known })
    }

    @Test
    fun merge_unknownModelsComeAfterCatalog_alphabetical_withUntestedNote() {
        val entries = GeminiModels.mergeWithCatalog(
            listOf("gemini-9-zeta", "gemini-3.8-flash", "gemma-9-b", "gemini-3-flash-preview", "gemini-9-alpha")
        )
        assertEquals(
            listOf("gemini-3-flash-preview", "gemini-3.8-flash", "gemini-9-alpha", "gemini-9-zeta", "gemma-9-b"),
            entries.map { it.id }
        )
        val extra = entries[2]
        assertFalse(extra.known)
        assertEquals("(غير مجرّب)", extra.note)
        assertEquals("gemini-9-alpha — (غير مجرّب)", extra.pickerText(blocked = false))
    }

    @Test
    fun merge_leavesOutCatalogModelsTheServerDidNotList() {
        val entries = GeminiModels.mergeWithCatalog(listOf("gemini-3-flash-preview", "gemini-3.6-flash"))
        assertEquals(listOf("gemini-3-flash-preview", "gemini-3.6-flash"), entries.map { it.id })
    }

    @Test
    fun merge_noListYet_showsWholeCatalog() {
        assertEquals(GeminiModels.CATALOG.map { it.id }, GeminiModels.mergeWithCatalog(emptyList()).map { it.id })
    }

    @Test
    fun pickerText_isLabelDashNote_andMarksBlockedModels() {
        val e = GeminiModels.entryFor("gemini-3-flash-preview")
        assertEquals("Gemini 3 Flash — الأسرع (1–2 ث)", e.pickerText(blocked = false))
        assertEquals("Gemini 3 Flash — الأسرع (1–2 ث) (انتهى الحد مؤقتًا)", e.pickerText(blocked = true))
    }

    @Test
    fun catalog_invariants() {
        val ids = GeminiModels.CATALOG.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals("gemini-3-flash-preview", GeminiModels.DEFAULT_ID)
        assertEquals("gemini-3-flash-preview", ids.first())
        // Shared quota groups from the lab data.
        assertEquals(GeminiModels.quotaGroup("gemini-3.8-flash"), GeminiModels.quotaGroup("gemini-flash-latest"))
        assertEquals(GeminiModels.quotaGroup("gemini-3.5-flash-lite"), GeminiModels.quotaGroup("gemini-flash-lite-latest"))
        assertFalse(GeminiModels.quotaGroup("gemini-3.8-flash") == GeminiModels.quotaGroup("gemini-3.5-flash-lite"))
        assertEquals("gemini-9-unknown", GeminiModels.quotaGroup("gemini-9-unknown"))
        // Paid-only models are never in the automatic fallback list.
        assertTrue(GeminiModels.CATALOG.filter { !it.freeTier }.none { it.autoFallback })
        for (id in listOf("gemini-pro-latest", "gemini-3.1-pro-preview", "gemini-omni-flash-preview", "gemini-omni-1.1-flash", "gemini-4-argon")) {
            assertFalse(id, GeminiModels.find(id)!!.freeTier)
            assertEquals("مدفوع فقط", GeminiModels.find(id)!!.note)
        }
    }

    @Test
    fun catalog_entriesAreShortEnoughNotToBeCutOffInTheSpinner() {
        // The picker line plus the blocked marker has to fit in the 3 lines of the spinner item.
        for (m in GeminiModels.CATALOG) {
            val line = GeminiModels.entryFor(m.id).pickerText(true)
            assertTrue("${m.id}: ${line.length} chars", line.length <= 80)
            assertTrue("${m.id}: note too long", m.note.length <= 30)
        }
    }

    // ------------------------------------------------------------ fallback order

    private val standardOrder = listOf(
        "gemini-3-flash-preview", "gemini-3.6-flash", "gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.7-flash"
    )

    @Test
    fun fallbackOrder_forUnlistedModel_isTheWholeStandardOrder() {
        assertEquals(standardOrder, GeminiModels.fallbackOrder("gemini-9-unknown"))
        assertEquals(standardOrder, GeminiModels.fallbackOrder("gemini-3.5-flash"))
    }

    @Test
    fun fallbackOrder_skipsTheUsersOwnModel() {
        assertEquals(
            listOf("gemini-3.6-flash", "gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.7-flash"),
            GeminiModels.fallbackOrder("gemini-3-flash-preview")
        )
        assertEquals(
            listOf("gemini-3-flash-preview", "gemini-3.6-flash", "gemini-3.8-flash", "gemini-3.5-flash-lite"),
            GeminiModels.fallbackOrder("gemini-3.7-flash")
        )
    }

    @Test
    fun fallbackOrder_skipsModelsInTheUsersQuotaGroup() {
        // gemini-flash-latest is gemini-3.8-flash, so asking 3.8 as a "fallback" would hit the same quota.
        assertEquals(
            listOf("gemini-3-flash-preview", "gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-3.7-flash"),
            GeminiModels.fallbackOrder("gemini-flash-latest")
        )
        assertEquals(
            listOf("gemini-3-flash-preview", "gemini-3.6-flash", "gemini-3.8-flash", "gemini-3.7-flash"),
            GeminiModels.fallbackOrder("gemini-flash-lite-latest")
        )
    }

    @Test
    fun fallbackOrder_neverRepeatsAQuotaGroup() {
        for (user in GeminiModels.CATALOG.map { it.id } + "gemini-9-unknown") {
            val order = GeminiModels.fallbackOrder(user)
            val groups = (listOf(user) + order).map { GeminiModels.quotaGroup(it) }
            assertEquals("duplicate group for user=$user: $groups", groups.size, groups.toSet().size)
            assertFalse(user in order)
        }
    }

    // ------------------------------------------------------------ plan: blocked models

    @Test
    fun plan_nothingBlocked_userFirstThenStandardFallbacks() {
        val p = GeminiModels.plan("gemini-3-flash-preview") { false }
        assertEquals("gemini-3-flash-preview", p.first)
        assertEquals(listOf("gemini-3.6-flash", "gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.7-flash"), p.fallbacks)
        assertFalse(p.forced)
    }

    @Test
    fun plan_blockedFallbacksAreSkipped() {
        val blocked = setOf("gemini-3.6-flash", "gemini-3.5-flash-lite")
        val p = GeminiModels.plan("gemini-3-flash-preview") { it in blocked }
        assertEquals("gemini-3-flash-preview", p.first)
        assertEquals(listOf("gemini-3.8-flash", "gemini-3.7-flash"), p.fallbacks)
    }

    @Test
    fun plan_blockedUserModel_startsAtOnceWithFirstFreeFallback() {
        val p = GeminiModels.plan("gemini-3-flash-preview") { it == "gemini-3-flash-preview" || it == "gemini-3.6-flash" }
        assertEquals("gemini-3.8-flash", p.first)
        assertEquals(listOf("gemini-3.5-flash-lite", "gemini-3.7-flash"), p.fallbacks)
        assertFalse(p.forced)
    }

    @Test
    fun plan_everyCandidateBlocked_triesTheUsersModelOnce() {
        val p = GeminiModels.plan("gemini-3.5-flash") { true }
        assertEquals("gemini-3.5-flash", p.first)
        assertTrue(p.fallbacks.isEmpty())
        assertTrue(p.forced)
    }

    @Test
    fun plan_withARealTracker_groupBlockCoversTheAlias() {
        val now = 1_000_000L
        val q = QuotaTracker(clock = { now })
        q.block("gemini-3.8-flash", 28_945_000L)
        // User picked the alias: it is blocked through the shared group, so 3.8 is not offered either.
        val p = GeminiModels.plan("gemini-flash-latest") { q.isBlocked(it) }
        assertEquals("gemini-3-flash-preview", p.first)
        assertEquals(listOf("gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-3.7-flash"), p.fallbacks)
    }

    // ------------------------------------------------------------ thinking config

    private fun cfg(id: String, mode: ThinkingMode): String? = GeminiModels.thinkingConfig(id, mode)?.toString()

    @Test
    fun thinkingMode_comesFromTheCatalog() {
        val none = emptyMap<String, ThinkingMode>()
        assertEquals(ThinkingMode.MINIMAL, GeminiModels.thinkingModeFor("gemini-3-flash-preview", none))
        assertEquals(ThinkingMode.MINIMAL, GeminiModels.thinkingModeFor("gemini-3.6-flash", none))
        assertEquals(ThinkingMode.MINIMAL, GeminiModels.thinkingModeFor("gemini-3.5-flash-lite", none))
        assertEquals(ThinkingMode.LOW, GeminiModels.thinkingModeFor("gemini-3.8-flash", none))
        assertEquals(ThinkingMode.LOW, GeminiModels.thinkingModeFor("gemini-3.7-flash", none))
        assertEquals(ThinkingMode.LOW, GeminiModels.thinkingModeFor("gemini-flash-latest", none))
        assertEquals(ThinkingMode.NONE, GeminiModels.thinkingModeFor("gemma-4-31b-it", none))
        assertEquals(ThinkingMode.NONE, GeminiModels.thinkingModeFor("gemma-4-26b-a4b-it", none))
        assertEquals(ThinkingMode.UNKNOWN, GeminiModels.thinkingModeFor("gemini-9-new", none))
    }

    @Test
    fun thinkingMode_unlistedGemmaGetsNoThinkingConfig() {
        val none = emptyMap<String, ThinkingMode>()
        // Not in the catalog (e.g. added by the refresh button): Gemma takes no thinkingConfig.
        assertEquals(ThinkingMode.NONE, GeminiModels.thinkingModeFor("gemma-5-9b-it", none))
        assertNull(cfg("gemma-5-9b-it", GeminiModels.thinkingModeFor("gemma-5-9b-it", none)))
        // Non-Gemma unknowns still start with "minimal".
        assertEquals(ThinkingMode.UNKNOWN, GeminiModels.thinkingModeFor("gemini-9-new", none))
        // A learned choice still wins over the Gemma default.
        assertEquals(
            ThinkingMode.LOW,
            GeminiModels.thinkingModeFor("gemma-5-9b-it", mapOf("gemma-5-9b-it" to ThinkingMode.LOW))
        )
    }

    @Test
    fun thinkingMode_learnedOverridesCatalog() {
        val learned = mapOf("gemini-3-flash-preview" to ThinkingMode.LOW, "gemini-9-new" to ThinkingMode.NONE)
        assertEquals(ThinkingMode.LOW, GeminiModels.thinkingModeFor("gemini-3-flash-preview", learned))
        assertEquals(ThinkingMode.NONE, GeminiModels.thinkingModeFor("gemini-9-new", learned))
        // Other models are untouched.
        assertEquals(ThinkingMode.MINIMAL, GeminiModels.thinkingModeFor("gemini-3.6-flash", learned))
    }

    @Test
    fun thinkingConfig_perMode() {
        assertEquals("""{"thinkingLevel":"minimal"}""", cfg("gemini-3-flash-preview", ThinkingMode.MINIMAL))
        assertEquals("""{"thinkingLevel":"low"}""", cfg("gemini-3.8-flash", ThinkingMode.LOW))
        assertNull(cfg("gemma-4-31b-it", ThinkingMode.NONE))
        // UNKNOWN is tried as minimal.
        assertEquals("""{"thinkingLevel":"minimal"}""", cfg("gemini-9-new", ThinkingMode.UNKNOWN))
    }

    @Test
    fun thinkingConfig_neverSendsBudgetToGemini3() {
        for (m in GeminiModels.CATALOG) {
            for (mode in ThinkingMode.values()) {
                val c = cfg(m.id, mode)
                assertTrue("${m.id} $mode: $c", c == null || !c.contains("thinkingBudget"))
            }
        }
    }

    @Test
    fun thinkingConfig_gemini2UsesBudgetZero() {
        assertEquals("""{"thinkingBudget":0}""", cfg("gemini-2.5-flash", ThinkingMode.MINIMAL))
        assertEquals("""{"thinkingBudget":0}""", cfg("gemini-2.5-flash", ThinkingMode.UNKNOWN))
        assertNull(cfg("gemini-2.5-flash", ThinkingMode.NONE))
    }

    @Test
    fun nextThinkingMode_stepsMinimalLowNone() {
        assertEquals(ThinkingMode.LOW, GeminiModels.nextThinkingMode("gemini-9-new", ThinkingMode.MINIMAL))
        assertEquals(ThinkingMode.LOW, GeminiModels.nextThinkingMode("gemini-9-new", ThinkingMode.UNKNOWN))
        assertEquals(ThinkingMode.NONE, GeminiModels.nextThinkingMode("gemini-9-new", ThinkingMode.LOW))
        assertNull(GeminiModels.nextThinkingMode("gemini-9-new", ThinkingMode.NONE))
        // 2.x has no levels, so minimal goes straight to nothing.
        assertEquals(ThinkingMode.NONE, GeminiModels.nextThinkingMode("gemini-2.5-flash", ThinkingMode.MINIMAL))
    }

    @Test
    fun isThinkingError_recognisesRealMessages() {
        assertTrue(GeminiModels.isThinkingError("Thinking level MINIMAL is not supported for this model. Please retry with other thinking level."))
        assertTrue(GeminiModels.isThinkingError("Thinking budget is not supported for this model."))
        assertTrue(GeminiModels.isThinkingError("Request contains an invalid argument."))
        assertFalse(GeminiModels.isThinkingError("API key not valid. Please pass a valid API key."))
        assertFalse(GeminiModels.isThinkingError("User location is not supported for the API use."))
        assertFalse(GeminiModels.isThinkingError(""))
    }

    @Test
    fun namesThinkingOption_isStricterThanIsThinkingError() {
        // Only a message that names the option is trusted enough to be remembered before a retry works.
        assertTrue(GeminiModels.namesThinkingOption("Thinking level MINIMAL is not supported for this model."))
        assertTrue(GeminiModels.namesThinkingOption("Thinking budget is not supported for this model."))
        assertFalse(GeminiModels.namesThinkingOption("Request contains an invalid argument."))
        assertFalse(GeminiModels.namesThinkingOption("API key not valid. Please pass a valid API key."))
        assertFalse(GeminiModels.namesThinkingOption(""))
    }

    @Test
    fun learnedThinking_roundTripsThroughItsStringForm() {
        val map = mapOf("gemini-3.8-flash" to ThinkingMode.LOW, "gemini-9-new" to ThinkingMode.NONE, "x" to ThinkingMode.UNKNOWN)
        val s = GeminiModels.encodeLearned(map)
        assertEquals("gemini-3.8-flash=LOW;gemini-9-new=NONE", s)
        assertEquals(mapOf("gemini-3.8-flash" to ThinkingMode.LOW, "gemini-9-new" to ThinkingMode.NONE), GeminiModels.decodeLearned(s))
        assertTrue(GeminiModels.decodeLearned("").isEmpty())
        assertEquals(mapOf("a" to ThinkingMode.LOW), GeminiModels.decodeLearned("junk;b=WHAT;=LOW;a=LOW"))
    }

    @Test
    fun thinkingMemory_learnsOncePersistsAndRestores() {
        val saved = mutableListOf<String>()
        val mem = ThinkingMemory().also { it.onChange = { s -> saved += s } }
        mem.learn("gemini-9-new", ThinkingMode.LOW)
        mem.learn("gemini-9-new", ThinkingMode.LOW)       // no change, no write
        mem.learn("gemini-9-new", ThinkingMode.UNKNOWN)   // never stored
        assertEquals(listOf("gemini-9-new=LOW"), saved)
        assertEquals(ThinkingMode.LOW, GeminiModels.thinkingModeFor("gemini-9-new", mem.snapshot()))

        val fresh = ThinkingMemory()
        fresh.restore(mem.encode())
        assertEquals(mapOf("gemini-9-new" to ThinkingMode.LOW), fresh.snapshot())
    }

    // ------------------------------------------------------------ saved-model migration

    @Test
    fun migrateSavedModel_replacesBlankAndGemini2() {
        assertEquals("gemini-3-flash-preview", GeminiModels.migrateSavedModel("", true))
        assertEquals("gemini-3-flash-preview", GeminiModels.migrateSavedModel("  ", false))
        assertEquals("gemini-3-flash-preview", GeminiModels.migrateSavedModel("gemini-2.5-flash", true))
        assertEquals("gemini-3-flash-preview", GeminiModels.migrateSavedModel("gemini-2.0-flash", false))
    }

    @Test
    fun migrateSavedModel_oldDefaultIsReplacedOnlyOnce() {
        assertEquals("gemini-3-flash-preview", GeminiModels.migrateSavedModel("gemini-3.5-flash", false))
        // After the first save with this version it is a deliberate choice and stays.
        assertEquals("gemini-3.5-flash", GeminiModels.migrateSavedModel("gemini-3.5-flash", true))
    }

    @Test
    fun migrateSavedModel_firstDefaultFlashLatestIsReplacedOnlyOnce() {
        // The app's first default; the old settings screen saved it on every pause.
        assertEquals("gemini-3-flash-preview", GeminiModels.migrateSavedModel("gemini-flash-latest", false))
        assertEquals("gemini-flash-latest", GeminiModels.migrateSavedModel("gemini-flash-latest", true))
    }

    @Test
    fun migrateSavedModel_keepsOtherChoices() {
        assertEquals("gemini-3.6-flash", GeminiModels.migrateSavedModel("gemini-3.6-flash", false))
        assertEquals("gemma-4-31b-it", GeminiModels.migrateSavedModel("gemma-4-31b-it", true))
    }

    @Test
    fun resourcesAreTheRealLabData() {
        // Guard against someone replacing the fixtures with hand-made ones.
        assertTrue(realList.contains("gemini-4-argon"))
        assertNotNull(javaClass.classLoader!!.getResource("gemini/429_daily_gemini-3.8-flash.json"))
    }
}
