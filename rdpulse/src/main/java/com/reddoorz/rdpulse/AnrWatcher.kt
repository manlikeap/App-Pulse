package com.reddoorz.rdpulse

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class AnrWatcher(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "rdpulse_anr"
        private const val CHANNEL_NAME = "RdPulse — ANR Alerts"
        private const val PING_INTERVAL_MS = 600L
    }

    val mainThreadBlocked = AtomicBoolean(false)
    val capturedStack = AtomicReference("")

    @Volatile
    var currentActivityName: String = "Unknown"

    val anrHistory = ArrayDeque<AnrEvent>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var watcherThread: Thread? = null
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when the main thread is blocked (potential ANR)"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun start() {
        if (watcherThread?.isAlive == true) return
        watcherThread = Thread {
            while (true) {
                val responded = AtomicBoolean(false)
                mainHandler.post { responded.set(true) }
                try {
                    Thread.sleep(PING_INTERVAL_MS)
                    if (!responded.get()) {
                        val frames = Looper.getMainLooper().thread.stackTrace
                        val trace = frames.take(14)
                            .filter { it.className.isNotEmpty() }
                            .joinToString("\n") { f ->
                                val cls = f.className.substringAfterLast(".")
                                "  $cls.${f.methodName}(${f.fileName}:${f.lineNumber})"
                            }
                        val snapshot = trace.ifEmpty { "  (no stack available)" }
                        capturedStack.set(snapshot)

                        if (!mainThreadBlocked.getAndSet(true)) {
                            // First detection of this block — record and notify
                            val ts = timeFormat.format(Date())
                            val actName = currentActivityName
                            val event = AnrEvent(
                                timestamp = ts,
                                activityName = actName,
                                trace = snapshot
                            )
                            synchronized(anrHistory) {
                                anrHistory.addFirst(event)
                                if (anrHistory.size > 5) anrHistory.removeLast()
                            }
                            fireNotification(event)
                        }
                    } else {
                        mainThreadBlocked.set(false)
                        capturedStack.set("")
                    }
                    Thread.sleep(400)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.also {
            it.name = "rd-anr-watcher"
            it.isDaemon = true
            it.start()
        }
    }

    private fun fireNotification(event: AnrEvent) {
        val notifId = (event.timestamp.hashCode() and 0x7FFFFFFF)
        val shortTrace = event.trace.take(200)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("⚠ ANR on ${event.activityName}")
            .setContentText(shortTrace)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(event.trace)
                    .setBigContentTitle("⚠ ANR on ${event.activityName}")
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            notificationManager.notify(notifId, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS permission not granted — silently skip
        }
    }
}
