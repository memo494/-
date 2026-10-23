package com.clu.quickanswer

import android.content.Context
import com.clu.quickanswer.ocr.BuiltinTemplates
import com.clu.quickanswer.ocr.LayoutTemplates
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The process-wide [LayoutTemplates], kept in a file in the app's private storage the same way as
 * [AnswerStore]: one background thread loads and saves, a burst of changes is written once, and
 * the file is replaced atomically.
 */
object TemplateStore {

    val templates = LayoutTemplates()

    private const val FILE_NAME = "layout_templates.json"
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "template-store").apply { isDaemon = true } }
    private val saveQueued = AtomicBoolean(false)
    @Volatile private var file: File? = null

    /** Loads the file once (in the background) and saves after every change from then on. */
    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        file = f
        io.execute {
            try {
                if (f.exists()) templates.restore(f.readText(Charsets.UTF_8))
            } catch (_: Throwable) {
                // An unreadable file means starting empty; it is overwritten on the next save.
            }
            if (mergeBuiltins(context.applicationContext)) scheduleSave()
            templates.onChange = { scheduleSave() }
        }
    }

    /**
     * Adds the templates the app ships with ([BuiltinTemplates]) once per version of the file. True
     * when the store changed. A broken or missing asset is skipped and tried again next time.
     */
    private fun mergeBuiltins(app: Context): Boolean {
        return try {
            if (Prefs.builtinTemplatesVersion(app) >= BuiltinTemplates.VERSION) return false
            val json = app.assets.open(BuiltinTemplates.ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
            val r = templates.merge(json) ?: return false
            Prefs.saveBuiltinTemplatesVersion(app, BuiltinTemplates.VERSION)
            r.added > 0
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Forgets every learned template and puts the built-in ones back: what "مسح القوالب" does.
     * Call on [runAfterLoad]'s thread.
     */
    fun resetToBuiltins(context: Context) {
        templates.clear()
        Prefs.saveBuiltinTemplatesVersion(context.applicationContext, 0)
        mergeBuiltins(context.applicationContext)
    }

    /** Runs [block] on the store's thread, after the file has been loaded. */
    fun runAfterLoad(block: () -> Unit) {
        io.execute(block)
    }

    private fun scheduleSave() {
        if (!saveQueued.compareAndSet(false, true)) return
        io.execute {
            saveQueued.set(false)
            val f = file ?: return@execute
            try {
                val tmp = File(f.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(templates.encode(), Charsets.UTF_8)
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            } catch (_: Exception) {
                // Try again on the next change.
            }
        }
    }
}
