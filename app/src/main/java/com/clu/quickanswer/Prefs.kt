package com.clu.quickanswer

import android.content.Context

object Prefs {
    private const val NAME = "clu_prefs"
    const val DEFAULT_MODEL = "gemini-flash-latest"

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = cleanKey(sp(c).getString("api_key", "") ?: "")
    fun model(c: Context): String =
        cleanModel(sp(c).getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }

    fun save(c: Context, key: String, model: String) {
        sp(c).edit().putString("api_key", cleanKey(key)).putString("model", cleanModel(model)).apply()
    }

    // Pasting into an RTL screen can carry invisible direction marks or spaces; any non-ASCII
    // character in an HTTP header makes the request throw IllegalArgumentException.
    fun cleanKey(s: String) = s.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
    fun cleanModel(s: String) = s.trim().removePrefix("models/")
        .filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' }
}
