package com.example.shortsblocker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
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
 * Android calls onAccessibilityEvent() whenever something changes on screen.
 * Events from other apps are only used to notice that YouTube has gone to the
 * background (then we remove our boxes); nothing from them is read or kept.
 *
 * For YouTube, each time the screen changes we look through the views on
 * screen ("nodes") once, and then:
 *
 *   - Shorts tab and Shorts rows -> cover them with plain boxes (OverlayManager).
 *   - Home feed                  -> cover it with a "Home feed blocked" panel.
 *   - Shorts player              -> press Back (only on strong signals, never
 *                                   on Home, and never more than 3 times in
 *                                   10 seconds, so it can't fight you).
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

        /** Words in the description of a single Short in a feed ("..., play Short"). */
        val SHORTS_ITEM_WORDS = listOf("play short")

        /**
         * Labels of YouTube's bottom-bar tabs and the search button. If your
         * phone isn't in English, add your language's words here (debug mode
         * shows them as desc="...").
         */
        val SHORTS_LABELS = listOf("Shorts")
        val HOME_TAB_LABELS = listOf("Home")
        val OTHER_TAB_LABELS = listOf("Subscriptions", "You", "Create", "Library")
        val SEARCH_LABELS = listOf("Search")

        /**
         * The back arrow in the top bar. YouTube keeps the Home tab selected
         * on search results and channel pages you open from Home, but those
         * always have a back arrow; the real Home feed doesn't.
         */
        val BACK_LABELS = listOf("Navigate up", "Back")

        /** The YouTube logo in the top bar (only logged, to help debugging). */
        val LOGO_LABELS = listOf("YouTube")

        /** The back arrow and logo sit in the top part of the window. */
        const val TOP_BAR_STRICT_SHARE = 0.12

        /** A view counts as the Shorts *player* if it is at least this big. */
        const val MIN_WIDTH_SHARE = 0.7
        const val MIN_HEIGHT_SHARE = 0.6

        /** The bottom bar is in the bottom part of the window. */
        const val BOTTOM_BAR_SHARE = 0.8

        /** The top bar (logo, search) is in the top part of the window. */
        const val TOP_BAR_SHARE = 0.2

        /** Wait at least this long between two Back presses. */
        const val BACK_COOLDOWN_MS = 1200L

        /** Loop guard: this many Backs within the window pauses Back for a while. */
        const val LOOP_MAX_BACKS = 3
        const val LOOP_WINDOW_MS = 10_000L
        const val LOOP_PAUSE_MS = 30_000L

        /** Re-check the screen this long after the last change (scrolling stopped). */
        const val SETTLE_MS = 250L

        /** In debug mode, save the whole screen at most this often. */
        const val DUMP_INTERVAL_MS = 2000L

        /** Write "hiding Shorts" to the activity log at most this often. */
        const val COVER_LOG_INTERVAL_MS = 60_000L

        /** Safety limits so a huge screen can't make us loop forever. */
        const val MAX_DEPTH = 60
        const val MAX_NODES = 1500
        const val MAX_NODES_IN_DUMP = 800
        const val MAX_BOXES = 12
    }

    private lateinit var overlays: OverlayManager
    private val handler = Handler(Looper.getMainLooper())
    private val settle = Runnable { refresh(null) }

    private var lastBackAt = 0L
    private val recentBacks = ArrayDeque<Long>()
    private var backPausedUntil = 0L
    private var lastDumpAt = 0L
    private var homeWasCovered = false
    private var lastCoverLogAt = 0L

    override fun onServiceConnected() {
        overlays = OverlayManager(this)
        Log.i(TAG, "Service connected.")
        Prefs.addLog(this, "Service started")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::overlays.isInitialized) return
        val pkg = event.packageName?.toString()
        if (pkg == packageName) return  // our own boxes

        if (pkg != YOUTUBE) {
            // Another app (home screen, notification shade, ...) opened a
            // window. If YouTube is no longer in front, take our boxes away.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                rootInActiveWindow?.packageName?.toString() != YOUTUBE
            ) {
                hideAll()
            }
            return
        }

        refresh(event)
        // Events stop when scrolling stops, so check once more shortly after
        // the last one, to put the boxes exactly in place.
        handler.removeCallbacks(settle)
        handler.postDelayed(settle, SETTLE_MS)
    }

    override fun onInterrupt() = hideAll()

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacks(settle)
        hideAll()
        return super.onUnbind(intent)
    }

    private fun hideAll() {
        if (::overlays.isInitialized) overlays.clear()
        homeWasCovered = false
    }

    // ---------------------------------------------------------------------
    // The main check
    // ---------------------------------------------------------------------

    private fun refresh(event: AccessibilityEvent?) {
        val blocking = Prefs.isBlockingOn(this)
        val debug = Prefs.isDebugOn(this)

        // The window YouTube is showing. It can be null for a moment while
        // screens change; then we keep things as they are and wait.
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != YOUTUBE) {  // e.g. another app is in front
            hideAll()
            return
        }
        if (!blocking && !debug) {
            hideAll()
            return
        }

        val window = Rect().also { root.getBoundsInScreen(it) }
        val screen = scan(root, window)
        val blockShorts = Prefs.isBlockShortsOn(this)
        val blockHome = Prefs.isBlockHomeOn(this)

        // 1. Shorts player is open -> press Back.
        val playerReason = if (blockShorts) shortsPlayerReason(screen) else null

        // 2. Everything to cover.
        val boxes = if (playerReason == null) boxesFor(screen, window, blockShorts, blockHome) else emptyList()

        if (debug) dumpScreen(root, event, window, screen, playerReason, boxes)
        if (!blocking) {
            hideAll()
            return
        }

        if (playerReason != null) {
            overlays.clear()
            pressBack(playerReason)
            return
        }

        overlays.show(boxes)
        logCoverChanges(screen, blockHome, boxes)
    }

    /** What we found on screen in one pass over the views. */
    private class Screen {
        var homeSelected = false
        var hasBackButton = false
        var hasSearchField = false
        var hasLogo = false
        var shortsTabSelected = false

        /** The actual Home feed, not a search result or page opened from Home. */
        val isHomeFeed get() = homeSelected && !hasBackButton && !hasSearchField
        var shortsTab: Rect? = null
        var barTop = Int.MAX_VALUE          // top of the bottom bar
        var topBarBottom = Int.MIN_VALUE    // bottom of the search button
        var biggestScrollable: Rect? = null
        var shortsPlayerId: String? = null
        val shortsItems = mutableListOf<Rect>()
    }

    private fun scan(root: AccessibilityNodeInfo, window: Rect): Screen {
        val s = Screen()
        val count = intArrayOf(0)
        scanNode(root, 0, window, s, count)
        if (s.barTop == Int.MAX_VALUE) s.barTop = window.bottom
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
        val tab = bottomTab(node, window)
        if (tab != null) {
            val (tabNode, tabRect) = tab
            s.barTop = minOf(s.barTop, tabRect.top)
            val selected = node.isSelected || tabNode.isSelected
            when {
                hasLabel(node, HOME_TAB_LABELS) -> if (selected) s.homeSelected = true
                hasLabel(node, SHORTS_LABELS) -> {
                    s.shortsTab = tabRect
                    if (selected) s.shortsTabSelected = true
                }
            }
            return
        }

        // Top bar: back arrow, search box or logo tell us which page this is.
        if (b.bottom <= window.top + window.height() * TOP_BAR_STRICT_SHARE) {
            if (labelIs(node, BACK_LABELS)) s.hasBackButton = true
            if (node.className?.toString()?.contains("EditText") == true) s.hasSearchField = true
            if (hasLabel(node, LOGO_LABELS)) s.hasLogo = true
        }

        // Search button: the Home cover starts below it.
        if (b.bottom <= window.top + window.height() * TOP_BAR_SHARE && hasLabel(node, SEARCH_LABELS)) {
            s.topBarBottom = maxOf(s.topBarBottom, b.bottom)
        }

        // The main scrolling list (the feed).
        if (node.isScrollable && b.width() * b.height() > (s.biggestScrollable?.let { it.width() * it.height() } ?: 0)) {
            s.biggestScrollable = b
        }

        // Shorts by view ID: big = the player, small = a row/item in a feed.
        if (containsAny(id, SHORTS_ID_WORDS)) {
            if (b.width() >= window.width() * MIN_WIDTH_SHARE && b.height() >= window.height() * MIN_HEIGHT_SHARE) {
                s.shortsPlayerId = id
            } else if (b.height() <= window.height() * 0.75) {
                s.shortsItems += b
            }
            return
        }

        // A single Short in a feed ("..., 1.2M views - play Short").
        val desc = node.contentDescription?.toString() ?: ""
        if (containsAny(desc, SHORTS_ITEM_WORDS)) {
            s.shortsItems += clickableAncestor(node)?.let { bounds(it) } ?: b
            return
        }

        // A "Shorts" heading (the title of a Shorts row) or a "Shorts" tab/chip.
        if (labelIs(node, SHORTS_LABELS) && b.height() < window.height() * 0.15) {
            s.shortsItems += shortsHeadingCover(node, b, window)
        }
    }

    /**
     * If this view is (part of) a bottom-bar tab, returns the tab's tappable
     * view and its bounds. A tab sits at the very bottom, is narrow and short.
     */
    private fun bottomTab(node: AccessibilityNodeInfo, window: Rect): Pair<AccessibilityNodeInfo, Rect>? {
        if (!hasLabel(node, HOME_TAB_LABELS + SHORTS_LABELS + OTHER_TAB_LABELS)) return null
        val tabNode = clickableAncestor(node) ?: node
        val r = bounds(tabNode)
        val inBar = r.top >= window.top + window.height() * BOTTOM_BAR_SHARE &&
                r.bottom >= window.top + window.height() * 0.9
        val small = r.width() < window.width() * 0.4 && r.height() < window.height() * 0.15
        return if (inBar && small) tabNode to r else null
    }

    /**
     * What to cover for a "Shorts" label outside the bottom bar:
     * - if it's a small tappable thing (a tab on a channel page, a chip), just that;
     * - otherwise it's the heading of a Shorts row: walk up to the row itself
     *   (full width, clearly taller than the heading) and cover all of it.
     */
    private fun shortsHeadingCover(node: AccessibilityNodeInfo, b: Rect, window: Rect): Rect {
        val clickable = clickableAncestor(node, maxSteps = 2)
        if (clickable != null) {
            val cb = bounds(clickable)
            if (cb.width() < window.width() * 0.5) return cb
        }
        var p = node.parent
        var steps = 0
        while (p != null && steps < 8) {
            val pb = bounds(p)
            if (pb.width() >= window.width() * 0.9 &&
                pb.height() >= b.height() * 3 &&
                pb.height() <= window.height() * 0.75
            ) return pb
            p = p.parent
            steps++
        }
        return b
    }

    private fun shortsPlayerReason(s: Screen): String? {
        // Never press Back on the Home feed: it would scroll you to the top or
        // close YouTube. Shorts rows there are covered instead.
        if (s.isHomeFeed) return null
        s.shortsPlayerId?.let { return "full-screen Shorts view (id \"$it\")" }
        // Pages opened from Home (search results, channels) keep Home selected,
        // so only trust the Shorts tab when Home isn't selected.
        if (s.shortsTabSelected && !s.homeSelected) return "Shorts tab is selected"
        return null
    }

    private fun boxesFor(s: Screen, window: Rect, blockShorts: Boolean, blockHome: Boolean): List<OverlayManager.Box> {
        val boxes = mutableListOf<OverlayManager.Box>()
        // The area between the top bar and the bottom bar.
        val contentTop = maxOf(s.biggestScrollable?.top ?: window.top, s.topBarBottom, window.top)
        val contentBottom = minOf(s.barTop, window.bottom)

        val coverHome = blockHome && s.isHomeFeed
        if (coverHome && contentBottom > contentTop) {
            boxes += OverlayManager.Box(
                Rect(window.left, contentTop, window.right, contentBottom),
                getString(R.string.overlay_home),
            )
        }

        if (blockShorts) {
            s.shortsTab?.let { boxes += OverlayManager.Box(Rect(it), null) }
            if (!coverHome) {
                // Biggest first, and skip anything already inside a box.
                val rows = s.shortsItems
                    .map { Rect(it).apply { intersect(window.left, contentTop, window.right, contentBottom) } }
                    .filter { it.width() > 0 && it.height() > 0 && it.top >= contentTop && it.bottom <= contentBottom }
                    .sortedByDescending { it.width() * it.height() }
                val kept = mutableListOf<Rect>()
                for (r in rows) {
                    if (kept.none { it.contains(r) }) kept += r
                }
                kept.forEach { boxes += OverlayManager.Box(it, getString(R.string.overlay_shorts)) }
            }
        }
        return boxes.take(MAX_BOXES)
    }

    // ---------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------

    private fun pressBack(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now < backPausedUntil) return
        if (now - lastBackAt < BACK_COOLDOWN_MS) return

        // Loop guard: if we keep pressing Back, something is being mistaken
        // for Shorts. Stop for a while instead of fighting the user.
        while (recentBacks.isNotEmpty() && now - recentBacks.first() > LOOP_WINDOW_MS) recentBacks.removeFirst()
        if (recentBacks.size >= LOOP_MAX_BACKS) {
            recentBacks.clear()
            backPausedUntil = now + LOOP_PAUSE_MS
            Prefs.addLog(this, "Pressed Back $LOOP_MAX_BACKS times in 10 s, so pausing Back for 30 s (reason was: $reason)")
            Log.w(TAG, "Loop guard: pausing Back ($reason)")
            return
        }

        recentBacks.addLast(now)
        lastBackAt = now
        performGlobalAction(GLOBAL_ACTION_BACK)
        Log.i(TAG, "Pressed Back ($reason)")
        Prefs.addLog(this, "Pressed Back: $reason")
        Toast.makeText(this, "Shorts blocked", Toast.LENGTH_SHORT).show()
    }

    private fun logCoverChanges(s: Screen, blockHome: Boolean, boxes: List<OverlayManager.Box>) {
        val homeCovered = blockHome && s.isHomeFeed
        if (homeCovered && !homeWasCovered) Prefs.addLog(this, "Covered the Home feed")
        homeWasCovered = homeCovered

        val shortsBoxes = boxes.count { it.message == getString(R.string.overlay_shorts) }
        val now = SystemClock.elapsedRealtime()
        if (shortsBoxes > 0 && now - lastCoverLogAt > COVER_LOG_INTERVAL_MS) {
            lastCoverLogAt = now
            Prefs.addLog(this, "Covered $shortsBoxes Shorts row(s)/item(s)")
        }
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
        playerReason: String?,
        boxes: List<OverlayManager.Box>,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDumpAt < DUMP_INTERVAL_MS) return
        lastDumpAt = now

        val out = StringBuilder()
        out.append("Screen info saved ").append(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()))
            .append(" (event: ").append(event?.let { AccessibilityEvent.eventTypeToString(it.eventType) } ?: "settle")
            .append(")\n")
        out.append("window=").append(window.toShortString())
            .append(" homeSelected=").append(s.homeSelected)
            .append(" back=").append(s.hasBackButton)
            .append(" searchField=").append(s.hasSearchField)
            .append(" logo=").append(s.hasLogo)
            .append(" homeFeed=").append(s.isHomeFeed)
            .append(" shortsTabSelected=").append(s.shortsTabSelected)
            .append(" shortsTab=").append(s.shortsTab?.toShortString())
            .append(" barTop=").append(s.barTop)
            .append(" topBarBottom=").append(s.topBarBottom)
            .append(" feed=").append(s.biggestScrollable?.toShortString())
            .append(" playerId=").append(s.shortsPlayerId)
            .append('\n')
        out.append("Back reason: ").append(playerReason ?: "none").append('\n')
        out.append("Boxes: ").append(boxes.joinToString { it.rect.toShortString() }.ifEmpty { "none" }).append("\n\n")

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
