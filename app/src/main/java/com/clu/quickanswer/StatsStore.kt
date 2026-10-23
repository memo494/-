package com.clu.quickanswer

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The process-wide [AnswerStats], kept in a file in the app's private storage the same way as
 * [AnswerStore] and [TemplateStore]: one background thread loads and saves, a burst of changes is
 * written once, and the file is replaced atomically.
 */
object StatsStore {

    val stats = AnswerStats()

    private const val FILE_NAME = "answer_stats.json"
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "stats-store").apply { isDaemon = true } }
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
                if (f.exists()) stats.restore(f.readText(Charsets.UTF_8))
            } catch (_: Throwable) {
                // An unreadable file means starting empty; it is overwritten on the next save.
            }
            stats.onChange = { scheduleSave() }
        }
    }

    /** Today in the phone's time zone, as [AnswerStats] wants it ("yyyy-MM-dd"). */
    fun today(): String = LocalDate.now().toString()

    /**
     * Counts one question. Runs on the store's thread, so it always comes after the load: a record
     * made before the file was read would otherwise be wiped by it. Never blocks the caller.
     */
    fun record(source: AnswerSource, millis: Long) {
        val day = today()
        io.execute {
            try {
                stats.record(source, millis, day)
            } catch (_: Throwable) {
                // Statistics are never worth a crash.
            }
        }
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
                tmp.writeText(stats.encode(), Charsets.UTF_8)
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
