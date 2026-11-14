package uz.nurbek.surfbot

/**
 * Version 1: no training needed. Looks a short distance ahead in each of the
 * three lanes and dodges what it sees. Geometry is for portrait Subway Surfers;
 * the constants below may need tuning after the first test.
 */
class RuleBrain : Brain {
    override val name = "Qoidalar (1-versiya)"
    override val learned = false

    /** Set by the service: true when a hoverboard may be used right now. */
    var boardAllowed = false

    private var lane = 1
    private var lastAct = 0L

    // Perspective: lanes meet at the vanishing point, spread out toward the player.
    private val vpX = 0.50f
    private val vpY = 0.30f
    private val baseY = 0.82f      // roughly the player's feet
    private val laneOff = 0.30f    // lane centers at baseY: 0.2, 0.5, 0.8 of width
    private val yNear = 0.68f      // just above the player's head
    private val yFar = 0.45f       // furthest point we care about

    private class Hit(val close: Float, val tall: Boolean)

    override fun reset() { lane = 1; lastAct = 0 }

    private fun laneX(l: Int, yf: Float): Float {
        val k = (yf - vpY) / (baseY - vpY)
        return vpX + (l - 1) * laneOff * k
    }

    private fun halfW(yf: Float) = laneOff * (yf - vpY) / (baseY - vpY) * 0.30f

    /** Trains and barriers are bright and saturated; track and gravel are grey-brown. */
    private fun isObj(c: Int): Boolean {
        val r = (c shr 16) and 255
        val g = (c shr 8) and 255
        val b = c and 255
        val mx = maxOf(r, maxOf(g, b))
        val mn = minOf(r, minOf(g, b))
        if (mx < 70) return false
        // Gold coins are collectibles, not obstacles.
        if (r > 190 && g in 110..215 && b < 90) return false
        return (mx - mn).toFloat() / mx > 0.42f
    }

    private fun coverage(f: Frame, l: Int, y: Int): Float {
        val yf = y.toFloat() / f.h
        val cx = laneX(l, yf) * f.w
        val hw = halfW(yf) * f.w
        val x0 = (cx - hw).toInt().coerceIn(0, f.w - 1)
        val x1 = (cx + hw).toInt().coerceIn(0, f.w - 1)
        var n = 0
        var t = 0
        for (x in x0..x1) { if (isObj(f.px[y * f.w + x])) n++; t++ }
        return if (t == 0) 0f else n.toFloat() / t
    }

    private fun scan(f: Frame, l: Int): Hit? {
        var run = 0
        val yStart = (yNear * f.h).toInt()
        val yEnd = (yFar * f.h).toInt()
        var y = yStart
        while (y >= yEnd) {
            // Coins are small; an obstacle fills most of the lane for several rows.
            if (coverage(f, l, y) > 0.6f) {
                run++
                if (run >= 4) {
                    val yHit = y + 3
                    val close = ((yHit.toFloat() / f.h) - yFar) / (yNear - yFar)
                    var up = 0
                    var yy = y - 1
                    while (yy > (vpY * f.h).toInt() && coverage(f, l, yy) > 0.5f) { up++; yy-- }
                    return Hit(close.coerceIn(0f, 1f), up > 0.10f * f.h)
                }
            } else run = 0
            y--
        }
        return null
    }

    override fun decide(f: Frame): Action {
        if (f.t - lastAct < 300) return Action.NONE
        val hits = Array(3) { scan(f, it) }
        val cur = hits[lane] ?: return Action.NONE
        if (cur.close < 0.45f) return Action.NONE

        fun closeness(l: Int) = hits[l]?.close ?: -1f
        fun free(l: Int) = l in 0..2 && closeness(l) < 0.25f

        val side = listOf(lane - 1, lane + 1).filter { it in 0..2 }
        val freeSide = side.filter { free(it) }
        val act = when {
            freeSide.isNotEmpty() -> if (freeSide.minBy { closeness(it) } < lane) Action.LEFT else Action.RIGHT
            !cur.tall -> Action.JUMP
            // Trapped in front of a train: the hoverboard saves the run.
            boardAllowed && cur.close > 0.6f -> Action.BOARD
            else ->if (side.minBy { closeness(it) } < lane) Action.LEFT else Action.RIGHT
        }
        when (act) {
            Action.LEFT -> lane--
            Action.RIGHT -> lane++
            else -> {}
        }
        lastAct = f.t
        return act
    }
}
