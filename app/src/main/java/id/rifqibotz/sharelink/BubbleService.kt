package id.rifqibotz.sharelink

import android.animation.ObjectAnimator
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.abs

const val APP_URL = "https://rifqibotz.github.io/aff/"

private val LIME = 0xFFB8FF3A.toInt()
private val MINT = 0xFF2CF5B0.toInt()
private val AMBER = 0xFFFFB23A.toInt()
private val ORANGE = 0xFFFF8A3A.toInt()
private val GRAY = 0xFF8A99A8.toInt()
private val INK = 0xFF050D0B.toInt()
private val SOFT = 0xFF1B2A25.toInt()
private val METHODS = listOf(
    Triple("manual", "Manual", 0xFF8FA4BB.toInt()),
    Triple("gpt", "GPT", 0xFF34B79A.toInt()),
    Triple("claude", "Claude", 0xFFE5A83B.toInt()),
    Triple("koleksi", "Koleksi", 0xFFFFB23A.toInt())
)
private const val PANEL_DP = 272
private const val CODE_STATE =
    "(function(){try{var t=window.trackerAPI;return t?t.state():null}catch(e){return null}})()"

/**
 * Gelembung melayang. Logika timer dan data tetap di JS tracker: service ini menjalankan
 * satu WebView tersembunyi ("engine") dan memanggil window.trackerAPI.
 * Mode bubble: 0 = diam (setengah keluar, redup), 1 = aktif (penuh), 2 = panel terbuka.
 */
class BubbleService : Service() {

