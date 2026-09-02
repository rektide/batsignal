package io.github.rektide.batsignal.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format checks against the fixed bytes in
 * `design/beacon-format/beacon-format.glm53.md`. Pure JVM — no Android
 * runtime needed because [BeaconFrames] touches no android.* classes.
 */
class BeaconFramesTest {

    @Test
    fun markerUuidBytesSpellTheGlyphString() {
        val glyphs = BeaconFrames.MARKER_GLYPHS.toByteArray(Charsets.UTF_8)
        assertEquals(16, glyphs.size)
        assertArrayEquals(glyphs, BeaconFrames.markerUuidBytes())
    }

    @Test
    fun markerUuidBytesMatchTheFixedWireBytes() {
        val expected = byteArrayOf(
            0x40, 0x40, 0xE2.toByte(), 0x98.toByte(), 0x8E.toByte(),
            0x40, 0x40, 0xE2.toByte(), 0x98.toByte(), 0x8E.toByte(),
            0x40, 0x40, 0xE2.toByte(), 0x98.toByte(), 0x8E.toByte(), 0x40,
        )
        assertArrayEquals(expected, BeaconFrames.markerUuidBytes())
    }

    @Test
    fun markerUuidIsWellFormedRandomShaped() {
        // RFC 9562 cosmetics from the format doc: version nibble 4 (byte 6
        // high half) and variant bits 10 (byte 8 top bits).
        val bytes = BeaconFrames.markerUuidBytes()
        assertEquals(0x4, (bytes[6].toInt() shr 4) and 0xF)
        assertEquals(0x2, (bytes[8].toInt() shr 6) and 0x3)
    }

    @Test
    fun legacyCompanionPayloadIsTagPlusVersionByte() {
        assertArrayEquals(
            "batsignal".toByteArray(Charsets.UTF_8) + byteArrayOf(0x01),
            BeaconFrames.legacyCompanionServiceData(),
        )
    }

    @Test
    fun legacyCompanionFrameFillsTheLegacyBudgetExactly() {
        assertEquals(31, BeaconFrames.legacyCompanionFrameBytes())
        assertTrue(BeaconFrames.legacyCompanionFrameBytes() <= BeaconFrames.LEGACY_FRAME_BUDGET_BYTES)
    }

    @Test
    fun identityPayloadIsTheIdentityUtf8() {
        val identity = "did:plc:abcdefghij123456789012"
        assertArrayEquals(identity.toByteArray(Charsets.UTF_8), BeaconFrames.identityServiceData(identity))
    }

    @Test
    fun identityPayloadRejectsEmbeddedNul() {
        assertThrows(IllegalArgumentException::class.java) {
            BeaconFrames.identityServiceData("did:\u0000plc:x")
        }
    }

    @Test
    fun identityPayloadRejectsEmptyString() {
        assertThrows(IllegalArgumentException::class.java) {
            BeaconFrames.identityServiceData("")
        }
    }

    @Test
    fun typicalExtendedBudgetCarriesAFullDid() {
        val did = "did:plc:" + "a".repeat(24)
        assertTrue(did.toByteArray(Charsets.UTF_8).size <= BeaconFrames.identityByteBudget(1650))
        // The format doc budgets this conservatively as max − ~19.
        assertTrue(BeaconFrames.identityByteBudget(1650) >= 1650 - 19)
    }
}
