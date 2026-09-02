package io.github.rektide.batsignal.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Owns the two batsignal advertising sets and folds their asynchronous
 * (binder-thread) callbacks into a single [BeaconAdvertiseState] stream.
 *
 *  * **Extended set** — `setLegacyMode(false)`, non-connectable,
 *    non-scannable, Service Data under [BeaconFrames.MARKER_UUID_STRING]
 *    carrying the identity as UTF-8 ([BeaconFrames.identityServiceData]).
 *    Needs a BT5 controller with extended advertising support; invisible to
 *    pure-legacy scanners.
 *  * **Legacy companion set** — legacy mode, Service Data carrying only
 *    [BeaconFrames.legacyCompanionServiceData] so every BT 4.x+ scanner sees
 *    that something batsignal-ish is nearby. The Flags AD structure (0x06)
 *    is added by the stack itself.
 *
 * Degrades instead of throwing: no/off adapter, a device that cannot
 * advertise, or missing extended-advertising support become
 * [BeaconAdvertiseState.Failed] or a legacy-only
 * [BeaconAdvertiseState.Running]. Bluetooth toggled off mid-broadcast tears
 * the sets down cleanly and the beacon resumes when it comes back (receiving
 * `ACTION_STATE_CHANGED` needs `BLUETOOTH_CONNECT` on API 31+, which the
 * permission flow requests; without it the last state simply sticks).
 *
 * [onState] is invoked under the advertiser's lock, usually on a binder
 * thread. It must be cheap and must not call back into this class.
 *
 * Wire format: [`PROTOCOL.md`](../../../../../PROTOCOL.md).
 */