    companion object {
        @Volatile var instance: BubbleService? = null
        @Volatile var dirty = false
        const val ACTION_STOP = "id.rifqibotz.sharelink.STOP"
        private const val CHANNEL = "bubble"

        fun prefs(c: Context) = c.getSharedPreferences("bubble", Context.MODE_PRIVATE)

        fun start(c: Context) {
            prefs(c).edit().putBoolean("enabled", true).apply()
            c.startForegroundService(Intent(c, BubbleService::class.java))
        }

        fun stop(c: Context) {
            prefs(c).edit().putBoolean("enabled", false).apply()
            c.stopService(Intent(c, BubbleService::class.java))
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val themed by lazy { ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault) }

    private lateinit var wm: WindowManager
    private lateinit var engine: WebView
    private lateinit var bubble: TextView
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private lateinit var panel: LinearLayout
    private lateinit var panelLp: WindowManager.LayoutParams
    private lateinit var tvTime: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnMain: Button
    private lateinit var btnStop: Button
    private lateinit var btnUndo: Button
    private val methodBtns = LinkedHashMap<String, Button>()

    private var st: JSONObject? = null
    private var stAt = 0L
    private var misses = 0
    private var mode = 0
    private var panelAttached = false
    private var onRight = true
    private var stopArmed = false
    private var ready = false
    private var flash = 0
    private var xAnim: ValueAnimator? = null
    private var alphaAnim: ObjectAnimator? = null
    private var outsideStamp = 0L
    private var screenW = 0
    private var screenH = 0
    private var dp = 1f

    private val size: Int get() = d(52)
    private fun d(v: Int) = (v * dp).toInt()

    private val hideTask = Runnable { go(0) }
    private val tick = object : Runnable {
        override fun run() {
            if (mode == 2) {
                tvTime.text = fmt(liveElapsed())
                ui.postDelayed(this, 250)
            }
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            engine.evaluateJavascript(CODE_STATE) { v ->
                if (v == null || v == "null") {
                    if (++misses >= 4 && st != null) { st = null; render() }
                } else {
                    misses = 0
                    applyState(v)
                }
            }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs(this).edit().putBoolean("enabled", false).apply()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        dp = resources.displayMetrics.density
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startFg()
        if (!Settings.canDrawOverlays(this)) {
            prefs(this).edit().putBoolean("enabled", false).apply()
            stopSelf()
            return
        }
        measureScreen()
        buildEngine()
        buildBubble()
        buildPanel()
        ready = true
        ui.post(poll)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!ready) return
        measureScreen()
        ui.removeCallbacks(hideTask)
        closePanel(false)
        mode = 0
        fadeBubble(0.5f, false)
        applyPos(false)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        xAnim?.cancel()
        runCatching { if (panelAttached) wm.removeView(panel) }
        runCatching { wm.removeView(bubble) }
        runCatching { wm.removeView(engine); engine.destroy() }
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun reloadEngine() { if (ready) engine.reload() }

    /** Dipanggil MainActivity: sembunyikan bubble saat app dibuka, tampilkan lagi saat ditinggalkan. */
    fun onAppForeground(fg: Boolean) {
        if (!ready) return
        ui.removeCallbacks(hideTask)
        if (fg) {
            closePanel(false)
            mode = 0
            bubble.visibility = View.GONE
        } else {
            bubble.visibility = View.VISIBLE
            go(0, false)
            engine.reload()
        }
    }

    // ---------- foreground notification ----------
    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Gelembung sharelink", NotificationManager.IMPORTANCE_MIN))
        val stopPi = PendingIntent.getService(
            this, 0, Intent(this, BubbleService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Gelembung sharelink aktif")
            .setContentText("Ketuk untuk membuka tracker")
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(0, "Matikan", stopPi).build())
            .setOngoing(true)
            .build()
        startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun measureScreen() {
        val m = resources.displayMetrics
        screenW = m.widthPixels
        screenH = m.heightPixels
    }

    // ---------- engine (WebView tersembunyi) ----------
    @SuppressLint("SetJavaScriptEnabled")
    private fun buildEngine() {
        engine = WebView(applicationContext)
        engine.settings.javaScriptEnabled = true
        engine.settings.domStorageEnabled = true
        engine.webViewClient = WebViewClient()
        engine.alpha = 0.01f
        val lp = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(engine, lp)
        engine.loadUrl(APP_URL)
    }

    private fun call(fn: String) {
        dirty = true
        engine.evaluateJavascript(
            "(function(){try{var t=window.trackerAPI;return t?t.$fn:null}catch(e){return null}})()"
        ) { v ->
            if (v != null && v != "null") { misses = 0; applyState(v) }
        }
    }

    private fun applyState(v: String) {
        st = try { JSONObject(v) } catch (e: Exception) { null }
        stAt = SystemClock.elapsedRealtime()
        render()
    }

    private fun liveElapsed(): Long {
        val s = st ?: return 0L
        val e = s.optLong("elapsed")
        return if (s.optBoolean("running")) e + (SystemClock.elapsedRealtime() - stAt) else e
    }

    // ---------- animasi ----------
    private fun moveX(to: Int, ms: Long = 260L, interp: TimeInterpolator = DecelerateInterpolator(2.2f)) {
        xAnim?.cancel()
        val from = bubbleLp.x
        if (from == to) { runCatching { wm.updateViewLayout(bubble, bubbleLp) }; return }
        xAnim = ValueAnimator.ofInt(from, to).apply {
            duration = ms
            interpolator = interp
            addUpdateListener {
                bubbleLp.x = it.animatedValue as Int
                runCatching { wm.updateViewLayout(bubble, bubbleLp) }
            }
            start()
        }
    }

    private fun pulse() {
        bubble.animate().cancel()
        bubble.animate().scaleX(1.22f).scaleY(1.22f).setDuration(90).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                bubble.animate().scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(OvershootInterpolator(3f)).start()
            }.start()
    }

    private fun flashRing(color: Int) {
        flash = color
        render()
        ui.postDelayed({ flash = 0; render() }, 450)
    }

    // ---------- bubble ----------
    @SuppressLint("ClickableViewAccessibility")
    private fun buildBubble() {
        bubble = TextView(themed).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            text = "…"
        }
        bubbleLp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        val p = prefs(this)
        onRight = p.getBoolean("right", true)
        bubbleLp.y = p.getInt("y", d(320))
        bubbleLp.x = targetX()
        bubble.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        bubble.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
            var touching = false; var openAtDown = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        touching = true
                        openAtDown = mode == 2 || recentOutside()
                        sx = e.rawX; sy = e.rawY; ox = bubbleLp.x; oy = bubbleLp.y; moved = false
                        xAnim?.cancel()
                        bubble.animate().cancel()
                        bubble.animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).setInterpolator(DecelerateInterpolator()).start()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - sx
                        val dy = e.rawY - sy
                        if (!moved && (abs(dx) > d(8) || abs(dy) > d(8))) {
                            moved = true
                            startDrag()
                        }
                        if (moved) {
                            bubbleLp.x = (ox + dx).toInt()
                            bubbleLp.y = (oy + dy).toInt().coerceIn(0, screenH - size)
                            wm.updateViewLayout(bubble, bubbleLp)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        touching = false
                        bubble.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(2.5f)).start()
                        if (moved) {
                            onRight = e.rawX > screenW / 2
                            prefs(this@BubbleService).edit().putBoolean("right", onRight).putInt("y", bubbleLp.y).apply()
                            go(1)
                        } else if (e.action == MotionEvent.ACTION_UP) {
                            onTap(openAtDown)
                        }
                        return true
                    }
                    MotionEvent.ACTION_OUTSIDE -> {
                        // bubble nonjol tanpa panel: sentuhan di luar membuatnya samar lagi
                        if (!touching && mode == 1 && claimOutside()) go(0)
                        return true
                    }
                }
                return false
            }
        })
        wm.addView(bubble, bubbleLp)
        go(0, false)
    }

    private fun recentOutside() = SystemClock.uptimeMillis() - outsideStamp < 150

    /** Satu sentuhan di luar bisa dikirim ke bubble DAN panel; hanya satu yang boleh bertindak. */
    private fun claimOutside(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - outsideStamp < 150) return false
        outsideStamp = now
        return true
    }

    private fun onTap(openAtDown: Boolean) {
        if (openAtDown) { go(1); return } // tap bubble saat panel terbuka: cukup tutup panel
        when (mode) {
            0 -> go(1)
            1 -> go(2)
            else -> go(1)
        }
    }

    /** Satu-satunya pintu pindah mode: 0 = samar, 1 = nonjol, 2 = panel terbuka. */
    private fun go(m: Int, animated: Boolean = true) {
        ui.removeCallbacks(hideTask)
        val prev = mode
        mode = m
        if (prev == 2 && m != 2) closePanel(animated)
        if (m == 2 && prev != 2) openPanel()
        fadeBubble(if (m == 0) 0.5f else 1f, animated)
        applyPos(animated)
        if (m != 0) scheduleHide()
    }

    private fun startDrag() {
        ui.removeCallbacks(hideTask)
        xAnim?.cancel()
        if (mode == 2) closePanel(true)
        mode = 1
        fadeBubble(1f)
    }

    private fun fadeBubble(to: Float, animated: Boolean = true) {
        alphaAnim?.cancel()
        if (!animated) { bubble.alpha = to; return }
        alphaAnim = ObjectAnimator.ofFloat(bubble, View.ALPHA, to).apply { duration = 180; start() }
    }

    private fun targetX(): Int =
        if (mode == 0) { if (onRight) screenW - size / 2 else -size / 2 }
        else { if (onRight) screenW - size - d(6) else d(6) }

    private fun applyPos(animate: Boolean = true) {
        bubbleLp.y = bubbleLp.y.coerceIn(0, screenH - size)
        if (animate) moveX(targetX())
        else {
            xAnim?.cancel()
            bubbleLp.x = targetX()
            runCatching { wm.updateViewLayout(bubble, bubbleLp) }
        }
    }

    private fun scheduleHide() {
        ui.removeCallbacks(hideTask)
        ui.postDelayed(hideTask, if (mode == 2) 7000L else 3500L)
    }

    // ---------- panel ----------
    private fun top(h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply { topMargin = d(10) }

    private fun grad(c1: Int, c2: Int, r: Float): Drawable =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(c1, c2)).apply { cornerRadius = r * dp }

    private fun methodText(label: String, n: Int): CharSequence {
        val sb = SpannableStringBuilder(label)
        sb.setSpan(RelativeSizeSpan(0.85f), 0, label.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("\n")
        val start = sb.length
        sb.append(n.toString())
        sb.setSpan(RelativeSizeSpan(1.9f), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun btn(label: String, fill: Drawable, textColor: Int, onClick: () -> Unit) = Button(themed).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(textColor)
        background = fill
        stateListAnimator = null
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(d(4), d(4), d(4), d(4))
        setOnClickListener { onClick(); scheduleHide() }
        setOnTouchListener { v, e ->
            if (v.isEnabled) {
                when (e.action) {
                    MotionEvent.ACTION_DOWN ->
                        v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).setInterpolator(DecelerateInterpolator()).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(OvershootInterpolator(2.5f)).start()
                }
            }
            false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPanel() {
        panel = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(14), d(12), d(14), d(14))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF2102520.toInt(), 0xF2050D0B.toInt())
            ).apply { cornerRadius = 24f * dp; setStroke(d(1), 0x33FFFFFF) }
            // sentuhan di luar panel: tutup otomatis, sentuhannya tetap diteruskan ke app di bawah
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_OUTSIDE) {
                    if (mode == 2 && claimOutside()) go(1)
                    true
                } else {
                    scheduleHide()
                    false
                }
            }
        }

        val head = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tvTime = TextView(themed).apply {
            textSize = 30f
            setTextColor(LIME)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        tvStatus = TextView(themed).apply {
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(d(10), d(4), d(10), d(4))
        }
        head.addView(tvTime, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(tvStatus)
        panel.addView(head)

        val rowMain = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL }
        btnMain = btn("Mulai", grad(LIME, MINT, 14f), INK) { call("main()") }
        btnStop = btn("Selesai", grad(SOFT, SOFT, 14f), Color.WHITE) { onStopTap() }
        rowMain.addView(btnMain, LinearLayout.LayoutParams(0, d(48), 2f))
        rowMain.addView(btnStop, LinearLayout.LayoutParams(0, d(48), 1f).apply { leftMargin = d(8) })
        panel.addView(rowMain, top())

        val rowM = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, m) in METHODS.withIndex()) {
            val key = m.first
            val b = btn(m.second, grad(m.third, m.third, 16f), INK) {
                if (st?.optBoolean("running") == true) {
                    buzz()
                    call("tap('$key')")
                    pulse()
                    flashRing(m.third)
                }
            }
            b.maxLines = 2
            methodBtns[key] = b
            rowM.addView(b, LinearLayout.LayoutParams(0, d(72), 1f).apply { if (i > 0) leftMargin = d(6) })
        }
        panel.addView(rowM, top())

        val rowBottom = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL }
        btnUndo = btn("Undo", grad(SOFT, SOFT, 14f), Color.WHITE) {
            if (st?.isNull("last") == false) { buzz(); call("undo()"); pulse() }
        }
        val btnWeb = btn("Visit web", grad(SOFT, SOFT, 14f), Color.WHITE) { openApp() }
        rowBottom.addView(btnUndo, LinearLayout.LayoutParams(0, d(44), 1f))
        rowBottom.addView(btnWeb, LinearLayout.LayoutParams(0, d(44), 1f).apply { leftMargin = d(8) })
        panel.addView(rowBottom, top())

        panelLp = WindowManager.LayoutParams(
            d(PANEL_DP), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    private fun openPanel() {
        val w = d(PANEL_DP)
        panelLp.x = if (onRight) screenW - size - d(6) - d(8) - w else d(6) + size + d(8)
        panelLp.y = bubbleLp.y.coerceAtMost(screenH - d(340)).coerceAtLeast(d(30))
        panel.animate().cancel()
        panel.alpha = 0f
        panel.scaleX = 0.82f
        panel.scaleY = 0.82f
        panel.pivotX = if (onRight) w.toFloat() else 0f
        panel.pivotY = (bubbleLp.y + size / 2f - panelLp.y).coerceAtLeast(0f)
        if (panelAttached) runCatching { wm.updateViewLayout(panel, panelLp) }
        else { wm.addView(panel, panelLp); panelAttached = true }
        renderPanel()
        // withLayer: seluruh panel difade sebagai satu lapisan, supaya tombol dan latar tidak berkedip terpisah
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240)
            .setInterpolator(OvershootInterpolator(1.15f)).withLayer().start()
        ui.post(tick)
    }

    private fun closePanel(animated: Boolean) {
        ui.removeCallbacks(tick)
        panel.animate().cancel()
        if (!animated || !panelAttached) { detachPanel(); return }
        panel.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(140)
            .setInterpolator(AccelerateInterpolator()).withLayer()
            .withEndAction { if (mode != 2) detachPanel() }.start()
    }

    private fun detachPanel() {
        if (panelAttached) {
            runCatching { wm.removeView(panel) }
            panelAttached = false
        }
    }

    private fun onStopTap() {
        if (st?.optBoolean("active") != true) return
        if (!stopArmed) {
            stopArmed = true
            render()
            ui.postDelayed({ stopArmed = false; render() }, 3000)
        } else {
            stopArmed = false
            call("stopNow()")
        }
    }

    private fun openApp() {
        go(0)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun buzz() {
        runCatching {
            getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return String.format("%02d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
    }

    private fun render() {
        renderBubble()
        if (mode == 2) renderPanel()
    }

    private fun statusColor(): Int {
        val s = st
        return if (s?.optBoolean("running") == true) LIME else if (s?.optBoolean("active") == true) AMBER else GRAY
    }

    private fun renderBubble() {
        val s = st
        val running = s?.optBoolean("running") == true
        val counts = s?.optJSONObject("counts")
        var total = 0
        for ((key, _, _) in METHODS) total += counts?.optInt(key) ?: 0
        bubble.text = if (s == null) "…" else total.toString()
        bubble.setTextColor(if (running) LIME else Color.WHITE)
        bubble.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF0183029.toInt(), 0xF0050D0B.toInt())
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(d(3), if (flash != 0) flash else statusColor())
        }
    }

    /** Hanya dipanggil saat panel terbuka, supaya panel yang sedang menutup tidak ikut berubah ukuran. */
    private fun renderPanel() {
        val s = st
        val active = s?.optBoolean("active") == true
        val running = s?.optBoolean("running") == true
        val counts = s?.optJSONObject("counts")
        val last = if (s != null && !s.isNull("last")) s.optString("last") else null
        for ((key, label, _) in METHODS) {
            methodBtns[key]?.let {
                it.text = methodText(label, counts?.optInt(key) ?: 0)
                it.isEnabled = running
                it.alpha = if (running) 1f else 0.4f
            }
        }
        val base = statusColor()
        tvTime.text = fmt(liveElapsed())
        tvStatus.text = when {
            s == null -> "Belum siap"
            !active -> "Belum jalan"
            running -> "Jalan"
            else -> "Dijeda"
        }
        tvStatus.setTextColor(base)
        tvStatus.background = GradientDrawable().apply {
            setColor((base and 0x00FFFFFF) or 0x2A000000)
            cornerRadius = 99f * dp
        }
        btnMain.text = if (!active) "Mulai" else if (running) "Jeda" else "Lanjut"
        btnMain.background = if (running) grad(AMBER, ORANGE, 14f) else grad(LIME, MINT, 14f)
        btnStop.text = if (stopArmed) "Yakin?" else "Selesai"
        btnStop.isEnabled = active
        btnStop.alpha = if (active) 1f else 0.4f
        val lastLabel = METHODS.firstOrNull { it.first == last }?.second
        btnUndo.text = if (lastLabel != null) "Undo $lastLabel" else "Undo"
        btnUndo.isEnabled = lastLabel != null
        btnUndo.alpha = if (lastLabel != null) 1f else 0.4f
    }
}
