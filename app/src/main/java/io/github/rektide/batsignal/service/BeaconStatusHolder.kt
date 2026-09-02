package io.github.rektide.batsignal.service

import io.github.rektide.batsignal.ble.BeaconAdvertiseState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide view of what the beacon is doing right now. Written by
 * [BeaconService] from advertiser callbacks and collected by the Compose UI,
 * so the on-screen status reflects the real on-air state instead of the
 * "service was started" assumption. Plain singleton on purpose: the flow and
 * the service live in one process, and the service resets it to
 * [BeaconAdvertiseState.Stopped] in `onDestroy`.
 */
object BeaconStatusHolder {

    private val _status = MutableStateFlow<BeaconAdvertiseState>(BeaconAdvertiseState.Stopped)

    val status: StateFlow<BeaconAdvertiseState> = _status.asStateFlow()

    fun update(status: BeaconAdvertiseState) {
        _status.value = status
    }
}
