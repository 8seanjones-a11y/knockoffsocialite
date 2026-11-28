# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Shorts Blocker: a small Android app (Kotlin, package `com.example.shortsblocker`) that uses an AccessibilityService to block YouTube Shorts and, optionally, the Home and Subscriptions feeds in the official YouTube app (`com.google.android.youtube`). The owner is a beginner on Windows with an Android phone: explain changes plainly and keep code simple and well commented. `README.md` is the user guide (install, Android 13+ "Allow restricted settings", testing, debug mode); keep it in step with behaviour changes.

## Rules

- **No permissions, no libraries.** The manifest has no `<uses-permission>` (no internet, no analytics) and the app has no dependencies beyond the Android framework and Kotlin. Check with `aapt2 dump permissions` after building.
- **Never change `applicationId`** or the signing key. `debug.keystore` (password `android`, alias `androiddebugkey`) is committed on purpose so every build, from any machine, installs over the copy on the phone.
- Bump `versionCode`/`versionName` in `app/build.gradle.kts` with each user-facing change.
- The app can't be tested against real YouTube here; detection is heuristic. When something misbehaves, ask the user for **Share screen info** output (debug mode) instead of guessing IDs.

## Build

```bash
echo "sdk.dir=/opt/android-sdk" > local.properties   # or set ANDROID_HOME
./gradlew assembleDebug                               # Windows: .\gradlew.bat assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
/opt/android-sdk/build-tools/35.0.0/aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk
```

AGP 8.7.3, Kotlin 2.0.21, Gradle 8.14.3 (wrapper), compileSdk/targetSdk 35, minSdk 26, JDK 17+. There are no tests or lint setup. Installing the SDK needs the user's acceptance of the Android SDK licence.

**CI:** `.github/workflows/build-apk.yml` builds on every push to `main` or `claude/**` and replaces the `latest` GitHub release with `ShortsBlocker.apk`. The user installs from `https://github.com/8seanjones-a11y/knockoffsocialite/releases/download/latest/ShortsBlocker.apk`, so **any push to a `claude/` branch changes what the user downloads.**

## Architecture

Four Kotlin files in `app/src/main/java/com/example/shortsblocker/`:

- **`ShortsBlockerService`** (the blocker). On each YouTube event it does one pass over the node tree (`scan()` → `Screen`), then decides:
  - **Shorts player or Shorts tab selected** → `GLOBAL_ACTION_BACK` immediately, with a 1.2 s cooldown and a loop guard (3 Backs in 10 s → pause 30 s, logged).
  - **Home / Subscriptions feed** (if ticked) → one touchable "Feed blocked" panel between the top bar and bottom bar, shown immediately.
  - **Shorts rows/items** → non-touchable "Shorts hidden" boxes, shown only after `STILL_MS` (3 s) with no scroll, click or window-state change. Any of those events removes them and restarts the timer (`lastMoveAt`, `stillTimer`). Content-change events don't count as movement, because YouTube emits them constantly.
- **`OverlayManager`** draws `TYPE_ACCESSIBILITY_OVERLAY` windows (no overlay permission needed). `show(boxes)` diffs against what's on screen. Boxes with `touchable = false` get `FLAG_NOT_TOUCHABLE`, so touches pass through to YouTube.
- **`Prefs`**: SharedPreferences switches (`blocking`, `block_shorts`, `block_home`, `block_subscriptions`, `debug`), a 30-line Recent activity log, and the last debug screen dump (`filesDir/screen_info.txt`).
- **`MainActivity`**: plain `Activity` with no AppCompat. Switches and checkboxes, service status, buttons for Accessibility settings and App info, Recent activity, and Share screen info (`ACTION_SEND`).

The service config `res/xml/accessibility_service_config.xml` deliberately has **no `packageNames`**: events from other apps are used only to remove overlays when YouTube leaves the foreground. Overlays would otherwise stay over the launcher.

### Detection heuristics (constants at the top of the service)

- **"reel" means Shorts.** IDs containing `reel`/`shorts`: a large one is the Shorts player (≥70% × ≥60% of the window; ≥85% tall when on a feed), smaller ones are rows. Items are also found by the description "play Short" and by rows under a "Shorts" heading (`shortsHeadingCover`).
- **Bottom-bar tabs** are found by label (Home/Shorts/Subscriptions/You…), and must sit in the bottom 20% of the window, be small, and be fully on screen. Labels are English-only; other languages need entries added to the label lists.
- **YouTube keeps Home selected** on search results, channels and the watch page. So a page only counts as a feed when it has no back arrow ("Navigate up"), no search `EditText` in the top bar, and no video player. A player is recognised by its button labels or a player-shaped view with an ID containing `player` in the top 45% of the screen, excluding `watch_while`/`inline`/`thumbnail`/`mini`.
- **Panel position:** it uses the last known top-bar and bottom-bar positions (`knownTopBarBottom`/`knownBarTop`), so it never grows over the whole screen when the top bar scrolls away.

### Things that went wrong before (don't reintroduce)

- **Boxes over Shorts rows that track scrolling.** They lag behind and get left as empty white boxes. That's why rows are only boxed when the screen is still.
- **Pressing Back on the Home feed.** It scrolls to the top or closes YouTube.
- **Redirecting Home by tapping the Subscriptions tab.** The user rejected it, and it fired on pages opened from Home.
- **Matching `player`/`watch` IDs without size and position limits.** YouTube's whole-screen layout matches them, so the feed was never detected.
- **Treating every big `reel*` view as the Shorts player.** `reel_time_bar` is a window-sized view that stays on the Home feed and watch page, so it caused endless Backs (minimising videos, leaving YouTube) and stopped the Home cover (Back wins over covering). It is listed in `NOT_SHORTS_ID_WORDS`. If another `reel*` view shows the same pattern, add it there after checking a Share screen info dump.