class BeaconAdvertiser(
    context: Context,
    private val onState: (BeaconAdvertiseState) -> Unit,
) {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private val lock = Any()

    private enum class SetKind(val label: String) {
        EXTENDED("extended identity frame"),
        LEGACY_COMPANION("legacy companion frame"),
    }

    /** Identity we should be broadcasting; null means stopped by intent. */
    private var desiredIdentity: String? = null

    /** Start callbacks still outstanding for the current attempt. */
    private var pendingSets = 0
    private var extendedRunning = false
    private var legacyRunning = false

    /** Failures of the current attempt, insertion-ordered for headline picking. */
    private val setFailures = LinkedHashMap<SetKind, String>()

    /** Live callbacks per kind; cleared on teardown/restart. */
    private val callbacks = LinkedHashMap<SetKind, SetCallback>()

    private var released = false
    private var lastEmitted: BeaconAdvertiseState? = null

    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF -> onAdapterOff()
                BluetoothAdapter.STATE_ON -> onAdapterOn()
            }
        }
    }

    init {
        // System broadcast (always an allowed sender, so NOT_EXPORTED is
        // safe). Receiving it on API 31+ additionally requires
        // BLUETOOTH_CONNECT, requested by the UI's permission flow.
        ContextCompat.registerReceiver(
            appContext,
            adapterStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** Begin (or restart) advertising [identity]. */
    fun start(identity: String) {
        synchronized(lock) {
            check(!released) { "BeaconAdvertiser released" }
            if (identity.isBlank()) {
                desiredIdentity = null
                teardown()
                emit(BeaconAdvertiseState.Failed(null, "no identity to broadcast"))
                return
            }
            desiredIdentity = identity
            beginAdvertising(identity)
        }
    }

    /** Stop advertising by user intent. */
    fun stop() {
        synchronized(lock) {
            if (desiredIdentity == null && !isAnythingRunning()) return
            desiredIdentity = null
            teardown()
            emit(BeaconAdvertiseState.Stopped)
        }
    }

    /** Final teardown: unregister the receiver too. Call from Service.onDestroy(). */
    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            desiredIdentity = null
            teardown()
        }
        runCatching { appContext.unregisterReceiver(adapterStateReceiver) }
            .onFailure { Log.w(TAG, "unregisterReceiver failed", it) }
        synchronized(lock) { emit(BeaconAdvertiseState.Stopped) }
    }

    // -------------------------------------------------------------------
    // Start / teardown
    // -------------------------------------------------------------------

    private fun beginAdvertising(identity: String) {
        teardown()
        setFailures.clear()
        pendingSets = 0
        emit(BeaconAdvertiseState.Starting(identity))

        val adapter = adapter
            ?: return fail(identity, "this device has no Bluetooth adapter")
        try {
            if (!adapter.isEnabled) {
                // Keep desiredIdentity; the receiver restarts on STATE_ON.
                return fail(
                    identity,
                    "Bluetooth is off — the beacon will start when Bluetooth is turned on",
                )
            }
            val leAdvertiser: BluetoothLeAdvertiser = adapter.bluetoothLeAdvertiser
                ?: return fail(identity, "this device cannot advertise over Bluetooth LE")

            // Preflight the extended payload against the controller limit so
            // an oversized identity fails with a readable reason instead of
            // ADVERTISE_FAILED_DATA_TOO_LARGE. (0 means "unsupported/unknown"
            // — isLeExtendedAdvertisingSupported gates the real decision.)
            val maxDataLength = adapter.getLeMaximumAdvertisingDataLength()
            if (maxDataLength > 0) {
                val budget = BeaconFrames.identityByteBudget(maxDataLength)
                val identityBytes = BeaconFrames.identityServiceData(identity)
                if (identityBytes.size > budget) {
                    return fail(
                        identity,
                        "identity is ${identityBytes.size} bytes; this controller can carry $budget in the extended frame",
                    )
                }
            }

            val marker = ParcelUuid(BeaconFrames.markerUuid())
            if (adapter.isLeExtendedAdvertisingSupported) {
                startSet(SetKind.EXTENDED, leAdvertiser, extendedParameters(), identityData(marker, identity))
            } else {
                val reason = "extended advertising not supported on this device"
                setFailures[SetKind.EXTENDED] = reason
                Log.w(TAG, reason)
            }
            startSet(SetKind.LEGACY_COMPANION, leAdvertiser, legacyParameters(), legacyData(marker))
        } catch (e: SecurityException) {
            teardown()
            return fail(identity, "missing Bluetooth permission: ${e.message}")
        }
        recompute()
    }

    private fun startSet(
        kind: SetKind,
        leAdvertiser: BluetoothLeAdvertiser,
        parameters: AdvertisingSetParameters,
        data: AdvertiseData,
    ) {
        val callback = SetCallback(kind)
        callbacks[kind] = callback
        pendingSets++
        leAdvertiser.startAdvertisingSet(parameters, data, null, null, null, callback)
    }

    private fun teardown() {
        val toStop = callbacks.toMap()
        callbacks.clear()
        pendingSets = 0
        extendedRunning = false
        legacyRunning = false
        if (toStop.isEmpty()) return
        val leAdvertiser = try {
            adapter?.takeIf { it.isEnabled }?.bluetoothLeAdvertiser
        } catch (e: SecurityException) {
            null
        }
        if (leAdvertiser == null) {
            // Adapter gone/off: the stack already tore the sets down; the
            // stale-callback guard below keeps their dying events out.
            Log.w(TAG, "no advertiser available to stop ${toStop.keys}; assuming stack-side teardown")
            return
        }
        for ((kind, callback) in toStop) {
            try {
                leAdvertiser.stopAdvertisingSet(callback)
            } catch (e: SecurityException) {
                // Permission was revoked mid-flight; the sets die with it.
                Log.w(TAG, "stopAdvertisingSet($kind) failed", e)
            }
        }
    }

    // -------------------------------------------------------------------
    // State reduction
    // -------------------------------------------------------------------

    private fun recompute() {
        val identity = desiredIdentity ?: return // stopped by intent; stop()/release() emitted
        if (pendingSets > 0) {
            emit(BeaconAdvertiseState.Starting(identity))
        } else if (extendedRunning || legacyRunning) {
            emit(
                BeaconAdvertiseState.Running(
                    identity = identity,
                    extended = extendedRunning,
                    legacy = legacyRunning,
                    note = setFailures.values.firstOrNull(),
                ),
            )
        } else {
            emit(
                BeaconAdvertiseState.Failed(
                    identity = identity,
                    reason = setFailures.values.firstOrNull() ?: "advertising stopped",
                ),
            )
        }
    }

    private fun fail(identity: String, reason: String) {
        emit(BeaconAdvertiseState.Failed(identity, reason))
    }

    private fun emit(state: BeaconAdvertiseState) {
        if (state == lastEmitted) return
        lastEmitted = state
        Log.i(TAG, "state -> $state")
        onState(state)
    }

    private fun isAnythingRunning() = extendedRunning || legacyRunning

    // -------------------------------------------------------------------
    // Adapter state
    // -------------------------------------------------------------------

    private fun onAdapterOff() {
        synchronized(lock) {
            val identity = desiredIdentity ?: return
            teardown()
            emit(
                BeaconAdvertiseState.Failed(
                    identity,
                    "Bluetooth turned off — the beacon will resume when Bluetooth is turned on",
                ),
            )
        }
    }

    private fun onAdapterOn() {
        synchronized(lock) {
            val identity = desiredIdentity ?: return
            Log.i(TAG, "Bluetooth back on; resuming beacon")
            beginAdvertising(identity)
        }
    }

    // -------------------------------------------------------------------
    // Per-set callback
    // -------------------------------------------------------------------

    private inner class SetCallback(private val kind: SetKind) : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            synchronized(lock) {
                if (callbacks[kind] !== this) return // stale: torn down or replaced
                pendingSets--
                if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                    if (kind == SetKind.EXTENDED) extendedRunning = true else legacyRunning = true
                    Log.i(TAG, "$kind on air (txPower=$txPower)")
                } else {
                    val description = describeFailure(kind, status)
                    setFailures[kind] = description
                    Log.w(TAG, "$kind failed to start: $description")
                }
                recompute()
            }
        }

        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
            synchronized(lock) {
                if (callbacks[kind] !== this) return
                if (kind == SetKind.EXTENDED) extendedRunning = false else legacyRunning = false
                if (desiredIdentity != null) {
                    // The stack stopped us without being asked (resource
                    // reclaim, adapter going down). If Bluetooth also went
                    // off, onAdapterOff's message already leads.
                    setFailures.putIfAbsent(kind, "${kind.label} stopped unexpectedly")
                }
                recompute()
            }
        }
    }

    private fun describeFailure(kind: SetKind, status: Int): String = when (status) {
        AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE ->
            "${kind.label} data too large for the advertising set"
        AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
            "${kind.label} rejected: too many advertisers (controller sets exhausted — another app may be advertising)"
        AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
            "${kind.label} rejected: feature unsupported by this controller"
        AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR ->
            "${kind.label} failed with an internal Bluetooth error"
        AdvertisingSetCallback.ADVERTISE_FAILED_ALREADY_STARTED ->
            "${kind.label} failed: an equivalent set is already started"
        else -> "${kind.label} failed: status $status"
    }

    // -------------------------------------------------------------------
    // Frame parameters and data (boring defaults from the format doc)
    // -------------------------------------------------------------------

    private fun extendedParameters(): AdvertisingSetParameters =
        AdvertisingSetParameters.Builder()
            .setLegacyMode(false)
            .setConnectable(false)
            .setScannable(false)
            .setPrimaryPhy(BluetoothDevice.PHY_LE_1M)
            .setSecondaryPhy(BluetoothDevice.PHY_LE_1M)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
            .setInterval(AdvertisingSetParameters.INTERVAL_HIGH) // ~1000 ms
            .setIncludeTxPower(true) // TX power in the extended header
            .build()

    private fun legacyParameters(): AdvertisingSetParameters =
        AdvertisingSetParameters.Builder()
            .setLegacyMode(true) // 31-byte BT 4.x frame; PHY settings are ignored
            .setConnectable(false)
            .setScannable(false)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
            .setInterval(AdvertisingSetParameters.INTERVAL_HIGH)
            .build()

    private fun identityData(marker: ParcelUuid, identity: String): AdvertiseData =
        AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(marker, BeaconFrames.identityServiceData(identity))
            .build()

    private fun legacyData(marker: ParcelUuid): AdvertiseData =
        AdvertiseData.Builder()
            // No room for extras: Flags (3) + header (2) + UUID (16) +
            // payload (10) already fill the 31-byte legacy budget exactly.
            .setIncludeDeviceName(false)
            .addServiceData(marker, BeaconFrames.legacyCompanionServiceData())
            .build()

    private companion object {
        const val TAG = "BeaconAdvertiser"
    }
}
