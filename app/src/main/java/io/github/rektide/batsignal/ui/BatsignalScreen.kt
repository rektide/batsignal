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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.rektide.batsignal.R
import io.github.rektide.batsignal.ble.BeaconAdvertiseState
import io.github.rektide.batsignal.config.ConfigActivity
import io.github.rektide.batsignal.data.IdentityStore
import io.github.rektide.batsignal.permissions.requiredRuntimePermissions
import io.github.rektide.batsignal.service.BeaconService
import io.github.rektide.batsignal.service.BeaconStatusHolder

/**
 * The single phase-1 screen: pick an identity, then two switches.
 *
 *  * **Beacon** — master on/off for the foreground service. Its checked state
 *    is derived from [BeaconStatusHolder] (any state but `Stopped` means the
 *    service is alive and intends to broadcast, including a `Failed` state
 *    that will auto-resume when Bluetooth comes back), so it never claims an
 *    on-air state the service hasn't reported.
 *  * **Legacy marker** — whether the 31-byte companion rides along. The
 *    preference persists and is retained while the beacon is off, but the
 *    switch is disabled then: legacy is a companion, never a beacon of its
 *    own. Toggling it while broadcasting restarts the advertising sets with
 *    the new setting (and, as a side effect, applies pending identity edits).
 *
 * The identity field persists per keystroke; while broadcasting, a supporting
 * hint appears if the text no longer matches what is on air. Dull on purpose.
 *
 * A top bar overflow menu leads to the Config screen ([ConfigActivity]) for
 * the tunable advertising parameters.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatsignalScreen() {
    val context = LocalContext.current
    val store = remember { IdentityStore(context) }
    var identity by remember { mutableStateOf(store.load()) }
    var legacyCompanion by remember { mutableStateOf(store.loadLegacyCompanion()) }
    var notice by remember { mutableStateOf<String?>(null) }
    val status by BeaconStatusHolder.status.collectAsState()

    // Any non-Stopped state means the service is alive and wants to broadcast
    // (Starting/Running, or Failed with an auto-resume pending).
    val beaconOn = status != BeaconAdvertiseState.Stopped

    fun startBeacon() {
        val intent = Intent(context, BeaconService::class.java)
            .setAction(BeaconService.ACTION_START)
            .putExtra(BeaconService.EXTRA_IDENTITY, identity.trim())
            .putExtra(BeaconService.EXTRA_LEGACY_COMPANION, legacyCompanion)
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

    fun toggleBeacon(on: Boolean) {
        when {
            !on -> stopBeacon()
            identity.isBlank() -> notice = context.getString(R.string.notice_identity_needed)
            else -> {
                val missing = requiredRuntimePermissions().filter {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing.isEmpty()) {
                    startBeacon()
                } else {
                    permissionLauncher.launch(missing.toTypedArray())
                }
            }
        }
    }

    // The identity currently on air (or being started), if any.
    val broadcastingIdentity = when (val s = status) {
        is BeaconAdvertiseState.Starting -> s.identity
        is BeaconAdvertiseState.Running -> s.identity
        is BeaconAdvertiseState.Failed -> s.identity
        BeaconAdvertiseState.Stopped -> null
    }
    val pendingIdentityChange =
        beaconOn && broadcastingIdentity != null && identity.isNotBlank() && identity.trim() != broadcastingIdentity

    MaterialTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = { BatsignalTopBar() },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
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
                    supportingText = {
                        if (pendingIdentityChange) {
                            Text(stringResource(R.string.identity_pending_change))
                        }
                    },
                    singleLine = true,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(stringResource(R.string.toggle_beacon))
                        Text(
                            stringResource(R.string.toggle_beacon_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = beaconOn,
                        onCheckedChange = { on -> toggleBeacon(on) },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(stringResource(R.string.toggle_legacy))
                        Text(
                            stringResource(R.string.toggle_legacy_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = legacyCompanion,
                        // Disabled while the beacon is off — the preference is
                        // retained but cannot be exercised: legacy never
                        // broadcasts alone.
                        enabled = beaconOn,
                        onCheckedChange = { enabled ->
                            legacyCompanion = enabled
                            store.saveLegacyCompanion(enabled)
                            if (beaconOn) {
                                // Re-START applies the companion setting
                                // immediately (and any pending identity edit).
                                startBeacon()
                            }
                        },
                    )
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

/**
 * App bar whose only overflow item opens the Config screen. TopAppBar is
 * still an experimental material3 API, hence the OptIn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatsignalTopBar() {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }

    TopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        actions = {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.menu_overflow),
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_config)) },
                    onClick = {
                        menuOpen = false
                        context.startActivity(Intent(context, ConfigActivity::class.java))
                    },
                )
            }
        },
    )
}
