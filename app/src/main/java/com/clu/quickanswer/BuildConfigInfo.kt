package com.clu.quickanswer

import android.content.Context

/** The installed version name (e.g. "1.0.1203-beta"), for the diagnostic log; "?" if unknown. */
object BuildConfigInfo {
    fun version(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
    } catch (_: Throwable) {
        "?"
    }
}
