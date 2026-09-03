package io.github.rektide.batsignal.ble

import io.github.rektide.batsignal.ble.AdvertiseConfig.SecondaryPhy
import io.github.rektide.batsignal.ble.AdvertiseConfig.TxPower
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing/clamping checks for the advertising config model. Pure JVM — no
 * Android runtime needed because [AdvertiseConfig] touches no android.*
 * classes (the symbolic-to-constant mapping happens in [BeaconAdvertiser]).
 */
class AdvertiseConfigTest {

    @Test
    fun absentValuesFallBackToDefaults() {
        val config = AdvertiseConfig.fromStorage(
            txPower = null,
            intervalMode = null,
            customIntervalMillis = null,
            secondaryPhy = null,
            includeTxPower = true,
        )
        assertEquals(TxPower.MEDIUM, config.txPower)
        assertEquals(AdvertiseConfig.DEFAULT_INTERVAL_MILLIS, config.intervalMillis)
        assertEquals(2_000, config.intervalMillis)
        assertEquals(SecondaryPhy.LE_1M, config.secondaryPhy)
        assertTrue(config.includeTxPower)
    }

    @Test
    fun txPowerParsingCoversEveryStep() {
        for (power in TxPower.entries) {
            assertEquals(power, TxPower.fromStorage(power.name.lowercase()))
        }
    }

    @Test
    fun unknownTxPowerFallsBackToMedium() {
        assertEquals(TxPower.MEDIUM, TxPower.fromStorage("loud"))
        assertEquals(TxPower.MEDIUM, TxPower.fromStorage(""))
        assertEquals(TxPower.MEDIUM, TxPower.fromStorage(null))
    }

    @Test
    fun intervalPresetsResolveToTheirMillis() {
        assertEquals(500, AdvertiseConfig.resolveIntervalMillis("500", null))
        assertEquals(2_000, AdvertiseConfig.resolveIntervalMillis("2000", null))
        assertEquals(10_000, AdvertiseConfig.resolveIntervalMillis("10000", null))
    }

    @Test
    fun customModeUsesTheCustomValue() {
        assertEquals(
            1_234,
            AdvertiseConfig.resolveIntervalMillis(
                AdvertiseConfig.INTERVAL_CUSTOM_MODE,
                "1234",
            ),
        )
    }

    @Test
    fun customIntervalBelowTheFloorIsClampedUp() {
        assertEquals(
            AdvertiseConfig.INTERVAL_MIN_MILLIS,
            AdvertiseConfig.resolveIntervalMillis(AdvertiseConfig.INTERVAL_CUSTOM_MODE, "99"),
        )
        assertEquals(
            AdvertiseConfig.INTERVAL_MIN_MILLIS,
            AdvertiseConfig.resolveIntervalMillis(AdvertiseConfig.INTERVAL_CUSTOM_MODE, "0"),
        )
        assertEquals(
            AdvertiseConfig.INTERVAL_MIN_MILLIS,
            AdvertiseConfig.resolveIntervalMillis(AdvertiseConfig.INTERVAL_CUSTOM_MODE, "-5000"),
        )
    }

    @Test
    fun intervalAtAndAboveTheFloorPassesThrough() {
        assertEquals(100, AdvertiseConfig.resolveIntervalMillis(AdvertiseConfig.INTERVAL_CUSTOM_MODE, "100"))
        assertEquals(250, AdvertiseConfig.resolveIntervalMillis(AdvertiseConfig.INTERVAL_CUSTOM_MODE, "250"))
    }

    @Test
    fun customIntervalAboveTheStackCeilingIsClampedDown() {
        assertEquals(
            AdvertiseConfig.INTERVAL_MAX_MILLIS,
            AdvertiseConfig.resolveIntervalMillis(AdvertiseConfig.INTERVAL_CUSTOM_MODE, "999999999"),
        )
    }

    @Test
    fun unparseableCustomIntervalFallsBackToDefault() {
        val fallback = AdvertiseConfig.DEFAULT_INTERVAL_MILLIS
        assertEquals(fallback, AdvertiseConfig.resolveIntervalMillis(AdvertiseConfig.INTERVAL_CUSTOM_MODE, null))
        assertEquals(fallback, AdvertiseConfig.resolveIntervalMillis(AdvertiseConfig.INTERVAL_CUSTOM_MODE, ""))
        assertEquals(fallback, AdvertiseConfig.resolveIntervalMillis(AdvertiseConfig.INTERVAL_CUSTOM_MODE, "soon"))
        // Beyond Long range: parse fails, not overflow-crash.
        assertEquals(fallback, AdvertiseConfig.resolveIntervalMillis(AdvertiseConfig.INTERVAL_CUSTOM_MODE, "9".repeat(30)))
    }

    @Test
    fun unknownIntervalModeFallsBackToDefault() {
        assertEquals(
            AdvertiseConfig.DEFAULT_INTERVAL_MILLIS,
            AdvertiseConfig.resolveIntervalMillis("whenever", null),
        )
        assertEquals(
            AdvertiseConfig.DEFAULT_INTERVAL_MILLIS,
            AdvertiseConfig.resolveIntervalMillis(null, null),
        )
    }

    @Test
    fun customValueOnlyCountsInCustomMode() {
        // A preset mode wins over stale custom text.
        assertEquals(500, AdvertiseConfig.resolveIntervalMillis("500", "999999"))
    }

    @Test
    fun phyParsingCoversEveryStep() {
        assertEquals(SecondaryPhy.LE_1M, SecondaryPhy.fromStorage("le_1m"))
        assertEquals(SecondaryPhy.LE_2M, SecondaryPhy.fromStorage("le_2m"))
        assertEquals(SecondaryPhy.LE_CODED, SecondaryPhy.fromStorage("le_coded"))
    }

    @Test
    fun unknownPhyFallsBackToLe1M() {
        assertEquals(SecondaryPhy.LE_1M, SecondaryPhy.fromStorage("5g"))
        assertEquals(SecondaryPhy.LE_1M, SecondaryPhy.fromStorage(""))
        assertEquals(SecondaryPhy.LE_1M, SecondaryPhy.fromStorage(null))
    }

    @Test
    fun includeTxPowerPassesThrough() {
        val config = AdvertiseConfig.fromStorage(null, null, null, null, includeTxPower = false)
        assertFalse(config.includeTxPower)
    }

    @Test
    fun fromStorageComposesTheParsedPieces() {
        val config = AdvertiseConfig.fromStorage(
            txPower = "ultra_high",
            intervalMode = AdvertiseConfig.INTERVAL_CUSTOM_MODE,
            customIntervalMillis = "50", // below floor
            secondaryPhy = "le_coded",
            includeTxPower = false,
        )
        assertEquals(TxPower.ULTRA_HIGH, config.txPower)
        assertEquals(AdvertiseConfig.INTERVAL_MIN_MILLIS, config.intervalMillis)
        assertEquals(SecondaryPhy.LE_CODED, config.secondaryPhy)
        assertFalse(config.includeTxPower)
    }
}
