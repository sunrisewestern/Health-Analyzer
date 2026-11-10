package com.healthanalyzer.app.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.healthanalyzer.app.MainActivity
import com.healthanalyzer.app.R

/**
 * Keeps the process allowed on the network while an AI request streams.
 *
 * Without it, switching apps or turning the screen off lets Android (Doze, app standby,
 * OEM battery savers) cut the app's sockets mid-request, which shows up as
 * "Software caused connection abort". A foreground service plus a wake lock keeps the
 * request alive until the answer is complete.
 */
object AiKeepAlive {
    private const val TAG = "AiKeepAlive"
    private var holders = 0

    @Synchronized
    fun acquire(context: Context) {
        holders++
        if (holders == 1) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, AiRequestService::class.java))
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException when started from the background;
                // the request still runs, just without the protection.
                Log.w(TAG, "Could not start the foreground service", e)
            }
        }
    }

    @Synchronized
    fun release(context: Context) {
        if (holders == 0) return
        holders--
        if (holders == 0) context.stopService(Intent(context, AiRequestService::class.java))
    }
}

class AiRequestService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w("AiKeepAlive", "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HealthAnalyzer:ai-request")
                .apply { setReferenceCounted(false); acquire(MAX_HOLD_MS) }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HealthAnalyzer:ai-request")
                ?.apply { setReferenceCounted(false); runCatching { acquire() } }
        }
        return START_NOT_STICKY
    }

    /** Android 15+ caps dataSync services; give up the protection rather than crash. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AI requests", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while an AI analysis or chat reply is being generated"
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_stat_ai)
            .setContentTitle("AI is working…")
            .setContentText("Generating your analysis. You can switch apps.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "ai_requests"
        private const val NOTIFICATION_ID = 4101
        private const val MAX_HOLD_MS = 30 * 60 * 1000L
    }
}
