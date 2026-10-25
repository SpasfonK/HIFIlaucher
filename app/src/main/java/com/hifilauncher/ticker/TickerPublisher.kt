package com.hifilauncher.ticker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.Date

/** Everything the secondary display (or the in-app strip) shows. */
data class TickerState(
    val title: String = "",
    val artist: String = "",
    val codec: String = "",
    /** "24 bit / 96 kHz" */
    val detail: String = "",
    val bitrateKbps: Int = 0,
    val batteryPct: Int = 0,
    val charging: Boolean = false,
    val clock: String = "",
    val playing: Boolean = false,
)

/**
 * Process-wide state shared by the playback service (writer) and the UI (reader).
 * The service and the launcher live in the same process, so no IPC is needed here.
 */
object TickerHub {
    val state = MutableStateFlow(TickerState())
    val activeSink = MutableStateFlow("none")

    /** Set by the service: re-evaluates which sink to use (after a settings change). */
    @Volatile
    var reload: (() -> Unit)? = null
}

/**
 * Event driven, no polling: track and play state come from the player, the clock from
 * ACTION_TIME_TICK (once a minute), the battery from ACTION_BATTERY_CHANGED. Bursts of
 * updates are coalesced into one push after [COALESCE_MS], and a push is skipped when the
 * state is identical to the last one, so the sink is touched as rarely as possible.
 *
 * The first candidate whose start() succeeds becomes the active sink; the others are
 * fallbacks (e.g. no overlay permission, so use the notification, then the in-app strip).
 */
class TickerPublisher(
    private val ctx: Context,
    private val candidates: () -> List<TickerSink>,
) {
    private val main = Handler(Looper.getMainLooper())
    private var active: TickerSink = NoopTickerSink
    private var lastPushed: TickerState? = null
    private val flush = Runnable { push() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onSystemEvent(intent)
    }

    /** Main thread. */
    fun start() {
        selectSink()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        // ACTION_BATTERY_CHANGED is sticky: the return value is the current battery state.
        ctx.registerReceiver(receiver, filter)?.let { onSystemEvent(it) }
        update { it.copy(clock = clockNow()) }
    }

    fun stop() {
        main.removeCallbacks(flush)
        runCatching { ctx.unregisterReceiver(receiver) }
        active.stop()
        active = NoopTickerSink
    }

    /** Any thread. */
    fun update(transform: (TickerState) -> TickerState) {
        TickerHub.state.update(transform)
        main.removeCallbacks(flush)
        main.postDelayed(flush, COALESCE_MS)
    }

    fun reload() {
        main.post { selectSink() }
    }

    private fun selectSink() {
        active.stop()
        active = candidates().firstOrNull { sink ->
            val ok = runCatching { sink.start(ctx) }.getOrDefault(false)
            if (!ok) sink.stop()
            ok
        } ?: NoopTickerSink
        TickerHub.activeSink.value = active.id
        lastPushed = null
        push()
    }

    private fun push() {
        val s = TickerHub.state.value
        if (s == lastPushed) return
        lastPushed = s
        active.update(s)
    }

    private fun onSystemEvent(i: Intent) {
        if (i.action == Intent.ACTION_BATTERY_CHANGED) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            update { it.copy(batteryPct = pct, charging = charging) }
        } else {
            update { it.copy(clock = clockNow()) }
        }
    }

    private fun clockNow(): String = DateFormat.getTimeFormat(ctx).format(Date())

    private companion object {
        const val COALESCE_MS = 80L
    }
}
