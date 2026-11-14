package uz.nurbek.surfbot

import kotlin.math.abs

enum class Screen { PLAYING, CONTINUE, RESULTS, MENU, BOX, UNKNOWN }

/**
 * Recognises game screens by the colour of a few fixed points.
 * Points and colours were measured on Samsung S25 screenshots (1080x2340),
 * stored as fractions so they also work on other screen sizes with the same layout.
 */
object ScreenReader {

    // Tap targets (fraction of screen width, height)
    val TAP_KEY = 0.50f to 0.54f       // "Continue?" -> blue key button
    val TAP_PLAY = 0.79f to 0.935f     // results -> green PLAY
    val TAP_MENU = 0.50f to 0.82f      // main menu -> "Tap to Play"
    val TAP_BOX = 0.50f to 0.50f       // mystery box -> "Tap to open"

    private fun at(f: Frame, xf: Float, yf: Float): Int {
        val cx = (xf * f.w).toInt().coerceIn(1, f.w - 2)
        val cy = (yf * f.h).toInt().coerceIn(1, f.h - 2)
        var r = 0; var g = 0; var b = 0
        for (dy in -1..1) for (dx in -1..1) {
            val c = f.px[(cy + dy) * f.w + cx + dx]
            r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255
        }
        return ((r / 9) shl 16) or ((g / 9) shl 8) or (b / 9)
    }

    private fun near(f: Frame, xf: Float, yf: Float, r: Int, g: Int, b: Int, tol: Int = 45): Boolean {
        val c = at(f, xf, yf)
        return abs(((c shr 16) and 255) - r) <= tol &&
            abs(((c shr 8) and 255) - g) <= tol &&
            abs((c and 255) - b) <= tol
    }

    fun read(f: Frame): Screen {
        // In a run: white pause icon with light-blue button around it, top-left.
        if (near(f, 0.07f, 0.077f, 255, 255, 255, 40) && near(f, 0.085f, 0.077f, 89, 171, 211, 50))
            return Screen.PLAYING
        // "Continue?": blue key button above the green ad button.
        if (near(f, 0.30f, 0.54f, 36, 111, 169) && near(f, 0.30f, 0.63f, 69, 157, 44))
            return Screen.CONTINUE
        val topBar = near(f, 0.50f, 0.035f, 133, 168, 190, 35)
        // Results: grey top bar + big green PLAY bottom-right.
        if (topBar && near(f, 0.70f, 0.93f, 125, 187, 104) && near(f, 0.87f, 0.93f, 125, 186, 106))
            return Screen.RESULTS
        if (topBar) return Screen.MENU
        // Mystery box: whole screen is blue rays.
        if (near(f, 0.5f, 0.15f, 72, 154, 240, 55) && near(f, 0.2f, 0.75f, 28, 105, 183, 55) &&
            near(f, 0.8f, 0.5f, 108, 196, 234, 55))
            return Screen.BOX
        return Screen.UNKNOWN
    }
}
