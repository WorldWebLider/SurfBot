package uz.nurbek.surfbot

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Looks for gold coins in the colour frame and suggests a lane change toward them
 * when nothing dangerous is happening. Geometry and colours were measured on a
 * real gameplay recording from the S25 (lanes are nearly fixed on screen).
 * The runner is found by the red cap, so it expects a character with a red hat.
 */
class CoinEye {
    private var lane = -1
    private var laneStable = 0
    private var want = 0
    private var wantStable = 0
    private var lastMove = 0L

    private fun r(c: Int) = (c shr 16) and 255
    private fun g(c: Int) = (c shr 8) and 255
    private fun b(c: Int) = c and 255

    /** Magnet active: red magnet icon inside the blue power-up frame, bottom-left. */
    fun magnet(f: Frame): Boolean {
        val icon = f.px[(0.955f * f.h).toInt() * f.w + (0.06f * f.w).toInt()]
        val frame = f.px[(0.957f * f.h).toInt() * f.w + (0.12f * f.w).toInt()]
        return r(icon) > 180 && g(icon) < 105 && b(icon) < 115 && b(frame) > 185 && r(frame) < 135
    }

    /** 0 = left, 1 = centre, 2 = right, -1 = runner not found. */
    private fun playerLane(f: Frame): Int {
        val y0 = (0.56f * f.h).toInt()
        val y1 = (0.80f * f.h).toInt()
        val col = IntArray(f.w)
        for (y in y0 until y1) {
            val row = y * f.w
            for (x in 0 until f.w) {
                val c = f.px[row + x]
                if (r(c) > 170 && g(c) < 75 && b(c) < 80) col[x]++
            }
        }
        val win = maxOf(3, f.w * 15 / 270)
        var sum = 0
        for (x in 0 until win) sum += col[x]
        var best = sum
        var bestX = win / 2
        for (x in win until f.w) {
            sum += col[x] - col[x - win]
            if (sum > best) { best = sum; bestX = x - win / 2 }
        }
        val need = 60f * f.w * f.h / (270f * 584f)
        if (best < need) return -1
        return ((bestX.toFloat() / f.w - 0.49f) / 0.21f).roundToInt().coerceIn(-1, 1) + 1
    }

    private fun coinsPerLane(f: Frame): IntArray {
        val cnt = IntArray(3)
        val y0 = (0.46f * f.h).toInt()
        val y1 = (0.64f * f.h).toInt()
        for (y in y0 until y1) {
            val spacing = 0.30f * (y.toFloat() / f.h - 0.30f) / 0.52f
            val row = y * f.w
            for (x in 0 until f.w) {
                val c = f.px[row + x]
                val rr = r(c); val gg = g(c)
                if (rr > 225 && gg in 150..225 && b(c) < 75 && rr - gg > 25) {
                    val rel = (x.toFloat() / f.w - 0.5f) / spacing
                    if (abs(rel) < 1.5f) cnt[rel.roundToInt() + 1]++
                }
            }
        }
        return cnt
    }

    fun reset() { lane = -1; laneStable = 0; want = 0; wantStable = 0 }

    /** LEFT / RIGHT toward a neighbouring lane with coins, or NONE. */
    fun suggest(f: Frame): Action {
        if (f.t - lastMove < 1200 || magnet(f)) { want = 0; wantStable = 0; return Action.NONE }
        val l = playerLane(f)
        if (l < 0) { reset(); return Action.NONE }
        if (l == lane) laneStable++ else { lane = l; laneStable = 1 }
        if (laneStable < 3) return Action.NONE

        val scale = f.w * f.h / (270f * 584f)
        val min = (10 * scale).toInt()
        val max = (400 * scale).toInt()   // more than this is a wooden crate or wall, not coins
        val c = coinsPerLane(f)
        var w = 0
        if (c[lane] < min) {
            var t = 0
            for (i in 1..2) if (c[i] > c[t]) t = i
            if (c[t] in min..max && abs(t - lane) == 1) w = t - lane
        }
        if (w != 0 && w == want) wantStable++ else { want = w; wantStable = if (w != 0) 1 else 0 }
        if (wantStable < 3) return Action.NONE
        lastMove = f.t
        want = 0; wantStable = 0
        return if (w < 0) Action.LEFT else Action.RIGHT
    }
}
