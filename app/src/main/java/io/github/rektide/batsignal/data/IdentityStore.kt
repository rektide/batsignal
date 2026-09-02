package io.github.rektide.batsignal.data

import android.content.Context

/**
 * Persists the last identity the user typed, so it survives app restarts.
 * SharedPreferences is plenty at this size; swap for DataStore if the state
 * ever grows structured.
 */
class IdentityStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): String = prefs.getString(KEY_IDENTITY, "").orEmpty()

    fun save(identity: String) {
        prefs.edit().putString(KEY_IDENTITY, identity).apply()
    }

    private companion object {
        const val PREFS_NAME = "batsignal"
        const val KEY_IDENTITY = "identity"
    }
}
