package id.rifqibotz.sharelink

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
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.math.abs

const val APP_URL = "https://rifqibotz.github.io/aff/"

private val LIME = 0xFFB8FF3A.toInt()
private val AMBER = 0xFFFFB23A.toInt()
private val GRAY = 0xFF8A99A8.toInt()
private val INK = 0xFF050D0B.toInt()
private val METHODS = listOf(
    Triple("manual", "Manual", 0xFF8FA4BB.toInt()),
    Triple("gpt", "GPT", 0xFF34B79A.toInt()),
    Triple("claude", "Claude", 0xFFE5A83B.toInt())
)

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
    private val methodBtns = LinkedHashMap<String, Button>()

    private var st: JSONObject? = null
    private var mode = 0
    private var panelShown = false
    private var onRight = true
    private var stopArmed = false
    private var ready = false
    private var screenW = 0
    private var screenH = 0
    private var dp = 1f

    private val size: Int get() = d(52)
    private fun d(v: Int) = (v * dp).toInt()

    private val hideTask = Runnable { hidePanel(); setMode(0) }
    private val poll = object : Runnable {
        override fun run() {
            js("window.trackerAPI?window.trackerAPI.state():null")
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
        scheduleHide()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!ready) return
        measureScreen()
        hidePanel()
        setMode(0)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        runCatching { if (panelShown) wm.removeView(panel) }
        runCatching { wm.removeView(bubble) }
        runCatching { wm.removeView(engine); engine.destroy() }
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Dipanggil MainActivity: sembunyikan bubble saat app dibuka, tampilkan lagi saat ditinggalkan. */
    fun onAppForeground(fg: Boolean) {
        if (!ready) return
        ui.removeCallbacks(hideTask)
        if (fg) {
            hidePanel()
            bubble.visibility = View.GONE
        } else {
            bubble.visibility = View.VISIBLE
            setMode(0)
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

    @Suppress("DEPRECATION")
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

    private fun js(code: String) = engine.evaluateJavascript(code) { applyState(it) }

    private fun call(fn: String) {
        dirty = true
        js("window.trackerAPI?window.trackerAPI.$fn:null")
    }

    private fun applyState(v: String?) {
        st = try {
            if (v == null || v == "null") null else JSONObject(v)
        } catch (e: Exception) {
            null
        }
        render()
    }

    // ---------- bubble ----------
    @SuppressLint("ClickableViewAccessibility")
    private fun buildBubble() {
        bubble = TextView(themed).apply {
            gravity = Gravity.CENTER
            textSize = 17f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.WHITE)
            text = "…"
        }
        bubbleLp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        val p = prefs(this)
        onRight = p.getBoolean("right", true)
        bubbleLp.y = p.getInt("y", d(320))

        bubble.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX; sy = e.rawY; ox = bubbleLp.x; oy = bubbleLp.y; moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - sx
                        val dy = e.rawY - sy
                        if (!moved && (abs(dx) > d(8) || abs(dy) > d(8))) {
                            moved = true
                            hidePanel()
                            mode = 1
                            bubble.alpha = 1f
                        }
                        if (moved) {
                            bubbleLp.x = (ox + dx).toInt()
                            bubbleLp.y = (oy + dy).toInt().coerceIn(0, screenH - size)
                            wm.updateViewLayout(bubble, bubbleLp)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            onRight = e.rawX > screenW / 2
                            prefs(this@BubbleService).edit().putBoolean("right", onRight).putInt("y", bubbleLp.y).apply()
                            setMode(1)
                            scheduleHide()
                        } else {
                            onTap()
                        }
                        return true
                    }
                }
                return false
            }
        })
        wm.addView(bubble, bubbleLp)
        setMode(0)
    }

    private fun onTap() {
        when (mode) {
            0 -> { setMode(1); scheduleHide() }
            1 -> showPanel()
            else -> { hidePanel(); setMode(1); scheduleHide() }
        }
    }

    private fun setMode(m: Int) {
        mode = m
        bubble.alpha = if (m == 0) 0.5f else 1f
        applyPos()
    }

    private fun applyPos() {
        bubbleLp.x = if (mode == 0) {
            if (onRight) screenW - size / 2 else -size / 2
        } else {
            if (onRight) screenW - size - d(6) else d(6)
        }
        bubbleLp.y = bubbleLp.y.coerceIn(0, screenH - size)
        runCatching { wm.updateViewLayout(bubble, bubbleLp) }
    }

    private fun scheduleHide() {
        ui.removeCallbacks(hideTask)
        ui.postDelayed(hideTask, if (mode == 2) 7000L else 4000L)
    }

    // ---------- panel ----------
    private fun top(h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply { topMargin = d(10) }

    private fun tv(sizeSp: Float, color: Int, bold: Boolean = false) = TextView(themed).apply {
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun btn(label: String, fill: Int, textColor: Int, onClick: () -> Unit) = Button(themed).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTextColor(textColor)
        background = GradientDrawable().apply { setColor(fill); cornerRadius = 14f * dp }
        stateListAnimator = null
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(d(4), d(4), d(4), d(4))
        setOnClickListener { onClick(); scheduleHide() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPanel() {
        panel = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(14), d(12), d(14), d(14))
            background = GradientDrawable().apply {
                setColor(0xF2081410.toInt()); cornerRadius = 22f * dp; setStroke(d(1), 0x33FFFFFF)
            }
            setOnTouchListener { _, _ -> scheduleHide(); false }
        }
        tvTime = tv(30f, LIME, true).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
        tvStatus = tv(12f, GRAY)
        panel.addView(tvTime)
        panel.addView(tvStatus)

        val rowMain = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL }
        btnMain = btn("Mulai", LIME, INK) { call("main()") }
        btnStop = btn("Selesai", 0xFF2A3A34.toInt(), Color.WHITE) { onStopTap() }
        rowMain.addView(btnMain, LinearLayout.LayoutParams(0, d(48), 2f))
        rowMain.addView(btnStop, LinearLayout.LayoutParams(0, d(48), 1f).apply { leftMargin = d(8) })
        panel.addView(rowMain, top())

        val rowM = LinearLayout(themed).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, m) in METHODS.withIndex()) {
            val key = m.first
            val b = btn(m.second, m.third, INK) {
                if (st?.optBoolean("running") == true) {
                    buzz()
                    call("tap('$key')")
                }
            }
            methodBtns[key] = b
            rowM.addView(b, LinearLayout.LayoutParams(0, d(64), 1f).apply { if (i > 0) leftMargin = d(8) })
        }
        panel.addView(rowM, top())

        panel.addView(btn("Visit web", 0xFF1B2A25.toInt(), Color.WHITE) { openApp() }, top(d(44)))

        panelLp = WindowManager.LayoutParams(
            d(264), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    private fun showPanel() {
        if (panelShown) return
        mode = 2
        panelShown = true
        bubble.alpha = 1f
        applyPos()
        val w = d(264)
        panelLp.x = if (onRight) screenW - size - d(6) - d(8) - w else d(6) + size + d(8)
        panelLp.y = bubbleLp.y.coerceAtMost(screenH - d(320)).coerceAtLeast(d(30))
        wm.addView(panel, panelLp)
        render()
        scheduleHide()
    }

    private fun hidePanel() {
        if (panelShown) {
            runCatching { wm.removeView(panel) }
            panelShown = false
        }
        if (mode == 2) mode = 1
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
        hidePanel()
        setMode(0)
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
        val s = st
        val active = s?.optBoolean("active") == true
        val running = s?.optBoolean("running") == true
        val counts = s?.optJSONObject("counts")
        var total = 0
        for ((key, label, _) in METHODS) {
            val n = counts?.optInt(key) ?: 0
            total += n
            methodBtns[key]?.let {
                it.text = "$label\n$n"
                it.isEnabled = running
                it.alpha = if (running) 1f else 0.4f
            }
        }
        val ring = if (running) LIME else if (active) AMBER else GRAY
        bubble.text = if (s == null) "…" else total.toString()
        bubble.setTextColor(if (running) LIME else Color.WHITE)
        bubble.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xE6081410.toInt())
            setStroke(d(3), ring)
        }
        tvTime.text = fmt(s?.optLong("elapsed") ?: 0L)
        tvStatus.text = when {
            s == null -> "Tracker belum siap"
            !active -> "Belum jalan"
            running -> "Jalan"
            else -> "Dijeda"
        }
        btnMain.text = if (!active) "Mulai" else if (running) "Jeda" else "Lanjut"
        btnStop.text = if (stopArmed) "Yakin?" else "Selesai"
        btnStop.isEnabled = active
        btnStop.alpha = if (active) 1f else 0.4f
    }
}
