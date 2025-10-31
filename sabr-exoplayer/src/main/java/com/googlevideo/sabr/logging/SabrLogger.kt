package com.googlevideo.sabr.logging

/**
 * Lightweight logging contract so hosts can observe SABR library activity without hard-tying to
 * Android's logging APIs. The library ships with a no-op implementation by default; hosts are free
 * to supply their own bridge (e.g., to Timber or SLF4J).
 */
interface SabrLogger {

    fun log(level: Level, tag: String, message: String, error: Throwable? = null)

    enum class Level {
        VERBOSE,
        DEBUG,
        INFO,
        WARN,
        ERROR,
    }

    companion object {
        val NO_OP: SabrLogger = object : SabrLogger {
            override fun log(level: Level, tag: String, message: String, error: Throwable?) = Unit
        }
    }
}

inline fun SabrLogger.v(tag: String, message: () -> String) {
    log(SabrLogger.Level.VERBOSE, tag, message())
}

inline fun SabrLogger.d(tag: String, message: () -> String) {
    log(SabrLogger.Level.DEBUG, tag, message())
}

inline fun SabrLogger.i(tag: String, message: () -> String) {
    log(SabrLogger.Level.INFO, tag, message())
}

inline fun SabrLogger.w(tag: String, error: Throwable? = null, message: () -> String) {
    log(SabrLogger.Level.WARN, tag, message(), error)
}

inline fun SabrLogger.e(tag: String, error: Throwable? = null, message: () -> String) {
    log(SabrLogger.Level.ERROR, tag, message(), error)
}
