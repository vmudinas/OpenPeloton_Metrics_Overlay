package org.openpelo.beltstatusdump

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel

data class AffernetMetrics(
    val speedRaw: Int,
    val inclineRaw: Int,
)

class AffernetTreadClient(
    private val context: Context,
    private val onConnectionChanged: (Boolean, String?) -> Unit,
    private val onMetrics: (AffernetMetrics) -> Unit,
) {
    private val mainHandler = Handler(context.mainLooper)
    private val pollingThread = HandlerThread("AffernetMetricPoll").apply { start() }
    private val pollingHandler = Handler(pollingThread.looper)
    private var binder: IBinder? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service
            onConnectionChanged(true, null)
            pollingHandler.removeCallbacks(pollRunnable)
            pollingHandler.post(pollRunnable)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            binder = null
            onConnectionChanged(false, "Affernet service disconnected")
        }

        override fun onBindingDied(name: ComponentName) {
            binder = null
            onConnectionChanged(false, "Affernet binding died")
        }

        override fun onNullBinding(name: ComponentName) {
            binder = null
            onConnectionChanged(false, "Affernet returned a null Tread interface")
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            val activeBinder = binder
            if (activeBinder != null) {
                runCatching {
                    AffernetMetrics(
                        speedRaw = readInt(activeBinder, TRANSACTION_GET_CURRENT_SPEED),
                        inclineRaw = readInt(activeBinder, TRANSACTION_GET_CURRENT_INCLINE),
                    )
                }.onSuccess { metrics ->
                    mainHandler.post { onMetrics(metrics) }
                }.onFailure { error ->
                    mainHandler.post {
                        onConnectionChanged(
                            false,
                            "Affernet read failed: ${error.message ?: error.javaClass.simpleName}",
                        )
                    }
                }
            }
            pollingHandler.postDelayed(this, POLL_INTERVAL_MILLIS)
        }
    }

    fun bind() {
        val intent = Intent(TREAD_INTERFACE_ACTION).apply {
            component = ComponentName(AFFERNET_PACKAGE, AFFERNET_SERVICE)
        }
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            onConnectionChanged(false, "Unable to bind Peloton Affernet Tread interface")
        }
    }

    fun close() {
        pollingHandler.removeCallbacksAndMessages(null)
        binder = null
        if (bound) {
            runCatching { context.unbindService(connection) }
            bound = false
        }
        pollingThread.quitSafely()
    }

    private fun readInt(binder: IBinder, transactionCode: Int): Int {
        val request = Parcel.obtain()
        val response = Parcel.obtain()
        return try {
            request.writeInterfaceToken(TREAD_INTERFACE_ACTION)
            check(binder.transact(transactionCode, request, response, 0)) {
                "Affernet transaction $transactionCode was rejected"
            }
            response.readException()
            response.readInt()
        } finally {
            response.recycle()
            request.recycle()
        }
    }

    companion object {
        private const val AFFERNET_PACKAGE = "com.onepeloton.affernetservice"
        private const val AFFERNET_SERVICE =
            "com.onepeloton.affernetservice.AffernetService"
        private const val TREAD_INTERFACE_ACTION =
            "com.onepeloton.affernetservice.ITreadInterface"
        private const val TRANSACTION_GET_CURRENT_SPEED = 0x11
        private const val TRANSACTION_GET_CURRENT_INCLINE = 0x12
        private const val POLL_INTERVAL_MILLIS = 500L
    }
}
