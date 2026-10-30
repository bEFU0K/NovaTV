package com.srep.novatv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.SystemClock
import android.provider.Settings

/** Звук при запуске приставки: один раз за включение (файл res/raw/startup.mp3). */
object StartupSound {
    @Synchronized
    fun playOnce(c: Context) {
        try {
            val el = SystemClock.elapsedRealtime()
            if (el > 5 * 60_000L) return // приложение открыли не сразу после включения
            val boot = Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, -1)
            val fresh = if (boot >= 0) boot != Prefs.soundBoot else el < Prefs.soundAt
            if (!fresh) return
            Prefs.soundBoot = boot
            Prefs.soundAt = el
            val mp = MediaPlayer.create(c, R.raw.startup) ?: return
            mp.setOnCompletionListener { it.release() }
            mp.setOnErrorListener { p, _, _ -> p.release(); true }
            mp.start()
        } catch (e: Throwable) {
            AppLog.w("sound", "startup", e)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) StartupSound.playOnce(c.applicationContext)
    }
}
