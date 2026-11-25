package com.example.shortsblocker

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * The accessibility service: the part that actually blocks Shorts.
 *
 * Android calls onAccessibilityEvent() whenever something changes on screen in
 * YouTube (the config file limits us to YouTube). Each time, we look through
 * the views on screen ("nodes") for signs of Shorts. If we find any, we press
 * Back.
 */
class ShortsBlockerService : AccessibilityService() {

    companion object {
        const val TAG = "ShortsBlocker"   // filter Logcat with this tag
        const val YOUTUBE = "com.google.android.youtube"

        /**
         * Check 1: view IDs that belong to the Shorts player. YouTube's
         * internal name for Shorts is "reel", so its player views have IDs
         * like "reel_recycler" or "reel_player_page_container". If YouTube
         * renames them, turn on debug mode, find the new ID in Logcat and add
         * a word from it here.
         */
        val SHORTS_ID_WORDS = listOf("reel", "shorts")

        /** Check 2: the label of the Shorts tab in the bottom bar. */
        const val SHORTS_TAB_LABEL = "Shorts"

        /**
         * A view only counts as "the Shorts player" if it covers at least this
         * much of YouTube's window. This stops the small row of Shorts on the
         * Home feed (which also uses "reel" IDs) from triggering a Back press.
         */
        const val MIN_WIDTH_SHARE = 0.7
        const val MIN_HEIGHT_SHARE = 0.6

        /** Wait at least this long between two Back presses, so we never spam Back. */
        const val BACK_COOLDOWN_MS = 1200L

        /** In debug mode, log the whole screen at most this often. */
        const val DUMP_INTERVAL_MS = 2000L

        /** Safety limits so a huge screen can't make us loop forever. */
        const val MAX_DEPTH = 60
        const val MAX_NODES_IN_DUMP = 400
    }

    private var lastBackAt = 0L
    private var lastDumpAt = 0L

    override fun onServiceConnected() {
        Log.i(TAG, "Service connected. Watching $YOUTUBE only.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != YOUTUBE) return

        val blocking = Prefs.isBlockingOn(this)
        val debug = Prefs.isDebugOn(this)
        if (!blocking && !debug) return  // both switches off: nothing to do

        // The window YouTube is showing right now. It can be null for a moment
        // while screens change, so then we simply wait for the next event.
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != YOUTUBE) return  // e.g. the keyboard is on top

        if (debug) dumpScreen(root, event)

        val reason = findShorts(root, event) ?: return

        if (debug) Log.i(TAG, "Shorts detected: $reason")
        if (blocking) pressBack(reason)
    }

    override fun onInterrupt() {
        // Required by Android; we have nothing to stop.
    }

    // ---------------------------------------------------------------------
    // Detection
    // ---------------------------------------------------------------------

    /**
     * Returns a short description of why this looks like Shorts,
     * or null if it doesn't.
     */
    private fun findShorts(root: AccessibilityNodeInfo, event: AccessibilityEvent): String? {
        // Check 3: when a new screen opens, Android tells us its class name.
        // Shorts screens sometimes have "Reel" or "Shorts" in it.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString() ?: ""
            if (containsShortsWord(className)) return "screen class name \"$className\""
        }

        // Checks 1 and 2 look at every view on screen.
        val window = Rect()
        root.getBoundsInScreen(window)
        return checkTree(root, window, depth = 0)
    }

    /** Looks at this view, then its children, then their children, and so on. */
    private fun checkTree(node: AccessibilityNodeInfo, window: Rect, depth: Int): String? {
        if (depth > MAX_DEPTH) return null

        checkNode(node, window)?.let { return it }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            checkTree(child, window, depth + 1)?.let { return it }
        }
        return null
    }

    private fun checkNode(node: AccessibilityNodeInfo, window: Rect): String? {
        if (!node.isVisibleToUser) return null

        // Check 1: a big view whose ID mentions "reel" or "shorts" = the Shorts player.
        val id = shortId(node)
        if (containsShortsWord(id)) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val bigEnough = bounds.width() >= window.width() * MIN_WIDTH_SHARE &&
                    bounds.height() >= window.height() * MIN_HEIGHT_SHARE
            if (bigEnough) return "full-screen view with id \"$id\""
        }

        // Check 2: the Shorts tab in the bottom bar is *selected*. (It's always
        // visible, so we only react when you're actually on it.) The selected
        // flag is sometimes on the tab's parent view, so we check that too.
        if (isShortsLabel(node.contentDescription) || isShortsLabel(node.text)) {
            if (node.isSelected || node.parent?.isSelected == true) {
                return "selected tab \"$SHORTS_TAB_LABEL\""
            }
        }
        return null
    }

    private fun containsShortsWord(s: String) =
        SHORTS_ID_WORDS.any { s.contains(it, ignoreCase = true) }

    private fun isShortsLabel(s: CharSequence?) =
        s != null && s.toString().trim().equals(SHORTS_TAB_LABEL, ignoreCase = true)

    /** "com.google.android.youtube:id/reel_recycler" -> "reel_recycler" */
    private fun shortId(node: AccessibilityNodeInfo) =
        node.viewIdResourceName?.substringAfter(":id/") ?: ""

    // ---------------------------------------------------------------------
    // Action
    // ---------------------------------------------------------------------

    private fun pressBack(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackAt < BACK_COOLDOWN_MS) return  // pressed it very recently
        lastBackAt = now

        performGlobalAction(GLOBAL_ACTION_BACK)
        Log.i(TAG, "Pressed Back ($reason)")
        Toast.makeText(this, "Shorts blocked", Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------------
    // Debug mode: write what's on screen to Logcat
    // ---------------------------------------------------------------------

    private fun dumpScreen(root: AccessibilityNodeInfo, event: AccessibilityEvent) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDumpAt < DUMP_INTERVAL_MS) return
        lastDumpAt = now

        Log.d(TAG, "===== Screen dump (event: ${AccessibilityEvent.eventTypeToString(event.eventType)}, " +
                "class: ${event.className}) =====")
        val count = intArrayOf(0)
        dumpNode(root, 0, count)
        Log.d(TAG, "===== End of dump (${count[0]} views) =====")
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int, count: IntArray) {
        if (depth > MAX_DEPTH || count[0] >= MAX_NODES_IN_DUMP) return
        count[0]++

        // Only log views that have something useful: an ID, text or description.
        val id = shortId(node)
        val text = node.text?.toString()?.replace('\n', ' ')
        val desc = node.contentDescription?.toString()?.replace('\n', ' ')
        if (id.isNotEmpty() || !text.isNullOrBlank() || !desc.isNullOrBlank()) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val line = buildString {
                append("  ".repeat(depth.coerceAtMost(20)))
                append(node.className?.toString()?.substringAfterLast('.') ?: "?")
                if (id.isNotEmpty()) append(" id=").append(id)
                if (!text.isNullOrBlank()) append(" text=\"").append(text.take(60)).append('"')
                if (!desc.isNullOrBlank()) append(" desc=\"").append(desc.take(60)).append('"')
                if (node.isSelected) append(" SELECTED")
                if (!node.isVisibleToUser) append(" (hidden)")
                append(" size=").append(bounds.width()).append('x').append(bounds.height())
            }
            Log.d(TAG, line)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNode(child, depth + 1, count)
        }
    }
}
