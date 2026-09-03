package io.github.rektide.batsignal.config

import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.github.rektide.batsignal.R
import io.github.rektide.batsignal.ble.AdvertiseConfig
import io.github.rektide.batsignal.data.AdvertiseConfigStore

/**
 * Preferences screen for the tunable advertising parameters
 * ([AdvertiseConfig]): TX power, advertise interval (preset or custom
 * milliseconds), secondary PHY, and the TX power header switch.
 *
 * Boring view layer: every preference persists into the shared "batsignal"
 * prefs via its declared key, and [io.github.rektide.batsignal.service.BeaconService]
 * does the reacting (fresh read on start; debounced restart while
 * broadcasting). The interval mode/value dance — entering a custom value
 * selects it automatically — is the only logic here, and it reuses
 * [AdvertiseConfig.resolveIntervalMillis] so the summaries agree with what
 * actually goes on air.
 */
class ConfigActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(android.R.id.content, ConfigFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class ConfigFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            wireIntervalPreferences()
        }

        /**
         * The interval is stored as a mode (preset millis or "custom") plus
         * the custom milliseconds text. The mode's summary shows
         * "Custom (N ms)" — the clamped value, matching what is advertised —
         * and saving a custom value auto-selects the custom mode.
         */
        private fun wireIntervalPreferences() {
            val mode = findPreference<ListPreference>(AdvertiseConfigStore.KEY_INTERVAL_MODE) ?: return
            val custom = findPreference<EditTextPreference>(AdvertiseConfigStore.KEY_INTERVAL_CUSTOM_MILLIS) ?: return

            custom.setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_NUMBER
            }
            custom.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                if (pref.text.isNullOrBlank()) {
                    getString(R.string.config_custom_interval_hint)
                } else {
                    val millis = AdvertiseConfig.resolveIntervalMillis(AdvertiseConfig.INTERVAL_CUSTOM_MODE, pref.text)
                    getString(R.string.config_custom_interval_summary, millis)
                }
            }
            mode.summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                if (pref.value == AdvertiseConfig.INTERVAL_CUSTOM_MODE) {
                    val millis = AdvertiseConfig.resolveIntervalMillis(AdvertiseConfig.INTERVAL_CUSTOM_MODE, custom.text)
                    getString(R.string.config_interval_custom_summary, millis)
                } else {
                    pref.entry
                }
            }
            // Persist the custom text ourselves first so the mode summary
            // (which reads it) renders the new value, then select the custom
            // mode; returning false stops the framework from persisting twice.
            custom.setOnPreferenceChangeListener { preference, newValue ->
                (newValue as? String)?.let { (preference as EditTextPreference).text = it }
                if (mode.value != AdvertiseConfig.INTERVAL_CUSTOM_MODE) {
                    mode.value = AdvertiseConfig.INTERVAL_CUSTOM_MODE
                }
                false
            }
        }
    }
}
