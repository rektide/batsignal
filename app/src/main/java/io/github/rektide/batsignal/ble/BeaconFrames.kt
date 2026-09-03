package io.github.rektide.batsignal.ble

import java.util.UUID

/**
 * batsignal wire-format assembly — the pure, Android-free half of the
 * advertiser, per PROTOCOL.md at the repo root. Kept free of
 * `android.*` imports so the byte layout is unit-testable on the JVM.
 *
 * Two Service Data payloads ride under the same marker UUID:
 *
 *  * Extended frame (identity): the identity string as raw UTF-8. No NUL, no
 *    padding — the AD structure's length field delimits it.
 *  * Legacy companion frame: `"batsignal" + 0x01`, sized with the stack-added
 *    Flags AD structure to fill the 31-byte legacy budget exactly.
 */
object BeaconFrames {

    /** AD structure length+type header bytes for Service Data (AD type 0x21). */
    const val SERVICE_DATA_HEADER_BYTES = 2

    /** Length of the 128-bit marker UUID in bytes. */
    const val UUID_BYTES = 16

    /** Flags AD structure (type 0x01, value 0x06) the stack adds in legacy mode. */
    const val LEGACY_FLAGS_BYTES = 3

    /** Total legacy (BT 4.x) advertising budget for all AD structures combined. */
    const val LEGACY_FRAME_BUDGET_BYTES = 31

    /**
     * The batsignal marker: a 128-bit service UUID whose bytes are the UTF-8
     * encoding of [MARKER_GLYPHS] — `@` ("at", as in handles) and ☎
     * (U+260E TELEPHONE, as in signal). Candidate D from the format doc: the
     * alignment lands the RFC 9562 version nibble on 4 and the variant bits
     * on 10, so UUID-aware tooling sees an unremarkable random v4 UUID while
     * the bytes remain pure glyphs, decodable in any UTF-8 terminal.
     */
    const val MARKER_UUID_STRING = "4040e298-8e40-40e2-988e-4040e2988e40"

    /** The glyphs whose UTF-8 bytes spell [MARKER_UUID_STRING]. */
    const val MARKER_GLYPHS = "@@☎@@☎@@☎@"

    /** Format version byte at the tail of the legacy companion payload. */
    const val LEGACY_COMPANION_VERSION: Int = 0x01

    private const val LEGACY_COMPANION_TAG = "batsignal"

    /** The marker UUID as a parsed [UUID]. */
    fun markerUuid(): UUID = UUID.fromString(MARKER_UUID_STRING)

    /** Marker UUID as big-endian bytes — the order it rides in the AD structure. */
    fun markerUuidBytes(): ByteArray = uuidToBytes(markerUuid())

    /**
     * Service Data payload for the extended (identity) frame: the identity
     * string as raw UTF-8. Self-describing text (`did:plc:…`, `did:web:…`,
     * `at://…`, bare handle); no NUL, no padding, no length prefix.
     */
    fun identityServiceData(identity: String): ByteArray {
        require(identity.isNotEmpty()) { "identity must not be empty" }
        val bytes = identity.toByteArray(Charsets.UTF_8)
        require(0.toByte() !in bytes) { "identity must not contain NUL bytes" }
        return bytes
    }

    /**
     * Service Data payload for the legacy companion frame: `"batsignal"` plus
     * a one-byte format version. 10 bytes — together with the stack-added
     * Flags (3 B), the 0x21 header (2 B) and the marker UUID (16 B) it fills
     * the 31-byte legacy budget exactly. Says *what* is nearby, never *who*.
     */
    fun legacyCompanionServiceData(): ByteArray =
        LEGACY_COMPANION_TAG.toByteArray(Charsets.UTF_8) + LEGACY_COMPANION_VERSION.toByte()

    /**
     * Total on-air AD bytes the legacy companion frame consumes (Flags +
     * Service Data structure). Must stay within [LEGACY_FRAME_BUDGET_BYTES].
     */
    fun legacyCompanionFrameBytes(): Int =
        LEGACY_FLAGS_BYTES + SERVICE_DATA_HEADER_BYTES + UUID_BYTES + legacyCompanionServiceData().size

    /**
     * How many identity bytes fit in the extended frame for a controller
     * reporting `maxAdvertisingDataLength` (from
     * `BluetoothAdapter.getLeMaximumAdvertisingDataLength()`). The format doc
     * budgets this conservatively as max − ~19.
     */
    fun identityByteBudget(maxAdvertisingDataLength: Int): Int =
        maxAdvertisingDataLength - SERVICE_DATA_HEADER_BYTES - UUID_BYTES

    /** UUID → 16 big-endian bytes (network order). */
    fun uuidToBytes(uuid: UUID): ByteArray {
        val bytes = ByteArray(UUID_BYTES)
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        for (i in 0 until 8) {
            bytes[i] = (msb ushr (8 * (7 - i))).toByte()
            bytes[8 + i] = (lsb ushr (8 * (7 - i))).toByte()
        }
        return bytes
    }
}
