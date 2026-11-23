package dev.sharptalons.obsdirector.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import dev.sharptalons.obsdirector.MainActivity
import dev.sharptalons.obsdirector.core.Director
import dev.sharptalons.obsdirector.core.LinkState
import dev.sharptalons.obsdirector.core.Prefs
import dev.sharptalons.obsdirector.ui.Stage
import dev.sharptalons.obsdirector.ui.T
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The floating bubble shown over other apps while connected: the recording time, and a tap opens
 * a small panel with the Clip button (it shows the clip's length while one runs), Open app and
 * Hide bubble. Drag it anywhere. Plain views, so it costs next to nothing while it floats.
 */
class BubbleService : Service() {

    companion object {
        @Volatile private var shown = false

        fun canShow(context: Context) = Settings.canDrawOverlays(context)

        /** Show it if the person wants it, the phone allows it and a PC is connected. */
        fun showIfWanted(context: Context) {
            if (shown || !Prefs.settings.value.bubble || !canShow(context)) return
            if (Director.link.value == LinkState.DISCONNECTED) return
            runCatching { context.startService(Intent(context, BubbleService::class.java)) }
        }

        fun hide(context: Context) {
            if (shown) context.stopService(Intent(context, BubbleService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var bubbleTime: TextView
    private lateinit var panel: LinearLayout
    private lateinit var panelTime: TextView
    private lateinit var clipButton: TextView
    private var expanded = false

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(2), stroke)
    }

    private fun label(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(color); gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        fontFeatureSettings = "tnum"
    }

    override fun onCreate() {
        super.onCreate()
        shown = true
        wm = getSystemService(WindowManager::class.java)

        bubbleTime = label(13f, T.text.toArgb(), bold = true).apply {
            background = rounded(T.surface.toArgb(), 28, T.negative.toArgb())
            minWidth = dp(56); minHeight = dp(56); setPadding(dp(10), 0, dp(10), 0)
            contentDescription = "OBS Director. Tap for the Clip button"
        }

        panelTime = label(28f, T.text.toArgb())
        clipButton = label(18f, T.text.toArgb(), bold = true).apply {
            minHeight = dp(72)
            setOnClickListener { if (Director.clipStart.value == null) Director.startClip() else Director.endClip(); refresh() }
        }
        val open = label(15f, T.accent.toArgb()).apply {
            text = "Open app"; minHeight = dp(48)
            setOnClickListener {
                startActivity(Intent(this@BubbleService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }
        val hideIt = label(15f, T.text2.toArgb()).apply {
            text = "Hide bubble"; minHeight = dp(48)
            setOnClickListener { stopSelf() }
        }
        val links = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(open, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(hideIt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(T.surface.toArgb(), 22)
            setPadding(dp(14), dp(12), dp(14), dp(6))
            addView(panelTime)
            addView(clipButton, LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(links, LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
            visibility = View.GONE
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bubbleTime, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(160) }
        dragAndTap(bubbleTime)
        if (runCatching { wm.addView(root, params) }.isFailure) { stopSelf(); return }

        scope.launch {
            while (isActive) {
                if (Director.link.value == LinkState.DISCONNECTED) { stopSelf(); break }
                refresh()
                delay(500)
            }
        }
    }

    private fun refresh() {
        val s = Director.status.value
        val time = if (s.recActive) Director.tc(Director.nowFileTimeMs()) else "Not recording"
        bubbleTime.text = if (s.recActive) time.removePrefix("00:") else "OBS"
        panelTime.text = time
        val start = Director.clipStart.value
        if (start != null) {
            val len = (Director.nowFileTimeMs() - start).coerceAtLeast(0) / 1000
            clipButton.text = "End clip · ${len / 60}:${"%02d".format(len % 60)}"
            clipButton.background = rounded(Stage.rec.toArgb(), 18)
        } else {
            clipButton.text = "Clip"
            clipButton.background = rounded(if (s.recActive) T.blue.toArgb() else T.raised.toArgb(), 18)
        }
        clipButton.isEnabled = s.recActive && Director.link.value == LinkState.CONNECTED
        clipButton.alpha = if (clipButton.isEnabled) 1f else 0.6f
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun dragAndTap(v: View) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) { params.x = startX + dx.toInt(); params.y = startY + dy.toInt(); runCatching { wm.updateViewLayout(root, params) } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) { expanded = !expanded; panel.visibility = if (expanded) View.VISIBLE else View.GONE; v.performClick() }
                    true
                }
                else -> false
            }
        }
        v.setOnClickListener { }
    }

    override fun onDestroy() {
        shown = false
        scope.cancel()
        runCatching { wm.removeView(root) }
        super.onDestroy()
    }
}
