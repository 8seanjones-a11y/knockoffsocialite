# Shorts Blocker

A small Android app that makes the official YouTube app less of a time sink.
You choose what it blocks:

- **Shorts.** If you open the Shorts tab or a Short (from a row or a link),
  it presses **Back** for you. Shorts rows in feeds, search results and under
  videos get covered with a box, but only after you've stopped scrolling for
  **3 seconds**, so it only bothers you when you're actually looking at them.
  The moment you scroll or tap again, the boxes go away and the 3 seconds
  start over. You can scroll straight through the boxes.
- **Home feed** and **Subscriptions feed**, each optional. They're covered
  with a blank "Feed blocked" panel. Then the only ways to find videos are
  **Search** and the **You** tab (History, Continue watching, Watch later,
  playlists).

Normal videos keep playing as usual.

- No internet permission (no permissions at all), no analytics, no data collection.
- Only acts on the YouTube app (`com.google.android.youtube`).
- Plain Kotlin and Android, no libraries.

**Quickest way to install:** on your Android phone, open
<https://github.com/8seanjones-a11y/knockoffsocialite/releases/download/latest/ShortsBlocker.apk>
in the browser (not the GitHub app), then go to section 5. GitHub rebuilds
that file every time the code changes (`.github/workflows/build-apk.yml`).

---

## 1. How it works (the short version)

Android has **accessibility services**: apps that can see what's on screen and
press buttons for you. Screen readers like TalkBack use them. This app is one.
It gets events from every app, but only so it can tell when YouTube goes to
the background (then it removes its boxes). It ignores everything else.

An app can't delete anything from inside another app, but an accessibility
service may draw **overlay boxes** on top of it, without any extra
permission (`OverlayManager.kt`). Each time YouTube's screen changes,
`ShortsBlockerService.kt` looks through the views on screen once and decides
what to do:

| What | How it's found | What happens |
|------|----------------|--------------|
| Shorts player | A view ≥70% wide and ≥60% tall whose ID contains `reel`/`shorts` (on a feed: ≥85% tall) | Presses **Back** straight away |
| Shorts tab | The bottom-bar Shorts tab is selected | Presses **Back** straight away |
| Shorts rows and items | IDs containing `reel`/`shorts`, descriptions containing "play Short", and rows under a "Shorts" heading | Covered with a "Shorts hidden" box **after 3 s without scrolling or tapping**. Removed the moment you move. Touches go through, so you can scroll past. |
| Home / Subscriptions feed (if ticked) | That bottom-bar tab is selected **and** there's no back arrow or search box in the top bar **and** no video player on screen. (YouTube keeps Home selected on search results, channels and videos opened from Home.) | Covered straight away by a "Feed blocked" panel that catches touches, so the feed can't be scrolled or tapped. The top bar (Search) and the bottom tabs stay usable. |

Two fixes over the earlier box version:

- **The panel never covers the whole screen.** If the top bar has scrolled
  away, it uses where the top bar was last time.
- **It doesn't stay over videos.** A video counts as playing when player
  buttons ("Pause video", "Enter fullscreen", …) or a video-shaped player view
  are in the top part of the screen. Then nothing is covered.

Safety rules for Back, so it can't fight you:

- On a feed it only presses Back for a Shorts player that fills the screen.
  (On YouTube, Back on Home scrolls to the top and then closes the app.)
- At most once every 1.2 s.
- **Loop guard:** after 3 Backs within 10 s it stops pressing Back for 30 s,
  and writes that in *Recent activity*.


### The main screen

| Control | What it does |
|---------|--------------|
| **Blocker on** | Main switch. Off = nothing is blocked. |
| **Shorts** ☑ | Close the Shorts tab and Shorts player; hide Shorts rows after 3 s still (on by default). |
| **Home feed** ☐ | Cover the Home feed (off by default). |
| **Subscriptions feed** ☑ | Cover the Subscriptions feed (on by default). |
| **Debug mode** | Saves a description of the last YouTube screen (section 7). |
| **Recent activity** | The last 30 things the blocker did (Back presses, covering, loop guard). |
| **Share screen info** | Sends the recent activity plus the saved screen (if debug mode is on) to any app, so you can paste it when asking for help. |

### What it can't block

The "Up next" list **under a video you're watching** can't be hidden without
also stopping you watching the video, so it stays. To cut it down, use
YouTube's own settings: *You → ⚙ Settings → Autoplay* → off. Also, if you
**pause your watch history** (*You → ⚙ → Manage all history*), YouTube shows
far fewer recommendations everywhere.

