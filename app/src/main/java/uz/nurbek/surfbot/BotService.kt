package uz.nurbek.surfbot

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Environment
import android.provider.MediaStore
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.RenderMode
import com.pedro.encoder.input.gl.render.filters.`object`.ImageFilterRender
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.rtmp.RtmpStream
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

    private var brainStamp = 0L

    /** Priority: model trained on this phone, then an imported .tflite, then the rules. */
    private fun loadBrain(): Brain {
        val net = File(filesDir, Trainer.MODEL)
        brainStamp = net.lastModified()
        if (net.exists()) try { return NetBrain(net) } catch (e: Exception) {}
        val f = File(filesDir, "model.tflite")
        return if (f.exists()) try { ModelBrain(f) } catch (e: Exception) { RuleBrain() } else RuleBrain()
    }

    /** Between runs: pick up a freshly trained model, and retrain when enough new play was collected. */
    private fun betweenRuns() {
        if (File(filesDir, Trainer.MODEL).lastModified() != brainStamp) brain = loadBrain()
        if (!cfg.selfLearn || Trainer.running || isStreaming) return
        val total = Trainer.sampleCount(this)
        if (total >= Trainer.MIN_SAMPLES && total - cfg.trainedAt >= 2000)
            Trainer.start(this, background = true) { toast(it) }
    }

    /**
     * One screen capture feeds everything (Android allows only one at a time):
     * ScreenSource -> GL -> (a) small preview into ImageReader for the bot,
     *                      (b) encoder for YouTube stream and/or mp4 recording.
     */
    private fun startCapture(data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(Activity.RESULT_OK, data)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)
        projection = p

        val audio: AudioSource =
            if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                InternalAudioSource(p) else NoAudioSource()
        val s = RtmpStream(this, checker, ScreenSource(this, p), audio)
        streamW = 720
        streamH = ((720L * screenH / screenW).toInt()) and 1.inv()
        s.prepareVideo(streamW, streamH, 3_500_000, 30)
        s.prepareAudio(44100, true, 128_000)
        stream = s

        val t = HandlerThread("cap").also { it.start() }
        capThread = t
        val w = (screenW / 4) and 1.inv()
        val h = (screenH / 4) and 1.inv()
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ onImage(it) }, Handler(t.looper))
        reader = r
        s.startPreview(r.surface, w, h)
    }

    // ---------- YouTube stream & mp4 recording ----------

    private var stream: RtmpStream? = null
    private var streamW = 720
    private var streamH = 1560
    @Volatile var streamWanted = false
        private set
    private var streamUrl = ""
    private var recordFile: File? = null
    private var pauseShown = false
    private var pauseFilter: ImageFilterRender? = null
    private var unknownSince = 0L

    val isStreaming get() = stream?.isStreaming == true
    val isRecording get() = stream?.isRecording == true

    private fun toast(msg: String) = main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    private val checker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {}
        override fun onConnectionSuccess() { toast("Strim boshlandi ✅") }
        override fun onConnectionFailed(reason: String) {
            toast("Strim uzildi ($reason). 5 soniyadan keyin qayta ulanadi…")
            main.post {
                stream?.stopStream()
                main.postDelayed({ if (streamWanted) startStreamInternal() }, 5000)
            }
        }
        override fun onDisconnect() {}
        override fun onAuthError() { toast("Stream key noto'g'ri"); main.post { stopStream() } }
        override fun onAuthSuccess() {}
    }

    fun startStream(key: String): Boolean {
        if (key.isBlank()) return false
        streamUrl = "rtmp://a.rtmp.youtube.com/live2/$key"
        streamWanted = true
        startStreamInternal()
        return true
    }

    private fun startStreamInternal() {
        val s = stream ?: return
        if (s.isStreaming) return
        try { s.startStream(streamUrl) } catch (e: Exception) { toast("Strim xatosi: ${e.message}") }
    }

    fun stopStream() {
        streamWanted = false
        stream?.let { if (it.isStreaming) it.stopStream() }
    }

    fun startRecording() {
        val s = stream ?: return
        if (s.isRecording) return
        val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        val f = File(dir, "surfbot_${System.currentTimeMillis()}.mp4")
        recordFile = f
        try {
            s.startRecord(f.absolutePath) { }
            toast("Video yozilyapti")
        } catch (e: Exception) { toast("Yozish xatosi: ${e.message}") }
    }

    fun stopRecording() {
        val s = stream ?: return
        if (!s.isRecording) return
        s.stopRecord()
        val f = recordFile ?: return
        // Give the muxer a moment to finish, then put the video in the Gallery (Movies/SurfBot).
        main.postDelayed({ Thread { saveToGallery(f) }.start() }, 800)
    }

    private fun saveToGallery(f: File) {
        if (Build.VERSION.SDK_INT < 29 || !f.exists()) { toast("Video saqlandi: ${f.name}"); return }
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SurfBot")
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            f.delete()
            toast("Video Galereyaga saqlandi (Movies/SurfBot)")
        } catch (e: Exception) { toast("Galereyaga saqlab bo'lmadi: ${e.message}") }
    }

    /** While an ad / unknown screen is up, viewers see a pause card instead. The bot still sees the real screen. */
    private fun updatePause(s: Screen, t: Long) {
        val live = isStreaming || isRecording
        if (s == Screen.UNKNOWN) { if (unknownSince == 0L) unknownSince = t } else unknownSince = 0L
        val want = live && cfg.hideAds && unknownSince != 0L && t - unknownSince > 600
        if (want == pauseShown) return
        pauseShown = want
        val gl = stream?.getGlInterface() ?: return
        if (want) {
            val f = ImageFilterRender().apply {
                setImage(pauseBitmap())
                setScale(100f, 100f)
                setPosition(0f, 0f)
                renderMode = RenderMode.OUTPUT  // stream/recording only, not the bot's view
            }
            pauseFilter = f
            gl.addFilter(f)
        } else {
            pauseFilter?.let { gl.removeFilter(it) }
            pauseFilter = null
        }
    }

    private var pauseBmp: Bitmap? = null
    private fun pauseBitmap(): Bitmap = pauseBmp ?: run {
        val b = Bitmap.createBitmap(streamW, streamH, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(16, 24, 32))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
        p.textSize = streamW * 0.09f
        c.drawText("Bir daqiqa…", streamW / 2f, streamH * 0.47f, p)
        p.textSize = streamW * 0.045f
        p.color = Color.rgb(170, 180, 190)
        c.drawText("O'yin hozir davom etadi", streamW / 2f, streamH * 0.53f, p)
        b.also { pauseBmp = it }
    }

    // ---------- frames ----------

    private fun onImage(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return
        try {
            // Frames are read even while the bot is off: the stream's ad-pause needs them.
            if (!active && !isStreaming && !isRecording) return
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
            val s = ScreenReader.read(f)
            updatePause(s, f.t)
            if (!active) return
            if (mode == Mode.BOT) autoStep(f, s) else recStep(f)
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

    private fun autoStep(f: Frame, s: Screen) {
        if (s != screen) { screen = s; screenSince = f.t; menuDelay = Human.menuWait() }

        if (s == Screen.PLAYING) {
            if (!inRun) { inRun = true; betweenRuns(); brain.reset(); keysUsed = 0; boardsUsed = 0 }
            (brain as? RuleBrain)?.boardAllowed =
                cfg.hoverboard && boardsUsed < cfg.maxBoards && f.t - lastBoard > 35_000
            botStep(f)
            return
        }
        if (s == Screen.CONTINUE || s == Screen.RESULTS) selfDrop()
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

    // ---------- self-learning: keep only moves the bot survived ----------

    private class SelfSample(val t: Long, val a: Action, val s: ByteArray)
    private val selfBuf = ArrayDeque<SelfSample>()
    @Volatile private var selfRec: Recorder? = null
    private var lastSelfNone = 0L

    /** A sample is written to disk only after the bot stayed alive 5 more seconds. */
    private fun selfAdd(t: Long, a: Action, f: Frame) {
        val rec = selfRec ?: return
        synchronized(selfBuf) {
            selfBuf.addLast(SelfSample(t, a, f.small()))
            while (selfBuf.isNotEmpty() && selfBuf.first().t < t - 5000) {
                val e = selfBuf.removeFirst()
                rec.add(e.a, e.s)
            }
        }
    }

    /** Crash: the last seconds led to it, so they are thrown away. */
    private fun selfDrop() = synchronized(selfBuf) { selfBuf.clear() }

    private fun botStep(f: Frame) {
        if (pending) return
        val b = brain
        val a = b.decide(f)
        if (a == Action.NONE) {
            if (f.t - lastSelfNone > 250) { lastSelfNone = f.t; if (Math.random() < 0.35) selfAdd(f.t, a, f) }
            return
        }
        if (a == Action.BOARD) { boardsUsed++; lastBoard = f.t } else selfAdd(f.t, a, f)
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
            } else if (cfg.selfLearn) {
                val dir = File(filesDir, "rec")
                val used = dir.listFiles()?.sumOf { it.length() } ?: 0L
                // Stop collecting at ~500 MB so the phone's storage is never filled.
                if (used < 500L * 1024 * 1024) selfRec = Recorder(dir, "self")
            }
        } else {
            selfDrop()
            selfRec?.close()
            selfRec = null
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
        val size = (24 * d).toInt()
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
        tv.text = ""  // no letters: nothing on stream hints at a bot
        val color = when {
            !active -> Color.GRAY
            mode == Mode.BOT -> Color.rgb(30, 160, 70)
            else -> Color.rgb(200, 40, 40)
        }
        // The ring shows the mode even while off (grey fill + green/red ring).
        val ring = when (mode) {
            Mode.BOT -> Color.rgb(30, 160, 70)
            Mode.REC -> Color.rgb(200, 40, 40)
        }
        val d = resources.displayMetrics.density
        tv.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((2 * d).toInt(), ring)
        }
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
        var x0 = 0f; var y0 = 0f
        var fired = false
        var snap: ByteArray? = null
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; fired = false; snap = lastSmall }
                // Fire as soon as the direction is clear — don't wait for the finger to lift.
                MotionEvent.ACTION_MOVE -> if (!fired && hypot(e.rawX - x0, e.rawY - y0) > screenW * 0.035f) {
                    fired = true
                    userSwipe(x0, y0, e.rawX - x0, e.rawY - y0, snap)
                }
                MotionEvent.ACTION_UP -> if (!fired) {
                    val dx = e.rawX - x0; val dy = e.rawY - y0
                    if (hypot(dx, dy) < screenW * 0.035f) passThrough(Path().apply { moveTo(x0, y0) }, 35L)
                    else userSwipe(x0, y0, dx, dy, snap)
                }
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

    /** Save the swipe for training, then send a short, fast swipe in the same direction to the game. */
    private fun userSwipe(x0: Float, y0: Float, dx: Float, dy: Float, snap: ByteArray?) {
        val horizontal = abs(dx) > abs(dy)
        val label = if (horizontal) {
            if (dx < 0) Action.LEFT else Action.RIGHT
        } else {
            if (dy < 0) Action.JUMP else Action.ROLL
        }
        lastUserSwipe = SystemClock.uptimeMillis()
        snap?.let { recorder?.add(label, it) }
        val len = screenW * 0.15f
        val ex = if (horizontal) x0 + (if (dx < 0) -len else len) else x0
        val ey = if (horizontal) y0 else y0 + (if (dy < 0) -len else len)
        passThrough(Path().apply { moveTo(x0, y0); lineTo(ex, ey) }, 35L)
    }

    private fun passThrough(path: Path, dur: Long) {
        val v = touchLayer ?: return
        val lp = touchParams ?: return
        lp.flags = lp.flags or FLAG_NOT_TOUCHABLE
        wm.updateViewLayout(v, lp)
        // One frame for the window manager to apply the flag, then inject.
        main.postDelayed({
            val restore = {
                touchLayer?.let { tl -> lp.flags = lp.flags and FLAG_NOT_TOUCHABLE.inv(); wm.updateViewLayout(tl, lp) }
                Unit
            }
            val g = GestureService.instance
            if (g == null || !g.perform(path, dur) { main.post { restore() } }) restore()
        }, 8)
    }

    // ---------- shutdown ----------

    override fun onDestroy() {
        active = false
        removeTouchLayer()
        bubble?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        recorder?.close()
        selfRec?.close()
        streamWanted = false
        stream?.let { s ->
            try {
                if (s.isStreaming) s.stopStream()
                if (s.isRecording) { s.stopRecord(); recordFile?.let { f -> Thread { saveToGallery(f) }.start() } }
                if (s.isOnPreview) s.stopPreview()
                s.release()
            } catch (e: Exception) {}
        }
        stream = null
        vd?.release()
        reader?.close()
        projection?.stop()
        capThread?.quitSafely()
        instance = null
        super.onDestroy()
    }
}
