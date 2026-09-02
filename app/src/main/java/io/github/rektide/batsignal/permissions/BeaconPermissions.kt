package io.github.rektide.batsignal.permissions

import android.Manifest
import android.os.Build

/**
 * Runtime permissions that must be granted (or at least requested) before the
 * beacon foreground service starts. Install-time permissions (the manifest
 * FOREGROUND_SERVICE* declarations) are handled in AndroidManifest.xml.
 *
 * BLUETOOTH_ADVERTISE gates the advertising sets themselves; BLUETOOTH_CONNECT
 * is needed to receive BluetoothAdapter.ACTION_STATE_CHANGED, so the beacon
 * can stop cleanly when Bluetooth is toggled off and resume when it returns.
 */
fun requiredRuntimePermissions(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
}
