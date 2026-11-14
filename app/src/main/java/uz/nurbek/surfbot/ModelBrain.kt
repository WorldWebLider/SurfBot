package uz.nurbek.surfbot

import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Version 2: a small neural network trained on your own recorded play. */
class ModelBrain(file: File) : Brain {
    override val name = "O'rgatilgan model (2-versiya)"
    override val learned = true

    private val interp = Interpreter(file)
    private val input = ByteBuffer.allocateDirect(4 * Frame.SMALL_W * Frame.SMALL_H).order(ByteOrder.nativeOrder())
    private val out = Array(1) { FloatArray(5) }  // NONE, LEFT, RIGHT, JUMP, ROLL
    private var lastAct = 0L

    override fun reset() { lastAct = 0 }

    override fun decide(f: Frame): Action {
        if (f.t - lastAct < 200) return Action.NONE
        val s = f.small()
        input.rewind()
        for (b in s) input.putFloat((b.toInt() and 255) / 255f)
        input.rewind()
        interp.run(input, out)
        val p = out[0]
        var best = 0
        for (i in 1 until p.size) if (p[i] > p[best]) best = i
        if (best == 0 || p[best] < 0.55f) return Action.NONE
        lastAct = f.t
        return Action.entries[best]
    }
}
