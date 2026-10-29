package com.srep.novatv

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger

object Sys {
    /** Открывает системный экран настроек; если его нет на устройстве — общие настройки + понятное сообщение. */
    fun open(c: Context, action: String, pkg: String? = null): Boolean {
        return try {
            val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pkg != null) i.data = Uri.parse("package:$pkg")
            c.startActivity(i)
            true
        } catch (e: Exception) {
            AppLog.w("sys", "open $action", e)
            try {
                c.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Toast.makeText(c, "Этот раздел недоступен на устройстве — открыты общие настройки", Toast.LENGTH_LONG).show()
            } catch (e2: Exception) {
                Toast.makeText(c, "Системные настройки недоступны на этом устройстве", Toast.LENGTH_LONG).show()
            }
            false
        }
    }
}

data class NetState(val online: Boolean = false, val type: String = "Нет сети")

object NetMonitor {
    val state = MutableStateFlow(NetState())

    fun start(c: Context) {
        try {
            val cm = c.getSystemService(ConnectivityManager::class.java)
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                    val t = when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Мобильная сеть"
                        else -> "Сеть"
                    }
                    state.value = NetState(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), t)
                }

                override fun onLost(n: Network) { state.value = NetState(false, "Нет сети") }
            })
        } catch (e: Exception) {
            AppLog.w("net", "register", e)
        }
    }
}

/** Статус системного VPN через ConnectivityManager. Сам VPN-туннель создаёт сторонний VPN-клиент. */
object VpnMonitor {
    val active = MutableStateFlow(false)
    val since = MutableStateFlow(0L)
    private val count = AtomicInteger(0)
    private var cm: ConnectivityManager? = null

    private fun set(on: Boolean) {
        if (on && !active.value) since.value = System.currentTimeMillis()
        if (!on) since.value = 0L
        active.value = on
    }

    fun start(c: Context) {
        try {
            val m = c.getSystemService(ConnectivityManager::class.java)
            cm = m
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            m.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { set(count.incrementAndGet() > 0) }
                override fun onLost(network: Network) { set(count.updateAndGet { maxOf(0, it - 1) } > 0) }
            })
        } catch (e: Exception) {
            AppLog.w("vpn", "register", e)
        }
    }

    /** Ручная перепроверка (кнопка «Проверить снова»). */
    @Suppress("DEPRECATION")
    fun recheck() {
        val m = cm ?: return
        val on = try {
            m.allNetworks.any { m.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        } catch (e: Exception) { false }
        set(on)
    }
}

object DeviceInfo {
    fun lines(c: Context): List<Pair<String, String>> {
        val am = c.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val st = StatFs(Environment.getDataDirectory().path)
        val dm = c.resources.displayMetrics
        fun sz(n: Long) = Formatter.formatFileSize(c, n)
        return listOf(
            "Модель" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Устройство / плата" to "${Build.DEVICE} / ${Build.BOARD}",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Патч безопасности" to Build.VERSION.SECURITY_PATCH,
            "Архитектуры (ABI)" to Build.SUPPORTED_ABIS.joinToString(", "),
            "Ядер CPU" to Runtime.getRuntime().availableProcessors().toString(),
            "ОЗУ" to "${sz(mi.totalMem)} (свободно ${sz(mi.availMem)})",
            "Хранилище" to "${sz(st.totalBytes)} (свободно ${sz(st.availableBytes)})",
            "Экран" to "${dm.widthPixels}×${dm.heightPixels}, ${dm.densityDpi} dpi",
            "Время работы" to Util.duration(SystemClock.elapsedRealtime()),
            "Nova TV OS" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "Сборка системы" to Build.FINGERPRINT
        )
    }
}
