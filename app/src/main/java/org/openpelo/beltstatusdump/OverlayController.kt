package org.openpelo.beltstatusdump

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

class OverlayController(
    private val context: Context,
    private val onTogglePause: () -> Unit,
    private val onToggleCompact: () -> Unit,
    private val onHide: () -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var rootView: View? = null
    private var metricsText: TextView? = null
    private var pauseButton: TextView? = null
    private var compactButton: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var compact = false
    private var latestSpeedMetersPerSecond = 0.0
    private var latestInclinePercent = 0.0
    private var latestDistanceMeters = 0.0
    private var latestElapsedMillis = 0L
    private var latestPaused = false
    private var latestDataStatus = "WAITING FOR BELT DATA"

    fun show(startCompact: Boolean = compact) {
        if (rootView != null) return
        compact = startCompact

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val pauseControl = controlText(if (latestPaused) "▶" else "Ⅱ", "Pause or resume") {
            onTogglePause()
        }
        pauseButton = pauseControl
        val compactControl = controlText(
            if (compact) "□" else "−",
            if (compact) "Expand overlay" else "Minimize overlay",
        ) {
            onToggleCompact()
        }
        compactButton = compactControl
        val hideControl = controlText("×", "Hide overlay") {
            onHide()
        }
        controls.addView(pauseControl)
        controls.addView(compactControl)
        controls.addView(hideControl)

        val text = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(12), dp(2), dp(12), dp(10))
        }
        metricsText = text

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(220, 18, 18, 18))
                setStroke(dp(1), Color.argb(180, 255, 255, 255))
            }
            addView(controls)
            addView(text)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(24)
            y = dp(100)
        }
        layoutParams = params
        attachDragHandler(container, params)

        windowManager.addView(container, params)
        rootView = container
        render()
    }

    fun setCompact(value: Boolean) {
        compact = value
        compactButton?.text = if (compact) "□" else "−"
        render()
    }

    fun update(
        speedMetersPerSecond: Double,
        inclinePercent: Double,
        distanceMeters: Double,
        elapsedMillis: Long,
        paused: Boolean,
        dataStatus: String,
    ) {
        latestSpeedMetersPerSecond = speedMetersPerSecond
        latestInclinePercent = inclinePercent
        latestDistanceMeters = distanceMeters
        latestElapsedMillis = elapsedMillis
        latestPaused = paused
        latestDataStatus = dataStatus
        pauseButton?.text = if (paused) "▶" else "Ⅱ"
        render()
    }

    fun remove() {
        rootView?.let { runCatching { windowManager.removeView(it) } }
        rootView = null
        metricsText = null
        pauseButton = null
        compactButton = null
        layoutParams = null
    }

    private fun render() {
        val speedMph = latestSpeedMetersPerSecond / 0.44704
        val distanceMiles = latestDistanceMeters / 1609.344
        metricsText?.text = if (compact) {
            String.format(
                Locale.US,
                "%.1f mph  |  %.1f%%  |  %.2f mi  |  %s",
                speedMph,
                latestInclinePercent,
                distanceMiles,
                SessionDatabase.formatDuration(latestElapsedMillis),
            )
        } else {
            String.format(
                Locale.US,
                "%s\nSpeed     %5.1f mph\nIncline   %5.1f %%\nDistance  %5.2f mi\nTime      %s",
                if (latestPaused) "PAUSED" else latestDataStatus,
                speedMph,
                latestInclinePercent,
                distanceMiles,
                SessionDatabase.formatDuration(latestElapsedMillis),
            )
        }
    }

    private fun controlText(
        label: String,
        description: String,
        action: () -> Unit,
    ): TextView =
        TextView(context).apply {
            text = label
            contentDescription = description
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            minWidth = dp(44)
            minHeight = dp(36)
            setOnClickListener { action() }
        }

    private fun attachDragHandler(
        container: LinearLayout,
        params: WindowManager.LayoutParams,
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false
        container.setOnClickListener { }
        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY
                    moved = moved || kotlin.math.abs(deltaX) > dp(4) ||
                        kotlin.math.abs(deltaY) > dp(4)
                    if (moved) {
                        params.x = initialX - deltaX.toInt()
                        params.y = initialY + deltaY.toInt()
                        windowManager.updateViewLayout(container, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) container.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
