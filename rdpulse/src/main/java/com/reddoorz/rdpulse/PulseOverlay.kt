package com.reddoorz.rdpulse

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object PulseOverlay {

    private const val OVERLAY_TAG = "rdpulse_overlay"

    // View tags
    private const val T_CIRCLE = "T_CIRCLE"
    private const val T_CIRCLE_CPU = "T_CIRCLE_CPU"
    private const val T_CIRCLE_MEM = "T_CIRCLE_MEM"
    private const val T_PANEL = "T_PANEL"
    private const val T_CPU_VAL = "T_CPU_VAL"
    private const val T_CPU_BAR = "T_CPU_BAR"
    private const val T_MEM_VAL = "T_MEM_VAL"
    private const val T_MEM_BAR = "T_MEM_BAR"
    private const val T_FPS_VAL = "T_FPS_VAL"
    private const val T_THREADS_VAL = "T_THREADS_VAL"
    private const val T_NET_VAL = "T_NET_VAL"
    private const val T_BAT_VAL = "T_BAT_VAL"
    private const val T_JANK_VAL = "T_JANK_VAL"
    private const val T_ANR_STATUS = "T_ANR_STATUS"
    private const val T_ANR_TRACE = "T_ANR_TRACE"
    private const val T_ANR_HISTORY_CONTAINER = "T_ANR_HISTORY_CONTAINER"
    private const val T_HEADER_DOT = "T_HEADER_DOT"

    // Colors
    private val COLOR_OK = Color.parseColor("#4CAF50")
    private val COLOR_MILD = Color.parseColor("#FFC107")
    private val COLOR_WARN = Color.parseColor("#FF9800")
    private val COLOR_CRIT = Color.parseColor("#F44336")
    private val COLOR_MUTED = Color.parseColor("#888888")

    // State
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var currentFps: Int = 0
    @Volatile private var jankCount: Int = 0
    @Volatile private var dismissed = false
    @Volatile private var isExpanded = false
    @Volatile private var choreographerRunning = false

    private var fpsFrameCount = 0
    private var fpsWindowStart = 0L
    private var lastFrameNanos = 0L

    private var pollingScope: CoroutineScope? = null
    private var anrWatcher: AnrWatcher? = null
    private var metrics: MetricsCollector? = null

    // ── Choreographer ─────────────────────────────────────────────────────────

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            fpsFrameCount++
            if (lastFrameNanos > 0L) {
                val ms = (frameTimeNanos - lastFrameNanos) / 1_000_000L
                if (ms > 32L) jankCount++
            }
            lastFrameNanos = frameTimeNanos
            val now = android.os.SystemClock.elapsedRealtime()
            if (fpsWindowStart == 0L) fpsWindowStart = now
            val elapsed = now - fpsWindowStart
            if (elapsed >= 1000L) {
                currentFps = (fpsFrameCount * 1000L / elapsed).toInt()
                fpsFrameCount = 0
                fpsWindowStart = now
            }
            if (choreographerRunning) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ── Lifecycle callbacks ───────────────────────────────────────────────────

    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            anrWatcher?.currentActivityName = activity.javaClass.simpleName
            dismissed = false
            attach(activity)
        }

        override fun onActivityPaused(activity: Activity) {
            detach(activity)
        }

        override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
        override fun onActivityStarted(a: Activity) = Unit
        override fun onActivityStopped(a: Activity) = Unit
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
        override fun onActivityDestroyed(a: Activity) = Unit
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    fun install(app: Application) {
        anrWatcher = AnrWatcher(app).also { it.start() }
        metrics = MetricsCollector()
        app.registerActivityLifecycleCallbacks(lifecycleCallbacks)
    }

    // ── Attach / detach ───────────────────────────────────────────────────────

    private fun attach(activity: Activity) {
        if (dismissed) return
        val decor = activity.window.decorView as? FrameLayout ?: return
        if (decor.findViewWithTag<View>(OVERLAY_TAG) != null) return

        val overlay = buildOverlay(activity)
        overlay.tag = OVERLAY_TAG

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(activity, 100f)
            leftMargin = dp(activity, 14f)
        }

        try {
            decor.addView(overlay, lp)
        } catch (_: Exception) {
            return
        }

        // Start choreographer on main thread
        mainHandler.post {
            choreographerRunning = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }

        startPolling(activity, overlay)
    }

    private fun detach(activity: Activity) {
        choreographerRunning = false
        pollingScope?.cancel()
        pollingScope = null
        val decor = activity.window.decorView as? ViewGroup ?: return
        decor.findViewWithTag<View>(OVERLAY_TAG)?.let { decor.removeView(it) }
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    private fun startPolling(ctx: Context, root: LinearLayout) {
        val m = metrics ?: return
        val watcher = anrWatcher ?: return

        pollingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        pollingScope?.launch {
            while (isActive) {
                val cpu = m.appCpuPercent()
                val (memUsed, memMax) = m.heapInfo()
                val (txKbps, rxKbps) = m.networkKbps()
                val threads = m.threadCount()
                val (batPct, charging) = m.batteryInfo(ctx)
                val blocked = watcher.mainThreadBlocked.get()
                val anrTrace = watcher.capturedStack.get()
                val fps = currentFps
                val jank = jankCount
                val memPct = if (memMax > 0) (memUsed * 100 / memMax).toInt() else 0

                // Snapshot ANR history
                val anrHistorySnapshot: List<AnrEvent>
                synchronized(watcher.anrHistory) {
                    anrHistorySnapshot = watcher.anrHistory.toList()
                }

                withContext(Dispatchers.Main) {
                    if (!root.isAttachedToWindow) return@withContext

                    val statusColor = statusColor(cpu, blocked)

                    // Circle updates
                    updateCircleBorder(root, statusColor)
                    root.findViewWithTag<TextView>(T_CIRCLE_CPU)?.text =
                        if (cpu < 0) "?" else "${cpu}%"
                    root.findViewWithTag<TextView>(T_CIRCLE_MEM)?.text = "${memUsed}MB"

                    // Header dot
                    root.findViewWithTag<View>(T_HEADER_DOT)?.let {
                        (it.background as? GradientDrawable)?.setColor(statusColor)
                    }

                    // CPU
                    root.findViewWithTag<TextView>(T_CPU_VAL)?.apply {
                        text = if (cpu < 0) "N/A" else "${cpu}%"
                        setTextColor(statusColor(cpu, false))
                    }
                    updateBar(root, T_CPU_BAR, cpu.coerceIn(0, 100), statusColor(cpu, false))

                    // Memory
                    root.findViewWithTag<TextView>(T_MEM_VAL)?.apply {
                        text = "${memUsed} / ${memMax} MB  ($memPct%)"
                        setTextColor(memColor(memPct))
                    }
                    updateBar(root, T_MEM_BAR, memPct, memColor(memPct))

                    // FPS
                    root.findViewWithTag<TextView>(T_FPS_VAL)?.apply {
                        text = "FPS: $fps"
                        setTextColor(if (fps in 1..44) COLOR_WARN else COLOR_OK)
                    }

                    // Threads
                    root.findViewWithTag<TextView>(T_THREADS_VAL)?.text = "Threads: $threads"

                    // Network
                    root.findViewWithTag<TextView>(T_NET_VAL)?.text =
                        "↑ ${txKbps} KB/s   ↓ ${rxKbps} KB/s"

                    // Battery
                    root.findViewWithTag<TextView>(T_BAT_VAL)?.apply {
                        val icon = if (charging) "⚡" else ""
                        text = "Battery: ${if (batPct < 0) "?" else "$batPct%"} $icon".trim()
                        setTextColor(if (batPct in 0..20) COLOR_CRIT else COLOR_MUTED)
                    }

                    // Jank
                    root.findViewWithTag<TextView>(T_JANK_VAL)?.apply {
                        text = "Jank frames: $jank"
                        setTextColor(if (jank > 10) COLOR_WARN else COLOR_MUTED)
                    }

                    // ANR status
                    root.findViewWithTag<TextView>(T_ANR_STATUS)?.apply {
                        if (blocked) {
                            text = "⚠  BLOCKED — potential ANR"
                            setTextColor(COLOR_CRIT)
                        } else {
                            text = "✓  Main thread responsive"
                            setTextColor(COLOR_OK)
                        }
                    }

                    // ANR trace
                    root.findViewWithTag<TextView>(T_ANR_TRACE)?.apply {
                        if (anrTrace.isNotEmpty()) {
                            text = anrTrace
                            visibility = View.VISIBLE
                        } else {
                            visibility = View.GONE
                        }
                    }

                    // Rebuild ANR history
                    rebuildAnrHistory(root, ctx, anrHistorySnapshot)
                }

                delay(1000)
            }
        }
    }

    // ── ANR history rebuild ───────────────────────────────────────────────────

    private fun rebuildAnrHistory(root: View, ctx: Context, events: List<AnrEvent>) {
        val container = root.findViewWithTag<LinearLayout>(T_ANR_HISTORY_CONTAINER) ?: return
        container.removeAllViews()
        if (events.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "No ANR events recorded"
                textSize = 9f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#666666"))
                setPadding(dp(ctx, 4f), dp(ctx, 4f), dp(ctx, 4f), dp(ctx, 4f))
            })
        } else {
            events.forEachIndexed { index, event ->
                if (index > 0) {
                    container.addView(View(ctx).apply {
                        setBackgroundColor(Color.parseColor("#333333"))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, 1
                        ).also { it.topMargin = dp(ctx, 4f); it.bottomMargin = dp(ctx, 4f) }
                    })
                }
                container.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = roundRect(Color.parseColor("#1A1A1A"), 6f)
                    setPadding(dp(ctx, 6f), dp(ctx, 4f), dp(ctx, 6f), dp(ctx, 4f))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = dp(ctx, 2f) }

                    addView(TextView(ctx).apply {
                        text = "[${event.timestamp}] ${event.activityName}"
                        textSize = 9f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.parseColor("#AAAAAA"))
                    })
                    addView(TextView(ctx).apply {
                        text = event.trace
                        textSize = 9f
                        typeface = Typeface.MONOSPACE
                        setTextColor(Color.parseColor("#666666"))
                    })
                })
            }
        }
    }

    // ── View building ─────────────────────────────────────────────────────────

    private fun buildOverlay(ctx: Context): LinearLayout {
        val circleSize = dp(ctx, 72f)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        // ── Circle ──────────────────────────────────────────────────────────
        val circle = FrameLayout(ctx).apply {
            tag = T_CIRCLE
            layoutParams = LinearLayout.LayoutParams(circleSize, circleSize)
            background = buildCircleBg(COLOR_OK)
            elevation = dp(ctx, 6f).toFloat()

            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                addView(TextView(ctx).apply {
                    tag = T_CIRCLE_CPU
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    text = "--"
                })
                addView(TextView(ctx).apply {
                    tag = T_CIRCLE_MEM
                    textSize = 9f
                    setTextColor(Color.parseColor("#AAAAAA"))
                    gravity = Gravity.CENTER
                    text = "--"
                })
            })
        }

        // ── Expanded panel ───────────────────────────────────────────────────
        val panel = LinearLayout(ctx).apply {
            tag = T_PANEL
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 260f), LinearLayout.LayoutParams.WRAP_CONTENT)
            background = roundRect(Color.parseColor("#EE0D0D0D"), dp(ctx, 12f).toFloat())
            elevation = dp(ctx, 8f).toFloat()
            setPadding(dp(ctx, 12f), dp(ctx, 10f), dp(ctx, 12f), dp(ctx, 12f))

            // Header row
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = lp(ctx, matchW = true, bottomMarginDp = 8f)

                addView(View(ctx).apply {
                    tag = T_HEADER_DOT
                    layoutParams = LinearLayout.LayoutParams(dp(ctx, 7f), dp(ctx, 7f)).also {
                        it.marginEnd = dp(ctx, 6f)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(COLOR_OK)
                    }
                })
                addView(TextView(ctx).apply {
                    text = "Performance Monitor (RdPulse)"
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(ctx).apply {
                    text = "✕"
                    textSize = 14f
                    setTextColor(Color.parseColor("#777777"))
                    setPadding(dp(ctx, 8f), 0, 0, 0)
                    setOnClickListener {
                        dismissed = true
                        choreographerRunning = false
                        pollingScope?.cancel()
                        pollingScope = null
                        (root.parent as? ViewGroup)?.removeView(root)
                    }
                })
            })

            addView(divider(ctx))

            // CPU section
            addView(sectionLabel(ctx, "APP CPU"))
            addView(TextView(ctx).apply {
                tag = T_CPU_VAL
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(COLOR_OK)
                text = "—"
            })
            addView(track(ctx, T_CPU_BAR))
            addView(spacer(ctx, 8f))

            // Memory section
            addView(sectionLabel(ctx, "HEAP MEMORY"))
            addView(TextView(ctx).apply {
                tag = T_MEM_VAL
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(COLOR_OK)
                text = "—"
            })
            addView(track(ctx, T_MEM_BAR))

            addView(divider(ctx))

            // FPS + Threads row
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = lp(ctx, matchW = true, bottomMarginDp = 4f)
                addView(TextView(ctx).apply {
                    tag = T_FPS_VAL
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    setTextColor(COLOR_OK)
                    text = "FPS: --"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(ctx).apply {
                    tag = T_THREADS_VAL
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    setTextColor(COLOR_MUTED)
                    text = "Threads: --"
                })
            })

            // Network
            addView(TextView(ctx).apply {
                tag = T_NET_VAL
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(COLOR_MUTED)
                text = "↑ -- KB/s   ↓ -- KB/s"
                layoutParams = lp(ctx, matchW = true, bottomMarginDp = 4f)
            })

            // Battery
            addView(TextView(ctx).apply {
                tag = T_BAT_VAL
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(COLOR_MUTED)
                text = "Battery: --"
                layoutParams = lp(ctx, matchW = true, bottomMarginDp = 0f)
            })

            addView(divider(ctx))

            // Jank
            addView(TextView(ctx).apply {
                tag = T_JANK_VAL
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(COLOR_MUTED)
                text = "Jank frames: 0"
                layoutParams = lp(ctx, matchW = true, bottomMarginDp = 6f)
            })

            // Main thread / ANR section
            addView(sectionLabel(ctx, "MAIN THREAD"))
            addView(TextView(ctx).apply {
                tag = T_ANR_STATUS
                textSize = 11f
                setTextColor(COLOR_OK)
                text = "✓  Main thread responsive"
                layoutParams = lp(ctx, matchW = true, bottomMarginDp = 2f)
            })
            addView(TextView(ctx).apply {
                tag = T_ANR_TRACE
                textSize = 9f
                typeface = Typeface.MONOSPACE
                setTextColor(COLOR_CRIT)
                visibility = View.GONE
                layoutParams = lp(ctx, matchW = true, bottomMarginDp = 4f)
            })

            addView(divider(ctx))

            // ANR history header row
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = lp(ctx, matchW = true, bottomMarginDp = 4f)
                addView(TextView(ctx).apply {
                    text = "ANR HISTORY"
                    textSize = 9f
                    setTextColor(Color.parseColor("#777777"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(ctx).apply {
                    text = "Clear"
                    textSize = 9f
                    setTextColor(Color.parseColor("#555555"))
                    setOnClickListener {
                        val watcher = anrWatcher ?: return@setOnClickListener
                        synchronized(watcher.anrHistory) { watcher.anrHistory.clear() }
                        watcher.capturedStack.set("")
                        watcher.mainThreadBlocked.set(false)
                        // Immediate UI update
                        val container = root.findViewWithTag<LinearLayout>(T_ANR_HISTORY_CONTAINER)
                        container?.removeAllViews()
                        container?.addView(TextView(ctx).apply {
                            text = "No ANR events recorded"
                            textSize = 9f
                            typeface = Typeface.MONOSPACE
                            setTextColor(Color.parseColor("#666666"))
                            setPadding(dp(ctx, 4f), dp(ctx, 4f), dp(ctx, 4f), dp(ctx, 4f))
                        })
                        root.findViewWithTag<TextView>(T_ANR_TRACE)?.visibility = View.GONE
                    }
                })
            })

            // ANR history scroll area (max 180dp)
            addView(MaxHeightScrollView(ctx, dp(ctx, 180f)).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                addView(LinearLayout(ctx).apply {
                    tag = T_ANR_HISTORY_CONTAINER
                    orientation = LinearLayout.VERTICAL
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                    addView(TextView(ctx).apply {
                        text = "No ANR events recorded"
                        textSize = 9f
                        typeface = Typeface.MONOSPACE
                        setTextColor(Color.parseColor("#666666"))
                        setPadding(dp(ctx, 4f), dp(ctx, 4f), dp(ctx, 4f), dp(ctx, 4f))
                    })
                })
            })
        }

        // ── Tap to expand/collapse + drag ────────────────────────────────────
        var dX = 0f
        var dY = 0f
        var startRawX = 0f
        var startRawY = 0f
        var isDragging = false

        root.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                    startRawX = event.rawX
                    startRawY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = kotlin.math.abs(event.rawX - startRawX)
                    val dy = kotlin.math.abs(event.rawY - startRawY)
                    if (!isDragging && (dx > 10 || dy > 10)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        v.x = (event.rawX + dX).coerceAtLeast(0f)
                        v.y = (event.rawY + dY).coerceAtLeast(0f)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Tap — toggle expand
                        isExpanded = !isExpanded
                        panel.visibility = if (isExpanded) View.VISIBLE else View.GONE
                    }
                    true
                }
                else -> false
            }
        }

        root.addView(circle)
        root.addView(panel)

        return root
    }

    // ── Inner class: height-capped ScrollView ─────────────────────────────────

    private class MaxHeightScrollView(ctx: Context, private val maxHeightPx: Int) : ScrollView(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cappedHeight = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
            super.onMeasure(widthMeasureSpec, cappedHeight)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun updateCircleBorder(root: View, color: Int) {
        root.findViewWithTag<FrameLayout>(T_CIRCLE)?.background = buildCircleBg(color)
    }

    private fun updateBar(root: View, fillTag: String, pct: Int, color: Int) {
        val fill = root.findViewWithTag<View>(fillTag) ?: return
        val track = fill.parent as? FrameLayout ?: return
        val w = track.width.takeIf { it > 0 } ?: return
        fill.layoutParams = (fill.layoutParams as FrameLayout.LayoutParams).also {
            it.width = (w * pct / 100).coerceAtLeast(0)
        }
        fill.requestLayout()
        (fill.background as? GradientDrawable)?.setColor(color)
    }

    private fun buildCircleBg(ringColor: Int): LayerDrawable {
        val outer = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ringColor)
        }
        val inner = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#E8000000"))
        }
        return LayerDrawable(arrayOf(outer, inner)).apply {
            setLayerInset(1, 5, 5, 5, 5)
        }
    }

    private fun statusColor(cpu: Int, blocked: Boolean) = when {
        blocked -> COLOR_CRIT
        cpu >= 80 -> COLOR_CRIT
        cpu >= 50 -> COLOR_WARN
        cpu >= 25 -> COLOR_MILD
        else -> COLOR_OK
    }

    private fun memColor(pct: Int) = when {
        pct > 85 -> COLOR_CRIT
        pct > 65 -> COLOR_WARN
        else -> COLOR_OK
    }

    private fun track(ctx: Context, fillTag: String) = FrameLayout(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 5f)
        ).also { it.topMargin = dp(ctx, 3f); it.bottomMargin = dp(ctx, 4f) }
        background = roundRect(Color.parseColor("#2A2A2A"), 4f)
        addView(View(ctx).apply {
            tag = fillTag
            layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
            background = roundRect(COLOR_OK, 4f)
        })
    }

    private fun sectionLabel(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 9f
        setTextColor(Color.parseColor("#666666"))
        layoutParams = lp(ctx, matchW = true, bottomMarginDp = 2f)
    }

    private fun divider(ctx: Context) = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#222222"))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        ).also { it.topMargin = dp(ctx, 8f); it.bottomMargin = dp(ctx, 8f) }
    }

    private fun spacer(ctx: Context, dpVal: Float) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, dpVal))
    }

    private fun lp(ctx: Context, matchW: Boolean = false, bottomMarginDp: Float = 0f) =
        LinearLayout.LayoutParams(
            if (matchW) LinearLayout.LayoutParams.MATCH_PARENT else LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp(ctx, bottomMarginDp) }

    private fun roundRect(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(ctx: Context, value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, ctx.resources.displayMetrics).toInt()
}
