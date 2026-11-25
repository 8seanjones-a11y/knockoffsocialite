package com.example.shortsblocker

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's settings (the switches on the main screen), saved on the phone
 * with SharedPreferences. Both the main screen and the service read them here.
 */
object Prefs {
    private const val FILE = "settings"
    private const val KEY_BLOCKING = "blocking"
    private const val KEY_BLOCK_SHORTS = "block_shorts"
    private const val KEY_BLOCK_HOME = "block_home"
    private const val KEY_DEBUG = "debug"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun get(context: Context, key: String, default: Boolean) =
        prefs(context).getBoolean(key, default)

    private fun set(context: Context, key: String, on: Boolean) =
        prefs(context).edit().putBoolean(key, on).apply()

    /** Main on/off switch. When off, nothing is blocked. On by default. */
    fun isBlockingOn(c: Context) = get(c, KEY_BLOCKING, true)
    fun setBlockingOn(c: Context, on: Boolean) = set(c, KEY_BLOCKING, on)

    /** Cover the Shorts tab and Shorts rows, and close the Shorts player. On by default. */
    fun isBlockShortsOn(c: Context) = get(c, KEY_BLOCK_SHORTS, true)
    fun setBlockShortsOn(c: Context, on: Boolean) = set(c, KEY_BLOCK_SHORTS, on)

    /** Cover the Home feed (recommendations). Off by default. */
    fun isBlockHomeOn(c: Context) = get(c, KEY_BLOCK_HOME, false)
    fun setBlockHomeOn(c: Context, on: Boolean) = set(c, KEY_BLOCK_HOME, on)

    /** Debug mode: save screen info (and log to Logcat). Off by default. */
    fun isDebugOn(c: Context) = get(c, KEY_DEBUG, false)
    fun setDebugOn(c: Context, on: Boolean) = set(c, KEY_DEBUG, on)

    // -----------------------------------------------------------------
    // Recent activity: the last few things the blocker did, shown on the
    // main screen so you can see (and share) what happened without a PC.
    // -----------------------------------------------------------------

    private const val KEY_LOG = "activity_log"
    private const val LOG_LINES = 30

    fun addLog(c: Context, line: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val old = getLog(c).lines().filter { it.isNotBlank() }.takeLast(LOG_LINES - 1)
        prefs(c).edit().putString(KEY_LOG, (old + "$time  $line").joinToString("\n")).apply()
    }

    fun getLog(c: Context): String = prefs(c).getString(KEY_LOG, "") ?: ""

    fun clearLog(c: Context) = prefs(c).edit().remove(KEY_LOG).apply()

    // -----------------------------------------------------------------
    // Screen info: in debug mode the service saves a description of the last
    // YouTube screen here, so it can be shared from the main screen.
    // -----------------------------------------------------------------

    private const val SCREEN_FILE = "screen_info.txt"

    fun saveScreenInfo(c: Context, text: String) {
        try { File(c.filesDir, SCREEN_FILE).writeText(text) } catch (_: Exception) {}
    }

    fun readScreenInfo(c: Context): String? = try {
        File(c.filesDir, SCREEN_FILE).takeIf { it.exists() }?.readText()
    } catch (_: Exception) { null }
}
