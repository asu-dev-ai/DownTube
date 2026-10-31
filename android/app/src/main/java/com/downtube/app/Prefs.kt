package com.downtube.app

import android.content.Context
import android.content.SharedPreferences

object Prefs {

    private const val NAME = "settings"
    private const val KEY_SOUNDS = "sounds"
    private const val KEY_VIBRATE = "vibrate"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun soundsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SOUNDS, true)

    fun vibrateEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VIBRATE, true)

    fun setSoundsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SOUNDS, enabled).apply()
    }

    fun setVibrateEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_VIBRATE, enabled).apply()
    }
}
