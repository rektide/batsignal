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

/**
 * Foreground service that will host BLE beacon advertising of an AT Protocol
 * identity. Phase 1: it holds the identity string, shows a persistent
 * notification with a Stop action, and parks the future Bluetooth work behind
 * [startAdvertising] / [stopAdvertising].
 *
 * Manifest pairing: foregroundServiceType="connectedDevice" plus the
 * FOREGROUND_SERVICE_CONNECTED_DEVICE permission; BLUETOOTH_ADVERTISE is
 * requested at runtime by the UI before the service is started.
 */
class BeaconService : Service() {

    private var identity: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
                identity = intent?.getStringExtra(EXTRA_IDENTITY).orEmpty()
                // connectedDevice is passed through ServiceCompat so API 29+
                // startForeground(int, Notification, int) gets the type; below
                // API 29 the type is ignored (the manifest declaration rules).
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(identity),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
                Log.i(TAG, "foreground service up (identity=$identity)")
                startAdvertising(identity)
            }
        }
        // Don't silently resurrect a killed broadcast whose intent extras
        // (and therefore identity) would be gone anyway.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopAdvertising()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // TODO: BLE advertising lands here. A parallel effort is evaluating
    // AltBeacon vs. raw BluetoothLeAdvertiser.startAdvertisingSet with
    // extended-length payloads; whichever wins, startAdvertising(identity)
    // is where the advertiser gets spun up and stopAdvertising() tears it
    // down. No Bluetooth is done yet.
    // ---------------------------------------------------------------------

    private fun startAdvertising(identity: String) {
        Log.i(TAG, "TODO startAdvertising(identity=$identity): no BLE yet")
    }

    private fun stopAdvertising() {
        Log.i(TAG, "TODO stopAdvertising(): no BLE yet")
    }

    // ---------------------------------------------------------------------

    private fun buildNotification(identity: String): Notification {
        val content = if (identity.isBlank()) {
            getString(R.string.notification_text_blank)
        } else {
            getString(R.string.notification_text, identity)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_batsignal_broadcast)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setContentIntent(activityPendingIntent())
            .addAction(0, getString(R.string.notification_stop), stopPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

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
