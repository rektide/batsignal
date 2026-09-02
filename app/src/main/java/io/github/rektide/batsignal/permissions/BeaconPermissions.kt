package io.github.rektide.batsignal.permissions

import android.Manifest
import android.os.Build

/**
 * Runtime permissions that must be granted (or at least requested) before the
 * beacon foreground service starts. Install-time permissions (the manifest
 * FOREGROUND_SERVICE* declarations) are handled in AndroidManifest.xml.
 */
fun requiredRuntimePermissions(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
    }
}
