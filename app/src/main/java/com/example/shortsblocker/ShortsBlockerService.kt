package com.example.shortsblocker

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The accessibility service: the part that actually blocks things.
 *
 * Android calls onAccessibilityEvent() whenever something changes on screen in
 * YouTube (the config file limits us to YouTube). Each time, we look through
 * the views on screen ("nodes") once, and then:
 *
 *   - Shorts player or Shorts tab -> press Back.
 *   - Home feed (if ticked)       -> tap the Subscriptions tab.
 *
 * We never draw anything on top of YouTube, so nothing can get stuck on
 * screen. Both actions share a loop guard: if we act 3 times in 10 seconds,
 * we pause for 30 seconds instead of fighting you.
 */
class ShortsBlockerService : AccessibilityService() {

    companion object {
        const val TAG = "ShortsBlocker"   // filter Logcat with this tag
        const val YOUTUBE = "com.google.android.youtube"

        /**
         * Words in the view IDs of Shorts things. YouTube's internal name for
         * Shorts is "reel", so its views have IDs like "reel_recycler" or
         * "reel_player_page_container".
         */
        val SHORTS_ID_WORDS = listOf("reel", "shorts")

        /**
         * Words in the view IDs of the normal video player. While a big one
         * of these is on screen you're watching a video, so we never switch
         * away from Home then (the Home tab stays "selected" underneath the
         * video).
         */
        val VIDEO_PLAYER_ID_WORDS = listOf("watch", "player")

        /** ...except the silent previews that play inside the Home feed. */
        val FEED_PREVIEW_ID_WORDS = listOf("inline", "thumbnail")

        /**
         * Labels of YouTube's bottom-bar tabs. If your phone isn't in
         * English, add your language's words here (debug mode shows them as
         * desc="...").
         */
        val SHORTS_LABELS = listOf("Shorts")
        val HOME_TAB_LABELS = listOf("Home")
        val SUBSCRIPTIONS_TAB_LABELS = listOf("Subscriptions")
        val OTHER_TAB_LABELS = listOf("You", "Create", "Library")

        /**
         * The back arrow in the top bar. YouTube keeps the Home tab selected
         * on search results and channel pages you open from Home, but those
         * always have a back arrow; the real Home feed doesn't.
         */
        val BACK_LABELS = listOf("Navigate up", "Back")

        /** The back arrow and search box sit in the top part of the window. */
        const val TOP_BAR_SHARE = 0.12

        /** A view counts as the Shorts *player* if it is at least this big. */
        const val MIN_WIDTH_SHARE = 0.7
        const val MIN_HEIGHT_SHARE = 0.6

        /**
         * On the Home feed we're extra careful: only a Shorts view this tall
         * counts (the Shorts row in the feed is much smaller).
         */
        const val FULL_PLAYER_HEIGHT_SHARE = 0.85

        /** A normal video player is at least this wide and this tall. */
        const val VIDEO_MIN_WIDTH_SHARE = 0.9
        const val VIDEO_MIN_HEIGHT_SHARE = 0.2

        /** The bottom bar is in the bottom part of the window. */
        const val BOTTOM_BAR_SHARE = 0.8

        /** Wait at least this long between two actions. */
        const val ACTION_COOLDOWN_MS = 1200L

        /** Loop guard: this many actions within the window pauses us for a while. */
        const val LOOP_MAX_ACTIONS = 3
        const val LOOP_WINDOW_MS = 10_000L
        const val LOOP_PAUSE_MS = 30_000L

        /**
         * Switching away from Home waits until the screen has been still for
         * this long, so we don't react half-way through YouTube opening a
         * video or a search.
         */
        const val SETTLE_MS = 300L

        /** In debug mode, save the whole screen at most this often. */
        const val DUMP_INTERVAL_MS = 2000L

        /** Safety limits so a huge screen can't make us loop forever. */
        const val MAX_DEPTH = 60
        const val MAX_NODES = 1500
        const val MAX_NODES_IN_DUMP = 800
    }

    private val handler = Handler(Looper.getMainLooper())
    private val settle = Runnable { check(event = null, settled = true) }

