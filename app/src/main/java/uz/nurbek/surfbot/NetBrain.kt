package uz.nurbek.surfbot

import java.io.File

/** Plays with the network that was trained on this phone (see Trainer). */
class NetBrain(file: File) : Brain {
    override val name = "Telefonda o'rgatilgan model"
    override val learned = true

    private val net = Net.load(file)
    private val inp = ByteArray(Net.IN)
    private var lastAct = 0L

    override fun reset() { lastAct = 0 }

    override fun decide(f: Frame): Action {
        // A lane change takes ~0.4 s on screen; deciding again earlier causes double swipes.
        if (f.t - lastAct < 400) return Action.NONE
        Net.pool(f.small(), inp, 0)
        val p = net.forward(inp, 0)
        var best = 0
        for (i in 1 until p.size) if (p[i] > p[best]) best = i
        if (best == 0 || p[best] < 0.5f) return Action.NONE
        lastAct = f.t
        return Action.entries[best]
    }
}
