package com.example.shortsblocker

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * Draws plain boxes on top of YouTube to hide things (the Shorts tab, Shorts
 * rows, the Home feed).
 *
 * An app can't remove anything from inside another app, but an accessibility
 * service may draw "accessibility overlay" windows above everything, without
 * any extra permission. The boxes also catch taps, so whatever is under them
 * can't be pressed.
 *
 * show() is called every time the screen changes with the full list of boxes
 * that should be visible right now. Boxes that already exist are moved, extra
 * ones are removed and missing ones are added.
 */
class OverlayManager(private val service: AccessibilityService) {

    /** One box: where it goes (screen coordinates) and an optional message. */
    data class Box(val rect: Rect, val message: String?)

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val views = mutableListOf<TextView>()
    private val shown = mutableListOf<Box>()

    val count get() = views.size

    fun show(boxes: List<Box>) {
        try {
            while (views.size > boxes.size) {
                windowManager.removeView(views.removeAt(views.size - 1))
                shown.removeAt(shown.size - 1)
            }
            boxes.forEachIndexed { i, box ->
                if (i < views.size) {
                    if (shown[i] != box) {
                        style(views[i], box)
                        windowManager.updateViewLayout(views[i], params(box.rect))
                        shown[i] = box
                    }
                } else {
                    val view = TextView(service)
                    style(view, box)
                    windowManager.addView(view, params(box.rect))
                    views.add(view)
                    shown.add(box)
                }
            }
        } catch (e: Exception) {
            // For example while the service is being switched off. Start over
            // next time rather than crash.
            Log.w(ShortsBlockerService.TAG, "Couldn't update overlays", e)
            removeAllQuietly()
        }
    }

    fun clear() {
        if (views.isNotEmpty()) show(emptyList())
    }

    private fun removeAllQuietly() {
        for (v in views) {
            try { windowManager.removeView(v) } catch (_: Exception) {}
        }
        views.clear()
        shown.clear()
    }

    private fun isDark() =
        (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    private fun style(view: TextView, box: Box) {
        val dark = isDark()
        // YouTube's own background colours, so the boxes blend in.
        view.setBackgroundColor(if (dark) Color.rgb(15, 15, 15) else Color.WHITE)
        view.setTextColor(if (dark) Color.rgb(170, 170, 170) else Color.rgb(96, 96, 96))
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        view.gravity = Gravity.CENTER
        val pad = (16 * service.resources.displayMetrics.density).toInt()
        view.setPadding(pad, 0, pad, 0)
        // Only show the message if the box is tall enough to fit it.
        val minHeightForText = 40 * service.resources.displayMetrics.density
        view.text = if (box.message != null && box.rect.height() >= minHeightForText) box.message else ""
        // Swallow taps so the thing underneath can't be pressed.
        view.isClickable = true
    }

    private fun params(rect: Rect) = WindowManager.LayoutParams(
        rect.width(),
        rect.height(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        // x/y are measured from the top-left corner of the screen, the same
        // coordinates getBoundsInScreen() gives us.
        gravity = Gravity.TOP or Gravity.START
        x = rect.left
        y = rect.top
        windowAnimations = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
}
