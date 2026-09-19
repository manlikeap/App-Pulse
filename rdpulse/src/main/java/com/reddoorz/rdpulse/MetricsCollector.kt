package com.reddoorz.rdpulse

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import java.io.File

internal class MetricsCollector {

    private var prevAppCpuTime = 0L
    private var prevElapsedMs = 0L

    private var prevRxBytes = 0L
    private var prevTxBytes = 0L

    /**
     * Returns CPU usage as a percentage of one core (0-999).
     * Reads /proc/self/stat fields utime (index 11) + stime (index 12) after the last ") ".
     * Returns -1 on error.
     */
    fun appCpuPercent(): Int {
        return try {
            val text = File("/proc/self/stat").readText()
            val after = text.substringAfterLast(") ").trim()
            val parts = after.split(" ")
            val cpuTime = parts[11].toLong() + parts[12].toLong() // utime + stime (indices relative to after-paren section)
            val now = SystemClock.elapsedRealtime()
            val dCpu = cpuTime - prevAppCpuTime
            val dMs = now - prevElapsedMs
            prevAppCpuTime = cpuTime
            prevElapsedMs = now
            if (dMs <= 0L) 0 else (dCpu * 1000L / dMs).toInt().coerceIn(0, 999)
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * Returns (usedMB, maxMB) from the JVM heap.
     */
    fun heapInfo(): Pair<Long, Long> {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / 1_048_576L to
                rt.maxMemory() / 1_048_576L
    }

    /**
     * Returns (txKbps, rxKbps) for the current UID since last call.
     * Returns (0, 0) if TrafficStats is unsupported (rx < 0).
     */
    fun networkKbps(): Pair<Long, Long> {
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx < 0L || tx < 0L) return 0L to 0L
        val dRx = ((rx - prevRxBytes) / 1024L).coerceAtLeast(0L)
        val dTx = ((tx - prevTxBytes) / 1024L).coerceAtLeast(0L)
        prevRxBytes = rx
        prevTxBytes = tx
        return dTx to dRx
    }

    /**
     * Returns (levelPercent, isCharging) via sticky ACTION_BATTERY_CHANGED broadcast.
     * Returns (-1, false) on failure.
     */
    fun batteryInfo(ctx: Context): Pair<Int, Boolean> {
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return -1 to false
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        return pct to charging
    }

    /**
     * Returns the number of active threads in the current thread group.
     */
    fun threadCount(): Int = Thread.activeCount()
}
