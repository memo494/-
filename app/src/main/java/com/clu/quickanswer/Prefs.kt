package com.clu.quickanswer

import android.content.Context

object Prefs {
    private const val NAME = "clu_prefs"
    const val DEFAULT_MODEL = GeminiModels.DEFAULT_ID
    const val DEFAULT_CLAUDE_MODEL = "claude-haiku-5-5"

    // Bumped when the meaning of the saved Gemini model changes (see GeminiModels.migrateSavedModel).
    private const val MODEL_VERSION = 3

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = cleanKey(sp(c).getString("api_key", "") ?: "")
    fun model(c: Context): String {
        val prefs = sp(c)
        val saved = cleanModel(prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL)
        val migrated = prefs.getInt("model_version", 0) >= MODEL_VERSION
        val m = GeminiModels.migrateSavedModel(saved, migrated)
        if (!migrated) {
            // Do the one-time replacement of the old default now, so a later deliberate choice sticks.
            prefs.edit().putString("model", m).putInt("model_version", MODEL_VERSION).apply()
        }
        return m
    }

    fun provider(c: Context): String =
        if (sp(c).getString("provider", "gemini") == "claude") "claude" else "gemini"
    fun claudeKey(c: Context): String = cleanKey(sp(c).getString("claude_key", "") ?: "")
    fun claudeModel(c: Context): String =
        cleanModel(sp(c).getString("claude_model", DEFAULT_CLAUDE_MODEL) ?: "").ifBlank { DEFAULT_CLAUDE_MODEL }

    /**
     * Where the answer panel is shown. Read each time a panel is shown, so a change made in the
     * settings screen applies to the running service without a restart.
     */
    fun panelMode(c: Context): PanelPlacement.Mode =
        PanelPlacement.Mode.fromKey(sp(c).getString("panel_position", PanelPlacement.Mode.TOP.key))

    fun savePanelMode(c: Context, mode: PanelPlacement.Mode) {
        sp(c).edit().putString("panel_position", mode.key).apply()
    }

    /**
     * Read the question on the device (OCR), answer from memory when it was seen before, and ask the
     * model with text only. Off: always send the screenshot, as before. Read at each answer.
     */
    fun smartReading(c: Context): Boolean = sp(c).getBoolean("smart_reading", true)

    /** Draw a green box around the right option on screen. Read at each answer. */
    fun highlightAnswer(c: Context): Boolean = sp(c).getBoolean("highlight_answer", true)

    /**
     * Watch the game's result screen after an answer and, when it colours a different option as
     * correct than the one remembered, fix the answer memory (see [com.clu.quickanswer.ocr.ResultReader]).
     * Read each time an answer is shown.
     */
    fun learnFromResults(c: Context): Boolean = sp(c).getBoolean("learn_from_results", true)

    fun saveLearnFromResults(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("learn_from_results", on).apply()
    }

    /** A short haptic tick when an answer is shown (not for errors). Read each time one is shown. */
    fun vibrateOnAnswer(c: Context): Boolean = sp(c).getBoolean("vibrate_on_answer", true)

    fun saveVibrateOnAnswer(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("vibrate_on_answer", on).apply()
    }

    /** Opt-in for bubble/A touches. A deliberate enabled volume shortcut has its own permission. */
    fun autoTouch(c: Context): Boolean = sp(c).getBoolean("auto_touch", false)

    fun saveAutoTouch(c: Context, enabled: Boolean) {
        sp(c).edit().putBoolean("auto_touch", enabled).apply()
    }

    /** Volume down solves and enters the answer (needs accessibility). Read again before each tap. */
    fun volumeKey(c: Context): Boolean = sp(c).getBoolean("volume_key", true)

    fun saveVolumeKey(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("volume_key", on).apply()
    }

    /**
     * The welcome dialog (see MainActivity) was dismissed: it is shown once, on the first launch,
     * and again after a rotation or a crash until the person has seen it.
     */
    fun welcomeShown(c: Context): Boolean = sp(c).getBoolean("welcome_shown", false)

    fun saveWelcomeShown(c: Context) {
        sp(c).edit().putBoolean("welcome_shown", true).apply()
    }

    fun saveReading(c: Context, smart: Boolean, highlight: Boolean) {
        sp(c).edit().putBoolean("smart_reading", smart).putBoolean("highlight_answer", highlight).apply()
    }

    fun saveClaude(c: Context, provider: String, key: String, model: String) {
        sp(c).edit().putString("provider", provider).putString("claude_key", cleanKey(key))
            .putString("claude_model", cleanModel(model)).apply()
    }

    fun save(c: Context, key: String, model: String) {
        sp(c).edit().putString("api_key", cleanKey(key)).putString("model", cleanModel(model))
            .putInt("model_version", MODEL_VERSION).apply()
    }

    /** Model ids from the last "refresh model list" (already filtered), empty if never refreshed. */
    fun modelList(c: Context): List<String> =
        (sp(c).getString("model_list", "") ?: "").split('\n').map { cleanModel(it) }.filter { it.isNotEmpty() }

    fun saveModelList(c: Context, ids: List<String>) {
        sp(c).edit().putString("model_list", ids.joinToString("\n")).apply()
    }

    /** Quota tracker state (see QuotaTracker.encode). */
    fun quotaState(c: Context): String = sp(c).getString("quota_state", "") ?: ""
    fun saveQuotaState(c: Context, state: String) {
        sp(c).edit().putString("quota_state", state).apply()
    }

    /** The [com.clu.quickanswer.ocr.BuiltinTemplates.VERSION] last merged into the template store, 0 if none. */
    fun builtinTemplatesVersion(c: Context): Int = sp(c).getInt("builtin_templates_version", 0)
    fun saveBuiltinTemplatesVersion(c: Context, version: Int) {
        sp(c).edit().putInt("builtin_templates_version", version).apply()
    }

    /** Thinking options learned per model (see ThinkingMemory.encode). */
    fun learnedThinking(c: Context): String = sp(c).getString("learned_thinking", "") ?: ""
    fun saveLearnedThinking(c: Context, state: String) {
        sp(c).edit().putString("learned_thinking", state).apply()
    }

    // Pasting into an RTL screen can carry invisible direction marks or spaces; any non-ASCII
    // character in an HTTP header makes the request throw IllegalArgumentException.
    // Newer keys look like "AQ.xxxx", so '.' must be kept.
    fun cleanKey(s: String) =
        s.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' }
    fun cleanModel(s: String) = s.trim().removePrefix("models/")
        .filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' }
}
