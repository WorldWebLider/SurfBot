package uz.nurbek.surfbot

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

class BotService : Service() {
    companion object {
        @Volatile var instance: BotService? = null
        const val EXTRA_DATA = "data"
        const val GAME_PKG = "com.kiloo.subwaysurf"
    }

    enum class Mode { BOT, REC }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var capThread: HandlerThread? = null

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var touchLayer: View? = null
    private var touchParams: WindowManager.LayoutParams? = null

    @Volatile var active = false
        private set
    @Volatile var mode = Mode.BOT
        private set
    @Volatile private var brain: Brain = RuleBrain()
    @Volatile private var pending = false
    @Volatile private var lastSmall: ByteArray? = null
    private var recorder: Recorder? = null
    @Volatile private var lastUserSwipe = 0L
    private var lastNoneSample = 0L
    private var screenW = 0
    private var screenH = 0
    private var rowBuf = ByteArray(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || projection != null) return START_NOT_STICKY
        startFg()
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(EXTRA_DATA)
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        measure()
        brain = loadBrain()
        startCapture(data)
        showBubble()
        Toast.makeText(this, "SurfBot tayyor: ${brain.name}", Toast.LENGTH_SHORT).show()
        return START_NOT_STICKY
    }

    // ---------- setup ----------

    private fun startFg() {
        val ch = "surfbot"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ch, "SurfBot", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch)
            .setContentTitle("SurfBot ishlayapti")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    private fun measure() {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            screenW = b.width(); screenH = b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            screenW = m.widthPixels; screenH = m.heightPixels
        }
    }

    private fun loadBrain(): Brain {
        val f = File(filesDir, "model.tflite")
        return if (f.exists()) try { ModelBrain(f) } catch (e: Exception) { RuleBrain() } else RuleBrain()
    }

    private fun startCapture(data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(Activity.RESULT_OK, data)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)
        projection = p

        val t = HandlerThread("cap").also { it.start() }
        capThread = t
        val capHandler = Handler(t.looper)
        val w = screenW / 4
        val h = screenH / 4
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ onImage(it) }, capHandler)
        reader = r
        vd = p.createVirtualDisplay(
            "surfbot", w, h, maxOf(1, resources.displayMetrics.densityDpi / 4),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, capHandler
        )
    }

    // ---------- frames ----------

    private fun onImage(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            if (!active) return
            val plane = img.planes[0]
            val buf = plane.buffer
            val ps = plane.pixelStride
            val rs = plane.rowStride
            val w = img.width
            val h = img.height
            if (rowBuf.size < rs) rowBuf = ByteArray(rs)
            val px = IntArray(w * h)
            for (y in 0 until h) {
                buf.position(y * rs)
                buf.get(rowBuf, 0, minOf(rs, buf.remaining()))
                var o = 0
                for (x in 0 until w) {
                    px[y * w + x] = ((rowBuf[o].toInt() and 255) shl 16) or
                        ((rowBuf[o + 1].toInt() and 255) shl 8) or
                        (rowBuf[o + 2].toInt() and 255)
                    o += ps
                }
            }
            val f = Frame(w, h, px, SystemClock.uptimeMillis())
            if (mode == Mode.BOT) autoStep(f) else recStep(f)
        } catch (e: Exception) {
            // Never crash mid-stream because of one bad frame.
        } finally {
            img.close()
        }
    }

    // ---------- full game loop: run, continue, restart, boxes, ads ----------

    private val cfg by lazy { Settings(this) }
    private var screen = Screen.UNKNOWN
    private var screenSince = 0L
    private var menuDelay = 1500L
    private var lastMenuAct = 0L
    private var inRun = false
    private var keysUsed = 0
    private var boardsUsed = 0
    private var lastBoard = 0L

    private fun autoStep(f: Frame) {
        val s = ScreenReader.read(f)
        if (s != screen) { screen = s; screenSince = f.t; menuDelay = Human.menuWait() }

        if (s == Screen.PLAYING) {
            if (!inRun) { inRun = true; brain.reset(); keysUsed = 0; boardsUsed = 0 }
            (brain as? RuleBrain)?.boardAllowed =
                cfg.hoverboard && boardsUsed < cfg.maxBoards && f.t - lastBoard > 35_000
            botStep(f)
            return
        }
        if (s == Screen.RESULTS || s == Screen.MENU) inRun = false
        if (!cfg.autoRestart || pending || f.t - lastMenuAct < 1500) return

        val stable = f.t - screenSince
        when (s) {
            // Never touch the green "watch ad" button; only the key button.
            Screen.CONTINUE -> if (cfg.useKeys && keysUsed < cfg.maxKeys && stable > 500) {
                keysUsed++
                tap(ScreenReader.TAP_KEY, f.t)
            }
            Screen.RESULTS -> if (stable > menuDelay) tap(ScreenReader.TAP_PLAY, f.t)
            Screen.MENU -> if (stable > menuDelay) tap(ScreenReader.TAP_MENU, f.t)
            Screen.BOX -> if (cfg.openBoxes && stable > 600) tap(ScreenReader.TAP_BOX, f.t)
                else if (stable > 3000) back(f.t)
            Screen.UNKNOWN -> handleUnknown(f.t, stable)
            else -> {}
        }
    }

    /** Ads, pop-ups, box rewards: wait like a person, then press Back. Relaunch the game if it was left. */
    private fun handleUnknown(now: Long, stable: Long) {
        val g = GestureService.instance ?: return
        val pkg = g.foregroundPkg
        if (pkg.isNotEmpty() && pkg != GAME_PKG && stable > 8000) {
            lastMenuAct = now
            launchGame()
            return
        }
        if (stable > 5000 && now - lastMenuAct > 4000) back(now)
    }

    private fun back(now: Long) {
        lastMenuAct = now
        main.post { GestureService.instance?.pressBack() }
    }

    private fun launchGame() = main.post {
        packageManager.getLaunchIntentForPackage(GAME_PKG)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startActivity(it) } catch (e: Exception) {}
        }
    }

    private fun tap(target: Pair<Float, Float>, now: Long) {
        lastMenuAct = now
        pending = true
        main.post {
            val g = GestureService.instance
            if (g == null || !g.perform(Human.tapPoint(target.first, target.second, screenW, screenH), Human.tapDuration()) { pending = false })
                pending = false
        }
    }

    private fun botStep(f: Frame) {
        if (pending) return
        val b = brain
        val a = b.decide(f)
        if (a == Action.NONE) return
        if (a == Action.BOARD) { boardsUsed++; lastBoard = f.t }
        pending = true
        main.postDelayed({ doAction(a) { pending = false } }, Human.reactionDelay(b.learned))
    }

    private fun doAction(a: Action, done: () -> Unit) {
        if (!active) { done(); return }
        val g = GestureService.instance ?: run { done(); return }
        if (a == Action.BOARD) {
            val p1 = Human.tapPoint(0.5f, 0.62f, screenW, screenH)
            val p2 = Human.tapPoint(0.5f, 0.62f, screenW, screenH)
            if (!g.doubleTap(p1, p2, Human.tapDuration(), Human.doubleTapGap(), Human.tapDuration()) { done() }) done()
            return
        }
        val (path, dur) = Human.swipePath(a, screenW, screenH)
        if (!g.perform(path, dur) { done() }) done()
    }

    private fun recStep(f: Frame) {
        val s = f.small()
        lastSmall = s
        // Also save "do nothing" moments so the model learns when NOT to swipe.
        if (f.t - lastUserSwipe > 400 && f.t - lastNoneSample > 200) {
            lastNoneSample = f.t
            if (Math.random() < 0.35) recorder?.add(Action.NONE, s)
        }
    }

    // ---------- on / off ----------

    fun toggle() = main.post {
        active = !active
        if (active) {
            brain = loadBrain()
            brain.reset()
            pending = false
            if (mode == Mode.REC) {
                recorder = Recorder(File(filesDir, "rec"))
                addTouchLayer()
            }
        } else {
            removeTouchLayer()
            recorder?.let {
                it.close()
                Toast.makeText(this, "Yozildi: ${it.count} ta namuna", Toast.LENGTH_SHORT).show()
            }
            recorder = null
        }
        updateBubble()
    }

    private fun switchMode() {
        if (active) toggle()
        main.post {
            mode = if (mode == Mode.BOT) Mode.REC else Mode.BOT
            updateBubble()
            val msg = if (mode == Mode.BOT) "Rejim: BOT o'ynaydi" else "Rejim: YOZISH (siz o'ynaysiz)"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- floating button ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val d = resources.displayMetrics.density
        val tv = TextView(this).apply {
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            alpha = 0.45f
        }
        val size = (34 * d).toInt()
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = screenH / 3 }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var downT = 0L; var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    downT = SystemClock.uptimeMillis(); moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    if (moved) { lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt(); wm.updateViewLayout(tv, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    if (SystemClock.uptimeMillis() - downT > 600) switchMode() else toggle()
                }
            }
            true
        }
        wm.addView(tv, lp)
        bubble = tv
        bubbleParams = lp
        updateBubble()
    }

    private fun updateBubble() {
        val tv = bubble ?: return
        tv.text = if (mode == Mode.BOT) "AI" else "REC"
        val color = when {
            !active -> Color.GRAY
            mode == Mode.BOT -> Color.rgb(30, 160, 70)
            else -> Color.rgb(200, 40, 40)
        }
        tv.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    }

    // ---------- recording your play ----------

    /** Transparent layer that sees your swipe, saves it, then passes it to the game. */
    @SuppressLint("ClickableViewAccessibility")
    private fun addTouchLayer() {
        if (touchLayer != null) return
        val v = View(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        var x0 = 0f; var y0 = 0f; var t0 = 0L
        var snap: ByteArray? = null
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; t0 = SystemClock.uptimeMillis(); snap = lastSmall }
                MotionEvent.ACTION_UP -> userGesture(x0, y0, e.rawX, e.rawY, SystemClock.uptimeMillis() - t0, snap)
            }
            true
        }
        wm.addView(v, lp)
        touchLayer = v
        touchParams = lp
        // Keep the floating button above the touch layer.
        bubble?.let { b -> bubbleParams?.let { wm.removeView(b); wm.addView(b, it) } }
    }

    private fun removeTouchLayer() {
        touchLayer?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        touchLayer = null
        touchParams = null
    }

    private fun userGesture(x0: Float, y0: Float, x1: Float, y1: Float, dur: Long, snap: ByteArray?) {
        val dx = x1 - x0
        val dy = y1 - y0
        val isTap = hypot(dx, dy) < screenW * 0.05f
        val path = Path().apply { moveTo(x0, y0); if (!isTap) lineTo(x1, y1) }
        if (!isTap) {
            val label = if (abs(dx) > abs(dy)) {
                if (dx < 0) Action.LEFT else Action.RIGHT
            } else {
                if (dy < 0) Action.JUMP else Action.ROLL
            }
            lastUserSwipe = SystemClock.uptimeMillis()
            snap?.let { recorder?.add(label, it) }
        }
        passThrough(path, dur.coerceIn(40L, 250L))
    }

    private fun passThrough(path: Path, dur: Long) {
        val v = touchLayer ?: return
        val lp = touchParams ?: return
        lp.flags = lp.flags or FLAG_NOT_TOUCHABLE
        wm.updateViewLayout(v, lp)
        main.postDelayed({
            val restore = {
                touchLayer?.let { tl -> lp.flags = lp.flags and FLAG_NOT_TOUCHABLE.inv(); wm.updateViewLayout(tl, lp) }
                Unit
            }
            val g = GestureService.instance
            if (g == null || !g.perform(path, dur) { main.post { restore() } }) restore()
        }, 16)
    }

    // ---------- shutdown ----------

    override fun onDestroy() {
        active = false
        removeTouchLayer()
        bubble?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        recorder?.close()
        vd?.release()
        reader?.close()
        projection?.stop()
        capThread?.quitSafely()
        instance = null
        super.onDestroy()
    }
}
