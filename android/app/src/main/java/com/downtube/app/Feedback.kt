package com.downtube.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat

/**
 * Small audio/haptic feedback so the user knows what happened without looking
 * at the screen. Both cues are switchable in the app.
 */
object Feedback {

    private var ringtone: Ringtone? = null

    fun success(context: Context) {
        playNotificationSound(context)
    }

    fun error(context: Context) {
        playNotificationSound(context)
        vibrate(context, longArrayOf(0, 90, 70, 90))
    }

    fun tick(context: Context) {
        vibrate(context, longArrayOf(0, 35))
    }

    private fun playNotificationSound(context: Context) {
        if (!Prefs.soundsEnabled(context)) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
        ringtone?.let { if (it.isPlaying) it.stop() }
        ringtone = RingtoneManager.getRingtone(context.applicationContext, uri)
        ringtone?.play()
    }

    private fun vibrate(context: Context, pattern: LongArray) {
        if (!Prefs.vibrateEnabled(context)) return
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.VIBRATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
}
