package com.googlevideo.sabr.logging

import android.util.Log

/**
 * Simple Android bridge that routes SABR logs through `android.util.Log`.
 *
 * Hosts can supply this logger when they want SABR internals to appear in logcat, or provide their
 * own implementation for production telemetry.
 */
class AndroidSabrLogger(
    private val minLevel: SabrLogger.Level = SabrLogger.Level.INFO,
) : SabrLogger {

    override fun log(level: SabrLogger.Level, tag: String, message: String, error: Throwable?) {
        if (level.ordinal < minLevel.ordinal) return
        when (level) {
            SabrLogger.Level.VERBOSE -> Log.v(tag, message, error)
            SabrLogger.Level.DEBUG -> Log.d(tag, message, error)
            SabrLogger.Level.INFO -> Log.i(tag, message, error)
            SabrLogger.Level.WARN -> Log.w(tag, message, error)
            SabrLogger.Level.ERROR -> Log.e(tag, message, error)
        }
    }
}
