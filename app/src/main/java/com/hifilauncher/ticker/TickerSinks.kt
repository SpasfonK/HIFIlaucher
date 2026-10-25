package com.hifilauncher.ticker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Where the ticker content goes. start() returns false when this sink cannot run here;
 * the publisher then falls back to the next candidate. All methods run on the main thread.
 *
 * (Changed from Gauntlet 1: start() now returns Boolean.)
 */
interface TickerSink {
    val id: String
    fun start(ctx: Context): Boolean
    fun update(state: TickerState)
    fun stop()
}

/** Ticker disabled. */
object NoopTickerSink : TickerSink {
    override val id = "none"
    override fun start(ctx: Context) = true
    override fun update(state: TickerState) = Unit
    override fun stop() = Unit
}

/**
 * Phones without a secondary screen (or when everything else fails): the UI shows the
 * state itself in its own status strip, reading TickerHub.state. Nothing to push.
 */
object InAppTickerSink : TickerSink {
    override val id = "in_app"
    override fun start(ctx: Context) = true
    override fun update(state: TickerState) = Unit
    override fun stop() = Unit
}

/**
 * Tier "notification": the HTC ticker shows notifications from the apps the user enabled
 * in its settings. Low importance, no sound, updated in place. Unverified on the real
 * device; the diagnostics screen tells us whether it ends up on the ticker.
 */
class NotificationTickerSink : TickerSink {
    override val id = "notification"
    private lateinit var appCtx: Context

    override fun start(ctx: Context): Boolean {
        appCtx = ctx.applicationContext
        val channel = NotificationChannel(CHANNEL, "Ticker", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        appCtx.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return NotificationManagerCompat.from(appCtx).areNotificationsEnabled()
    }

    override fun update(state: TickerState) {
        val nm = NotificationManagerCompat.from(appCtx)
        if (state.title.isEmpty()) {
            nm.cancel(NOTIFICATION_ID)
            return
        }
        val format = listOf(state.codec, state.detail).filter { it.isNotEmpty() }.joinToString(" ")
        val n = NotificationCompat.Builder(appCtx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play) // TODO: replace with the app's own icon
            .setContentTitle(state.title)
            .setContentText(state.artist)
            .setSubText(format)
            .setOngoing(state.playing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }

    override fun stop() {
        if (::appCtx.isInitialized) NotificationManagerCompat.from(appCtx).cancel(NOTIFICATION_ID)
    }

    private companion object {
        const val CHANNEL = "hifi_ticker"
        const val NOTIFICATION_ID = 2 // 1 is left to Media3's playback notification
    }
}

/**
 * Tier "presentation": if the secondary screen is exposed as a real Display, draw on it
 * with a Presentation window. A service has no activity token, so the window is an
 * overlay: it needs the "display over other apps" permission (see overlayPermissionIntent).
 */
class PresentationTickerSink(private val display: Display) : TickerSink {
    override val id = "presentation"
    private var presentation: Presentation? = null
    private var view: TickerView? = null

    override fun start(ctx: Context): Boolean {
        if (!Settings.canDrawOverlays(ctx)) return false
        return try {
            val p = Presentation(ctx, display)
            p.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            p.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            val v = TickerView(p.context)
            p.setContentView(
                v,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            p.show()
            presentation = p
            view = v
            true
        } catch (e: Exception) {
            // BadTokenException, InvalidDisplayException, display gone: let the next sink try.
            false
        }
    }

    override fun update(state: TickerState) {
        view?.setState(state)
    }

    override fun stop() {
        runCatching { presentation?.dismiss() }
        presentation = null
        view = null
    }
}

/**
 * Plain View drawn with Canvas for a wide, short strip (1040x160 on the U Ultra). Sizes
 * derive from the view's height, so it adapts to any strip. Redraws only when the state
 * changes: no animation, no timers.
 */
class TickerView(context: Context) : View(context) {
    private var state = TickerState()

    private val hi = textPaint(0xFFF4F1EC.toInt(), "sans-serif-condensed", Typeface.BOLD)
    private val lo = textPaint(0xFF8C8F96.toInt(), "sans-serif", Typeface.NORMAL)
    private val amber = textPaint(0xFFFFB547.toInt(), "sans-serif", Typeface.NORMAL)

    private fun textPaint(color: Int, family: String, style: Int) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            typeface = Typeface.create(family, style)
        }

    fun setState(s: TickerState) {
        if (s == state) return
        state = s
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = h * 0.14f
        hi.textSize = h * 0.40f
        lo.textSize = h * 0.25f
        amber.textSize = h * 0.30f

        // Right block: clock above battery, right-aligned.
        val battery = (if (state.charging) "+" else "") + "${state.batteryPct}%"
        amber.textAlign = Paint.Align.RIGHT
        lo.textAlign = Paint.Align.RIGHT
        canvas.drawText(state.clock, w - pad, h * 0.50f, amber)
        canvas.drawText(battery, w - pad, h * 0.84f, lo)
        val rightBlock = maxOf(amber.measureText(state.clock), lo.measureText(battery)) + pad * 2

        // Left block: title, then artist and format.
        lo.textAlign = Paint.Align.LEFT
        val avail = (w - pad - rightBlock).coerceAtLeast(0f)
        if (state.title.isEmpty()) {
            canvas.drawText(fit("Nothing playing", lo, avail), pad, h * 0.62f, lo)
            return
        }
        hi.alpha = if (state.playing) 255 else 150
        canvas.drawText(fit(state.title, hi, avail), pad, h * 0.50f, hi)
        val sub = listOf(
            state.artist,
            state.codec,
            state.detail,
            state.bitrateKbps.takeIf { it > 0 }?.let { "$it kbps" },
        ).filter { !it.isNullOrEmpty() }.joinToString("  ")
        canvas.drawText(fit(sub, lo, avail), pad, h * 0.84f, lo)
    }

    private fun fit(text: String, paint: TextPaint, avail: Float): String =
        TextUtils.ellipsize(text, paint, avail, TextUtils.TruncateAt.END).toString()
}

object TickerSinkFactory {

    /**
     * Ordered candidates; the publisher uses the first that starts. [override] comes from
     * TickerPrefs (null = automatic). The in-app strip is always the last resort, so the
     * app works unchanged on phones without a secondary screen.
     */
    fun candidates(ctx: Context, override: String?): List<TickerSink> {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val presentationDisplay = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        val anySecondary = dm.displays.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }
        // Heuristic: the model string is only used to decide whether the notification tier
        // is worth trying. The override in diagnostics can force any tier.
        val isHtcUltra = Build.MODEL.contains("U Ultra", ignoreCase = true)

        return when (override) {
            "presentation" -> listOfNotNull(anySecondary?.let { PresentationTickerSink(it) }, InAppTickerSink)
            "notification" -> listOf(NotificationTickerSink(), InAppTickerSink)
            "in_app" -> listOf(InAppTickerSink)
            "none" -> listOf(NoopTickerSink)
            else -> buildList {
                presentationDisplay?.let { add(PresentationTickerSink(it)) }
                if (isHtcUltra) add(NotificationTickerSink())
                add(InAppTickerSink)
            }
        }
    }
}

/** Ticker mode chosen from the diagnostics screen (no adb, no rebuild). */
object TickerPrefs {
    private const val FILE = "hifi"
    private const val KEY = "ticker_override"
    private val modes = listOf<String?>(null, "presentation", "notification", "in_app", "none")

