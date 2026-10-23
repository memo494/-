package com.clu.quickanswer

/** Where an answer came from (or [FAILED]); see [AnswerStats]. */
enum class AnswerSource { MEMORY, CALCULATOR, TEXT, PICTURE, IMAGE, FAILED }

/** Skeleton: the tests in AnswerStatsTest are the contract, the implementation follows. */
class AnswerStats {

    class Summary(val count: Map<AnswerSource, Int>, val averageMillis: Map<AnswerSource, Long>) {
        val total: Int get() = TODO()
    }

    @Volatile var onChange: (() -> Unit)? = null

    fun record(source: AnswerSource, millis: Long, day: String): Unit = TODO()
    fun today(day: String): Summary = TODO()
    fun allTime(): Summary = TODO()
    fun encode(): String = TODO()
    fun restore(json: String): Boolean = TODO()
    fun clear(): Unit = TODO()

    companion object {
        fun describe(today: Summary, all: Summary): String = TODO()
    }
}
