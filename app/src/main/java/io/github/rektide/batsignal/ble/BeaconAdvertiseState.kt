package io.github.rektide.batsignal.ble

/**
 * What the beacon advertiser is doing right now. Written by
 * [BeaconAdvertiser] (via the service), surfaced to the UI through
 * `service.BeaconStatusHolder`.
 */
sealed interface BeaconAdvertiseState {

    /** Not advertising and not trying to. */
    data object Stopped : BeaconAdvertiseState

    /** Advertising requested; waiting for the stack's start callbacks. */
    data class Starting(val identity: String) : BeaconAdvertiseState

    /**
     * At least one advertising set is on air.
     *
     * @param identity the identity the extended frame carries
     * @param extended extended (non-legacy) identity frame is on air
     * @param legacy legacy companion marker frame is on air
     * @param note non-fatal detail, e.g. why the other frame isn't running
     */
    data class Running(
        val identity: String,
        val extended: Boolean,
        val legacy: Boolean,
        val note: String? = null,
    ) : BeaconAdvertiseState

    /** Wanted to advertise but nothing is on air; [reason] is human-readable. */
    data class Failed(val identity: String?, val reason: String) : BeaconAdvertiseState
}
