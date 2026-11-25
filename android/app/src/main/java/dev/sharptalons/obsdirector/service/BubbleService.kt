package dev.sharptalons.obsdirector.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import dev.sharptalons.obsdirector.MainActivity
import dev.sharptalons.obsdirector.core.Director
import dev.sharptalons.obsdirector.core.LinkState
import dev.sharptalons.obsdirector.core.Prefs
import dev.sharptalons.obsdirector.ui.T
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A floating bubble over other apps while connected, the way chat bubbles work: tap it to open
 * OBS Director; drag it anywhere and it settles against the nearest side; drag it onto the ✕
 * that appears at the bottom to put it away (it comes back the next time you leave the app).
 * The red dot says OBS is recording; a red ring says a clip is running.
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
    private lateinit var bubble: FrameLayout
    private lateinit var dot: View
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var target: TextView
    private lateinit var targetParams: WindowManager.LayoutParams
    private var targetShown = false
    private var snap: ValueAnimator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val size get() = dp(56)

    private fun oval(fill: Int, stroke: Int, strokeDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
    }

    private fun overlayParams(w: Int, h: Int, gravity: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { this.gravity = gravity }

    override fun onCreate() {
        super.onCreate()
        shown = true
        wm = getSystemService(WindowManager::class.java)

        dot = View(this).apply { background = oval(T.negative.toArgb(), 0, 0) }
        bubble = FrameLayout(this).apply {
            background = oval(T.surface.toArgb(), T.text.toArgb(), 2)
            elevation = dp(6).toFloat()
            contentDescription = "OBS Director. Tap to open the app; drag to move."
            addView(dot, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        }
        params = overlayParams(size, size, Gravity.TOP or Gravity.START).apply { x = screenWidth() - size - dp(8); y = dp(180) }

        target = TextView(this).apply {
            text = "✕"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(T.text.toArgb())
            background = oval(0xCC1C1C20.toInt(), T.text.toArgb(), 2)
            contentDescription = "Drop here to hide the bubble"
        }
        targetParams = overlayParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { y = dp(56) }

        dragAndTap()
        if (runCatching { wm.addView(bubble, params) }.isFailure) { stopSelf(); return }

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
        val clip = Director.clipStart.value != null
        dot.background = oval(if (s.recActive && !s.recPaused) T.negative.toArgb() else T.muted.toArgb(), 0, 0)
        bubble.background = oval(T.surface.toArgb(), if (clip) T.negative.toArgb() else T.text.toArgb(), if (clip) 3 else 2)
        bubble.contentDescription = "OBS Director, " + when {
            !s.recActive -> "not recording"
            s.recPaused -> "recording paused"
            clip -> "recording, clip running"
            else -> "recording"
        } + ". Tap to open the app."
    }

    private fun screenWidth(): Int = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.width() else resources.displayMetrics.widthPixels
    private fun screenHeight(): Int = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.height() else resources.displayMetrics.heightPixels

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    /** Is the bubble's centre over the ✕ target? */
    private fun overTarget(): Boolean {
        val bx = params.x + size / 2f
        val by = params.y + size / 2f
        val tx = screenWidth() / 2f
        val ty = screenHeight() - targetParams.y - dp(32)
        return hypot(bx - tx, by - ty) < dp(80)
    }

    private fun showTarget(on: Boolean) {
        if (on == targetShown) return
        targetShown = on
        runCatching { if (on) wm.addView(target, targetParams) else wm.removeView(target) }
    }

    /** Settle against the nearer side, keeping it on screen. */
    private fun snapToEdge() {
        val w = screenWidth()
        val endX = if (params.x + size / 2 < w / 2) dp(8) else w - size - dp(8)
        val endY = params.y.coerceIn(dp(48), screenHeight() - size - dp(96))
        val startX = params.x
        val startY = params.y
        snap?.cancel()
        snap = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                params.x = (startX + (endX - startX) * f).toInt()
                params.y = (startY + (endY - startY) * f).toInt()
                runCatching { wm.updateViewLayout(bubble, params) }
            }
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun dragAndTap() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        bubble.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snap?.cancel()
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) { dragging = true; showTarget(true) }
                    if (dragging) {
                        params.x = startX + dx.toInt(); params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(bubble, params) }
                        val over = overTarget()
                        target.scaleX = if (over) 1.25f else 1f; target.scaleY = target.scaleX
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val drop = overTarget() && e.actionMasked == MotionEvent.ACTION_UP
                        showTarget(false)
                        if (drop) stopSelf() else snapToEdge()
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        v.performClick()
                        openApp()
                    }
                    true
                }
                else -> false
            }
        }
        bubble.setOnClickListener { }
    }

    override fun onDestroy() {
        shown = false
        scope.cancel()
        snap?.cancel()
        showTarget(false)
        runCatching { wm.removeView(bubble) }
        super.onDestroy()
    }
}
