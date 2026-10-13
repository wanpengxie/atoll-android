package dev.atoll.app.app

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window

/** FrameMetrics delivery runs off the UI thread; no per-frame Compose state updates. */
class WindowFrameObserver(private val window: Window, private val telemetry: AppTelemetry, refreshRate: Float) : AutoCloseable {
    private val worker = HandlerThread("atoll-frame-metrics").apply { start() }
    private val fallbackBudget = (1_000_000_000.0 / refreshRate.coerceAtLeast(1f)).toLong()
    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
        val deadline = if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else 0L
        telemetry.frame(metrics.getMetric(FrameMetrics.TOTAL_DURATION), deadline.takeIf { it > 0 } ?: fallbackBudget, dropped)
    }
    init { window.addOnFrameMetricsAvailableListener(listener, Handler(worker.looper)) }
    override fun close() { window.removeOnFrameMetricsAvailableListener(listener); worker.quitSafely() }
}
