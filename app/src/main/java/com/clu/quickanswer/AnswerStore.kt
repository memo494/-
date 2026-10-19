package com.clu.quickanswer

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The process-wide [AnswerMemory], kept in a file in the app's private storage. Loading and saving
 * happen on one background thread; a burst of changes is written once. The file is replaced
 * atomically (write a temporary file, then rename), so a crash mid-write never loses what was saved.
 */
object AnswerStore {

    val memory = AnswerMemory()

    private const val FILE_NAME = "answer_memory.json"
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "answer-store").apply { isDaemon = true } }
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
                if (f.exists()) memory.restore(f.readText(Charsets.UTF_8))
            } catch (_: Throwable) {
                // An unreadable file means starting empty; it is overwritten on the next save.
            }
            memory.onChange = { scheduleSave() }
        }
    }

    /** Runs [block] on the store's thread, after the file has been loaded. */
    fun runAfterLoad(block: () -> Unit) {
        io.execute(block)
    }

    /** The whole memory as JSON, for exporting to a file. Call on [runAfterLoad]'s thread. */
    fun exportJson(): String = memory.encode()

    private fun scheduleSave() {
        if (!saveQueued.compareAndSet(false, true)) return
        io.execute {
            saveQueued.set(false)
            val f = file ?: return@execute
            try {
                val tmp = File(f.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(memory.encode(), Charsets.UTF_8)
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
