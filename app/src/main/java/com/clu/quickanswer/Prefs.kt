package com.clu.quickanswer

import android.content.Context

object Prefs {
    private const val NAME = "clu_prefs"
    const val DEFAULT_MODEL = "gemini-flash-latest"

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun apiKey(c: Context): String = sp(c).getString("api_key", "") ?: ""
    fun model(c: Context): String =
        (sp(c).getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }

    fun save(c: Context, key: String, model: String) {
        sp(c).edit().putString("api_key", key.trim()).putString("model", model.trim()).apply()
    }
}
