package com.reddoorz.pulsedemo

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug-only screen with one button per problem RdPulse can detect, so each
 * overlay metric can be triggered on demand (e.g. for screenshots).
 */
class PulseDemoActivity : Activity() {

    private companion object {
        // Deliberately static: these are the "leaks".
        val leakedMemory = ArrayList<ByteArray>()
        val leakedActivities = ArrayList<Activity>()
        val leakedThreads = ArrayList<Thread>()
        val cpuBurning = AtomicBoolean(false)
        val continuousLeak = AtomicBoolean(false)
    }

    private lateinit var leakInfo: TextView
    private val ticker = object : Runnable {
        override fun run() {
            val rt = Runtime.getRuntime()
            val used = (rt.totalMemory() - rt.freeMemory()) / 1_048_576L
            val max = rt.maxMemory() / 1_048_576L
            leakInfo.text = "Leaked: ${leakedMemory.sumOf { it.size.toLong() } / 1_048_576L} MB in ${leakedMemory.size} arrays, " +
                "${leakedActivities.size} activities | Heap: $used / $max MB"
            if (continuousLeak.get()) {
                if (used * 100 / max >= 85) {
                    continuousLeak.set(false)
                    say("Continuous leak stopped at 85% of heap (to avoid a crash)")
                } else {
                    leakedMemory.add(ByteArray(5 * 1024 * 1024).also { it.fill(1) })
                }
            }
            main.postDelayed(this, 500)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(48), dp(16), dp(16))
            setBackgroundColor(Color.WHITE)
        }
        root.addView(TextView(this).apply {
            text = "Pulse Demo"
            textSize = 24f
            setTextColor(Color.BLACK)
        })
        leakInfo = TextView(this).apply {
            setTextColor(Color.parseColor("#B00020"))
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(leakInfo)
        status = TextView(this).apply {
            text = "Tap a button, then watch the overlay."
            setPadding(0, dp(8), 0, dp(12))
        }
        root.addView(status)

        button(root, "Trigger ANR (block main thread 6s)") { triggerAnr() }
        button(root, "Leak memory now (+50 MB)") { leakMemory() }
        button(root, "Start / stop continuous leak (+10 MB/s)") { toggleContinuousLeak() }
        button(root, "Leak this Activity (finish it)") { leakActivity() }
        button(root, "Free leaked memory") { freeMemory() }
        button(root, "Start CPU spike (4 threads)") { toggleCpu() }
        button(root, "Cause jank (slow frames)") { causeJank() }
        button(root, "Spawn 20 threads") { spawnThreads() }
        button(root, "Network burst (download ~20 MB)") { networkBurst() }

        actionBar?.hide()
        setContentView(ScrollView(this).apply {
            addView(root)
            // Edge-to-edge on targetSdk 35+: keep content below the status bar.
            setOnApplyWindowInsetsListener { _, insets ->
                root.setPadding(dp(16), insets.systemWindowInsetTop + dp(16), dp(16), dp(16))
                insets
            }
        })
        main.post(ticker)
    }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        super.onDestroy()
    }

    private fun triggerAnr() {
        say("Blocking main thread for 6s…")
        main.postDelayed({ Thread.sleep(6000); say("ANR block finished") }, 300)
    }

    private fun leakMemory() {
        repeat(5) { leakedMemory.add(ByteArray(10 * 1024 * 1024).also { it.fill(1) }) }
        say("Leaked ${leakedMemory.sumOf { it.size.toLong() } / 1_048_576L} MB total")
    }

    private fun toggleContinuousLeak() {
        say(if (continuousLeak.getAndSet(!continuousLeak.get())) "Continuous leak stopped"
            else "Leaking 10 MB/s… watch the heap bar")
    }

    private fun leakActivity() {
        leakedActivities.add(this)
        say("Activity leaked via static reference")
        finish()
    }

    private fun freeMemory() {
        continuousLeak.set(false)
        leakedMemory.clear()
        leakedActivities.clear()
        System.gc()
        say("Released leaked memory")
    }

    private fun toggleCpu() {
        if (cpuBurning.getAndSet(!cpuBurning.get())) {
            say("CPU spike stopped")
            return
        }
        say("CPU spike running… tap again to stop")
        repeat(4) {
            Thread {
                var x = 0.0
                while (cpuBurning.get()) x += Math.sqrt(x + 1.0)
            }.apply { name = "rd-demo-cpu"; isDaemon = true; start() }
        }
    }

    private fun causeJank() {
        say("Janking for ~3s…")
        var n = 0
        val tick = object : Runnable {
            override fun run() {
                Thread.sleep(80)
                if (++n < 30) main.postDelayed(this, 16) else say("Jank finished")
            }
        }
        main.post(tick)
    }

    private fun spawnThreads() {
        repeat(20) {
            leakedThreads.add(Thread { Thread.sleep(Long.MAX_VALUE) }.apply {
                name = "rd-demo-idle"; isDaemon = true; start()
            })
        }
        say("Spawned threads (total ${leakedThreads.size})")
    }

    private fun networkBurst() {
        say("Downloading…")
        Thread {
            val result = try {
                val buf = ByteArray(16 * 1024)
                var total = 0L
                URL("https://speed.cloudflare.com/__down?bytes=20000000").openStream().use {
                    while (true) {
                        val r = it.read(buf)
                        if (r < 0) break
                        total += r
                    }
                }
                "Downloaded ${total / 1024 / 1024} MB"
            } catch (e: Exception) {
                "Network failed: ${e.javaClass.simpleName}"
            }
            main.post { say(result) }
        }.start()
    }

    private fun say(msg: String) {
        if (::status.isInitialized) status.text = msg
    }

    private fun button(parent: LinearLayout, label: String, onClick: () -> Unit) {
        parent.addView(Button(this).apply {
            text = label
            isAllCaps = false
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
