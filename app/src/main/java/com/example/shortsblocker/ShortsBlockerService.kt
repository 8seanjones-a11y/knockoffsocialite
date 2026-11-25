package com.example.shortsblocker

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * The accessibility service: the part that actually blocks things.
 *
 * Android calls onAccessibilityEvent() whenever something changes on screen in
 * YouTube (the config file limits us to YouTube). Each time, we look through
 * the views on screen ("nodes") for the things you chose to block:
 *
 *   - Shorts       -> we press Back.
 *   - Home feed    -> we tap the Subscriptions tab instead. (Pressing Back on
 *                     Home would just close YouTube, because Home is the
 *                     first screen.)
 */
class ShortsBlockerService : AccessibilityService() {

    companion object {
        const val TAG = "ShortsBlocker"   // filter Logcat with this tag
        const val YOUTUBE = "com.google.android.youtube"

        /**
         * View IDs that belong to the Shorts player. YouTube's internal name
         * for Shorts is "reel", so its player views have IDs like
         * "reel_recycler" or "reel_player_page_container". If YouTube renames
         * them, turn on debug mode, find the new ID in Logcat and add a word
         * from it here.
         */
        val SHORTS_ID_WORDS = listOf("reel", "shorts")

        /**
         * The labels of YouTube's bottom-bar tabs. "Shorts" is the same in
         * almost every language, but "Home" and "Subscriptions" are
         * translated. If your phone isn't in English, add your language's
         * words here (debug mode shows them as desc="...").
         */
        val SHORTS_TAB_LABELS = listOf("Shorts")
        val HOME_TAB_LABELS = listOf("Home")
        val SUBSCRIPTIONS_TAB_LABELS = listOf("Subscriptions")

        /**
         * A view only counts as "the Shorts player" if it covers at least this
         * much of YouTube's window. This stops the small row of Shorts on the
         * Home feed (which also uses "reel" IDs) from triggering a Back press.
         */
        const val MIN_WIDTH_SHARE = 0.7
        const val MIN_HEIGHT_SHARE = 0.6

        /**
         * The bottom bar is the bottom part of the window. Requiring a tab to
         * be down there stops us reacting to, for example, the "Home" tab on a
         * channel's page, which sits near the top.
         */
        const val BOTTOM_BAR_SHARE = 0.8

        /** Wait at least this long between two actions, so we never spam Back. */
        const val ACTION_COOLDOWN_MS = 1200L

        /** In debug mode, log the whole screen at most this often. */
        const val DUMP_INTERVAL_MS = 2000L

        /** Safety limits so a huge screen can't make us loop forever. */
        const val MAX_DEPTH = 60
        const val MAX_NODES_IN_DUMP = 400
    }

    private var lastActionAt = 0L
    private var lastDumpAt = 0L

    override fun onServiceConnected() {
        Log.i(TAG, "Service connected. Watching $YOUTUBE only.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != YOUTUBE) return

        val blocking = Prefs.isBlockingOn(this)
        val debug = Prefs.isDebugOn(this)
        if (!blocking && !debug) return  // nothing to do

        // The window YouTube is showing right now. It can be null for a moment
        // while screens change, so then we simply wait for the next event.
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != YOUTUBE) return  // e.g. the keyboard is on top

        if (debug) dumpScreen(root, event)

        val window = Rect()
        root.getBoundsInScreen(window)

        // 1. Shorts -> press Back.
        if (Prefs.isBlockShortsOn(this)) {
            val reason = findShorts(root, window, event)
            if (reason != null) {
                if (debug) Log.i(TAG, "Shorts detected: $reason")
                if (blocking) pressBack(reason)
                return
            }
        }

        // 2. Home feed -> tap the Subscriptions tab.
        if (Prefs.isBlockHomeOn(this)) {
            val homeTab = findNode(root) { isBottomTab(it, window, HOME_TAB_LABELS, mustBeSelected = true) }
            if (homeTab != null) {
                if (debug) Log.i(TAG, "Home feed detected (Home tab is selected)")
                if (blocking) openSubscriptions(root, window)
            }
        }
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
    private fun findShorts(root: AccessibilityNodeInfo, window: Rect, event: AccessibilityEvent): String? {
        // Check A: when a new screen opens, Android tells us its class name.
        // Shorts screens sometimes have "Reel" or "Shorts" in it.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString() ?: ""
            if (containsShortsWord(className)) return "screen class name \"$className\""
        }

        // Check B: a big view whose ID mentions "reel" or "shorts" = the Shorts player.
        findNode(root) { isBigShortsView(it, window) }?.let {
            return "full-screen view with id \"${shortId(it)}\""
        }

        // Check C: a tab labelled "Shorts" is *selected*. This catches the
        // bottom-bar tab, and also the Shorts tab on channel pages. (The tab is
        // always visible, so we only react when you're actually on it.)
        findNode(root) { isSelected(it) && hasLabel(it, SHORTS_TAB_LABELS) }?.let {
            return "selected tab \"${label(it)}\""
        }
        return null
    }

