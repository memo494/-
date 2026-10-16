package com.clu.quickanswer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide Gemini state shared by the settings screen and the overlay service: which models are
 * out of quota and which thinking options each model accepts. Loaded from [Prefs] by [init] and
 * written back whenever it changes. It also owns the "refresh model list" fetch, so a screen
 * rotation (which recreates the activity) neither loses the result nor keeps the old activity alive.
 */
object GeminiRuntime {
    val quota = QuotaTracker()
    val thinking = ThinkingMemory()

    private var ready = false

    @Synchronized
    fun init(context: Context) {
        if (ready) return
        val app = context.applicationContext
        quota.restore(Prefs.quotaState(app))
        thinking.restore(Prefs.learnedThinking(app))
        quota.onChange = { Prefs.saveQuotaState(app, it) }
        thinking.onChange = { Prefs.saveLearnedThinking(app, it) }
        ready = true
    }

    // ------------------------------------------------------------ model list refresh

    private val mainHandler = Handler(Looper.getMainLooper())

    // Touched on the main thread only.
    private val refreshListeners = CopyOnWriteArraySet<Runnable>()

    /** True while a refresh is running. Main thread only. */
    var refreshingModels = false
        private set

    /** [listener] runs on the main thread whenever a refresh finishes. Remove it when the screen pauses. */
    fun addRefreshListener(listener: Runnable) {
        refreshListeners.add(listener)
    }

    fun removeRefreshListener(listener: Runnable) {
        refreshListeners.remove(listener)
    }

    /**
     * Fetches the models [apiKey] can use on a background thread, saves them in [Prefs], shows a Toast
     * with the outcome and tells the listeners. Call on the main thread. Returns false (and does
     * nothing) when a refresh is already running.
     */
    fun refreshModelList(context: Context, apiKey: String): Boolean {
        if (refreshingModels) return false
        refreshingModels = true
        val app = context.applicationContext
        Thread({
            val result = GeminiClient.fetchModelIds(apiKey)
            val ids = result.ids
            if (ids != null && ids.isNotEmpty()) Prefs.saveModelList(app, ids)
            mainHandler.post {
                refreshingModels = false
                val message = when {
                    ids == null -> "تعذر تحديث القائمة: ${result.error ?: ""}"
                    ids.isEmpty() -> "لم يرجع Google أي نموذج مناسب"
                    else -> "تم تحديث القائمة: ${ids.size} نموذجًا"
                }
                Toast.makeText(
                    app,
                    message,
                    if (ids != null && ids.isNotEmpty()) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                ).show()
                refreshListeners.forEach { it.run() }
            }
        }, "gemini-models").start()
        return true
    }
}
