package app.sabbaq.fleet

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import app.sabbaq.LocaleManager
import app.sabbaq.Settings
import app.sabbaq.ui.MainActivity
import kotlin.math.abs

/**
 * The floating button the reference engine shows while it runs (its overlay sidebar): a small
 * "Sabbaq ●" pill above other apps. Besides showing at a glance that monitoring is on, a visible
 * overlay window raises the process to "perceptible", so Android and phone-maker cleaners leave it
 * alone. Dragged vertically; a tap opens Sabbaq. Needs the "display over other apps" permission;
 * without it nothing is shown.
 */
object FloatingBubble {
    private var view: TextView? = null

    fun permitted(context: Context): Boolean = AndroidSettings.canDrawOverlays(context)

    fun permissionIntent(context: Context): Intent =
        Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Shows the pill when the driver wants it and Android allows it; otherwise removes it. */
    fun sync(context: Context, active: Boolean) {
        if (active && Settings.floatingBubble && permitted(context)) show(context) else hide(context)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show(context: Context) {
        val app = context.applicationContext
        view?.let { refresh(it); return }
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val density = app.resources.displayMetrics.density
        val pill = TextView(app).apply {
            text = if (LocaleManager.isArabic()) "سبّاق ●" else "Sabbaq ●"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
            background = GradientDrawable().apply { cornerRadius = 40 * density; setColor(0xE60F7A4A.toInt()) }
            contentDescription = if (LocaleManager.isArabic()) "سبّاق يعمل. اضغط للفتح" else "Sabbaq is running. Tap to open"
        }
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = Settings.floatingBubbleY
        }
        var downY = 0f
        var startY = 0
        var moved = false
        pill.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startY = params.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (e.rawY - downY).toInt()
                    if (abs(dy) > 8 * density) moved = true
                    if (moved) { params.y = (startY + dy).coerceAtLeast(0); runCatching { wm.updateViewLayout(v, params) } }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) Settings.floatingBubbleY = params.y
                    else runCatching {
                        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    }
                    true
                }
                else -> false
            }
        }
        if (runCatching { wm.addView(pill, params) }.isSuccess) view = pill
    }

    private fun refresh(v: View) { v.visibility = View.VISIBLE }

    fun hide(context: Context) {
        val v = view ?: return
        view = null
        val wm = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        runCatching { wm.removeView(v) }
    }
}
