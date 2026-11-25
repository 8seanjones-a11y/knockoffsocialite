package com.example.shortsblocker

import android.content.Context

/**
 * The app's two settings (the switches on the main screen), saved on the phone
 * with SharedPreferences. Both the main screen and the service read them here.
 */
object Prefs {
    private const val FILE = "settings"
    private const val KEY_BLOCKING = "blocking"
    private const val KEY_DEBUG = "debug"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** "Block Shorts" switch. On by default. */
    fun isBlockingOn(context: Context) = prefs(context).getBoolean(KEY_BLOCKING, true)
    fun setBlockingOn(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_BLOCKING, on).apply()

    /** "Debug logging" switch. Off by default. */
    fun isDebugOn(context: Context) = prefs(context).getBoolean(KEY_DEBUG, false)
    fun setDebugOn(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_DEBUG, on).apply()
}
