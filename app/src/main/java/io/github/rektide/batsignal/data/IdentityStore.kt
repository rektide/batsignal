package io.github.rektide.batsignal.data

import android.content.Context

/**
 * Persists the last identity the user typed and their legacy-companion
 * preference, so both survive app restarts. SharedPreferences is plenty at
 * this size; swap for DataStore if the state ever grows structured.
 */
class IdentityStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): String = prefs.getString(KEY_IDENTITY, "").orEmpty()

    fun save(identity: String) {
        prefs.edit().putString(KEY_IDENTITY, identity).apply()
    }

    /** Legacy companion default is on: maximum scanner visibility. */
    fun loadLegacyCompanion(): Boolean = prefs.getBoolean(KEY_LEGACY_COMPANION, true)

    fun saveLegacyCompanion(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LEGACY_COMPANION, enabled).apply()
    }

    companion object {
        /** Shared with [AdvertiseConfigStore] — one prefs file per app. */
        const val PREFS_NAME = "batsignal"

        private const val KEY_IDENTITY = "identity"
        private const val KEY_LEGACY_COMPANION = "legacy_companion"
    }
}
