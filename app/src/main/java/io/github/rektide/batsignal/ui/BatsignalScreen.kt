package io.github.rektide.batsignal.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.rektide.batsignal.R
import io.github.rektide.batsignal.ble.BeaconAdvertiseState
import io.github.rektide.batsignal.data.IdentityStore
import io.github.rektide.batsignal.permissions.requiredRuntimePermissions
import io.github.rektide.batsignal.service.BeaconService
import io.github.rektide.batsignal.service.BeaconStatusHolder

/**
 * The single phase-1 screen: pick an identity, start or stop the beacon
 * foreground service. Dull on purpose — the status line shows the service's
 * real advertise state (from [BeaconStatusHolder]), so it stops claiming
 * "broadcasting" the moment the service says otherwise.
 */
@Composable
fun BatsignalScreen() {
    val context = LocalContext.current
    val store = remember { IdentityStore(context) }
    var identity by remember { mutableStateOf(store.load()) }
    var notice by remember { mutableStateOf<String?>(null) }
    val status by BeaconStatusHolder.status.collectAsState()

    fun startBeacon() {
        val intent = Intent(context, BeaconService::class.java)
            .setAction(BeaconService.ACTION_START)
            .putExtra(BeaconService.EXTRA_IDENTITY, identity.trim())
        ContextCompat.startForegroundService(context, intent)
        notice = null
    }

    fun stopBeacon() {
        context.startService(
            Intent(context, BeaconService::class.java).setAction(BeaconService.ACTION_STOP),
        )
        notice = null
    }

    // Runtime permissions are asked before every start attempt for which they
    // are missing; the service only launches once advertising is granted
    // (best-effort: POST_NOTIFICATIONS denial still lets the beacon run, its
    // notification just isn't shown).
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val blockingDenied = requiredRuntimePermissions()
            .filter { it != Manifest.permission.POST_NOTIFICATIONS }
            .any { grants[it] == false }
        if (!blockingDenied) {
            startBeacon()
        } else {
            notice = context.getString(R.string.permission_denied)
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = identity,
                    onValueChange = {
                        identity = it
                        store.save(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.identity_field_label)) },
                    placeholder = { Text(stringResource(R.string.identity_field_placeholder)) },
                    singleLine = true,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            val missing = requiredRuntimePermissions().filter {
                                ContextCompat.checkSelfPermission(context, it) !=
                                    PackageManager.PERMISSION_GRANTED
                            }
                            if (missing.isEmpty()) {
                                startBeacon()
                            } else {
                                permissionLauncher.launch(missing.toTypedArray())
                            }
                        },
                        enabled = identity.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.button_start))
                    }
                    Button(
                        onClick = { stopBeacon() },
                    ) {
                        Text(stringResource(R.string.button_stop))
                    }
                }

                Text(
                    text = when {
                        notice != null -> notice!!
                        else -> when (val s = status) {
                            BeaconAdvertiseState.Stopped -> stringResource(R.string.status_idle)
                            is BeaconAdvertiseState.Starting -> stringResource(R.string.status_starting, s.identity)
                            is BeaconAdvertiseState.Running -> {
                                val base = when {
                                    s.extended && s.legacy ->
                                        stringResource(R.string.status_broadcasting, s.identity)
                                    s.legacy ->
                                        stringResource(R.string.status_broadcasting_legacy_only, s.identity)
                                    else ->
                                        stringResource(R.string.status_broadcasting_extended_only, s.identity)
                                }
                                s.note?.let { "$base\n$it" } ?: base
                            }
                            is BeaconAdvertiseState.Failed -> stringResource(R.string.status_failed, s.reason)
                        }
                    },
                )
            }
        }
    }
}
