package uz.nurbek.surfbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Performs swipes like a finger, and listens for the hidden volume shortcut. */
class GestureService : AccessibilityService() {
    companion object {
        @Volatile var instance: GestureService? = null
    }

    private var lastVolDown = 0L

    /** Package of the app currently in front (to notice ads that leave the game). */
    @Volatile var foregroundPkg: String = ""
        private set

    override fun onServiceConnected() { instance = this }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        foregroundPkg = pkg
    }

    fun pressBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Double-press Volume Down = toggle the bot. Invisible on stream. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val bot = BotService.instance ?: return false
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN) {
            val now = SystemClock.uptimeMillis()
            if (now - lastVolDown < 450) {
                lastVolDown = 0
                bot.toggle()
                return true
            }
            lastVolDown = now
        }
        return false
    }

    fun perform(path: Path, durMs: Long, done: (() -> Unit)? = null): Boolean {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durMs))
            .build()
        return send(g, done)
    }

    /** Two quick taps (hoverboard). */
    fun doubleTap(p1: Path, p2: Path, d1: Long, gap: Long, d2: Long, done: (() -> Unit)? = null): Boolean {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p1, 0, d1))
            .addStroke(GestureDescription.StrokeDescription(p2, d1 + gap, d2))
            .build()
        return send(g, done)
    }

    private fun send(g: GestureDescription, done: (() -> Unit)?): Boolean {
        return dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { done?.invoke() }
            override fun onCancelled(d: GestureDescription?) { done?.invoke() }
        }, null)
    }
}
