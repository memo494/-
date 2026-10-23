package com.clu.quickanswer

/** Tracks only package transitions, never window text or the accessibility view tree. */
internal class ForegroundWindowGuard(private val ownPackage: String) {
    private var externalPackage: String? = null

    /** True means the previous tap context is no longer known to be the same foreground app. */
    fun onWindow(packageName: String?): Boolean {
        if (packageName == ownPackage) return false
        if (packageName.isNullOrBlank()) {
            externalPackage = null
            return true
        }
        // The first event establishes a baseline, but cannot prove that an already-running
        // sequence belonged to that package. Later events inside the same game are harmless.
        val changed = externalPackage != packageName
        externalPackage = packageName
        return changed
    }

    fun reset() { externalPackage = null }
}
