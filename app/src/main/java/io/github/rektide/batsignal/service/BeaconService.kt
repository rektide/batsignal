package io.github.rektide.batsignal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.rektide.batsignal.MainActivity
import io.github.rektide.batsignal.R
import io.github.rektide.batsignal.ble.BeaconAdvertiseState
import io.github.rektide.batsignal.ble.BeaconAdvertiser

/**
 * Foreground service hosting BLE beacon advertising of an AT Protocol
 * identity. It owns a [BeaconAdvertiser] driving the two advertising sets
 * (extended identity frame + legacy companion marker) and mirrors the real
 * advertise state into both the persistent notification and
 * [BeaconStatusHolder] for the UI — advertising / marker-only / failed with
 * reason, never an assumed success.
 *
 * Manifest pairing: foregroundServiceType="connectedDevice" plus the
 * FOREGROUND_SERVICE_CONNECTED_DEVICE permission; BLUETOOTH_ADVERTISE (and
 * BLUETOOTH_CONNECT, for observing adapter state) are requested at runtime by
 * the UI before the service is started.
 */
class BeaconService : Service() {

    private var advertiser: BeaconAdvertiser? = null

    @Volatile
    private var lastNotificationText: String? = null

    @Volatile
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        advertiser = BeaconAdvertiser(this) { state -> onAdvertiserState(state) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAdvertising()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                val identity = intent?.getStringExtra(EXTRA_IDENTITY).orEmpty()
                // connectedDevice is passed through ServiceCompat so API 29+
                // startForeground(int, Notification, int) gets the type; below
                // API 29 the type is ignored (the manifest declaration rules).
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(notificationText(BeaconAdvertiseState.Starting(identity))),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
                Log.i(TAG, "foreground service up (identity=$identity)")
                advertiser?.start(identity)
            }
        }
        // Don't silently resurrect a killed broadcast whose intent extras
        // (and therefore identity) would be gone anyway.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        advertiser?.release()
        advertiser = null
        BeaconStatusHolder.update(BeaconAdvertiseState.Stopped)
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Advertising + state plumbing
    // ---------------------------------------------------------------------

    /**
     * Single sink for advertiser states (invoked on binder threads while the
     * advertiser holds its lock): publish to the UI via [BeaconStatusHolder]
     * and refresh the notification, but only when the text actually changed.
     * Both touches are thread-safe; must not call back into the advertiser.
     * Once destroyed, only the holder is updated — re-posting the
     * notification mid-teardown could leave a stale non-foreground one.
     */
    private fun onAdvertiserState(state: BeaconAdvertiseState) {
        BeaconStatusHolder.update(state)
        if (destroyed) return
        val text = notificationText(state)
        if (text != lastNotificationText) {
            lastNotificationText = text
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun stopAdvertising() {
        advertiser?.stop()
    }

    // ---------------------------------------------------------------------
    // Notification
    // ---------------------------------------------------------------------

    private fun notificationText(state: BeaconAdvertiseState): String = when (state) {
        BeaconAdvertiseState.Stopped -> getString(R.string.notification_text_stopped)
        is BeaconAdvertiseState.Starting -> getString(R.string.notification_text_starting, state.identity)
        is BeaconAdvertiseState.Running -> when {
            state.extended && state.legacy -> getString(R.string.notification_text, state.identity)
            state.legacy -> getString(R.string.notification_text_legacy_only, state.identity)
            else -> getString(R.string.notification_text_extended_only, state.identity)
        }
        is BeaconAdvertiseState.Failed -> getString(R.string.notification_text_failed, state.reason)
    }

    private fun buildNotification(contentText: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_batsignal_broadcast)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setContentIntent(activityPendingIntent())
            .addAction(0, getString(R.string.notification_stop), stopPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun activityPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, BeaconService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "BeaconService"
        private const val CHANNEL_ID = "batsignal_beacon"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "io.github.rektide.batsignal.action.START"
        const val ACTION_STOP = "io.github.rektide.batsignal.action.STOP"
        const val EXTRA_IDENTITY = "io.github.rektide.batsignal.extra.IDENTITY"
    }
}
