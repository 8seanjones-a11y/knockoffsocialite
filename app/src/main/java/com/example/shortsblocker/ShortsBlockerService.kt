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
 * Events from other apps are only used to notice that YouTube has gone to the
 * background (then we remove our boxes); nothing from them is read or kept.
 *
 * For YouTube, each time the screen changes we look through the views on
 * screen ("nodes") once, and then:
 *
 *   - Shorts player / Shorts tab  -> press Back straight away.
 *   - Home / Subscriptions feed   -> cover it with a blank panel (if ticked).
 *                                    The panel catches touches, so the feed
 *                                    can't be scrolled or tapped.
 *   - Shorts rows (in feeds, search results, under videos)
 *                                 -> covered with a box, but only once you've
 *                                    stopped scrolling/tapping for 3 seconds.
 *                                    The moment you move again the boxes go
 *                                    and the 3 seconds start over. Touches go
 *                                    through these boxes, so scrolling works.
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
         * ...except these. "reel_time_bar" is a window-sized view that YouTube
         * keeps on screen on the Home feed and watch page too (seen in debug
         * mode), so it must never count as "a Short is playing".
         */
        val NOT_SHORTS_ID_WORDS = listOf("time_bar")

        /** A single Short in a feed is described as "..., 1.2M views - play Short". */
        val SHORTS_ITEM_WORDS = listOf("play short")

        /**
         * Labels of YouTube's bottom-bar tabs and the search button. If your
         * phone isn't in English, add your language's words here (debug mode
         * shows them as desc="...").
         */
        val SHORTS_LABELS = listOf("Shorts")
        val HOME_TAB_LABELS = listOf("Home")
        val SUBSCRIPTIONS_TAB_LABELS = listOf("Subscriptions")
        val OTHER_TAB_LABELS = listOf("You", "Create", "Library")
        val SEARCH_LABELS = listOf("Search")

        /**
         * The back arrow in the top bar. YouTube keeps the Home tab selected
         * on search results and channel pages you open from Home, but those
         * always have a back arrow; the real feed doesn't.
         */
        val BACK_LABELS = listOf("Navigate up", "Back")

        /**
         * Buttons of the normal video player. If one of these is in the top
         * part of the screen you're watching a video, so feeds aren't covered
         * (YouTube keeps the Home tab "selected" underneath the video).
         */
        val VIDEO_PLAYER_LABELS = listOf(
            "Pause video", "Play video", "Show player controls", "Hide player controls",
            "Enter fullscreen", "Exit fullscreen",
        )

        /** ...or a video-player-shaped view with "player" in its ID... */
        val VIDEO_PLAYER_ID_WORDS = listOf("player")

        /** ...but not these (the whole-screen layout, previews in feeds, the mini player). */
        val NOT_VIDEO_PLAYER_ID_WORDS = listOf("watch_while", "inline", "thumbnail", "mini", "reel", "shorts")

        /** The normal video player sits in the top part of the screen. */
        const val VIDEO_PLAYER_TOP_SHARE = 0.45

        /** The back arrow and search box sit in the top part of the window. */
        const val TOP_BAR_STRICT_SHARE = 0.12

        /** The search button is in the top part of the window. */
        const val TOP_BAR_SHARE = 0.2

        /** A view counts as the Shorts *player* if it is at least this big. */
        const val MIN_WIDTH_SHARE = 0.7
        const val MIN_HEIGHT_SHARE = 0.6

        /** On a feed we're extra careful: only a Shorts view this tall counts as the player. */
        const val FULL_PLAYER_HEIGHT_SHARE = 0.85

        /** The bottom bar is in the bottom part of the window. */
        const val BOTTOM_BAR_SHARE = 0.8

        /** How long you must stop scrolling/tapping before Shorts rows are covered. */
        const val STILL_MS = 3000L

        /** Re-check this long after the last screen change. */
        const val SETTLE_MS = 250L

        /** Wait at least this long between two Back presses. */
        const val BACK_COOLDOWN_MS = 1200L

        /**
         * Back is the risky action (if we're wrong it kicks you out of YouTube),
         * so the Shorts view must still be there this long after we first saw
         * it. Keep this shorter than SETTLE_MS so the re-check can confirm it.
         */
        const val BACK_CONFIRM_MS = 200L

        /**
         * After a Back we don't press again until the Shorts view has gone away
         * (so one Short can't cause a chain of Backs that leaves YouTube). If it
         * never goes away (the first Back only closed a pop-up), try again after this.
         */
        const val BACK_RETRY_MS = 4000L

        /** Loop guard: this many Backs within the window pauses Back for a while. */
        const val LOOP_MAX_BACKS = 3
        const val LOOP_WINDOW_MS = 10_000L
        const val LOOP_PAUSE_MS = 30_000L

        /** In debug mode, save the whole screen at most this often. */
        const val DUMP_INTERVAL_MS = 2000L

        /** Safety limits so a huge screen can't make us loop forever. */
        const val MAX_DEPTH = 60
        const val MAX_NODES = 1500
        const val MAX_NODES_IN_DUMP = 800
        const val MAX_BOXES = 12
    }

    private lateinit var overlays: OverlayManager
    private val handler = Handler(Looper.getMainLooper())
    private val settle = Runnable { refresh(null) }
    private val stillTimer = Runnable { refresh(null) }

    /** When you last scrolled, tapped or changed screen in YouTube. */
    private var lastMoveAt = 0L

    /** Where the top bar ends / bottom bar starts, remembered for when the top bar scrolls away. */
    private var knownTopBarBottom = -1
    private var knownBarTop = -1

    private var lastBackAt = 0L

    /** When we first saw the Shorts view in a row of checks (0 = not seen right now). */
    private var shortsFirstSeenAt = 0L

    /** True once the Shorts view has been off screen since our last Back press. */
    private var shortsGoneSinceBack = true

    /** Last error written to Recent activity, so a repeating error is only written once. */
    private var lastErrorLog = ""
    private val recentBacks = ArrayDeque<Long>()
    private var backPausedUntil = 0L
    private var lastDumpAt = 0L
    private var lastCoverLog = ""

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

        val moved = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> true
            else -> false  // content changes happen all the time (videos playing, loading)
        }
        if (moved) {
            // You're moving: Shorts boxes go away now, and the 3 seconds start over.
            lastMoveAt = SystemClock.elapsedRealtime()
            handler.removeCallbacks(stillTimer)
            handler.postDelayed(stillTimer, STILL_MS)
        }

        refresh(event)
        // Check once more shortly after the last change, to put the boxes
        // exactly in place.
        handler.removeCallbacks(settle)
        handler.postDelayed(settle, SETTLE_MS)
    }

    override fun onInterrupt() = hideAll()

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacks(settle)
        handler.removeCallbacks(stillTimer)
        hideAll()
        return super.onUnbind(intent)
    }

    private fun hideAll() {
        shortsFirstSeenAt = 0L  // a new visit to YouTube must be confirmed again
        if (::overlays.isInitialized) overlays.clear()
    }

    // ---------------------------------------------------------------------
    // The main check
    // ---------------------------------------------------------------------

    /**
     * Runs the check, but never lets an unexpected error stop the service.
     * (Screens change while we read them, so odd errors can happen.) The error
     * is written to Recent activity so it shows up in Share screen info.
     */
    private fun refresh(event: AccessibilityEvent?) {
        try {
            refreshNow(event)
        } catch (e: Exception) {
            val msg = "Error: ${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, msg, e)
            if (msg != lastErrorLog) Prefs.addLog(this, msg)
            lastErrorLog = msg
            hideAll()
        }
    }

    private fun refreshNow(event: AccessibilityEvent?) {
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

        // Shorts only play upright. A phone turned sideways is showing a normal
        // video full screen, and pressing Back there shrinks it into the little
        // corner player. So in landscape we do nothing at all.
        val landscape = window.width() > window.height()

        // 1. Shorts player is open -> press Back.
        val playerReason = if (blockShorts && !landscape) shortsPlayerReason(screen) else null

        // 2. Everything to cover.
        val still = SystemClock.elapsedRealtime() - lastMoveAt >= STILL_MS
        val boxes = if (playerReason == null && !landscape) boxesFor(screen, window, blockShorts, still) else emptyList()

        if (debug) dumpScreen(root, event, window, screen, playerReason, still, boxes)
        if (!blocking) {
            hideAll()
            return
        }

        if (playerReason != null) {
            overlays.clear()
            val now = SystemClock.elapsedRealtime()
            if (shortsFirstSeenAt == 0L) shortsFirstSeenAt = now
            // Only press Back when (a) the Shorts view is still there on a second
            // look, and (b) the last Back has had its effect: the Shorts view went
            // away since then, or enough time passed that it clearly didn't work.
            val confirmed = now - shortsFirstSeenAt >= BACK_CONFIRM_MS
            val lastBackDone = shortsGoneSinceBack || now - lastBackAt >= BACK_RETRY_MS
            if (confirmed && lastBackDone) pressBack(playerReason)
            return
        }
        // No Shorts view on screen right now.
        shortsFirstSeenAt = 0L
        shortsGoneSinceBack = true

        overlays.show(boxes)
        logCoverChanges(screen, boxes)
    }

    /** What we found on screen in one pass over the views. */
    private class Screen {
        var homeSelected = false
        var subsSelected = false
        var shortsTabSelected = false
        var hasBackButton = false
        var hasSearchField = false
        var watching: String? = null        // why we think a video is playing
        var barTop = -1                      // top of the bottom bar
        var topBarBottom = -1                // bottom of the search button
        var shortsPlayerId: String? = null
        var shortsPlayerFullHeight = false
        val shortsItems = mutableListOf<Rect>()

        /** A real feed page: not a search, a page opened from it, or a video. */
        private val onFeedPage get() = !hasBackButton && !hasSearchField && watching == null
        val isHomeFeed get() = homeSelected && onFeedPage
        val isSubsFeed get() = subsSelected && onFeedPage
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
        val tab = bottomTab(node, window)
        if (tab != null) {
            val (tabNode, tabRect) = tab
            s.barTop = if (s.barTop < 0) tabRect.top else minOf(s.barTop, tabRect.top)
            val selected = node.isSelected || tabNode.isSelected
            if (selected) when {
                hasLabel(node, HOME_TAB_LABELS) -> s.homeSelected = true
                hasLabel(node, SUBSCRIPTIONS_TAB_LABELS) -> s.subsSelected = true
                hasLabel(node, SHORTS_LABELS) -> s.shortsTabSelected = true
            }
            return
        }

        // Top bar: a back arrow or search box means this isn't a feed page.
        if (b.bottom <= window.top + window.height() * TOP_BAR_STRICT_SHARE) {
            if (labelIs(node, BACK_LABELS)) s.hasBackButton = true
            if (node.className?.toString()?.contains("EditText") == true) s.hasSearchField = true
        }

        // Search button: the feed panel starts below it.
        if (b.bottom <= window.top + window.height() * TOP_BAR_SHARE && hasLabel(node, SEARCH_LABELS)) {
            s.topBarBottom = maxOf(s.topBarBottom, b.bottom)
        }

        // The normal video player (in the top part of the screen).
        if (s.watching == null && b.top < window.top + window.height() * VIDEO_PLAYER_TOP_SHARE) {
            if (hasLabel(node, VIDEO_PLAYER_LABELS)) {
                s.watching = "player button \"${texts(node).firstOrNull()}\""
            } else if (containsAny(id, VIDEO_PLAYER_ID_WORDS) && !containsAny(id, NOT_VIDEO_PLAYER_ID_WORDS) &&
                b.width() >= window.width() * 0.9 &&
                b.height() >= window.height() * 0.2 && b.height() <= window.height() * 0.6
            ) {
                s.watching = "player view \"$id\""
            }
        }

        // Shorts by view ID: big = the player, small = a row/item in a feed.
        if (containsAny(id, SHORTS_ID_WORDS) && !containsAny(id, NOT_SHORTS_ID_WORDS)) {
            if (b.width() >= window.width() * MIN_WIDTH_SHARE && b.height() >= window.height() * MIN_HEIGHT_SHARE) {
                s.shortsPlayerId = id
                if (b.height() >= window.height() * FULL_PLAYER_HEIGHT_SHARE) s.shortsPlayerFullHeight = true
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
     * view and its bounds. A tab sits at the very bottom (fully on screen),
     * and is narrow and short.
     */
    private fun bottomTab(node: AccessibilityNodeInfo, window: Rect): Pair<AccessibilityNodeInfo, Rect>? {
        if (!hasLabel(node, HOME_TAB_LABELS + SHORTS_LABELS + SUBSCRIPTIONS_TAB_LABELS + OTHER_TAB_LABELS)) return null
        val tabNode = clickableAncestor(node) ?: node
        val r = bounds(tabNode)
        val inBar = r.top >= window.top + window.height() * BOTTOM_BAR_SHARE &&
                r.bottom >= window.top + window.height() * 0.9 &&
                r.bottom <= window.bottom
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
        // On a feed, only a Shorts player that fills the screen counts (a
        // Short you tapped in a Shorts row). The loop guard protects us if wrong.
        val id = s.shortsPlayerId
        val onFeed = s.isHomeFeed || s.isSubsFeed
        if (id != null && (!onFeed || s.shortsPlayerFullHeight)) {
            return "full-screen Shorts view (id \"$id\")"
        }
        // Pages opened from Home keep Home selected, so only trust the Shorts
        // tab when Home isn't selected.
        if (s.shortsTabSelected && !s.homeSelected) return "Shorts tab is selected"
        return null
    }

    private fun boxesFor(s: Screen, window: Rect, blockShorts: Boolean, still: Boolean): List<OverlayManager.Box> {
        // The area between the top bar and the bottom bar. When the top bar
        // has scrolled away we use where it was last time, so the panel never
        // grows over the whole screen.
        if (s.topBarBottom >= 0) knownTopBarBottom = s.topBarBottom
        if (s.barTop >= 0) knownBarTop = s.barTop
        val contentTop = when {
            s.topBarBottom >= 0 -> s.topBarBottom
            knownTopBarBottom >= 0 -> knownTopBarBottom
            else -> window.top + (window.height() * TOP_BAR_STRICT_SHARE).toInt()
        }
        val contentBottom = when {
            s.barTop >= 0 -> s.barTop
            knownBarTop >= 0 -> knownBarTop
            else -> window.bottom
        }
        if (contentBottom <= contentTop) return emptyList()

        // Feeds: a blank panel that catches touches, shown straight away.
        val coverFeed = (Prefs.isBlockHomeOn(this) && s.isHomeFeed) ||
                (Prefs.isBlockSubsOn(this) && s.isSubsFeed)
        if (coverFeed) {
            return listOf(
                OverlayManager.Box(
                    Rect(window.left, contentTop, window.right, contentBottom),
                    getString(R.string.overlay_feed),
                    touchable = true,
                )
            )
        }

        // Shorts rows: only once you've been still for 3 seconds.
        if (!blockShorts || !still) return emptyList()
        val rows = s.shortsItems
            .map { Rect(it).apply { intersect(window.left, contentTop, window.right, contentBottom) } }
            .filter { it.width() > 0 && it.height() > 0 && it.top >= contentTop && it.bottom <= contentBottom }
            .sortedByDescending { it.width() * it.height() }
        val kept = mutableListOf<Rect>()
        for (r in rows) {
            if (kept.none { it.contains(r) }) kept += r  // skip anything already inside a box
        }
        return kept.take(MAX_BOXES).map {
            OverlayManager.Box(it, getString(R.string.overlay_shorts), touchable = false)
        }
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
        shortsGoneSinceBack = false  // no more Backs until this one has worked
        shortsFirstSeenAt = 0L
        performGlobalAction(GLOBAL_ACTION_BACK)
        Log.i(TAG, "Pressed Back ($reason)")
        Prefs.addLog(this, "Pressed Back: $reason")
        Toast.makeText(this, "Shorts blocked", Toast.LENGTH_SHORT).show()
    }

    /** Writes to Recent activity when what's covered changes (not on every refresh). */
    private fun logCoverChanges(s: Screen, boxes: List<OverlayManager.Box>) {
        val now = when {
            boxes.any { it.touchable } -> if (s.isHomeFeed) "Covered the Home feed" else "Covered the Subscriptions feed"
            boxes.isNotEmpty() -> "Hid Shorts (you were still for 3 s)"
            else -> ""
        }
        if (now != lastCoverLog && now.isNotEmpty()) Prefs.addLog(this, now)
        lastCoverLog = now
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
        still: Boolean,
        boxes: List<OverlayManager.Box>,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDumpAt < DUMP_INTERVAL_MS) return
        lastDumpAt = now

        val out = StringBuilder()
        out.append("Screen info saved ").append(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()))
            .append(" (event: ").append(event?.let { AccessibilityEvent.eventTypeToString(it.eventType) } ?: "timer")
            .append(")\n")
        out.append("window=").append(window.toShortString())
            .append(" landscape=").append(window.width() > window.height())
            .append(" homeSelected=").append(s.homeSelected)
            .append(" subsSelected=").append(s.subsSelected)
            .append(" back=").append(s.hasBackButton)
            .append(" searchField=").append(s.hasSearchField)
            .append(" watching=").append(s.watching)
            .append(" homeFeed=").append(s.isHomeFeed)
            .append(" subsFeed=").append(s.isSubsFeed)
            .append(" shortsTabSelected=").append(s.shortsTabSelected)
            .append(" barTop=").append(s.barTop)
            .append(" topBarBottom=").append(s.topBarBottom)
            .append(" playerId=").append(s.shortsPlayerId)
            .append(" shortsItems=").append(s.shortsItems.size)
            .append(" still=").append(still)
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
