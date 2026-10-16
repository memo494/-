package com.clu.quickanswer

/**
 * Remembers which models ran out of free quota and until when (from the 429's retryDelay). Models in
 * one quota group are blocked together. Pure Kotlin: persistence goes through [encode]/[restore] and
 * [onChange], so tests need no Android.
 */
class QuotaTracker(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val groupOf: (String) -> String = GeminiModels::quotaGroup
) {
    private val until = HashMap<String, Long>()

    /**
     * Called with the new [encode]d state whenever the blocked set changes. It runs while the tracker's
     * lock is held, so two racing threads cannot hand their states to it out of order (a stale state
     * overwriting a newer one in Prefs); keep it quick and never call back into the tracker.
     */
    @Volatile var onChange: ((String) -> Unit)? = null

    /** Blocks [model]'s group for [delayMs] (60 s when the 429 gave no retryDelay). */
    fun block(model: String, delayMs: Long?) {
        val ms = (delayMs ?: DEFAULT_BLOCK_MS).coerceAtLeast(MIN_BLOCK_MS)
        synchronized(this) {
            val group = groupOf(model)
            val end = clock() + ms
            until[group] = maxOf(until[group] ?: 0L, end)
            onChange?.invoke(encodeLocked())
        }
    }

    fun isBlocked(model: String): Boolean = remainingMs(model) > 0

    fun remainingMs(model: String): Long = synchronized(this) {
        val end = until[groupOf(model)] ?: return 0L
        (end - clock()).coerceAtLeast(0L)
    }

    /** A model answered, so its quota is back; forget any block on its group. */
    fun clear(model: String) {
        synchronized(this) {
            if (until.remove(groupOf(model)) == null) return
            onChange?.invoke(encodeLocked())
        }
    }

    fun clearAll() {
        synchronized(this) {
            if (until.isEmpty()) return
            until.clear()
            onChange?.invoke("")
        }
    }

    fun encode(): String = synchronized(this) { encodeLocked() }

    /** Loads a state written by [encode]; expired entries are dropped. Does not call [onChange]. */
    fun restore(state: String) {
        synchronized(this) {
            until.clear()
            val now = clock()
            for (part in state.split(';')) {
                val i = part.lastIndexOf('=')
                if (i <= 0) continue
                val end = part.substring(i + 1).trim().toLongOrNull() ?: continue
                if (end > now) until[part.substring(0, i).trim()] = end
            }
        }
    }

    private fun encodeLocked(): String {
        val now = clock()
        until.values.removeAll { it <= now }
        return until.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }
    }

    companion object {
        const val DEFAULT_BLOCK_MS = 60_000L
        private const val MIN_BLOCK_MS = 1_000L
    }
}

/** Per-model thinking options found out the hard way (see [GeminiModels.nextThinkingMode]). */
class ThinkingMemory {
    private val learned = HashMap<String, ThinkingMode>()

    /** Called with the new [encode]d state whenever something is learned, under the lock (see [QuotaTracker.onChange]). */
    @Volatile var onChange: ((String) -> Unit)? = null

    fun snapshot(): Map<String, ThinkingMode> = synchronized(this) { HashMap(learned) }

    fun learn(model: String, mode: ThinkingMode) {
        if (mode == ThinkingMode.UNKNOWN) return
        synchronized(this) {
            if (learned[model] == mode) return
            learned[model] = mode
            onChange?.invoke(GeminiModels.encodeLearned(learned))
        }
    }

    fun encode(): String = synchronized(this) { GeminiModels.encodeLearned(learned) }

    /** Loads a state written by [encode]. Does not call [onChange]. */
    fun restore(state: String) {
        val map = GeminiModels.decodeLearned(state)
        synchronized(this) {
            learned.clear()
            learned.putAll(map)
        }
    }
}
