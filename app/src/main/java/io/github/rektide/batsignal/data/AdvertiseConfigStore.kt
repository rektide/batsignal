package io.github.rektide.batsignal.data

import android.content.Context
import android.content.SharedPreferences
import io.github.rektide.batsignal.ble.AdvertiseConfig

/**
 * Loads [AdvertiseConfig] from the shared "batsignal" prefs file (written by
 * the Config screen's preferences, which persist into the same file as
 * [IdentityStore]), and lets [io.github.rektide.batsignal.service.BeaconService]
 * watch for changes. Parsing and clamping live in [AdvertiseConfig] — pure
 * Kotlin, JVM-tested; this class is just the SharedPreferences glue.
 */
class AdvertiseConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(IdentityStore.PREFS_NAME, Context.MODE_PRIVATE)

    /** Read the current config; never writes, so fresh installs get defaults. */
    fun load(): AdvertiseConfig = AdvertiseConfig.fromStorage(
        txPower = prefs.getString(KEY_TX_POWER, null),
        intervalMode = prefs.getString(KEY_INTERVAL_MODE, null),
        customIntervalMillis = prefs.getString(KEY_INTERVAL_CUSTOM_MILLIS, null),
        secondaryPhy = prefs.getString(KEY_SECONDARY_PHY, null),
        includeTxPower = prefs.getBoolean(KEY_INCLUDE_TX_POWER, true),
    )

    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val KEY_TX_POWER = "tx_power"
        const val KEY_INTERVAL_MODE = "interval_mode"
        const val KEY_INTERVAL_CUSTOM_MILLIS = "interval_custom_millis"
        const val KEY_SECONDARY_PHY = "secondary_phy"
        const val KEY_INCLUDE_TX_POWER = "include_tx_power"

        /**
         * Keys whose changes are advertising-parameter changes. The service
         * filters its prefs listener through this set — the same file also
         * carries `identity` (saved per keystroke by the main screen) and
         * `legacy_companion`, which must not trigger config restarts.
         */
        val CONFIG_KEYS: Set<String> = setOf(
            KEY_TX_POWER,
            KEY_INTERVAL_MODE,
            KEY_INTERVAL_CUSTOM_MILLIS,
            KEY_SECONDARY_PHY,
            KEY_INCLUDE_TX_POWER,
        )
    }
}
