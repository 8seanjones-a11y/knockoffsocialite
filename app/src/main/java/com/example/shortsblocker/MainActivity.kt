package com.example.shortsblocker

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.Switch
import android.widget.TextView

/**
 * The screen you see when you open the app: switches, buttons, a status line
 * and the recent activity. All the blocking happens in ShortsBlockerService.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var activityLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        activityLog = findViewById(R.id.activityLog)

        // Each switch/checkbox shows the saved setting and saves it when
        // changed. The service reads the same settings.
        val blockingSwitch = findViewById<Switch>(R.id.blockingSwitch)
        blockingSwitch.isChecked = Prefs.isBlockingOn(this)
        blockingSwitch.setOnCheckedChangeListener { _, on -> Prefs.setBlockingOn(this, on) }

        val blockShorts = findViewById<CheckBox>(R.id.blockShorts)
        blockShorts.isChecked = Prefs.isBlockShortsOn(this)
        blockShorts.setOnCheckedChangeListener { _, on -> Prefs.setBlockShortsOn(this, on) }

        val blockHome = findViewById<CheckBox>(R.id.blockHome)
        blockHome.isChecked = Prefs.isBlockHomeOn(this)
        blockHome.setOnCheckedChangeListener { _, on -> Prefs.setBlockHomeOn(this, on) }

        val blockSubs = findViewById<CheckBox>(R.id.blockSubs)
        blockSubs.isChecked = Prefs.isBlockSubsOn(this)
        blockSubs.setOnCheckedChangeListener { _, on -> Prefs.setBlockSubsOn(this, on) }

        // "Debug mode" switch.
        val debugSwitch = findViewById<Switch>(R.id.debugSwitch)
        debugSwitch.isChecked = Prefs.isDebugOn(this)
        debugSwitch.setOnCheckedChangeListener { _, on -> Prefs.setDebugOn(this, on) }

        // Sends the recent activity and the last saved YouTube screen to any app
        // you pick (messages, email, notes...), so it can be pasted for help.
        findViewById<Button>(R.id.shareInfo).setOnClickListener { shareInfo() }

        findViewById<Button>(R.id.clearActivity).setOnClickListener {
            Prefs.clearLog(this)
            showLog()
        }

        // Opens Android's Accessibility settings, where you turn the service on.
        findViewById<Button>(R.id.openAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Opens this app's "App info" page (needed for "Allow restricted settings").
        findViewById<Button>(R.id.openAppInfo).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null))
            )
        }
    }

    // onResume runs every time you come back to this screen (for example after
    // visiting Accessibility settings), so the status line is always current.
    override fun onResume() {
        super.onResume()
        status.setText(if (isServiceEnabled()) R.string.status_on else R.string.status_off)
        showLog()
    }

    private fun showLog() {
        val log = Prefs.getLog(this)
        activityLog.text = log.ifBlank { getString(R.string.no_activity) }
    }

    private fun shareInfo() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "?" }
        val text = buildString {
            append("Shorts Blocker ").append(version)
            append(" on ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            append(", Android ").append(Build.VERSION.RELEASE).append("\n\n")
            append("Recent activity:\n").append(Prefs.getLog(this@MainActivity).ifBlank { "(none)" }).append("\n\n")
            append(Prefs.readScreenInfo(this@MainActivity) ?: getString(R.string.no_screen_info))
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Shorts Blocker screen info")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_info)))
    }

    /** Asks Android whether our accessibility service is switched on. */
    private fun isServiceEnabled(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any {
                it.resolveInfo.serviceInfo.packageName == packageName &&
                        it.resolveInfo.serviceInfo.name == ShortsBlockerService::class.java.name
            }
    }
}
