package app.sabbaq.fleet

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.sabbaq.LocaleManager
import app.sabbaq.Settings
import app.sabbaq.network.SupabaseClient
import app.sabbaq.service.KeeperService
import app.sabbaq.ui.MainActivity
import kotlin.math.abs

/**
 * The reference engine's floating control (its overlay sidebar), rebuilt for Sabbaq:
 * - collapsed: a 56 dp ring with the app icon, a status dot (green running, red stopped) and an
 *   orange glow that breathes while monitoring runs; dragged anywhere, it snaps to the nearest
 *   side edge (300 ms) and keeps 100 dp from the top and 150 dp from the bottom;
 * - tapped: a 220 dp glass card with the title, a drag handle, a collapse button, «المستخدم»
 *   (opens Sabbaq), «الأدمن» (administrators only), the engine button («🚀 بدء» / «⏹️ إيقاف»)
 *   and «إغلاق», which hides the control until monitoring is next started.
 *
 * It is hosted by the accessibility service, so it stays available while monitoring is off and
 * can start it again. With "display over other apps" allowed it is an application overlay (the
 * reference's window type, which also lets the start button bring up the foreground service);
 * otherwise an accessibility overlay.
 */
object FloatingBubble {
    private val main = Handler(Looper.getMainLooper())
    private var host: AccessibilityService? = null
    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var collapsed: View? = null
    private var card: View? = null
    private var glow: View? = null
    private var dot: View? = null
    private var engineEmoji: TextView? = null
    private var engineText: TextView? = null
    private var engineButton: View? = null
    private var adminButton: View? = null
    private var breathing: ValueAnimator? = null
    @Volatile private var dismissed = false

    fun permitted(context: Context): Boolean = AndroidSettings.canDrawOverlays(context)

    fun permissionIntent(context: Context): Intent =
        Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Called by the accessibility service once connected. */
    fun attach(service: AccessibilityService) {
        host = service
        main.post { refresh() }
    }

    fun detach() {
        main.post { remove() }
        host = null
    }

    /** Monitoring was started from the app: a dismissed control comes back. */
    fun monitoringStarted() { dismissed = false; main.post { refresh() } }