    fun get(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)

    /** Moves to the next mode (auto, presentation, notification, in_app, none) and returns it. */
    fun cycle(ctx: Context): String? {
        val next = modes[(modes.indexOf(get(ctx)) + 1) % modes.size]
        val editor = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        if (next == null) editor.remove(KEY) else editor.putString(KEY, next)
        editor.apply()
        return next
    }
}

fun overlayPermissionIntent(ctx: Context): Intent =
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Plain text for the diagnostics screen: what the phone exposes, and what the ticker is doing. */
fun buildDiagnostics(ctx: Context): String = buildString {
    appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    appendLine("Active ticker sink: ${TickerHub.activeSink.value}")
    appendLine("Ticker mode: ${TickerPrefs.get(ctx) ?: "auto"}")
    appendLine("Overlay permission: ${Settings.canDrawOverlays(ctx)}")
    appendLine("Notifications enabled: ${NotificationManagerCompat.from(ctx).areNotificationsEnabled()}")
    // Without a chosen default, Android answers with its own chooser ("android").
    val home = ctx.packageManager
        .resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
    appendLine("Default home: ${home ?: "?"}${if (home == ctx.packageName) " (this app)" else ""}")
    appendLine()

    val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    appendLine("Displays:")
    dm.displays.forEach {
        appendLine("  #${it.displayId} '${it.name}' ${it.width}x${it.height} flags=${it.flags}")
    }
    appendLine("  usable for presentations: ${dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).size}")
    appendLine()

    appendLine("HTC packages:")
    ctx.packageManager.getInstalledPackages(0)
        .map { it.packageName }
        .filter { it.contains("htc", ignoreCase = true) }
        .sorted()
        .forEach { appendLine("  $it") }
    appendLine()

    val t = TickerHub.state.value
    appendLine("Now playing: ${t.title.ifEmpty { "-" }} / ${t.artist.ifEmpty { "-" }}")
    appendLine("Format: ${t.codec} ${t.detail} ${t.bitrateKbps} kbps")
}
