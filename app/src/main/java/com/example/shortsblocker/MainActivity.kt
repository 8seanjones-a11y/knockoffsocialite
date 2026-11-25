package com.example.shortsblocker

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.Switch
import android.widget.TextView

/**
 * The screen you see when you open the app: two switches, two buttons and a
 * status line. All the blocking happens in ShortsBlockerService.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)

        // "Block Shorts" switch: saved setting, read by the service.
        val blockingSwitch = findViewById<Switch>(R.id.blockingSwitch)
        blockingSwitch.isChecked = Prefs.isBlockingOn(this)
        blockingSwitch.setOnCheckedChangeListener { _, on -> Prefs.setBlockingOn(this, on) }

        // "Debug logging" switch.
        val debugSwitch = findViewById<Switch>(R.id.debugSwitch)
        debugSwitch.isChecked = Prefs.isDebugOn(this)
        debugSwitch.setOnCheckedChangeListener { _, on -> Prefs.setDebugOn(this, on) }

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
