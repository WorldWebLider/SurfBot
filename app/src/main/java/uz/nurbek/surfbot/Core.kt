package uz.nurbek.surfbot

import android.graphics.Path
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Random
import kotlin.math.hypot

/** Label order is also the model's output order (first 5). Do not reorder. BOARD = hoverboard. */
enum class Action { NONE, LEFT, RIGHT, JUMP, ROLL, BOARD }

/** User settings, changed on the app's main screen. */
class Settings(ctx: android.content.Context) {
    private val p = ctx.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE)
    var autoRestart: Boolean
        get() = p.getBoolean("autoRestart", true); set(v) { p.edit().putBoolean("autoRestart", v).apply() }
    var useKeys: Boolean
        get() = p.getBoolean("useKeys", true); set(v) { p.edit().putBoolean("useKeys", v).apply() }
    var maxKeys: Int
        get() = p.getInt("maxKeys", 1); set(v) { p.edit().putInt("maxKeys", v).apply() }
    var hoverboard: Boolean
        get() = p.getBoolean("hoverboard", true); set(v) { p.edit().putBoolean("hoverboard", v).apply() }
    var maxBoards: Int
        get() = p.getInt("maxBoards", 1); set(v) { p.edit().putInt("maxBoards", v).apply() }
    var openBoxes: Boolean
        get() = p.getBoolean("openBoxes", true); set(v) { p.edit().putBoolean("openBoxes", v).apply() }
    var streamKey: String
        get() = p.getString("streamKey", "") ?: ""; set(v) { p.edit().putString("streamKey", v.trim()).apply() }
    /** Show a pause card on stream/recording while an ad or unknown screen is up. */
    var hideAds: Boolean
        get() = p.getBoolean("hideAds", true); set(v) { p.edit().putBoolean("hideAds", v).apply() }
}

/** One captured screen frame, RGB packed as 0xRRGGBB. */
class Frame(val w: Int, val h: Int, val px: IntArray, val t: Long) {
    /** Grayscale downsample used for recording and for the learned model. */
    fun small(ow: Int = SMALL_W, oh: Int = SMALL_H): ByteArray {
        val out = ByteArray(ow * oh)
        for (y in 0 until oh) {
            val sy = y * h / oh
            for (x in 0 until ow) {
                val c = px[sy * w + x * w / ow]
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                out[y * ow + x] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
            }
        }
        return out
    }

    companion object {
        const val SMALL_W = 64
        const val SMALL_H = 112
    }
}

interface Brain {
    val name: String
    /** A learned model already contains human reaction time, so it waits less. */
    val learned: Boolean
    fun reset()
    fun decide(f: Frame): Action
}

/** Makes every gesture slightly different, like a real thumb. */
object Human {
    private val rnd = Random()

    fun reactionDelay(learned: Boolean): Long {
        val base = if (learned) 45.0 else 140.0
        val spread = if (learned) 15.0 else 30.0
        return (base + rnd.nextGaussian() * spread).coerceIn(base * 0.6, base * 1.7).toLong()
    }

    fun swipePath(a: Action, w: Int, h: Int): Pair<Path, Long> {
        val sx = w * (0.40f + rnd.nextFloat() * 0.20f)
        val sy = h * (0.55f + rnd.nextFloat() * 0.15f)
        val len = w * (0.16f + rnd.nextFloat() * 0.10f)
        val drift = len * (rnd.nextFloat() - 0.5f) * 0.30f
        val (dx, dy) = when (a) {
            Action.LEFT -> -len to drift
            Action.RIGHT -> len to drift
            Action.JUMP -> drift to -len
            Action.ROLL -> drift to len
            else -> 0f to 0f
        }
        // Thumbs move in a slight arc, not a ruler-straight line.
        val d = hypot(dx, dy).coerceAtLeast(1f)
        val bend = (rnd.nextFloat() - 0.5f) * len * 0.20f
        val cx = sx + dx / 2 + (-dy / d) * bend
        val cy = sy + dy / 2 + (dx / d) * bend
        val p = Path().apply { moveTo(sx, sy); quadTo(cx, cy, sx + dx, sy + dy) }
        val dur = 55L + rnd.nextInt(65)
        return p to dur
    }

    /** A single finger tap near (xf, yf), never exactly on the same pixel. */
    fun tapPoint(xf: Float, yf: Float, w: Int, h: Int): Path {
        val x = w * xf + (rnd.nextFloat() - 0.5f) * w * 0.04f
        val y = h * yf + (rnd.nextFloat() - 0.5f) * h * 0.012f
        return Path().apply { moveTo(x, y) }
    }

    fun tapDuration(): Long = 40L + rnd.nextInt(45)

    /** Gap between two taps of a double-tap (hoverboard). */
    fun doubleTapGap(): Long = 90L + rnd.nextInt(60)

    /** How long a person looks at a menu before pressing. */
    fun menuWait(): Long = 900L + rnd.nextInt(1600)
}

/** Stores (label, 64x112 grayscale frame) pairs: 1 + 7168 bytes per sample. */
class Recorder(dir: File) {
    private val out: BufferedOutputStream
    var count = 0
        private set

    init {
        dir.mkdirs()
        out = BufferedOutputStream(FileOutputStream(File(dir, "rec_${System.currentTimeMillis()}.bin")))
    }

    @Synchronized fun add(a: Action, s: ByteArray) {
        out.write(a.ordinal)
        out.write(s)
        count++
    }

    @Synchronized fun close() {
        out.flush()
        out.close()
    }
}
