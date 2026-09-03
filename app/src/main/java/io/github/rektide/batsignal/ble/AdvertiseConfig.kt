package io.github.rektide.batsignal.ble

/**
 * User-tunable advertising parameters for both batsignal advertising sets,
 * parsed from their persisted string forms by [fromStorage].
 *
 * Pure Kotlin on purpose — no `android.*` classes — so the parsing and
 * clamping are unit-testable on the JVM ([AdvertiseConfigTest]).
 * [BeaconAdvertiser] maps the symbolic values onto the Android constants
 * (`AdvertisingSetParameters.TX_POWER_*`, `BluetoothDevice.PHY_LE_*`) at the
 * single Android boundary.
 *
 * Defaults follow the transmit parameters of `PROTOCOL.md` §3 (medium TX
 * power, TX power header included, LE 1M secondary PHY) except the interval:
 * the app default is the calmer 2 s presence rate — §3's ~1000 ms stays the
 * protocol's example value, and per §3 "all tunable".
 *
 * TX power and interval apply to both sets; the secondary PHY and the TX
 * power header are extended-set-only (legacy mode ignores them by spec).
 */
data class AdvertiseConfig(
    val txPower: TxPower = TxPower.MEDIUM,
    val intervalMillis: Int = DEFAULT_INTERVAL_MILLIS,
    val secondaryPhy: SecondaryPhy = SecondaryPhy.LE_1M,
    val includeTxPower: Boolean = true,
) {

    /** TX power steps; names mirror `AdvertisingSetParameters.TX_POWER_*`. */
    enum class TxPower {
        ULTRA_LOW,
        LOW,
        MEDIUM,
        HIGH,
        ULTRA_HIGH,
        ;

        companion object {
            fun fromStorage(raw: String?): TxPower =
                entries.firstOrNull { it.name.lowercase() == raw } ?: MEDIUM
        }
    }

    /**
     * Secondary PHY of the extended set. The legacy companion is BT 4.x
     * shaped — the stack ignores PHY settings there entirely.
     */
    enum class SecondaryPhy {
        /** Baseline compatibility. */
        LE_1M,

        /** 2 Mbps, extended-set scanners only. */
        LE_2M,

        /** LE Coded S=2, longer range at lower rate. */
        LE_CODED,
        ;

        companion object {
            fun fromStorage(raw: String?): SecondaryPhy =
                entries.firstOrNull { it.name.lowercase() == raw } ?: LE_1M
        }
    }

    companion object {
        /**
         * App-level floor for the advertise interval. The stack's own
         * minimum (`AdvertisingSetParameters.INTERVAL_MIN`) is 160 ms; the
         * controller clamps whatever we hand it, so a lower app floor just
         * means "give me your fastest".
         */
        const val INTERVAL_MIN_MILLIS = 100

        /** Mirrors `AdvertisingSetParameters.INTERVAL_MAX`. */
        const val INTERVAL_MAX_MILLIS = 16_777_215

        const val DEFAULT_INTERVAL_MILLIS = 2_000

        /** [ListPreference] value marking the custom-milliseconds path. */
        const val INTERVAL_CUSTOM_MODE = "custom"

        /**
         * Resolve the interval preference's storage shape — a mode that is
         * either a preset millisecond string or [INTERVAL_CUSTOM_MODE],
         * plus the custom milliseconds text — into concrete milliseconds,
         * with the floor and ceiling applied. Unparseable custom input
         * falls back to [DEFAULT_INTERVAL_MILLIS].
         */
        fun resolveIntervalMillis(mode: String?, customMillis: String?): Int {
            if (mode == INTERVAL_CUSTOM_MODE) {
                return customMillis?.trim()?.toLongOrNull()?.let { clampIntervalMillis(it) }
                    ?: DEFAULT_INTERVAL_MILLIS
            }
            return mode?.toLongOrNull()?.let { clampIntervalMillis(it) } ?: DEFAULT_INTERVAL_MILLIS
        }

        fun clampIntervalMillis(millis: Long): Int =
            millis.coerceIn(INTERVAL_MIN_MILLIS.toLong(), INTERVAL_MAX_MILLIS.toLong()).toInt()

        /**
         * Parse the raw persisted values (as read from SharedPreferences —
         * `null` means never written, i.e. defaults apply) into a config.
         */
        fun fromStorage(
            txPower: String?,
            intervalMode: String?,
            customIntervalMillis: String?,
            secondaryPhy: String?,
            includeTxPower: Boolean,
        ): AdvertiseConfig = AdvertiseConfig(
            txPower = TxPower.fromStorage(txPower),
            intervalMillis = resolveIntervalMillis(intervalMode, customIntervalMillis),
            secondaryPhy = SecondaryPhy.fromStorage(secondaryPhy),
            includeTxPower = includeTxPower,
        )
    }
}
