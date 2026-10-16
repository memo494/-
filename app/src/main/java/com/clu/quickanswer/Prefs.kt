package com.clu.quickanswer

import android.content.Context

object Prefs {
    private const val NAME = "clu_prefs"
    const val DEFAULT_MODEL = "gemini-3.5-flash"
    const val DEFAULT_CLAUDE_MODEL = "claude-opus-5-5"

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = cleanKey(sp(c).getString("api_key", "") ?: "")
    fun model(c: Context): String {
        val m = cleanModel(sp(c).getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL)
        // Gemini 2.x is closed to new API users (404), so treat old saved names as the default.
        return if (m.isBlank() || m.startsWith("gemini-2.")) DEFAULT_MODEL else m
    }

    fun provider(c: Context): String =
        if (sp(c).getString("provider", "gemini") == "claude") "claude" else "gemini"
    fun claudeKey(c: Context): String = cleanKey(sp(c).getString("claude_key", "") ?: "")
    fun claudeModel(c: Context): String =
        cleanModel(sp(c).getString("claude_model", DEFAULT_CLAUDE_MODEL) ?: "").ifBlank { DEFAULT_CLAUDE_MODEL }

    fun saveClaude(c: Context, provider: String, key: String, model: String) {
        sp(c).edit().putString("provider", provider).putString("claude_key", cleanKey(key))
            .putString("claude_model", cleanModel(model)).apply()
    }

    fun save(c: Context, key: String, model: String) {
        sp(c).edit().putString("api_key", cleanKey(key)).putString("model", cleanModel(model)).apply()
    }

    // Pasting into an RTL screen can carry invisible direction marks or spaces; any non-ASCII
    // character in an HTTP header makes the request throw IllegalArgumentException.
    // Newer keys look like "AQ.xxxx", so '.' must be kept.
    fun cleanKey(s: String) =
        s.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' }
    fun cleanModel(s: String) = s.trim().removePrefix("models/")
        .filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' }
}
