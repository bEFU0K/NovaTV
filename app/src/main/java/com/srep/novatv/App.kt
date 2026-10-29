package com.srep.novatv

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        AppLog.init(this)
        SessionStore.load()
        NetMonitor.start(this)
        VpnMonitor.start(this)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { scope.launch { AppRepo.reload(applicationContext); Protection.enforce(applicationContext) } }
        }, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch { Protection.enforce(applicationContext) }
    }
}
