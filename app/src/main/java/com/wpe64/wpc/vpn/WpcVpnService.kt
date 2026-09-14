package com.wpe64.wpc.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.wpe64.wpc.MainActivity
import com.wpe64.wpc.R
import com.wpe64.wpc.WpcApp
import com.wpe64.wpc.service.ProxyService
import kotlinx.coroutines.CompletableDeferred

/**
 * VPN 服务：建隧道、在进程里起内核、挂前台通知。连接逻辑都在 ProxyService，这里只管系统那一层。
 *
 * 【隧道参数】必须与 assets/base-android.yaml 的 tun 段一致：mtu 9000、172.19.0.1/30、fdfe:dcba:9876::1/126。
 * 路由接管 IPv4 与 IPv6 全部流量；DNS 指向隧道里的 172.19.0.2，由内核的 dns-hijack 接住。
 *
 * 【应用范围】
 *   全部应用 → addDisallowedApplication(本应用)：本应用发出的连接（到 WPE 的控制通道、SOCKS5 数据连接、DNS）不进隧道，
 *              所以不需要逐个套接字 protect，也不会自己绕回自己；
 *   仅选中应用 → addAllowedApplication(每个选中的)：本应用不在名单里，同样不进隧道。两种不能混用。
 */
class WpcVpnService : VpnService() {

    class StartRequest(
        val config: String,
        val appProxyMode: String,
        val appProxyPackages: List<String>,
        val serverName: String,
        val result: CompletableDeferred<Boolean>,
    )

    companion object {
        const val ACTION_START = "com.wpe64.wpc.action.START"
        const val ACTION_STOP = "com.wpe64.wpc.action.STOP"

        private const val CHANNEL = "vpn"
        private const val NOTIF_ID = 1

        const val MTU = 9000
        const val TUN_V4 = "172.19.0.1"
        const val TUN_V4_PREFIX = 30
        const val TUN_V6 = "fdfe:dcba:9876::1"
        const val TUN_V6_PREFIX = 126
        const val TUN_DNS = "172.19.0.2"

        @Volatile var instance: WpcVpnService? = null
            private set

        /** ProxyService 把这次要起的配置放在这里，再 startForegroundService；服务取走后在 result 里报结果。 */
        @Volatile var pending: StartRequest? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val svc: ProxyService get() = (application as WpcApp).service
    private val tunLock = Any()
    private var tun: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                svc.disconnectFromNotification()
            }

            ACTION_START -> {
                // Android 要求 startForegroundService 之后 5 秒内挂上通知，先挂「正在连接」
                goForeground(getString(R.string.notif_connecting))
                val req = pending
                pending = null
                if (req == null) {
                    stopTunnel()
                } else {
                    Thread({
                        val ok = establishAndStart(req)
                        req.result.complete(ok)
                        if (!ok) stopTunnel()
                    }, "wpc-vpn-start").start()
                }
            }

            else -> {
                // 系统的「始终开启 VPN」拉起（或服务被系统重建）：没有界面，用保存的账号在后台连
                goForeground(getString(R.string.notif_connecting))
                svc.connectInBackground()
            }
        }
        return START_NOT_STICKY
    }

    private fun establishAndStart(req: StartRequest): Boolean {
        return try {
            val b = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(MTU)
                .addAddress(TUN_V4, TUN_V4_PREFIX)
                .addRoute("0.0.0.0", 0)
                .addAddress(TUN_V6, TUN_V6_PREFIX)
                .addRoute("::", 0)
                .addDnsServer(TUN_DNS)
                .setConfigureIntent(openAppIntent())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) b.setMetered(false)
            applyAppScope(b, req)

            val pfd = b.establish()
            if (pfd == null) {
                svc.log(ProxyService.LogType.Error, "WpcVpnService", "系统没有建立隧道（VPN 授权可能已被收回）")
                return false
            }

            synchronized(tunLock) {
                tun?.close()
                tun = pfd
            }

            svc.kernel.start(req.config, pfd.fd)
            showConnected(req.serverName)
            true
        } catch (e: Exception) {
            svc.log(ProxyService.LogType.Error, "WpcVpnService", e.message ?: e.javaClass.simpleName)
            false
        }
    }

    private fun applyAppScope(b: Builder, req: StartRequest) {
        if (req.appProxyMode == "selected" && req.appProxyPackages.isNotEmpty()) {
            var added = 0
            for (pkg in req.appProxyPackages) {
                if (pkg == packageName) continue
                try {
                    b.addAllowedApplication(pkg)
                    added++
                } catch (_: PackageManager.NameNotFoundException) {
                    // 选过的应用已经卸载了
                }
            }
            if (added > 0) {
                svc.log(ProxyService.LogType.Debug, "WpcVpnService", "分应用代理：只有选中的 $added 个应用走代理")
                return
            }
            svc.log(ProxyService.LogType.Warning, "WpcVpnService", "选中的应用都已卸载，本次按「全部应用」连接")
        }
        b.addDisallowedApplication(packageName)
    }

    /** 控制通道重连后换令牌：不重建隧道，只让内核换配置。 */
    fun reload(config: String): Boolean {
        val fd = synchronized(tunLock) { tun?.fd } ?: return false
        return try {
            svc.kernel.reload(config, fd)
            true
        } catch (e: Exception) {
            svc.log(ProxyService.LogType.Error, "WpcVpnService", "内核换配置失败：${e.message}")
            false
        }
    }

    /** 停内核、关隧道、摘通知、结束服务。可以从任何线程调。 */
    fun stopTunnel() {
        runCatching { svc.kernel.stop() }
        synchronized(tunLock) {
            runCatching { tun?.close() }
            tun = null
        }
        main.post {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onRevoke() {
        // 用户在系统设置里关了 VPN，或者别的 VPN 应用顶掉了我们
        stopTunnel()
        svc.onVpnRevoked()
    }

    override fun onDestroy() {
        synchronized(tunLock) {
            if (tun != null) {
                runCatching { svc.kernel.stop() }
                runCatching { tun?.close() }
                tun = null
            }
        }
        instance = null
        super.onDestroy()
    }

    // ———————————————— 通知 ————————————————

    fun showConnected(serverName: String) = main.post { notifyText(getString(R.string.notif_connected) + if (serverName.isNotEmpty()) " · $serverName" else "") }

    fun showReconnecting() = main.post { notifyText(getString(R.string.notif_reconnecting)) }

    private fun goForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), type)
    }

    private fun notifyText(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, WpcVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.notif_disconnect), stop)
            .build()
    }
}
