package org.openpelo.beltstatusdump

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var metricsText: TextView
    private lateinit var diagnosticsText: TextView
    private lateinit var sessionsText: TextView
    private lateinit var logText: TextView
    private lateinit var fakeModeSwitch: SwitchMaterial
    private lateinit var speedUnitSpinner: Spinner
    private lateinit var database: SessionDatabase
    private var receiversRegistered = false
    private var pendingCsv = ""

    private val exportDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(pendingCsv)
                }
                statusText.text = "CSV exported."
            }
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val updatesReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                TrackingService.ACTION_METRICS_UPDATE -> renderMetrics(intent)
                TrackingService.ACTION_DIAGNOSTICS_UPDATE -> renderDiagnostics(intent)
                TrackingService.ACTION_STATE_UPDATE -> {
                    statusText.text = intent.getStringExtra(TrackingService.EXTRA_MESSAGE)
                        ?: "Tracking state changed."
                    refreshSessions()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        metricsText = findViewById(R.id.metricsText)
        diagnosticsText = findViewById(R.id.diagnosticsText)
        sessionsText = findViewById(R.id.sessionsText)
        logText = findViewById(R.id.logText)
        fakeModeSwitch = findViewById(R.id.fakeModeSwitch)
        speedUnitSpinner = findViewById(R.id.speedUnitSpinner)
        database = SessionDatabase(this)

        findViewById<Button>(R.id.overlayPermissionButton).setOnClickListener {
            requestOverlayPermission()
        }
        findViewById<Button>(R.id.startButton).setOnClickListener {
            requestNotificationPermissionIfNeeded()
            startTracking()
        }
        findViewById<Button>(R.id.pauseButton).setOnClickListener {
            sendServiceAction(TrackingService.ACTION_TOGGLE_PAUSE)
        }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            sendServiceAction(TrackingService.ACTION_STOP)
        }
        findViewById<Button>(R.id.toggleOverlayButton).setOnClickListener {
            sendServiceAction(TrackingService.ACTION_TOGGLE_OVERLAY)
        }
        findViewById<Button>(R.id.exportButton).setOnClickListener {
            pendingCsv = database.exportCsv()
            exportDocument.launch("peloton-tread-sessions.csv")
        }

        refreshSessions()
        updateOverlayPermissionStatus()
    }

    override fun onStart() {
        super.onStart()
        registerUpdateReceivers()
        updateOverlayPermissionStatus()
        refreshSessions()
    }

    override fun onStop() {
        super.onStop()
        unregisterUpdateReceivers()
    }

    override fun onDestroy() {
        database.close()
        super.onDestroy()
    }

    private fun startTracking() {
        val intent = Intent(this, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START
            putExtra(TrackingService.EXTRA_FAKE_MODE, fakeModeSwitch.isChecked)
            putExtra(TrackingService.EXTRA_SPEED_UNIT, speedUnitSpinner.selectedItemPosition)
        }
        ContextCompat.startForegroundService(this, intent)
        statusText.text = if (Settings.canDrawOverlays(this)) {
            "Starting tracking service..."
        } else {
            "Tracking started without overlay permission. Metrics will still be saved."
        }
    }

    private fun sendServiceAction(action: String) {
        startService(Intent(this, TrackingService::class.java).setAction(action))
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            statusText.text = "Draw-over-apps permission is already granted."
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
        )
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun updateOverlayPermissionStatus() {
        if (Settings.canDrawOverlays(this)) {
            statusText.text = "Ready. Draw-over-apps permission is granted."
        }
    }

    private fun registerUpdateReceivers() {
        if (receiversRegistered) return
        val filter = IntentFilter().apply {
            addAction(TrackingService.ACTION_METRICS_UPDATE)
            addAction(TrackingService.ACTION_DIAGNOSTICS_UPDATE)
            addAction(TrackingService.ACTION_STATE_UPDATE)
        }
        ContextCompat.registerReceiver(
            this,
            updatesReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiversRegistered = true
    }

    private fun unregisterUpdateReceivers() {
        if (!receiversRegistered) return
        runCatching { unregisterReceiver(updatesReceiver) }
        receiversRegistered = false
    }

    private fun renderMetrics(intent: Intent) {
        val speedMps = intent.getDoubleExtra(TrackingService.EXTRA_SPEED_MPS, 0.0)
        val incline = intent.getDoubleExtra(TrackingService.EXTRA_INCLINE, 0.0)
        val distanceMeters =
            intent.getDoubleExtra(TrackingService.EXTRA_DISTANCE_METERS, 0.0)
        val elapsedMillis =
            intent.getLongExtra(TrackingService.EXTRA_ELAPSED_MILLIS, 0L)
        val paused = intent.getBooleanExtra(TrackingService.EXTRA_PAUSED, false)
        val dataStatus =
            intent.getStringExtra(TrackingService.EXTRA_DATA_STATUS) ?: "TRACKING"
        metricsText.text = String.format(
            Locale.US,
            "%s\nSpeed: %.2f mph\nIncline: %.2f %%\nDistance: %.3f mi\nTime: %s",
            if (paused) "PAUSED" else dataStatus,
            speedMps / 0.44704,
            incline,
            distanceMeters / 1609.344,
            SessionDatabase.formatDuration(elapsedMillis),
        )

        val rawDump = intent.getStringExtra(TrackingService.EXTRA_RAW_DUMP).orEmpty()
        if (rawDump.isNotBlank()) {
            val existing = logText.text?.toString().orEmpty()
            logText.text = ("$rawDump\n${"-".repeat(32)}\n$existing").take(MAX_LOG_CHARS)
        }
    }

    private fun renderDiagnostics(intent: Intent) {
        val parsedEvents =
            intent.getIntExtra(TrackingService.EXTRA_PARSED_EVENT_COUNT, 0)
        val ageMillis =
            intent.getLongExtra(TrackingService.EXTRA_LAST_BELT_AGE_MILLIS, -1L)
        val dataStatus =
            intent.getStringExtra(TrackingService.EXTRA_DATA_STATUS) ?: "UNKNOWN"
        val lastEvent = if (ageMillis < 0) {
            "never"
        } else {
            String.format(Locale.US, "%.1f seconds ago", ageMillis / 1000.0)
        }
        diagnosticsText.text = String.format(
            Locale.US,
            "Status: %s\nData source: Peloton Affernet\n" +
                "Real metric samples: %d\nLast metric sample: %s",
            dataStatus,
            parsedEvents,
            lastEvent,
        )

        val rawDump = intent.getStringExtra(TrackingService.EXTRA_RAW_DUMP).orEmpty()
        if (rawDump.isNotBlank() && rawDump != "Waiting for Peloton Affernet metrics.") {
            val existing = logText.text?.toString().orEmpty()
            if (!existing.startsWith(rawDump)) {
                logText.text =
                    ("$rawDump\n${"-".repeat(32)}\n$existing").take(MAX_LOG_CHARS)
            }
        }
    }

    private fun refreshSessions() {
        sessionsText.text = database.recentSessions()
    }

    companion object {
        private const val MAX_LOG_CHARS = 50_000
    }
}