    private fun isBigShortsView(node: AccessibilityNodeInfo, window: Rect): Boolean {
        if (!containsShortsWord(shortId(node))) return false
        val b = bounds(node)
        return b.width() >= window.width() * MIN_WIDTH_SHARE &&
                b.height() >= window.height() * MIN_HEIGHT_SHARE
    }

    /** A tab in YouTube's bottom bar with one of these labels. */
    private fun isBottomTab(
        node: AccessibilityNodeInfo,
        window: Rect,
        labels: List<String>,
        mustBeSelected: Boolean,
    ): Boolean {
        if (!hasLabel(node, labels)) return false
        if (mustBeSelected && !isSelected(node)) return false
        return bounds(node).top >= window.top + window.height() * BOTTOM_BAR_SHARE
    }

    // ---------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------

    /** Returns true (and starts the cooldown) if we're allowed to act now. */
    private fun cooldownOver(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastActionAt < ACTION_COOLDOWN_MS) return false
        lastActionAt = now
        return true
    }

    private fun pressBack(reason: String) {
        if (!cooldownOver()) return
        performGlobalAction(GLOBAL_ACTION_BACK)
        Log.i(TAG, "Pressed Back ($reason)")
        Toast.makeText(this, "Shorts blocked", Toast.LENGTH_SHORT).show()
    }

    private fun openSubscriptions(root: AccessibilityNodeInfo, window: Rect) {
        val tab = findNode(root) { isBottomTab(it, window, SUBSCRIPTIONS_TAB_LABELS, mustBeSelected = false) }
        if (tab == null) {
            Log.w(TAG, "Home feed is blocked, but I can't find the Subscriptions tab. " +
                    "Check SUBSCRIPTIONS_TAB_LABELS with debug mode.")
            return
        }
        if (!cooldownOver()) return

        // The label is sometimes on a view inside the actual button, so walk up
        // to the nearest view that can be tapped.
        var target: AccessibilityNodeInfo? = tab
        var steps = 0
        while (target != null && !target.isClickable && steps < 4) {
            target = target.parent
            steps++
        }
        val tapped = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        Log.i(TAG, if (tapped) "Home feed blocked: tapped Subscriptions" else "Couldn't tap Subscriptions")
        if (tapped) Toast.makeText(this, "Home feed blocked", Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------

    /**
     * Looks at this view, then its children, then their children, and so on,
     * and returns the first visible view for which test(view) is true.
     */
    private fun findNode(
        node: AccessibilityNodeInfo,
        depth: Int = 0,
        test: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        if (node.isVisibleToUser && test(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNode(child, depth + 1, test)?.let { return it }
        }
        return null
    }

    private fun containsShortsWord(s: String) =
        SHORTS_ID_WORDS.any { s.contains(it, ignoreCase = true) }

    /** The view's description or text, e.g. "Subscriptions". */
    private fun label(node: AccessibilityNodeInfo) =
        (node.contentDescription ?: node.text)?.toString()?.trim() ?: ""

    /**
     * True if the description or text starts with one of the labels. We use
     * "starts with" because YouTube sometimes adds to the label, for example
     * "Subscriptions: New content is available".
     */
    private fun hasLabel(node: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val texts = listOfNotNull(node.contentDescription, node.text).map { it.toString().trim() }
        return texts.any { t -> labels.any { t.startsWith(it, ignoreCase = true) } }
    }

    /** The "selected" flag is sometimes on the tab's parent view, so check both. */
    private fun isSelected(node: AccessibilityNodeInfo) =
        node.isSelected || node.parent?.isSelected == true

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also { node.getBoundsInScreen(it) }

    /** "com.google.android.youtube:id/reel_recycler" -> "reel_recycler" */
    private fun shortId(node: AccessibilityNodeInfo) =
        node.viewIdResourceName?.substringAfter(":id/") ?: ""

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
            val b = bounds(node)
            val line = buildString {
                append("  ".repeat(depth.coerceAtMost(20)))
                append(node.className?.toString()?.substringAfterLast('.') ?: "?")
                if (id.isNotEmpty()) append(" id=").append(id)
                if (!text.isNullOrBlank()) append(" text=\"").append(text.take(60)).append('"')
                if (!desc.isNullOrBlank()) append(" desc=\"").append(desc.take(60)).append('"')
                if (node.isSelected) append(" SELECTED")
                if (node.isClickable) append(" clickable")
                if (!node.isVisibleToUser) append(" (hidden)")
                append(" at y=").append(b.top)
                append(" size=").append(b.width()).append('x').append(b.height())
            }
            Log.d(TAG, line)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNode(child, depth + 1, count)
        }
    }
}
