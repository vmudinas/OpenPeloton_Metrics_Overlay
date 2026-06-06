package org.openpelo.beltstatusdump

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.sin

class TrackingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val distanceAccumulator = DistanceAccumulator()
    private lateinit var database: SessionDatabase
    private var overlayController: OverlayController? = null
    private var receiverRegistered = false
    private var sessionId: Long? = null
    private var sessionStartedElapsed = 0L
    private var pausedStartedElapsed = 0L
    private var totalPausedMillis = 0L
    private var lastSampleSavedElapsed = 0L
    private var latestSpeedMetersPerSecond = 0.0
    private var latestInclinePercent = 0.0
    private var speedUnit = SpeedUnit.MPH
    private var paused = false
    private var fakeMode = false
    private var fakeTick = 0
    private var overlayVisible = false
    private var overlayCompact = false
    private var affernetClient: AffernetTreadClient? = null
    private var affernetConnected = false
    private var affernetError: String? = null
    private var beltBroadcastCount = 0
    private var parsedMetricEventCount = 0
    private var classBroadcastCount = 0
    private var lastBeltEventElapsed = 0L
    private var lastMetricEventElapsed = 0L
    private var lastRawDump = "Waiting for Peloton Affernet metrics."

    private val beltReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BELT_STATUS_ACTION -> {
                    beltBroadcastCount += 1
                    lastBeltEventElapsed = SystemClock.elapsedRealtime()
                    Log.i(DIAGNOSTIC_TAG, "Received $BELT_STATUS_ACTION event $beltBroadcastCount")
                    processExtras(intent.extras)
                }
                IN_CLASS_STATUS_ACTION -> {
                    classBroadcastCount += 1
                    val parsed = MetricParser.parse(intent.extras, speedUnit)
                    lastRawDump =
                        "ACTION: $IN_CLASS_STATUS_ACTION\n${parsed.rawDump}"
                    Log.i(
                        DIAGNOSTIC_TAG,
                        "Received $IN_CLASS_STATUS_ACTION event $classBroadcastCount: ${parsed.rawDump}"
                    )
                    broadcastDiagnostics()
                }
            }
        }
    }

    private val fakeMetricsRunnable = object : Runnable {
        override fun run() {
            if (!fakeMode || sessionId == null) return
            fakeTick += 1
            val speedMph = 4.0 + (fakeTick % 12) * 0.25
            val incline = 3.0 + sin(fakeTick / 4.0) * 2.0
            processValues(
                speedMetersPerSecond = SpeedUnit.MPH.toMetersPerSecond(speedMph),
                inclinePercent = incline,
                rawDump = String.format(
                    Locale.US,
                    "FAKE speed=%.2f mph\nFAKE incline=%.2f %%",
                    speedMph,
                    incline,
                ),
            )
            handler.postDelayed(this, 1000)
        }
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (sessionId == null) return
            emitLatest()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        database = SessionDatabase(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTracking(intent)
            ACTION_TOGGLE_PAUSE -> togglePause()
            ACTION_TOGGLE_OVERLAY -> toggleOverlay()
            ACTION_SHOW_OVERLAY -> showOverlay()
            ACTION_HIDE_OVERLAY -> hideOverlay()
            ACTION_TOGGLE_COMPACT -> toggleCompact()
            ACTION_STOP -> stopTracking()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(fakeMetricsRunnable)
        handler.removeCallbacks(heartbeatRunnable)
        closeAffernetClient()
        unregisterBeltReceiver()
        overlayController?.remove()
        overlayController = null
        database.close()
        super.onDestroy()
    }

    private fun startTracking(intent: Intent) {
        if (sessionId != null) return

        speedUnit = SpeedUnit.fromOrdinal(intent.getIntExtra(EXTRA_SPEED_UNIT, 0))
        fakeMode = intent.getBooleanExtra(EXTRA_FAKE_MODE, false)
        overlayCompact = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getBoolean(PREFERENCE_OVERLAY_COMPACT, false)
        sessionId = database.startSession(speedUnit)
        sessionStartedElapsed = SystemClock.elapsedRealtime()
        totalPausedMillis = 0L
        lastSampleSavedElapsed = 0L
        latestSpeedMetersPerSecond = 0.0
        latestInclinePercent = 0.0
        paused = false
        beltBroadcastCount = 0
        parsedMetricEventCount = 0
        classBroadcastCount = 0
        lastBeltEventElapsed = 0L
        lastMetricEventElapsed = 0L
        affernetConnected = false
        affernetError = null
        lastRawDump = "Waiting for Peloton Affernet metrics."
        distanceAccumulator.reset()

        registerBeltReceiver()
        startForeground(NOTIFICATION_ID, buildNotification())

        if (Settings.canDrawOverlays(this)) {
            showOverlay()
        }

        if (fakeMode) {
            handler.post(fakeMetricsRunnable)
        } else {
            bindAffernetClient()
        }
        handler.post(heartbeatRunnable)
        broadcastState("Tracking started${if (fakeMode) " in simulated mode" else ""}.")
        broadcastDiagnostics()
    }

    private fun togglePause() {
        if (sessionId == null) return
        paused = !paused
        if (paused) {
            pausedStartedElapsed = SystemClock.elapsedRealtime()
        } else {
            totalPausedMillis += SystemClock.elapsedRealtime() - pausedStartedElapsed
            distanceAccumulator.breakContinuity()
        }
        updateNotification()
        broadcastState("Tracking ${if (paused) "paused" else "resumed"}.")
        emitLatest()
    }

    private fun stopTracking() {
        val activeSessionId = sessionId ?: return stopSelf()
        val elapsedMillis = activeElapsedMillis()
        database.finishSession(activeSessionId, elapsedMillis, distanceAccumulator.distanceMeters)
        sessionId = null
        handler.removeCallbacks(fakeMetricsRunnable)
        handler.removeCallbacks(heartbeatRunnable)
        closeAffernetClient()
        unregisterBeltReceiver()
        hideOverlay()
        broadcastState("Session saved.")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun processExtras(extras: Bundle?) {
        val parsed = MetricParser.parse(extras, speedUnit)
        lastRawDump = buildString {
            append("ACTION: ").append(BELT_STATUS_ACTION).append('\n')
            append("BELT EVENT: ").append(beltBroadcastCount).append('\n')
            append(parsed.rawDump)
        }
        Log.i(DIAGNOSTIC_TAG, lastRawDump)

        if (parsed.speedMetersPerSecond == null && parsed.inclinePercent == null) {
            broadcastMetrics(lastRawDump, activeElapsedMillis())
            broadcastDiagnostics()
            return
        }

        parsedMetricEventCount += 1
        lastMetricEventElapsed = SystemClock.elapsedRealtime()
        processValues(
            speedMetersPerSecond = parsed.speedMetersPerSecond ?: latestSpeedMetersPerSecond,
            inclinePercent = parsed.inclinePercent ?: latestInclinePercent,
            rawDump = lastRawDump,
        )
    }

    private fun processValues(
        speedMetersPerSecond: Double,
        inclinePercent: Double,
        rawDump: String,
    ) {
        if (sessionId == null) return
        latestSpeedMetersPerSecond = speedMetersPerSecond
        latestInclinePercent = inclinePercent

        val snapshot = MetricsSnapshot(
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
            speedMetersPerSecond = if (paused) 0.0 else speedMetersPerSecond,
            inclinePercent = inclinePercent,
        )
        if (!paused) {
            distanceAccumulator.update(snapshot)
        } else {
            distanceAccumulator.breakContinuity()
        }

        val elapsedMillis = activeElapsedMillis()
        if (elapsedMillis - lastSampleSavedElapsed >= 1000) {
            database.addSample(
                sessionId = sessionId ?: return,
                elapsedMillis = elapsedMillis,
                speedMetersPerSecond = speedMetersPerSecond,
                inclinePercent = inclinePercent,
                distanceMeters = distanceAccumulator.distanceMeters,
            )
            lastSampleSavedElapsed = elapsedMillis
        }

        overlayController?.update(
            speedMetersPerSecond,
            inclinePercent,
            distanceAccumulator.distanceMeters,
            elapsedMillis,
            paused,
            currentDataStatus(),
        )
        broadcastMetrics(rawDump, elapsedMillis)
        broadcastDiagnostics()
    }

    private fun bindAffernetClient() {
        closeAffernetClient()
        affernetClient = AffernetTreadClient(
            context = this,
            onConnectionChanged = { connected, error ->
                affernetConnected = connected
                affernetError = error
                if (error != null) {
                    lastRawDump = error
                    Log.e(DIAGNOSTIC_TAG, error)
                } else if (connected) {
                    lastRawDump = "Connected to Peloton Affernet Tread interface."
                    Log.i(DIAGNOSTIC_TAG, lastRawDump)
                }
                emitLatest()
            },
            onMetrics = { metrics ->
                if (sessionId == null || fakeMode) return@AffernetTreadClient
                affernetConnected = true
                affernetError = null
                parsedMetricEventCount += 1
                lastMetricEventElapsed = SystemClock.elapsedRealtime()
                val speed = metrics.speedRaw / AFFERNET_METRIC_SCALE
                val incline = metrics.inclineRaw / AFFERNET_METRIC_SCALE
                lastRawDump = String.format(
                    Locale.US,
                    "AFFERNET speedRaw=%d speed=%.1f %s\n" +
                        "AFFERNET inclineRaw=%d incline=%.1f %%",
                    metrics.speedRaw,
                    speed,
                    speedUnit.name.lowercase(),
                    metrics.inclineRaw,
                    incline,
                )
                processValues(
                    speedMetersPerSecond = speedUnit.toMetersPerSecond(speed),
                    inclinePercent = incline,
                    rawDump = lastRawDump,
                )
            },
        ).also { it.bind() }
    }

    private fun closeAffernetClient() {
        affernetClient?.close()
        affernetClient = null
        affernetConnected = false
    }

    private fun activeElapsedMillis(): Long {
        if (sessionId == null) return 0L
        val now = if (paused) pausedStartedElapsed else SystemClock.elapsedRealtime()
        return (now - sessionStartedElapsed - totalPausedMillis).coerceAtLeast(0L)
    }

    private fun registerBeltReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BELT_STATUS_ACTION)
            addAction(IN_CLASS_STATUS_ACTION)
        }
        ContextCompat.registerReceiver(
            this,
            beltReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun unregisterBeltReceiver() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(beltReceiver) }
        receiverRegistered = false
    }

    private fun broadcastMetrics(rawDump: String, elapsedMillis: Long) {
        sendBroadcast(
            Intent(ACTION_METRICS_UPDATE).apply {
                setPackage(packageName)
                putExtra(EXTRA_SPEED_MPS, latestSpeedMetersPerSecond)
                putExtra(EXTRA_INCLINE, latestInclinePercent)
                putExtra(EXTRA_DISTANCE_METERS, distanceAccumulator.distanceMeters)
                putExtra(EXTRA_ELAPSED_MILLIS, elapsedMillis)
                putExtra(EXTRA_PAUSED, paused)
                putExtra(EXTRA_RAW_DUMP, rawDump)
                putExtra(EXTRA_DATA_STATUS, currentDataStatus())
            }
        )
    }

    private fun emitLatest() {
        broadcastMetrics(lastRawDump, activeElapsedMillis())
        overlayController?.update(
            latestSpeedMetersPerSecond,
            latestInclinePercent,
            distanceAccumulator.distanceMeters,
            activeElapsedMillis(),
            paused,
            currentDataStatus(),
        )
        broadcastDiagnostics()
    }

    private fun broadcastState(message: String) {
        sendBroadcast(
            Intent(ACTION_STATE_UPDATE).apply {
                setPackage(packageName)
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_ACTIVE, sessionId != null)
                putExtra(EXTRA_PAUSED, paused)
                putExtra(EXTRA_OVERLAY_VISIBLE, overlayVisible)
                putExtra(EXTRA_OVERLAY_COMPACT, overlayCompact)
            }
        )
    }

    private fun broadcastDiagnostics() {
        val ageMillis = if (lastMetricEventElapsed == 0L) {
            -1L
        } else {
            SystemClock.elapsedRealtime() - lastMetricEventElapsed
        }
        sendBroadcast(
            Intent(ACTION_DIAGNOSTICS_UPDATE).apply {
                setPackage(packageName)
                putExtra(EXTRA_BELT_EVENT_COUNT, beltBroadcastCount)
                putExtra(EXTRA_PARSED_EVENT_COUNT, parsedMetricEventCount)
                putExtra(EXTRA_CLASS_EVENT_COUNT, classBroadcastCount)
                putExtra(EXTRA_LAST_BELT_AGE_MILLIS, ageMillis)
                putExtra(EXTRA_DATA_STATUS, currentDataStatus())
                putExtra(EXTRA_RAW_DUMP, lastRawDump)
                putExtra(EXTRA_OVERLAY_VISIBLE, overlayVisible)
                putExtra(EXTRA_OVERLAY_COMPACT, overlayCompact)
            }
        )
    }

    private fun currentDataStatus(): String {
        if (fakeMode) return "SIMULATED"
        if (affernetError != null) return "AFFERNET ERROR"
        if (!affernetConnected) return "CONNECTING TO AFFERNET"
        if (parsedMetricEventCount == 0) return "WAITING FOR AFFERNET DATA"
        val ageMillis = SystemClock.elapsedRealtime() - lastMetricEventElapsed
        return if (ageMillis > STALE_DATA_MILLIS) "AFFERNET DATA STALE" else "TRACKING"
    }

    private fun toggleOverlay() {
        if (sessionId == null) {
            broadcastState("Start tracking before showing the overlay.")
            stopSelf()
            return
        }
        if (overlayVisible) hideOverlay() else showOverlay()
    }

    private fun showOverlay() {
        if (sessionId == null) return
        if (!Settings.canDrawOverlays(this)) {
            broadcastState("Draw-over-apps permission is not granted.")
            return
        }
        if (overlayController == null) {
            overlayController = OverlayController(
                context = this,
                onTogglePause = ::togglePause,
                onToggleCompact = ::toggleCompact,
                onHide = ::hideOverlay,
            )
        }
        overlayController?.show(overlayCompact)
        overlayVisible = true
        emitLatest()
        updateNotification()
        broadcastState("Overlay shown.")
    }

    private fun hideOverlay() {
        overlayController?.remove()
        overlayVisible = false
        updateNotification()
        broadcastState("Overlay hidden. Tracking continues.")
        broadcastDiagnostics()
    }

    private fun toggleCompact() {
        overlayCompact = !overlayCompact
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(PREFERENCE_OVERLAY_COMPACT, overlayCompact)
            .apply()
        overlayController?.setCompact(overlayCompact)
        broadcastState(
            if (overlayCompact) "Overlay minimized." else "Overlay expanded."
        )
        broadcastDiagnostics()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Tread metric tracking",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pauseIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TrackingService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val overlayIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, TrackingService::class.java).setAction(ACTION_TOGGLE_OVERLAY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Peloton metrics tracking")
            .setContentText(if (paused) "Paused" else "Recording speed, incline, and distance")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, if (overlayVisible) "Hide Overlay" else "Show Overlay", overlayIntent)
            .addAction(0, if (paused) "Resume" else "Pause", pauseIntent)
            .addAction(0, "Stop + Save", stopIntent)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        const val BELT_STATUS_ACTION = "onepeloton.intent.action.BELT_STATUS"
        const val IN_CLASS_STATUS_ACTION = "onepeloton.intent.action.IN_CLASS_STATUS"
        const val ACTION_START = "org.openpelo.beltstatusdump.action.START"
        const val ACTION_TOGGLE_PAUSE = "org.openpelo.beltstatusdump.action.TOGGLE_PAUSE"
        const val ACTION_TOGGLE_OVERLAY = "org.openpelo.beltstatusdump.action.TOGGLE_OVERLAY"
        const val ACTION_SHOW_OVERLAY = "org.openpelo.beltstatusdump.action.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "org.openpelo.beltstatusdump.action.HIDE_OVERLAY"
        const val ACTION_TOGGLE_COMPACT = "org.openpelo.beltstatusdump.action.TOGGLE_COMPACT"
        const val ACTION_STOP = "org.openpelo.beltstatusdump.action.STOP"
        const val ACTION_METRICS_UPDATE = "org.openpelo.beltstatusdump.action.METRICS_UPDATE"
        const val ACTION_STATE_UPDATE = "org.openpelo.beltstatusdump.action.STATE_UPDATE"
        const val ACTION_DIAGNOSTICS_UPDATE =
            "org.openpelo.beltstatusdump.action.DIAGNOSTICS_UPDATE"
        const val EXTRA_FAKE_MODE = "fake_mode"
        const val EXTRA_SPEED_UNIT = "speed_unit"
        const val EXTRA_SPEED_MPS = "speed_mps"
        const val EXTRA_INCLINE = "incline"
        const val EXTRA_DISTANCE_METERS = "distance_meters"
        const val EXTRA_ELAPSED_MILLIS = "elapsed_millis"
        const val EXTRA_PAUSED = "paused"
        const val EXTRA_RAW_DUMP = "raw_dump"
        const val EXTRA_DATA_STATUS = "data_status"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_ACTIVE = "active"
        const val EXTRA_OVERLAY_VISIBLE = "overlay_visible"
        const val EXTRA_OVERLAY_COMPACT = "overlay_compact"
        const val EXTRA_BELT_EVENT_COUNT = "belt_event_count"
        const val EXTRA_PARSED_EVENT_COUNT = "parsed_event_count"
        const val EXTRA_CLASS_EVENT_COUNT = "class_event_count"
        const val EXTRA_LAST_BELT_AGE_MILLIS = "last_belt_age_millis"
        private const val CHANNEL_ID = "tread_tracking"
        private const val NOTIFICATION_ID = 1001
        private const val STALE_DATA_MILLIS = 5_000L
        private const val AFFERNET_METRIC_SCALE = 10.0
        private const val PREFERENCES_NAME = "overlay_preferences"
        private const val PREFERENCE_OVERLAY_COMPACT = "overlay_compact"
        private const val DIAGNOSTIC_TAG = "TreadMetrics"
    }
}
