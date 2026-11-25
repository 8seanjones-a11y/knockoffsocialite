package com.example.shortsblocker

import android.content.Context

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

    /** Block the Shorts player and Shorts tab. On by default. */
    fun isBlockShortsOn(c: Context) = get(c, KEY_BLOCK_SHORTS, true)
    fun setBlockShortsOn(c: Context, on: Boolean) = set(c, KEY_BLOCK_SHORTS, on)

    /** Block the Home feed (recommendations) by switching to Subscriptions. Off by default. */
    fun isBlockHomeOn(c: Context) = get(c, KEY_BLOCK_HOME, false)
    fun setBlockHomeOn(c: Context, on: Boolean) = set(c, KEY_BLOCK_HOME, on)

    /** Debug logging to Logcat. Off by default. */
    fun isDebugOn(c: Context) = get(c, KEY_DEBUG, false)
    fun setDebugOn(c: Context, on: Boolean) = set(c, KEY_DEBUG, on)
}
