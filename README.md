# Shorts Blocker

A small Android app that stops you watching YouTube Shorts in the official
YouTube app. When the Shorts player or the Shorts tab shows up, it presses
**Back** for you. Normal videos, search, subscriptions and the Home feed keep
working.

- No internet permission (no permissions at all), no analytics, no data collection.
- Watches **only** the YouTube app (`com.google.android.youtube`).
- Plain Kotlin and Android, no libraries.

---

## 1. How it works (the short version)

Android has **accessibility services**: apps that can see what's on screen and
press buttons for you. Screen readers like TalkBack use them. This app is one
that only listens to YouTube.

Each time YouTube's screen changes, `ShortsBlockerService.kt` looks through the
views on screen and asks three questions:

| # | Check | Why it's reliable |
|---|-------|-------------------|
| 1 | Is there a **big view** (≥70% wide, ≥60% tall) whose **view ID** contains `reel` or `shorts`? | "Reel" is YouTube's internal name for Shorts, used in IDs like `reel_recycler`. The size rule ignores the small Shorts row on the Home feed. |
| 2 | Is a tab or button labelled **"Shorts"** currently **selected**? | Catches the bottom-bar Shorts tab (and the Shorts tab on channel pages). The label "Shorts" is the same in almost every language. |
| 3 | Does the **name of the screen** that just opened contain `Reel` or `Shorts`? | A different signal in case the IDs change. |

If any check says yes, it presses Back (at most once every 1.2 s, so it can't
get stuck pressing Back over and over) and shows a "Shorts blocked" toast.

### Project files

```
app/src/main/
  AndroidManifest.xml                  what's in the app (one screen, one service, no permissions)
  java/com/example/shortsblocker/
    ShortsBlockerService.kt            the blocker (detection + Back + debug logging)
    MainActivity.kt                    the main screen
    Prefs.kt                           saves the two switches
  res/xml/accessibility_service_config.xml   "only YouTube, give me view IDs"
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

With the service on and **Block Shorts** switched on:

| Try this | Expected |
|----------|----------|
| Open YouTube, tap the **Shorts** tab | You're sent straight back, with a "Shorts blocked" toast |
| Tap a Short in the Home feed's Shorts row | The player closes immediately |
| Open a Shorts link (`youtube.com/shorts/…`) from another app | Blocked. Back may close YouTube if the Short was the first screen. |
| Scroll the Home feed past the Shorts row | Nothing happens (the row itself is allowed) |
| Play a normal video, search, open Subscriptions, Library | Everything works normally |
| Turn **Block Shorts** off in the app, open Shorts | Shorts play (the switch pauses blocking) |

You may see the Short for a split second before Back fires. That's normal.

---

## 7. Debug mode: find the right IDs yourself

If a YouTube update breaks the blocker, debug mode shows you what YouTube's
screen is made of so you can fix it.

1. Connect the phone over USB (section 4, Option A).
2. In Shorts Blocker, turn on **Debug logging**. You can turn **Block Shorts**
   off while you look, so the blocker doesn't close Shorts on you.
3. In PowerShell, clear old logs and start watching:

   ```powershell
   adb logcat -c
   adb logcat -s ShortsBlocker
   ```

   `-s ShortsBlocker` shows only this app's messages. Press `Ctrl+C` to stop.
   To save to a file instead: `adb logcat -s ShortsBlocker > shorts-log.txt`.

4. Open YouTube and go to a Short. Every 2 seconds you get a screen dump like:

   ```
   D ShortsBlocker: ===== Screen dump (event: TYPE_WINDOW_CONTENT_CHANGED, class: android.widget.FrameLayout) =====
   D ShortsBlocker:     FrameLayout id=reel_player_page_container size=1080x2201
   D ShortsBlocker:       RecyclerView id=reel_recycler size=1080x2201
   D ShortsBlocker:         Button desc="Like this video along with 12K other people" size=132x132
   D ShortsBlocker:   Button desc="Shorts" SELECTED size=216x150
   D ShortsBlocker: ===== End of dump (187 views) =====
   I ShortsBlocker: Shorts detected: full-screen view with id "reel_player_page_container"
   I ShortsBlocker: Pressed Back (full-screen view with id "reel_player_page_container")
   ```

   Each line is one view: its type, `id=`, `text=`, `desc=` (content
   description), `SELECTED` if it's the active tab, and its size in pixels.
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

6. **If something normal gets blocked by mistake:** the `Shorts detected: …`
   line says which check fired and on which ID, so you know what to make more
   specific.

Turn debug logging off when you're done. It uses a little extra battery.

---

## 8. Troubleshooting

| Problem | Fix |
|---------|-----|
| The service switches itself off | Android turns accessibility services off if the app crashes. Turn it on again. If it keeps happening, look at `adb logcat` (without `-s`) for the error. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | An older copy was signed with a different key. Uninstall it (`adb uninstall com.example.shortsblocker`) and install again. |
| `adb devices` shows `unauthorized` | Unlock the phone and accept the "Allow USB debugging?" popup. |
| Nothing is blocked | Check the main screen says ✅, **Block Shorts** is on, and battery is Unrestricted. Then use debug mode (section 7). |
