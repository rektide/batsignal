package io.github.rektide.batsignal.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.rektide.batsignal.MainActivity
import io.github.rektide.batsignal.R
import io.github.rektide.batsignal.ble.BeaconAdvertiseState
import io.github.rektide.batsignal.ble.BeaconAdvertiser
import io.github.rektide.batsignal.data.AdvertiseConfigStore

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
 *
 * Advertising parameters are read fresh from [AdvertiseConfigStore] on every
 * start; while broadcasting, changes to the config keys are applied live by
 * restarting the sets, debounced (see [CONFIG_RESTART_DEBOUNCE_MILLIS]) so a
 * burst of tweaks in the Config screen collapses into one restart after the
 * user goes quiet.
 */
class BeaconService : Service() {

    private var advertiser: BeaconAdvertiser? = null

    @Volatile
    private var lastNotificationText: String? = null

    @Volatile
    private var destroyed = false

    private lateinit var configStore: AdvertiseConfigStore

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Pending debounced config restart, if any; runs/cancels on the main looper. */
    private var pendingConfigRestart: Runnable? = null

    // Written on the main thread (onStartCommand), read only from the main
    // looper (config restart) — what the beacon is currently broadcasting.
    private var activeIdentity: String? = null
    private var activeLegacyCompanion = true

    private val configChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key in AdvertiseConfigStore.CONFIG_KEYS) {
            scheduleConfigRestart()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        advertiser = BeaconAdvertiser(this) { state -> onAdvertiserState(state) }
        configStore = AdvertiseConfigStore(this)
        configStore.registerChangeListener(configChangeListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                activeIdentity = null
                cancelConfigRestart()
                stopAdvertising()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                val identity = intent?.getStringExtra(EXTRA_IDENTITY).orEmpty()
                val legacyCompanion = intent?.getBooleanExtra(EXTRA_LEGACY_COMPANION, true) ?: true
                // A natural start already applies the freshest config; any
                // restart pending from earlier tweaks would be redundant.
                cancelConfigRestart()
                activeIdentity = identity.ifBlank { null }
                activeLegacyCompanion = legacyCompanion
                // connectedDevice is passed through ServiceCompat so API 29+
                // startForeground(int, Notification, int) gets the type; below
                // API 29 the type is ignored (the manifest declaration rules).
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(notificationText(BeaconAdvertiseState.Starting(identity))),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
                Log.i(TAG, "foreground service up (identity=$identity, legacyCompanion=$legacyCompanion)")
                advertiser?.start(identity, legacyCompanion, configStore.load())
            }
        }
        // Don't silently resurrect a killed broadcast whose intent extras
        // (and therefore identity) would be gone anyway.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        pendingConfigRestart = null
        configStore.unregisterChangeListener(configChangeListener)
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
    // Live config apply
    // ---------------------------------------------------------------------

    /**
     * (Re)schedule the debounced restart: every config change cancels any
     * pending one and pushes the timer out, so only one restart runs per
     * quiet period. No-op while not broadcasting — a change made with the
     * beacon off is simply picked up by the next start, which reads the
     * config fresh anyway.
     */
    private fun scheduleConfigRestart() {
        // Listener callbacks can arrive off-main; funnel to the main looper.
        mainHandler.post { scheduleConfigRestartOnMain() }
    }

    private fun scheduleConfigRestartOnMain() {
        val identity = activeIdentity ?: return // not broadcasting
        cancelConfigRestartOnMain()
        val restart = Runnable {
            pendingConfigRestart = null
            val currentIdentity = activeIdentity ?: return@Runnable // stopped meanwhile
            Log.i(TAG, "applying advertising config change")
            advertiser?.start(currentIdentity, activeLegacyCompanion, configStore.load())
        }
        pendingConfigRestart = restart
        mainHandler.postDelayed(restart, CONFIG_RESTART_DEBOUNCE_MILLIS)
        Log.i(
            TAG,
            "config change for '$identity'; advertising restart scheduled in ${CONFIG_RESTART_DEBOUNCE_MILLIS} ms",
        )
    }

    private fun cancelConfigRestart() {
        mainHandler.post { cancelConfigRestartOnMain() }
    }

    private fun cancelConfigRestartOnMain() {
        pendingConfigRestart?.let { mainHandler.removeCallbacks(it) }
        pendingConfigRestart = null
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

        /**
         * How long to wait after the last config change before restarting
         * the advertising sets, so tinkering in the Config screen collapses
         * into a single restart rather than one per tweak.
         */
        private const val CONFIG_RESTART_DEBOUNCE_MILLIS = 20_000L

        const val ACTION_START = "io.github.rektide.batsignal.action.START"
        const val ACTION_STOP = "io.github.rektide.batsignal.action.STOP"
        const val EXTRA_IDENTITY = "io.github.rektide.batsignal.extra.IDENTITY"
        const val EXTRA_LEGACY_COMPANION = "io.github.rektide.batsignal.extra.LEGACY_COMPANION"
    }
}