    private var lastActionAt = 0L
    private val recentActions = ArrayDeque<Long>()
    private var pausedUntil = 0L
    private var lastDumpAt = 0L

    override fun onServiceConnected() {
        Log.i(TAG, "Service connected. Watching $YOUTUBE only.")
        Prefs.addLog(this, "Service started")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != YOUTUBE) return

        // Check straight away (so Shorts are closed quickly), and once more
        // when the screen has stopped changing (for the Home feed).
        check(event, settled = false)
        handler.removeCallbacks(settle)
        handler.postDelayed(settle, SETTLE_MS)
    }

    override fun onInterrupt() {
        handler.removeCallbacks(settle)
    }

    // ---------------------------------------------------------------------
    // The main check
    // ---------------------------------------------------------------------

    private fun check(event: AccessibilityEvent?, settled: Boolean) {
        val blocking = Prefs.isBlockingOn(this)
        val debug = Prefs.isDebugOn(this)
        if (!blocking && !debug) return

        // The window YouTube is showing. It can be null for a moment while
        // screens change; then we wait for the next event.
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != YOUTUBE) return  // e.g. the keyboard is on top

        val window = Rect().also { root.getBoundsInScreen(it) }
        val screen = scan(root, window)

        // 1. Shorts -> press Back.
        val shortsReason = if (Prefs.isBlockShortsOn(this)) shortsReason(screen) else null

        // 2. The real Home feed -> tap Subscriptions. Only once the screen is still.
        val homeReason = if (shortsReason == null && settled && Prefs.isBlockHomeOn(this)) homeReason(screen) else null

        if (debug) dumpScreen(root, event, window, screen, shortsReason, homeReason)
        if (!blocking) return

        if (shortsReason != null) {
            if (canAct(shortsReason)) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                done("Pressed Back: $shortsReason", "Shorts blocked")
            }
        } else if (homeReason != null) {
            val tab = screen.subscriptionsTab
            if (tab == null) {
                Log.w(TAG, "Home feed is blocked, but I can't find the Subscriptions tab")
            } else if (canAct(homeReason)) {
                if (tab.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    done("Switched to Subscriptions: $homeReason", "Home feed blocked")
                }
            }
        }
    }

    /** What we found on screen in one pass over the views. */
    private class Screen {
        var homeSelected = false
        var shortsTabSelected = false
        var hasBackButton = false
        var hasSearchField = false
        var subscriptionsTab: AccessibilityNodeInfo? = null
        var shortsPlayerId: String? = null
        var shortsPlayerFullHeight = false
        var videoPlayerId: String? = null

        /** The actual Home feed: not a search, a page opened from Home, or a video. */
        val isHomeFeed get() = homeSelected && !hasBackButton && !hasSearchField && videoPlayerId == null
    }

    private fun scan(root: AccessibilityNodeInfo, window: Rect): Screen {
        val s = Screen()
        scanNode(root, 0, window, s, intArrayOf(0))
        return s
    }

    private fun scanNode(node: AccessibilityNodeInfo, depth: Int, window: Rect, s: Screen, count: IntArray) {
        if (depth > MAX_DEPTH || count[0]++ > MAX_NODES) return
        if (node.isVisibleToUser) inspect(node, window, s)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            scanNode(child, depth + 1, window, s, count)
        }
    }

    private fun inspect(node: AccessibilityNodeInfo, window: Rect, s: Screen) {
        val b = bounds(node)
        if (b.isEmpty) return
        val id = shortId(node)

        // Bottom-bar tabs.
        val tabNode = bottomTab(node, window)
        if (tabNode != null) {
            val selected = node.isSelected || tabNode.isSelected
            when {
                hasLabel(node, HOME_TAB_LABELS) -> if (selected) s.homeSelected = true
                hasLabel(node, SHORTS_LABELS) -> if (selected) s.shortsTabSelected = true
                hasLabel(node, SUBSCRIPTIONS_TAB_LABELS) -> s.subscriptionsTab = tabNode
            }
            return
        }

        // Top bar: a back arrow or search box means this isn't the Home feed.
        if (b.bottom <= window.top + window.height() * TOP_BAR_SHARE) {
            if (labelIs(node, BACK_LABELS)) s.hasBackButton = true
            if (node.className?.toString()?.contains("EditText") == true) s.hasSearchField = true
        }

        val wide = b.width() >= window.width() * MIN_WIDTH_SHARE
        if (containsAny(id, SHORTS_ID_WORDS)) {
            // A big view whose ID mentions "reel"/"shorts" is the Shorts player.
            // (Small ones are the Shorts rows in feeds, which we leave alone.)
            if (wide && b.height() >= window.height() * MIN_HEIGHT_SHARE) {
                s.shortsPlayerId = id
                if (b.height() >= window.height() * FULL_PLAYER_HEIGHT_SHARE) s.shortsPlayerFullHeight = true
            }
        } else if (containsAny(id, VIDEO_PLAYER_ID_WORDS) && !containsAny(id, FEED_PREVIEW_ID_WORDS) &&
            b.width() >= window.width() * VIDEO_MIN_WIDTH_SHARE &&
            b.height() >= window.height() * VIDEO_MIN_HEIGHT_SHARE
        ) {
            // A big normal video player: you're watching something.
            s.videoPlayerId = id
        }
    }

    /**
     * If this view is (part of) a bottom-bar tab, returns the tab's tappable
     * view. A tab sits at the very bottom, and is narrow and short.
     */
    private fun bottomTab(node: AccessibilityNodeInfo, window: Rect): AccessibilityNodeInfo? {
        if (!hasLabel(node, HOME_TAB_LABELS + SHORTS_LABELS + SUBSCRIPTIONS_TAB_LABELS + OTHER_TAB_LABELS)) return null
        val tabNode = clickableAncestor(node) ?: node
        val r = bounds(tabNode)
        val inBar = r.top >= window.top + window.height() * BOTTOM_BAR_SHARE &&
                r.bottom >= window.top + window.height() * 0.9
        val small = r.width() < window.width() * 0.4 && r.height() < window.height() * 0.15
        return if (inBar && small) tabNode else null
    }

    private fun shortsReason(s: Screen): String? {
        // On the Home feed, only a Shorts player that fills the screen counts
        // (a Short you tapped in a Shorts row).
        val id = s.shortsPlayerId
        if (id != null && (!s.isHomeFeed || s.shortsPlayerFullHeight)) {
            return "full-screen Shorts view (id \"$id\")"
        }
        // Pages opened from Home keep Home selected, so only trust the Shorts
        // tab when Home isn't selected.
        if (s.shortsTabSelected && !s.homeSelected) return "Shorts tab is selected"
        return null
    }

    private fun homeReason(s: Screen): String? =
        if (s.isHomeFeed) "Home feed was showing" else null

    // ---------------------------------------------------------------------
    // Cooldown and loop guard
    // ---------------------------------------------------------------------

    /** True if we may act now (not too soon after the last action, not looping). */
    private fun canAct(reason: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now < pausedUntil) return false
        if (now - lastActionAt < ACTION_COOLDOWN_MS) return false

        // Loop guard: if we keep acting, something is being mistaken for
        // Shorts or Home. Stop for a while instead of fighting the user.
        while (recentActions.isNotEmpty() && now - recentActions.first() > LOOP_WINDOW_MS) recentActions.removeFirst()
        if (recentActions.size >= LOOP_MAX_ACTIONS) {
            recentActions.clear()
            pausedUntil = now + LOOP_PAUSE_MS
            Prefs.addLog(this, "Acted $LOOP_MAX_ACTIONS times in 10 s, so pausing for 30 s (reason was: $reason)")
            Log.w(TAG, "Loop guard: pausing ($reason)")
            return false
        }
        recentActions.addLast(now)
        lastActionAt = now
        return true
    }

    private fun done(logLine: String, toast: String) {
        Log.i(TAG, logLine)
        Prefs.addLog(this, logLine)
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------

    /** This view or the nearest parent that can be tapped. */
    private fun clickableAncestor(node: AccessibilityNodeInfo, maxSteps: Int = 4): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var steps = 0
        while (n != null && steps <= maxSteps) {
            if (n.isClickable) return n
            n = n.parent
            steps++
        }
        return null
    }

    private fun containsAny(s: String, words: List<String>) =
        words.any { s.contains(it, ignoreCase = true) }

    private fun texts(node: AccessibilityNodeInfo) =
        listOfNotNull(node.contentDescription, node.text).map { it.toString().trim() }

    /**
     * True if the description or text starts with one of the labels. We use
     * "starts with" because YouTube sometimes adds to the label, for example
     * "Subscriptions: New content is available".
     */
    private fun hasLabel(node: AccessibilityNodeInfo, labels: List<String>) =
        texts(node).any { t -> labels.any { t.startsWith(it, ignoreCase = true) } }

    /** True if the description or text is exactly one of the labels. */
    private fun labelIs(node: AccessibilityNodeInfo, labels: List<String>) =
        texts(node).any { t -> labels.any { t.equals(it, ignoreCase = true) } }

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also { node.getBoundsInScreen(it) }

    /** "com.google.android.youtube:id/reel_recycler" -> "reel_recycler" */
    private fun shortId(node: AccessibilityNodeInfo) =
        node.viewIdResourceName?.substringAfter(":id/") ?: ""

    // ---------------------------------------------------------------------
    // Debug mode: describe the screen (Logcat + a file you can share)
    // ---------------------------------------------------------------------

    private fun dumpScreen(
        root: AccessibilityNodeInfo,
        event: AccessibilityEvent?,
        window: Rect,
        s: Screen,
        shortsReason: String?,
        homeReason: String?,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDumpAt < DUMP_INTERVAL_MS) return
        lastDumpAt = now

        val out = StringBuilder()
        out.append("Screen info saved ").append(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()))
            .append(" (event: ").append(event?.let { AccessibilityEvent.eventTypeToString(it.eventType) } ?: "settled")
            .append(")\n")
        out.append("window=").append(window.toShortString())
            .append(" homeSelected=").append(s.homeSelected)
            .append(" back=").append(s.hasBackButton)
            .append(" searchField=").append(s.hasSearchField)
            .append(" videoPlayer=").append(s.videoPlayerId)
            .append(" homeFeed=").append(s.isHomeFeed)
            .append(" shortsTabSelected=").append(s.shortsTabSelected)
            .append(" shortsPlayer=").append(s.shortsPlayerId)
            .append(" subscriptionsTab=").append(s.subscriptionsTab != null)
            .append('\n')
        out.append("Shorts: ").append(shortsReason ?: "none")
            .append(" | Home: ").append(homeReason ?: "none").append("\n\n")

        val count = intArrayOf(0)
        dumpNode(root, 0, count, out)
        out.append("(").append(count[0]).append(" views)\n")

        val text = out.toString()
        text.lines().forEach { Log.d(TAG, it) }
        Prefs.saveScreenInfo(this, text)
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int, count: IntArray, out: StringBuilder) {
        if (depth > MAX_DEPTH || count[0] >= MAX_NODES_IN_DUMP) return
        count[0]++

        // Only list views that have something useful: an ID, text or description,
        // or that can be tapped/scrolled.
        val id = shortId(node)
        val text = node.text?.toString()?.replace('\n', ' ')
        val desc = node.contentDescription?.toString()?.replace('\n', ' ')
        if (id.isNotEmpty() || !text.isNullOrBlank() || !desc.isNullOrBlank() || node.isClickable || node.isScrollable) {
            out.append("  ".repeat(depth.coerceAtMost(20)))
            out.append(node.className?.toString()?.substringAfterLast('.') ?: "?")
            if (id.isNotEmpty()) out.append(" id=").append(id)
            if (!text.isNullOrBlank()) out.append(" text=\"").append(text.take(60)).append('"')
            if (!desc.isNullOrBlank()) out.append(" desc=\"").append(desc.take(80)).append('"')
            if (node.isSelected) out.append(" SELECTED")
            if (node.isClickable) out.append(" clickable")
            if (node.isScrollable) out.append(" scrollable")
            if (!node.isVisibleToUser) out.append(" (hidden)")
            out.append(' ').append(bounds(node).toShortString())
            out.append('\n')
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNode(child, depth + 1, count, out)
        }
    }
}