    /** Shows, hides or updates the control for the current state; safe from any thread. */
    fun refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { refresh() }; return }
        val service = host ?: return
        val wanted = Settings.floatingBubble && !dismissed && SupabaseClient.hasSavedSession()
        if (!wanted) { remove(); return }
        if (root == null) build(service)
        applyState()
    }

    private fun dp(context: Context, v: Float): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun shape(oval: Boolean, fill: Int, radiusDp: Float = 0f, strokeDp: Float = 0f, stroke: Int = 0, context: Context) =
        GradientDrawable().apply {
            shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            setColor(fill)
            if (!oval) cornerRadius = dp(context, radiusDp).toFloat()
            if (strokeDp > 0f) setStroke(dp(context, strokeDp), stroke)
        }

    private fun label(context: Context, text: String, sp: Float, color: Int = Color.WHITE) = TextView(context).apply {
        this.text = text; textSize = sp; setTextColor(color); gravity = Gravity.CENTER
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun build(service: AccessibilityService) {
        val ctx: Context = service
        val ar = LocaleManager.isArabic()
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val frame = FrameLayout(ctx)

        // Collapsed: glow 72 dp, ring 56 dp with the app icon, status dot 14 dp.
        val collapsedView = FrameLayout(ctx)
        val glowView = View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0x60FFCC44, 0x30FF8C00, 0x00FF8C00)).apply {
                shape = GradientDrawable.OVAL; gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = dp(ctx, 36f).toFloat()
            }
        }
        collapsedView.addView(glowView, FrameLayout.LayoutParams(dp(ctx, 72f), dp(ctx, 72f)))
        val ring = FrameLayout(ctx).apply { background = shape(true, 0xFF1A1A2E.toInt(), strokeDp = 2.5f, stroke = 0xFFFFCC44.toInt(), context = ctx) }
        val icon = ImageView(ctx).apply {
            setImageDrawable(runCatching { ctx.packageManager.getApplicationIcon(ctx.packageName) }.getOrNull())
            val pad = dp(ctx, 6f); setPadding(pad, pad, pad, pad)
        }
        ring.addView(icon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        collapsedView.addView(ring, FrameLayout.LayoutParams(dp(ctx, 56f), dp(ctx, 56f), Gravity.CENTER))
        val statusDot = View(ctx)
        collapsedView.addView(statusDot, FrameLayout.LayoutParams(dp(ctx, 14f), dp(ctx, 14f), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(ctx, 8f); marginEnd = dp(ctx, 8f)
        })
        collapsedView.contentDescription = if (ar) "تحكم سبّاق" else "Sabbaq control"

        // Expanded glass card, 220 dp.
        val cardView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(false, 0xDD1A1A2E.toInt(), radiusDp = 30f, strokeDp = 1.5f, stroke = 0x4DFFFFFF, context = ctx)
            val pad = dp(ctx, 14f); setPadding(pad, pad, pad, pad)
            visibility = View.GONE
        }
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val drag = ImageView(ctx).apply { setImageResource(android.R.drawable.ic_menu_sort_by_size); alpha = 0.7f }
        header.addView(drag, LinearLayout.LayoutParams(dp(ctx, 32f), dp(ctx, 32f)))
        header.addView(label(ctx, if (ar) "تحكم سبّاق" else "Sabbaq Control", 14f, 0xCCFFFFFF.toInt()),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val collapse = label(ctx, if (ar) "‹" else "›", 18f, 0xFFFFD700.toInt()).apply {
            background = shape(true, 0x66FFD700, strokeDp = 2f, stroke = 0xFFFFD700.toInt(), context = ctx)
        }
        header.addView(collapse, LinearLayout.LayoutParams(dp(ctx, 30f), dp(ctx, 30f)))
        cardView.addView(header)

        val launch = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun launchButton(emoji: String, text: String, fill: Int, stroke: Int) = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = shape(false, fill, radiusDp = 16f, strokeDp = 1f, stroke = stroke, context = ctx)
            addView(label(ctx, emoji, 16f)); addView(label(ctx, " $text", 12f))
        }
        val admin = launchButton("🛠", if (ar) "الأدمن" else "Admin", 0xCC1E3A8A.toInt(), 0x8093C5FD.toInt())
        val user = launchButton("📱", if (ar) "المستخدم" else "User", 0xCC065F46.toInt(), 0x8034D399.toInt())
        launch.addView(admin, LinearLayout.LayoutParams(0, dp(ctx, 48f), 1f).apply { marginEnd = dp(ctx, 6f) })
        launch.addView(user, LinearLayout.LayoutParams(0, dp(ctx, 48f), 1f))
        cardView.addView(launch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 10f) })

        val engine = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val emoji = label(ctx, "🚀", 20f)
        val engineLabel = label(ctx, "", 18f)
        engine.addView(emoji); engine.addView(engineLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(ctx, 8f) })
        cardView.addView(engine, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 54f)).apply { topMargin = dp(ctx, 10f) })

        val close = label(ctx, if (ar) "إغلاق" else "Close", 13f, 0xFFFFB3B3.toInt()).apply {
            background = shape(false, 0x22FFFFFF, radiusDp = 12f, context = ctx)
        }
        cardView.addView(close, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 40f)).apply { topMargin = dp(ctx, 10f) })

        frame.addView(collapsedView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        frame.addView(cardView, FrameLayout.LayoutParams(dp(ctx, 220f), FrameLayout.LayoutParams.WRAP_CONTENT))

        val type = if (permitted(ctx) && Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = Settings.floatingBubbleX; y = Settings.floatingBubbleY }

        fun haptic(v: View) = v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        fun expand(expanded: Boolean) {
            collapsedView.visibility = if (expanded) View.GONE else View.VISIBLE
            cardView.visibility = if (expanded) View.VISIBLE else View.GONE
            runCatching { wm.updateViewLayout(frame, lp) }
            if (!expanded) main.post { snapToEdge(wm, frame, lp) }
        }

        // Dragging (collapsed control or the card's handle) with a tap on the collapsed control expanding it.
        fun dragger(tapAction: (() -> Unit)?) = object : View.OnTouchListener {
            var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                        if (!moved && (abs(dx) > dp(ctx, 8f) || abs(dy) > dp(ctx, 8f))) moved = true
                        if (moved) { lp.x = startX + dx; lp.y = startY + dy; runCatching { wm.updateViewLayout(frame, lp) } }
                    }
                    MotionEvent.ACTION_UP -> if (moved) snapToEdge(wm, frame, lp) else tapAction?.invoke()
                }
                return true
            }
        }
        collapsedView.setOnTouchListener(dragger { haptic(collapsedView); expand(true) })
        drag.setOnTouchListener(dragger(null))
        collapse.setOnClickListener { haptic(it); expand(false) }
        user.setOnClickListener { haptic(it); openApp(ctx, admin = false); expand(false) }
        admin.setOnClickListener { haptic(it); openApp(ctx, admin = true); expand(false) }
        engine.setOnClickListener { haptic(it); toggleEngine(ctx) }
        close.setOnClickListener { haptic(it); dismissed = true; remove() }

        if (runCatching { wm.addView(frame, lp) }.isFailure) return
        root = frame; params = lp; collapsed = collapsedView; card = cardView; glow = glowView; dot = statusDot
        engineEmoji = emoji; engineText = engineLabel; engineButton = engine; adminButton = admin
        main.post { snapToEdge(wm, frame, lp) }
    }

    /** Running: green dot, breathing glow, red «⏹️ إيقاف»; stopped: red dot, no glow, green «🚀 بدء». */
    private fun applyState() {
        val ctx: Context = host ?: return
        val ar = LocaleManager.isArabic()
        val running = Settings.isActive && Settings.monitoringWanted
        dot?.background = shape(true, if (running) 0xFF4CAF50.toInt() else 0xFFE53935.toInt(), strokeDp = 2f, stroke = Color.WHITE, context = ctx)
        engineEmoji?.text = if (running) "⏹️" else "🚀"
        engineText?.text = if (running) (if (ar) "إيقاف" else "STOP") else (if (ar) "بدء" else "START")
        engineButton?.background = shape(false, if (running) 0xFFEF4444.toInt() else 0xFF10B981.toInt(), radiusDp = 25f,
            strokeDp = 1.5f, stroke = 0xB3FFFFFF.toInt(), context = ctx)
        adminButton?.visibility = if (Settings.lastSignedInAdmin) View.VISIBLE else View.GONE
        glow?.visibility = if (running) View.VISIBLE else View.GONE
        if (running) startBreathing() else stopBreathing()
    }

    private fun startBreathing() {
        if (breathing?.isRunning == true) return
        val g = glow ?: return
        breathing = ValueAnimator.ofFloat(1f, 1.3f).apply {
            duration = 1_000L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { val s = it.animatedValue as Float; g.scaleX = s; g.scaleY = s; g.alpha = 1.6f - s }
            start()
        }
    }

    private fun stopBreathing() {
        breathing?.cancel(); breathing = null
        glow?.apply { scaleX = 1f; scaleY = 1f; alpha = 1f }
    }

    /** The reference's snapToEdge: nearest side, 300 ms, top margin 100 px and bottom margin 150 px. */
    private fun snapToEdge(wm: WindowManager, view: View, lp: WindowManager.LayoutParams) {
        val metrics = view.resources.displayMetrics
        val target = if (lp.x + view.width / 2 < metrics.widthPixels / 2) 0 else metrics.widthPixels - view.width
        val maxY = (metrics.heightPixels - view.height - 150).coerceAtLeast(100)
        ValueAnimator.ofInt(lp.x, target).apply {
            duration = 300L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                lp.x = it.animatedValue as Int
                lp.y = lp.y.coerceIn(100, maxY)
                runCatching { wm.updateViewLayout(view, lp) }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    Settings.floatingBubbleX = lp.x; Settings.floatingBubbleY = lp.y
                }
            })
            start()
        }
    }

    private fun openApp(ctx: Context, admin: Boolean) {
        runCatching {
            ctx.startActivity(Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_ROUTE, if (admin) "admin" else "driver"))
        }
    }

    /**
     * The reference's engine button: stop = monitoring off now («⏸️ تم إيقاف العمل»); start =
     * monitoring on when consent and a confirmed subscription are on the device, otherwise the app
     * opens to finish what is missing.
     */
    private fun toggleEngine(ctx: Context) {
        val ar = LocaleManager.isArabic()
        if (Settings.isActive && Settings.monitoringWanted) {
            Settings.monitoringWanted = false
            Settings.isActive = false
            runCatching { ctx.stopService(Intent(ctx, KeeperService::class.java)) }
            Toast.makeText(ctx, if (ar) "⏸️ تم إيقاف العمل" else "⏸️ Everything paused", Toast.LENGTH_SHORT).show()
            applyState()
            return
        }
        val canStart = Settings.a11yDisclosureAccepted &&
            (SupabaseClient.hasValidLicense() || Settings.subscriptionEndsAtMs > System.currentTimeMillis())
        if (!canStart) { openApp(ctx, admin = false); return }
        Settings.monitoringWanted = true
        Settings.isActive = true
        if (!KeeperService.resumeIfWanted(ctx)) {
            Settings.isActive = false
            Settings.monitoringWanted = false
            Toast.makeText(ctx, if (ar) "افتح سبّاق لبدء العمل" else "Open Sabbaq to start", Toast.LENGTH_LONG).show()
            openApp(ctx, admin = false)
        }
        applyState()
    }

    private fun remove() {
        stopBreathing()
        val view = root ?: return
        val wm = host?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        runCatching { wm?.removeView(view) }
        root = null; params = null; collapsed = null; card = null; glow = null; dot = null
        engineEmoji = null; engineText = null; engineButton = null; adminButton = null
    }

    const val EXTRA_ROUTE = "app.sabbaq.extra.ROUTE"
}