### Not using YouTube in English?

"Shorts" is the same word in almost every language, but **Home** and
**Subscriptions** are translated. If Home blocking doesn't work, use debug
mode to see your tab labels (`desc="..."` at the bottom of the screen) and add
them in `ShortsBlockerService.kt`:

```kotlin
val HOME_TAB_LABELS = listOf("Home", "Startseite")
val SUBSCRIPTIONS_TAB_LABELS = listOf("Subscriptions", "Abos")
val BACK_LABELS = listOf("Navigate up", "Back", "Nach oben")
```

### Project files

```
app/src/main/
  AndroidManifest.xml                  what's in the app (one screen, one service, no permissions)
  java/com/example/shortsblocker/
    ShortsBlockerService.kt            the blocker (detection, Back, debug screen info)
    OverlayManager.kt                  draws the boxes that cover things
    MainActivity.kt                    the main screen
    Prefs.kt                           saves the switches, recent activity and screen info
  res/xml/accessibility_service_config.xml   "give me screen changes, taps and view IDs"
.github/workflows/build-apk.yml        builds the APK on GitHub and publishes it
  res/layout/activity_main.xml         main screen layout
  res/values/strings.xml               all the text
debug.keystore                         signing key for debug builds (see note below)
gradlew.bat / gradlew                  runs Gradle without installing it
```

> **About `debug.keystore`:** Android only installs an update if it's signed
> with the same key as the version already on your phone. Keeping the key in
> the project means every build (from any computer) can update the installed
> app. It's a throwaway debug key with the standard password `android`, so
> it's fine to share. Don't use it for apps you publish.

---

## 2. Install the build tools on Windows (one time)

You need three things. Android Studio is **not** needed.

| What | Why |
|------|-----|
| **JDK 17** (Java) | Gradle and the Android build tools run on Java |
| **Android SDK command-line tools** | Android's compiler, libraries and `sdkmanager` |
| **Android platform-tools** | `adb`, the program that talks to your phone over USB |

Gradle itself is **not** needed: `gradlew.bat` downloads the right version the
first time you build.

### 2a. Java (JDK 17)

Open **PowerShell** and run:

```powershell
winget install EclipseAdoptium.Temurin.17.JDK
```

Close PowerShell, open a new one, and check:

```powershell
java -version
```

You should see `openjdk version "17..."`.

(No `winget`? Download the `.msi` installer from https://adoptium.net, pick
version 17, and tick "Set JAVA_HOME" during setup.)

### 2b. Android SDK command-line tools

1. Go to https://developer.android.com/studio, scroll to **"Command line
   tools only"**, and download the **Windows** zip.
2. Create the folder `C:\Android\cmdline-tools\latest`.
3. Open the zip. It contains a folder called `cmdline-tools`. Copy **what's
   inside** that folder (`bin`, `lib`, …) into `C:\Android\cmdline-tools\latest`.
   You should end up with `C:\Android\cmdline-tools\latest\bin\sdkmanager.bat`.
4. Tell Windows where the SDK is, and add `adb` to the PATH. In PowerShell:

   ```powershell
   [Environment]::SetEnvironmentVariable("ANDROID_HOME", "C:\Android", "User")
   $p = [Environment]::GetEnvironmentVariable("Path", "User")
   [Environment]::SetEnvironmentVariable("Path", "$p;C:\Android\platform-tools;C:\Android\cmdline-tools\latest\bin", "User")
   ```

   (This adds to your own user PATH. Avoid `setx PATH …`, which can cut a
   long PATH short.)

   Close PowerShell and open a new one so the change takes effect.

5. Accept the licences (read them, then type `y`) and install what this
   project needs:

   ```powershell
   sdkmanager --licenses
   sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
   ```

6. Check that `adb` works:

   ```powershell
   adb version
   ```

---

## 3. Build the APK

In PowerShell, go to the project folder and run the debug build:

```powershell
cd C:\path\to\shorts-blocker
.\gradlew.bat assembleDebug
```

The first build downloads Gradle and the Android build plugin, which takes a
few minutes. Later builds take seconds. When you see `BUILD SUCCESSFUL`, the
APK is at:

```
app\build\outputs\apk\debug\app-debug.apk
```

If the build says it can't find the SDK, create a file called
`local.properties` in the project folder containing this line (note the
double backslashes):

