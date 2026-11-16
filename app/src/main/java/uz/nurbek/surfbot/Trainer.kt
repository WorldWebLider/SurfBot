package uz.nurbek.surfbot

import android.content.Context
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.util.Random

/**
 * Trains the network on the phone itself from everything in files/rec:
 * your own REC play (rec_*.bin) and the bot's surviving self-play (self_*.bin).
 */
object Trainer {
    const val MODEL = "net.bin"
    const val MIN_SAMPLES = 800
    private const val SMALL = Frame.SMALL_W * Frame.SMALL_H
    private const val REC = 1 + SMALL
    private const val MAX_SAMPLES = 24000
    private const val MAX_MILLIS = 20 * 60 * 1000L

    @Volatile var running = false
        private set
    @Volatile var status = ""
        private set

    fun sampleCount(ctx: Context): Long =
        File(ctx.filesDir, "rec").listFiles()?.sumOf { it.length() / REC } ?: 0L

    /** background = true: lowest priority, so the game keeps running smoothly meanwhile. */
    fun start(ctx: Context, background: Boolean, scratch: Boolean = false, onDone: ((String) -> Unit)? = null): Boolean {
        if (running) return false
        running = true
        val app = ctx.applicationContext
        Thread({
            if (background) android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val msg = try { train(app, scratch) } catch (e: Throwable) { "O'qitishda xato: ${e.message}" }
            status = msg
            running = false
            onDone?.invoke(msg)
        }, "trainer").start()
        return true
    }

    private fun train(ctx: Context, scratch: Boolean): String {
        val t0 = System.currentTimeMillis()
        val files = (File(ctx.filesDir, "rec").listFiles() ?: emptyArray())
            .filter { it.length() >= REC }
            .sortedByDescending { it.lastModified() }  // newest first: they fit the current game world best
        var total = 0L
        for (f in files) total += f.length() / REC
        if (total < MIN_SAMPLES) return "Namuna kam: $total ta (kamida $MIN_SAMPLES kerak)"

        status = "Yozuvlar o'qilmoqda…"
        val cap = minOf(total, MAX_SAMPLES.toLong()).toInt()
        val x = ByteArray(cap * Net.IN)
        val y = ByteArray(cap)
        val small = ByteArray(SMALL)
        var n = 0
        for (f in files) {
            if (n >= cap) break
            DataInputStream(BufferedInputStream(f.inputStream(), 1 shl 16)).use { inp ->
                var left = f.length() / REC
                while (left-- > 0 && n < cap) {
                    val label = inp.read()
                    inp.readFully(small)
                    if (label in 0..4) {
                        Net.pool(small, x, n * Net.IN)
                        y[n] = label.toByte()
                        n++
                    }
                }
            }
        }
        if (n < MIN_SAMPLES) return "Namuna kam: $n ta"

        // Rare actions (jump, roll) count more, so the bot doesn't learn to "do nothing".
        val counts = IntArray(5)
        for (i in 0 until n) counts[y[i].toInt()]++
        val weight = FloatArray(5) { c -> (n / (5f * maxOf(counts[c], 1))).coerceIn(0.3f, 4f) }

        val rnd = Random()
        val idx = IntArray(n) { it }
        for (i in n - 1 downTo 1) { val j = rnd.nextInt(i + 1); val t = idx[i]; idx[i] = idx[j]; idx[j] = t }
        val nVal = if (n >= 1000) n / 10 else 0
        val nTr = n - nVal

        val modelFile = File(ctx.filesDir, MODEL)
        val fresh = scratch || !modelFile.exists()
        val net = if (fresh) Net() else try { Net.load(modelFile) } catch (e: Exception) { Net() }
        // Small data sets need more passes; an existing model is only refreshed.
        val epochs = if (fresh) (60000 / nTr).coerceIn(8, 30) else (30000 / nTr).coerceIn(4, 15)
        var lr = if (fresh) 0.02f else 0.01f
        for (ep in 0 until epochs) {
            status = "O'qitilmoqda: ${ep + 1}/$epochs"
            var inBatch = 0
            for (i in 0 until nTr) {
                val s = idx[nVal + rnd.nextInt(nTr)]
                val label = y[s].toInt()
                net.accumulate(x, s * Net.IN, label, weight[label])
                if (++inBatch == 16) { net.step(lr, 16); inBatch = 0 }
            }
            if (inBatch > 0) net.step(lr, inBatch)
            lr *= 0.9f
            if (System.currentTimeMillis() - t0 > MAX_MILLIS) break
        }

        // Check on samples the network has not trained on.
        var ok = 0; var acts = 0; var actsOk = 0
        for (i in 0 until nVal) {
            val s = idx[i]
            val p = net.forward(x, s * Net.IN)
            var best = 0
            for (k in 1 until p.size) if (p[k] > p[best]) best = k
            val label = y[s].toInt()
            if (best == label) ok++
            if (label != 0) { acts++; if (best == label) actsOk++ }
        }
        val acc = if (nVal > 0) 100 * ok / nVal else -1
        val actAcc = if (acts > 0) 100 * actsOk / acts else -1

        val tmp = File(ctx.filesDir, "$MODEL.tmp")
        net.save(tmp)
        if (!tmp.renameTo(modelFile)) { tmp.copyTo(modelFile, overwrite = true); tmp.delete() }

        val cfg = Settings(ctx)
        cfg.trainedAt = total
        val mins = (System.currentTimeMillis() - t0) / 60000.0
        val res = "Model tayyor ✅ $n ta namuna" +
            (if (acc >= 0) ", aniqlik $acc%" else "") +
            (if (actAcc >= 0) ", harakatlar $actAcc%" else "") +
            String.format(" (%.1f daqiqa)", mins)
        cfg.lastTrain = res
        return res
    }
}
