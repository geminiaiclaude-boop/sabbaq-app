package app.sabbaq.service

import android.content.Context
import app.sabbaq.core.VerifiedIdHints

/** Device-local hints only; no offer text, screenshots, IDs or network telemetry. */
internal class JeenyAcceptIdStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val hints = VerifiedIdHints()

    init {
        hints.replace(prefs.getStringSet(KEY, emptySet()).orEmpty())
    }

    fun snapshot(): Set<String> = hints.snapshot().toSet()

    fun learnVerified(viewId: String?): Boolean {
        val changed = hints.learn(viewId)
        if (changed) prefs.edit().putStringSet(KEY, hints.snapshot().toSet()).apply()
        return changed
    }

    private companion object {
        const val PREFS = "jeeny_accept_hints"
        const val KEY = "verified_view_ids_v1"
    }
}