```
sdk.dir=C:\\Android
```

---

## 4. Install the APK on your phone

Pick **one** of the two ways.

### Option A: over USB with adb (recommended, and you'll need it for the logs)

1. **Turn on Developer options:** on the phone, go to *Settings → About phone*
   and tap **Build number** 7 times. (On Samsung: *About phone → Software
   information → Build number*.)
2. **Turn on USB debugging:** *Settings → System → Developer options →
   USB debugging* → On. (On Samsung: *Settings → Developer options*.)
3. Plug the phone into your PC with a USB cable. If the phone asks what the
   USB connection is for, choose **File transfer**.
4. In PowerShell run:

   ```powershell
   adb devices
   ```

   The phone shows **"Allow USB debugging?"**. Tick *Always allow from this
   computer* and tap **Allow**. Run `adb devices` again; your phone should be
   listed as `device` (not `unauthorized`).

   > If no device appears, Windows may need a USB driver for your phone.
   > Pixel: install the Google USB Driver (`sdkmanager "extras;google;usb_driver"`).
   > Samsung and others: search for "<brand> USB driver".

5. Install (`-r` means "replace if already installed", so the same command
   works for updates):

   ```powershell
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   ```

   You should see `Success`.

### Option B: copy the file over

1. Copy `app-debug.apk` to the phone's **Download** folder (USB file transfer,
   Google Drive, email to yourself, …).
2. On the phone, open the **Files** app → Downloads → tap `app-debug.apk`.
3. Android says the app isn't allowed to install unknown apps. Tap
   **Settings**, turn on **Allow from this source**, and go back.
4. Tap **Install**. If Play Protect warns that the app is unknown, tap
   **More details → Install anyway**. (It's unknown to Google because you built
   it yourself.)

---

## 5. Turn on the accessibility service

1. Open **Shorts Blocker** on the phone and tap **Open Accessibility Settings**.
2. Find **Shorts Blocker**. Depending on the phone, it's under *Downloaded apps*,
   *Installed apps* or *Installed services*.
3. Turn it **on** and tap **Allow** in the confirmation popup.
4. Go back to Shorts Blocker. The top line should say
   **✅ The service is turned on**.

### Android 13+ only: "Restricted setting"

If you installed the APK from a file (Option B), Android 13 and newer block
the switch in step 3. It's greyed out, or you see a **"Restricted setting"**
popup. This protects people from scam apps. To allow it for this app:

1. Try to turn the service on once (step 3). You must see the
   "Restricted setting" popup first, or the option in step 3 below won't appear.
2. In Shorts Blocker, tap **Open App Info** (or go to *Settings → Apps →
   Shorts Blocker*).
3. Tap **⋮** (top right) → **Allow restricted settings**, and confirm with your
   PIN or fingerprint.
4. Go back to Accessibility settings and turn the service on.

Installs over USB with `adb` (Option A) usually skip this step.

### Keep it running (some phones)

Samsung, Xiaomi, OnePlus and others sometimes shut down background services to
save battery. If blocking stops after a while:
*Settings → Apps → Shorts Blocker → Battery → **Unrestricted***.

---

## 6. Test it

With the service on, **Blocker on** switched on and **Shorts** ticked:

