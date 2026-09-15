
package com.aistudio.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat

class QueueForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val serviceScope = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var isRunning = false

    companion object {
        const val CHANNEL_ID = "AI_STUDIO_QUEUE"
        const val NOTIF_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        acquireLocks()
        Log.d("AI_STUDIO_FG", "Service onCreate - чистый Foreground Service")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "KEEP_ALIVE") {
            Log.d("AI_STUDIO_FG", "KEEP_ALIVE from JS")
            updateNotification("Очередь активна", "Автономная генерация работает")
            return START_STICKY
        }

        startForeground(NOTIF_ID, buildNotification("AI STUDIO", "Очередь готова • Работает при выкл. экране"))

        if (!isRunning) {
            isRunning = true
            serviceScope.execute {
                // Держим сервис живым, polling делает WebView JS, а мы держим WakeLock
                while (isRunning) {
                    try {
                        Thread.sleep(5000)
                        // Обновляем уведомление чтобы система видела активность
                        // Логика генерации в JS, мы только держим проц
                    } catch(e: Exception) {
                        Log.e("AI_STUDIO_FG", "Loop error", e)
                    }
                }
            }
        }

        return START_STICKY // Перезапустится если убьют
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "AI STUDIO Очередь", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Автономная генерация видео при выкл. экране"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(title, text))
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AI_STUDIO:QueueLock")
            wakeLock?.acquire(2 * 60 * 60 * 1000L) // 2 часа
            Log.d("AI_STUDIO_FG", "WakeLock acquired")

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AI_STUDIO:WifiLock")
            wifiLock?.acquire()
            Log.d("AI_STUDIO_FG", "WifiLock acquired")
        } catch(e: Exception) {
            Log.e("AI_STUDIO_FG", "Lock error", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        Log.d("AI_STUDIO_FG", "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Если юзер смахнул приложение - перезапускаем сервис
        val restart = Intent(applicationContext, QueueForegroundService::class.java)
        val pending = PendingIntent.getService(applicationContext, 1, restart, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.set(AlarmManager.RTC, System.currentTimeMillis() + 1000, pending)
        Log.d("AI_STUDIO_FG", "onTaskRemoved - scheduling restart")
    }
}