| Try this | Expected |
|----------|----------|
| Tap the **Shorts** tab | You're sent straight back, with a "Shorts blocked" toast |
| Search for something with Shorts in the results, then stop scrolling | After 3 s the Shorts are covered by "Shorts hidden" boxes |
| Scroll again | The boxes disappear straight away; stop for 3 s and they come back |
| Tap a Short in a Shorts row (before it's covered) | It closes again, with a "Shorts blocked" toast |
| Open a Shorts link (`youtube.com/shorts/…`) from another app | Closed with a "Shorts blocked" toast. Back may close YouTube if the Short was the first screen. |
| Scroll, tap Subscriptions, play a normal video, search, open You | Everything works normally. No jumping to the top. |
| Turn **Blocker on** off in the app, go back to YouTube | All boxes are gone |

Now tick **Home feed** and **Subscriptions feed** too:

| Try this | Expected |
|----------|----------|
| Open YouTube on Home | The feed is covered by "Feed blocked"; the top bar and tabs still work |
| Tap **Subscriptions** | Covered the same way |
| Tap **Search** and search for something | The results are visible |
| Tap **You**, open Continue watching / History and play a video | The video plays with nothing over it, even after a few minutes |
| Open a channel and tap its own "Home" tab | Works normally (it's not the bottom bar) |

If something misbehaves, open Shorts Blocker and look at **Recent activity**.

---

## 7. Debug mode: find the right IDs yourself

If a YouTube update breaks the blocker, debug mode shows you what YouTube's
screen is made of so you can fix it.

### On the phone only (no PC)

1. In Shorts Blocker, turn on **Debug mode**.
2. Open YouTube and go to the screen that's wrong (for example a Shorts row
   that isn't blocked). Wait 2–3 seconds.
3. Switch back to Shorts Blocker, tap **Share screen info**, and send it to
   yourself (notes, email, chat). It contains the recent activity and a list
   of the views that were on that screen.
4. Turn debug mode off again. It uses a little extra battery.

### With a PC (Logcat)

1. Connect the phone over USB (section 4, Option A).
2. In Shorts Blocker, turn on **Debug mode**. You can turn **Blocker on**
   off while you look, so the blocker doesn't close things on you. (Debug
   mode still logs what it *would* have blocked.)
3. In PowerShell, clear old logs and start watching:

   ```powershell
   adb logcat -c
   adb logcat -s ShortsBlocker
   ```

   `-s ShortsBlocker` shows only this app's messages. Press `Ctrl+C` to stop.
   To save to a file instead: `adb logcat -s ShortsBlocker > shorts-log.txt`.

4. Open YouTube and go to a Short. Every 2 seconds you get a screen dump like:

   ```
   D ShortsBlocker: Screen info saved 14:03:12 (event: TYPE_WINDOW_CONTENT_CHANGED)
   D ShortsBlocker: window=[0,0][1080,2340] homeSelected=false shortsTabSelected=true ...
   D ShortsBlocker: Back reason: full-screen Shorts view (id "reel_player_page_container")
   D ShortsBlocker:     FrameLayout id=reel_player_page_container [0,0][1080,2201]
   D ShortsBlocker:       RecyclerView id=reel_recycler scrollable [0,0][1080,2201]
   D ShortsBlocker:         Button desc="Like this video along with 12K other people" clickable [930,1500][1062,1632]
   D ShortsBlocker:   Button desc="Shorts" SELECTED clickable [432,2190][648,2340]
   I ShortsBlocker: Pressed Back (full-screen Shorts view (id "reel_player_page_container"))
   ```

   Each line is one view: its type, `id=`, `text=`, `desc=` (content
   description), `SELECTED` if it's the active tab, `clickable` if it can be
   tapped, and where it is on screen as `[left,top][right,bottom]` in pixels.
   Indentation shows which views are inside which.

5. **If Shorts aren't blocked any more:** look for an `id=` on a view that
   only appears on Shorts and covers most of the screen (size close to your
   screen size). Open `ShortsBlockerService.kt` and add a word from that ID to:

   ```kotlin
   val SHORTS_ID_WORDS = listOf("reel", "shorts")
   ```

   For example, if the new ID is `vertical_player_root`, add `"vertical_player"`.
   Pick something that won't appear in normal videos (check a normal video's
   dump too). Then rebuild (section 3) and reinstall with `adb install -r …`.

6. **If something normal gets blocked by mistake:** the `Back reason:` line
   (and *Recent activity*) says which check fired and on which ID, so you know
   what to make more specific.

Turn debug logging off when you're done. It uses a little extra battery.

---

## 8. Troubleshooting

| Problem | Fix |
|---------|-----|
| The service switches itself off | Android turns accessibility services off if the app crashes. Turn it on again. If it keeps happening, look at `adb logcat` (without `-s`) for the error. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | An older copy was signed with a different key. Uninstall it (`adb uninstall com.example.shortsblocker`) and install again. |
| `adb devices` shows `unauthorized` | Unlock the phone and accept the "Allow USB debugging?" popup. |
| Nothing is blocked | Check the main screen says ✅, **Blocker on** is on, the right boxes are ticked, and battery is Unrestricted. Then use debug mode (section 7). |
| A feed isn't covered | Your tab label is different (another language, or YouTube renamed it). See "Not using YouTube in English?" in section 1. |
| A box stays over a video | The video player wasn't recognised. Turn on debug mode, play the video, and use **Share screen info**: look at `watching=` on the second line. |
| Recent activity says "pausing Back for 30 s" | Something normal was mistaken for Shorts. Turn on debug mode, open that screen, and use **Share screen info** (section 7). |
