package com.wpe64.wpc.service

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import com.wpe64.wpc.BuildConfig
import com.wpe64.wpc.core.ConfigBuilder
import com.wpe64.wpc.core.Net
import com.wpe64.wpc.core.NoticeInfo
import com.wpe64.wpc.core.ServerInfo
import com.wpe64.wpc.core.WpcApi
import com.wpe64.wpc.core.WpcControlClient
import com.wpe64.wpc.core.WpcControlClient.RegisterCode
import com.wpe64.wpc.vpn.WpcVpnService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * 逻辑层 —— Windows 版 App/Services/ProxyService.cs 的 Android 继任者。零界面依赖，只经 push(事件, 数据) 与返回值交互。
 *
 * 连接链（与 Windows 版同一顺序）：
 *   VPN 授权 → 测延迟 → 控制通道注册（拿令牌；老 WPE / 没开认证 → 普通模式）→ 生成配置（筛规则、内核兜底验）
 *   → 起 VPN 服务（建隧道 + 进程内起内核）→ 监控（每秒推一次网速 / 内存 / 延迟 / 在线时长）
 *
 * 与 Windows 版不同的地方：
 *   · 内核在进程里（core/wpccore.go），不是子进程；流量与内存直接读内核统计；
 *   · 控制连接断了先<b>静默重注册</b>（手机切网、短暂冻结都会断），拿到新令牌后热换配置，不重建隧道；
 *     重试都失败才断开 —— Windows 版是直接断开；
 *   · 多了 VPN 授权、分应用代理、电池优化、「始终开启 VPN」时的后台连接。
 */
class ProxyService(private val context: Context, val kernel: Kernel = MihomoKernel) {

    enum class LogType(val value: Int) { Info(0), Warning(1), Error(2), Debug(3) }

    companion object {
        private const val TIMEOUT = 5000
        /** 节点测速只看通不通、快不快：3 秒还连不上就算不通，免得一个死节点拖住整页 */
        private const val DELAY_TIMEOUT = 3000
        private const val HEALTH_SECONDS = 3 * 3600.0
        private val RE_REGISTER_WAITS = longArrayOf(0L, 2_000L, 5_000L, 10_000L, 20_000L)
    }

    val store = ConfigStore(context)
    private val cfg get() = store.config
    private val api = WpcApi(ApiKey.get())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ———————————————— 与界面的连线（MainActivity 挂上 / 摘下）————————————————

    @Volatile private var push: ((String, Any?) -> Unit)? = null
    @Volatile private var vpnConsent: (suspend (Intent) -> Boolean)? = null
    @Volatile private var appearance: ((Boolean) -> Unit)? = null

    fun attach(push: (String, Any?) -> Unit, vpnConsent: suspend (Intent) -> Boolean, appearance: (Boolean) -> Unit) {
        this.push = push
        this.vpnConsent = vpnConsent
        this.appearance = appearance
    }

    fun detach() {
        push = null
        vpnConsent = null
        appearance = null
    }

    private fun emit(name: String, data: Any?) {
        push?.invoke(name, data)
    }

    // ———————————————— 状态 ————————————————

    @Volatile private var servers: List<ServerInfo> = emptyList()
    @Volatile private var selected: ServerInfo? = null
    @Volatile var isConnected = false
        private set
    @Volatile private var control: WpcControlClient? = null

    private data class Credentials(val user: String, val pass: String)

    @Volatile private var credentials: Credentials? = null
    @Volatile private var lastDelay = -1L
    private var monitorJob: Job? = null
    private val connectMutex = Mutex()
    private val reRegisterMutex = Mutex()
    private val template: String by lazy { context.assets.open("base-android.yaml").bufferedReader().use { it.readText() } }
    private var appsCache: JSONArray? = null

    val version: String = BuildConfig.VERSION_NAME

    init {
        kernel.setHomeDir(File(context.filesDir, "mihomo").apply { mkdirs() }.absolutePath)
        kernel.setLogSink { level, message -> if (message.isNotEmpty()) log(mihomoLevel(level), "Mihomo", message) }
        // 今日累计在线的初值在「实时监控」那一段的属性声明里算（Kotlin 的 init 块不能给写在它后面的属性赋值）
    }

    private fun mihomoLevel(level: String): LogType = when (level.lowercase(Locale.ROOT)) {
        "info" -> LogType.Info
        "warning", "warn" -> LogType.Warning
        "debug" -> LogType.Debug
        else -> LogType.Error
    }

    // ———————————————— 日志 ————————————————

    fun log(type: LogType, name: String, content: String) {
        if (name.isEmpty() || content.isEmpty()) return
        emit(
            "log",
            JSONObject()
                .put("type", type.value)
                .put("logTime", isoNow())
                .put("logName", name)
                .put("logContent", content),
        )
    }

    private fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())

    // ———————————————— 只读查询 ————————————————

    fun stateJson(): JSONObject = JSONObject()
        .put("version", version)
        .put("isConnected", isConnected)
        .put("subscriberName", cfg.subscriberName)
        .put("subscriberTime", if (cfg.subscriberTime.isEmpty()) JSONObject.NULL else cfg.subscriberTime)
        .put("selectedServerId", selected?.serverId ?: JSONObject.NULL)
        .put("userName", cfg.userName)
        .put("password", if (cfg.rememberAccount) cfg.passWord else "")
        .put("rememberAccount", cfg.rememberAccount)
        .put("language", cfg.language)
        .put("themeMode", cfg.themeMode)
        .put("isDark", cfg.isDark)
        .put("scanLine", cfg.scanLine)

    private fun serverJson(s: ServerInfo): JSONObject = JSONObject()
        .put("serverId", s.serverId)
        .put("serverName", s.serverName)
        .put("serverIP", s.serverIP)
        .put("serverPort", s.serverPort)
        .put("forgotURL", s.forgotURL)
        .put("registerURL", s.registerURL)
        .put("verifyURL", s.verifyURL)
        .put("phoneRules", ConfigBuilder.phoneSummary(s.rules).let { p ->
            JSONObject().put("total", p.total).put("kept", p.kept).put("skipped", p.skipped).put("proxy", p.proxy)
        })

    private fun noticeJson(n: NoticeInfo): JSONObject = JSONObject()
        .put("noticeType", n.noticeType)
        .put("noticeTitle", n.noticeTitle)
        .put("noticeContent", n.noticeContent)
        .put("noticeMore", n.noticeMore)
        .put("noticeTime", n.noticeTime)

    /** 订阅服务器（官网主站）状态：online / slow / lag / offline。与 Windows 版分档一致。 */
    suspend fun checkWpeServer(): String = withContext(Dispatchers.IO) {
        val d = Net.tcpDelay(WpcApi.WPE_SITE_HOST, WpcApi.WPE_SITE_PORT, TIMEOUT)
        when {
            d < 0 -> "offline"
            d <= 100 -> "online"
            d <= 200 -> "slow"
            else -> "lag"
        }
    }

    suspend fun checkSubscriberServer(): Long = withContext(Dispatchers.IO) {
        if (cfg.subscriberIP.isEmpty() || cfg.subscriberPort == 0) -1L else Net.tcpDelay(cfg.subscriberIP, cfg.subscriberPort, TIMEOUT)
    }

    suspend fun getServers(): JSONArray = withContext(Dispatchers.IO) {
        val list = if (cfg.subscriberIP.isNotEmpty() && cfg.subscriberPort > 0) {
            try {
                api.serverList(cfg.subscriberIP, cfg.subscriberPort)
            } catch (e: Exception) {
                log(LogType.Error, "getServers", e.message ?: e.javaClass.simpleName)
                emptyList()
            }
        } else {
            emptyList()
        }

        servers = list
        // 刷新前选中的节点还在就保持（没有的话看上次保存的），否则选第一个
        val prev = selected?.serverId ?: cfg.selectedServerId
        selected = list.firstOrNull { it.serverId == prev } ?: list.firstOrNull()

        JSONArray().apply { list.forEach { put(serverJson(it)) } }
    }

    /** 各节点的 TCP 延迟（ms，-1 = 不通），并行测。节点页「重新测速」与每次刷新订阅之后调用。 */
    suspend fun testServerDelays(): JSONArray = withContext(Dispatchers.IO) {
        val list = servers
        val delays = coroutineScope {
            list.map { s -> async { s.serverId to Net.tcpDelay(s.serverIP, s.serverPort, DELAY_TIMEOUT) } }.awaitAll()
        }
        JSONArray().apply { delays.forEach { (id, d) -> put(JSONObject().put("serverId", id).put("delay", d)) } }
    }

    suspend fun getNotices(): JSONArray = withContext(Dispatchers.IO) {
        val out = JSONArray()
        try {
            api.globalNotices().forEach { out.put(noticeJson(it)) }
        } catch (e: Exception) {
            log(LogType.Error, "System", "无法获取WPC公告信息")
            log(LogType.Debug, "getNotices", e.message ?: "")
        }
        if (cfg.subscriberIP.isNotEmpty() && cfg.subscriberPort > 0) {
            try {
                api.serverNotices(cfg.subscriberIP, cfg.subscriberPort).forEach { out.put(noticeJson(it)) }
            } catch (e: Exception) {
                log(LogType.Error, "System", "无法获取订阅者公告信息")
                log(LogType.Debug, "getNotices", e.message ?: "")
            }
        }
        out
    }

    fun kernelVersion(): String = runCatching { kernel.version() }.getOrDefault("")

    fun osVersion(): String {
        val wv = webViewMajor()
        return "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}" + if (wv > 0) " · WebView $wv" else ""
    }

    /** 报给 WPE 的系统标签（控制通道 Register 的 os 字段），WPE 客户端列表显示成「WPC 1.1 · Android 14」。 */
    private fun osLabel(): String = "Android ${Build.VERSION.RELEASE}"

    fun webViewMajor(): Int = runCatching {
        WebViewCompat.getCurrentWebViewPackage(context)?.versionName?.substringBefore('.')?.toIntOrNull() ?: 0
    }.getOrDefault(0)

    fun readAgreement(type: String): String {
        val file = if (type == "PrivacyPolicy") "agreements/PrivacyPolicy.txt" else "agreements/UserAgreement.txt"
        return runCatching { context.assets.open(file).bufferedReader(Charsets.UTF_8).use { it.readText() } }.getOrDefault("")
    }

    // ———————————————— 界面偏好 ————————————————

    fun setLanguage(language: String): Boolean {
        if (language.isBlank()) return false
        cfg.language = language
        store.save()
        return true
    }

    /** 稀疏报文：出现哪一项才改哪一项（与 Windows 版 SetAppearance 同一条协议）。 */
    fun setAppearance(mode: String?, isDark: Boolean?, scan: Boolean?): Boolean {
        if (!mode.isNullOrEmpty()) cfg.themeMode = mode
        if (isDark != null) cfg.isDark = isDark
        if (scan != null) cfg.scanLine = scan
        store.save()
        appearance?.invoke(cfg.isDark)
        return true
    }

    // ———————————————— 订阅 / 账号 / 节点 ————————————————

    suspend fun setSubscriber(name0: String): String = withContext(Dispatchers.IO) {
        val name = name0.trim()
        if (name.isEmpty()) {
            log(LogType.Warning, "System", "请输入订阅号")
            return@withContext "empty"
        }

        when (val r = api.subscribe(name)) {
            is WpcApi.SubscribeOutcome.Ok -> {
                cfg.subscriberName = name
                cfg.subscriberIP = r.info.ipAddress
                cfg.subscriberPort = r.info.port
                cfg.subscriberTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                store.save()
                log(LogType.Debug, "System", "更新订阅成功，已自动保存订阅地址")
                "ok"
            }
            WpcApi.SubscribeOutcome.Invalid -> {
                log(LogType.Warning, "System", "订阅号不存在或已过期")
                "invalid"
            }
            is WpcApi.SubscribeOutcome.Network -> {
                log(LogType.Error, "System", "无法连接订阅服务器")
                log(LogType.Debug, "setSubscriber", r.detail)
                "network"
            }
        }
    }

    /**
     * 账号记忆。Windows 版攒到关窗时才落盘；手机上进程随时可能被系统杀掉，所以立刻落盘。
     * 勾选「记住我」且账号密码都不为空才存，否则清掉已存的。
     */
    fun updateAccount(username: String, password: String, remember: Boolean): Boolean {
        cfg.rememberAccount = remember
        if (remember && username.isNotEmpty() && password.isNotEmpty()) {
            cfg.userName = username
            cfg.passWord = password
        } else if (!remember) {
            cfg.userName = ""
            cfg.passWord = ""
        }
        store.save()
        return remember
    }

    fun selectServer(serverId: String): Boolean {
        val s = servers.firstOrNull { it.serverId == serverId } ?: return false
        selected = s
        cfg.selectedServerId = s.serverId
        store.save()
        return true
    }

    // ———————————————— 连接 ————————————————

    suspend fun connect(username0: String, password0: String): Boolean = withContext(Dispatchers.IO) {
        connectMutex.withLock {
            val server = selected
            if (server == null) {
                log(LogType.Error, "System", "无法获取服务器地址，请检查订阅设置是否正确")
                return@withLock false
            }

            // 记住我场景：界面传空则回退到已存凭据
            var username = username0
            var password = password0
            if (username.isEmpty() && cfg.userName.isNotEmpty()) username = cfg.userName
            if (password.isEmpty() && cfg.rememberAccount && cfg.passWord.isNotEmpty()) password = cfg.passWord

            // ① VPN 授权（第一次连接时系统会弹框问用户）
            if (!ensureVpnConsent()) {
                emit("connectError", JSONObject().put("reason", "vpn"))
                log(LogType.Error, "System", "没有授予 VPN 权限，无法连接")
                return@withLock false
            }

            lastDelay = Net.tcpDelay(server.serverIP, server.serverPort, TIMEOUT)

            // ② 控制通道：新版 WPE（认证已开）→ 注册拿令牌；老版 / 没开认证 → 普通模式
            closeControl()
            var socksPassword = password
            val (mode, client) = WpcControlClient.negotiate(server.serverIP, server.serverPort, TIMEOUT, { s -> log(LogType.Debug, "WpcControlClient", s) })

            when (mode) {
                WpcControlClient.Mode.Control -> {
                    val c = client!!
                    val r = c.register(username, password, DeviceFingerprint.id(context), version, osLabel(), TIMEOUT)
                    if (r.code != RegisterCode.OK) {
                        c.close()
                        log(LogType.Error, "System", registerMessage(r.code))
                        return@withLock false
                    }
                    socksPassword = r.token
                    c.onClosed = { reason -> onControlClosed(c, reason) }
                    c.startKeepAlive()
                    control = c
                    log(LogType.Debug, "System", "设备已注册到服务器（设备标识 ${DeviceFingerprint.id(context).take(8)}…）")
                }
                WpcControlClient.Mode.Legacy -> log(LogType.Debug, "System", "服务器不支持设备注册，按普通模式连接")
                WpcControlClient.Mode.Unreachable -> {
                    log(LogType.Error, "System", "无法连接代理服务器，请检查网络或稍后再试")
                    return@withLock false
                }
            }

            credentials = Credentials(username, password)

            // ③ 配置
            val config = buildConfig(server, username, socksPassword)
            if (config == null) {
                closeControl()
                return@withLock false
            }

            // ④ VPN 服务：建隧道 + 起内核
            if (!startVpn(config, server.serverName)) {
                closeControl()
                log(LogType.Error, "System", "无法建立 VPN 隧道或启动内核，请查看系统日志")
                return@withLock false
            }

            isConnected = true
            startMonitoring()
            emit("connected", stateJson())
            log(LogType.Debug, "System", "已成功登录 - ${server.serverName}")
            true
        }
    }

    private suspend fun ensureVpnConsent(): Boolean {
        val intent = VpnService.prepare(context) ?: return true
        val ask = vpnConsent ?: return false
        return ask(intent)
    }

    private fun buildConfig(server: ServerInfo, user: String, pass: String): String? {
        val input = ConfigBuilder.Input(server.serverIP, server.serverPort, user, pass)

        val formatted = ConfigBuilder.formatRules(server.rules)
        formatted.skipped.forEach { log(LogType.Warning, "System", "已跳过一条规则：${it.rule}（${it.reason}）") }

        val checked = ConfigBuilder.filterWithKernel(template, input, formatted.lines) { kernel.validate(it) }
        checked.skipped.forEach { log(LogType.Warning, "System", "内核不认这条规则，已跳过：${it.rule}（${it.reason}）") }

        // 一条走代理的规则都没留下：连上也不会有流量经过 WPE（常见于只按进程名写规则的节点）。不拦连接，只提醒
        if (ConfigBuilder.proxyLineCount(checked.lines) == 0) {
            log(LogType.Warning, "System", "节点「${server.serverName}」在手机上没有走代理的规则，连接后所有流量都会直连，不经过 WPE")
            emit("rulesNoProxy", JSONObject().put("serverName", server.serverName))
        }

        val yaml = ConfigBuilder.build(template, input, checked.lines)
        val err = kernel.validate(yaml)
        if (err.isNotEmpty()) {
            log(LogType.Error, "Mihomo", "配置无效：$err")
            return null
        }
        return yaml
    }

    private suspend fun startVpn(config: String, serverName: String): Boolean {
        val result = CompletableDeferred<Boolean>()
        WpcVpnService.pending = WpcVpnService.StartRequest(config, cfg.appProxyMode, cfg.appProxyPackages, serverName, result)

        val intent = Intent(context, WpcVpnService::class.java).setAction(WpcVpnService.ACTION_START)
        try {
            withContext(Dispatchers.Main) { ContextCompat.startForegroundService(context, intent) }
        } catch (e: Exception) {
            WpcVpnService.pending = null
            log(LogType.Error, "WpcVpnService", e.message ?: e.javaClass.simpleName)
            return false
        }
        return withTimeoutOrNull(20_000) { result.await() } ?: false
    }

    suspend fun disconnect(): Boolean = withContext(Dispatchers.IO) {
        stopMonitoring()
        // 先断控制连接：WPE 那边设备立刻下线、令牌作废，再停内核
        closeControl()
        credentials = null
        stopVpn()
        markDisconnected()
        log(LogType.Debug, "System", "已断开连接 - ${selected?.serverName ?: ""}")
        true
    }

    private fun stopVpn() {
        val svc = WpcVpnService.instance
        if (svc != null) svc.stopTunnel() else runCatching { kernel.stop() }
    }

    /** 通知栏的「断开」。 */
    fun disconnectFromNotification() {
        scope.launch { disconnect() }
    }

    /** 系统收回了 VPN（用户在系统设置里关掉、或者别的 VPN 应用顶掉了）。隧道那边服务自己已经在收尾。 */
    fun onVpnRevoked() {
        scope.launch {
            stopMonitoring()
            closeControl()
            credentials = null
            markDisconnected()
            log(LogType.Warning, "System", "VPN 已被系统或其它应用关闭，连接已断开")
        }
    }

    /** 系统「始终开启 VPN」拉起服务时没有界面：用保存的账号与节点在后台连。 */
    fun connectInBackground() {
        scope.launch {
            if (isConnected) return@launch
            if (!cfg.rememberAccount || cfg.userName.isEmpty() || cfg.passWord.isEmpty() || cfg.subscriberIP.isEmpty()) {
                log(LogType.Warning, "System", "始终开启 VPN：没有保存的账号或订阅，无法在后台连接")
                WpcVpnService.instance?.stopTunnel()
                return@launch
            }
            getServers()
            if (!connect(cfg.userName, cfg.passWord)) WpcVpnService.instance?.stopTunnel()
        }
    }

    private fun closeControl() {
        val c = control
        control = null
        c?.close()
    }

    /**
     * 控制连接意外断开。Windows 版在这里直接断开；手机上切网、短暂冻结都会断，所以先静默重注册：
     * 同一台设备重注册，WPE 会换新令牌并踢掉旧连接（不占新的设备槽）；新令牌热换进内核，不重建隧道。
     * 账号被拒（密码改了、过期、禁用、设备满）不再重试。
     */
    private fun onControlClosed(sender: WpcControlClient, reason: String) {
        if (control !== sender) return
        control = null
        log(LogType.Warning, "System", "与服务器的控制连接已断开：$reason，正在重新注册…")
        WpcVpnService.instance?.showReconnecting()
        scope.launch { reRegister() }
    }

    private suspend fun reRegister() = reRegisterMutex.withLock {
        val server = selected
        val cred = credentials
        if (!isConnected || server == null || cred == null) return@withLock

        for (wait in RE_REGISTER_WAITS) {
            delay(wait)
            if (!isConnected) return@withLock

            val (mode, c) = WpcControlClient.negotiate(server.serverIP, server.serverPort, TIMEOUT, { s -> log(LogType.Debug, "WpcControlClient", s) })
            if (mode == WpcControlClient.Mode.Legacy) break
            if (mode != WpcControlClient.Mode.Control || c == null) continue

            val r = c.register(cred.user, cred.pass, DeviceFingerprint.id(context), version, osLabel(), TIMEOUT)
            if (r.code != RegisterCode.OK) {
                c.close()
                if (r.code in setOf(RegisterCode.BadCredential, RegisterCode.Expired, RegisterCode.Disabled, RegisterCode.DeviceLimit, RegisterCode.AuthOff)) {
                    log(LogType.Error, "System", registerMessage(r.code))
                    break
                }
                continue
            }

            val config = buildConfig(server, cred.user, r.token)
            if (config != null && WpcVpnService.instance?.reload(config) == true) {
                c.onClosed = { why -> onControlClosed(c, why) }
                c.startKeepAlive()
                control = c
                WpcVpnService.instance?.showConnected(server.serverName)
                log(LogType.Debug, "System", "已重新注册到服务器，令牌已更新")
                return@withLock
            }
            c.close()
        }

        log(LogType.Error, "System", "重新注册失败，连接已断开")
        stopMonitoring()
        credentials = null
        stopVpn()
        markDisconnected()
    }

    private fun registerMessage(code: RegisterCode): String = when (code) {
        RegisterCode.OK -> "代理服务器连接正常，设备已注册，账号密码安全验证已通过"
        RegisterCode.BadCredential -> "代理服务器：用户名或密码错误"
        RegisterCode.Expired -> "代理服务器：账号已过期"
        RegisterCode.Disabled -> "代理服务器：账号已被禁用"
        RegisterCode.DeviceLimit -> "代理服务器：该账号的设备数已达上限"
        RegisterCode.AuthOff -> "代理服务器：未启用账号认证，按普通模式连接"
        RegisterCode.BadRequest -> "代理服务器：设备注册请求被拒绝"
        RegisterCode.Timeout -> "代理服务器：设备注册超时"
        RegisterCode.Protocol -> "代理服务器：控制通道协议错误"
    }

    // 注册结果给界面的代码（前端按界面语言翻成 vf.r.*）；上面的中文只写系统日志，界面直接显示会在英文等界面上冒中文
    private fun registerKey(code: RegisterCode): String = when (code) {
        RegisterCode.OK -> "okDevice"
        RegisterCode.BadCredential -> "badAuth"
        RegisterCode.Expired -> "expired"
        RegisterCode.Disabled -> "disabled"
        RegisterCode.DeviceLimit -> "deviceLimit"
        RegisterCode.AuthOff -> "authOff"
        RegisterCode.BadRequest -> "badRequest"
        RegisterCode.Timeout -> "regTimeout"
        RegisterCode.Protocol -> "protocol"
    }

    private fun markDisconnected() {
        if (!isConnected) return
        isConnected = false
        commitTodayOnline()
        emit("disconnected", null)
    }

    // ———————————————— 安全验证 ————————————————

    /*
      返回 { success, error, code }：界面按 code 翻译（vf.r.*），error 是给日志与老前端的中文原文。
      进度事件 verifyProgress 也只发代码（server / handshake / auth / device → vf.p.*）。与 Windows 版 ProxyService.VerifyProxyAsync 同一张代码表。
    */
    suspend fun verifyProxy(): JSONObject = withContext(Dispatchers.IO) {
        val server = selected ?: return@withContext JSONObject().put("success", false).put("error", "未选择服务器").put("code", "noServer")

        lastDelay = Net.tcpDelay(server.serverIP, server.serverPort, TIMEOUT)

        // 连接中且控制通道还活着：设备已经注册、令牌有效，不必再去敲服务器（再注册一次会让 WPE 踢掉正在用的控制连接）
        if (isConnected && control?.isConnected == true) {
            val live = "设备已注册，控制通道正常，账号密码安全验证已通过"
            log(LogType.Debug, "System", live)
            return@withContext JSONObject().put("success", true).put("error", live).put("code", "live")
        }

        val user = credentials?.user ?: cfg.userName
        val pass = credentials?.pass ?: cfg.passWord

        emit("verifyProgress", "server")
        val (mode, c) = WpcControlClient.negotiate(server.serverIP, server.serverPort, TIMEOUT, { s -> log(LogType.Debug, "WpcControlClient", s) })

        val (ok, msg, key) = if (mode == WpcControlClient.Mode.Control && c != null) {
            emit("verifyProgress", "device")
            val r = c.register(user, pass, DeviceFingerprint.id(context), version, osLabel(), TIMEOUT)
            c.close()
            Triple(r.code == RegisterCode.OK, registerMessage(r.code), registerKey(r.code))
        } else {
            testSocks5(server.serverIP, server.serverPort, user, pass)
        }

        log(if (ok) LogType.Debug else LogType.Error, "System", if (ok) "服务器安全验证通过" else msg)
        JSONObject().put("success", ok).put("error", msg).put("code", key)
    }

    /** 返回 (是否通过, 中文原文给日志, 界面代码 vf.r.*)。 */
    private fun testSocks5(ip: String, port: Int, user: String, pass: String): Triple<Boolean, String, String> {
        return try {
            Socket().use { s ->
                emit("verifyProgress", "server")
                s.connect(InetSocketAddress(ip, port), TIMEOUT)
                s.soTimeout = TIMEOUT
                val out = s.getOutputStream()
                val inp = s.getInputStream()

                out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
                out.flush()
                emit("verifyProgress", "handshake")

                val r = ByteArray(2)
                if (WpcControlClient.readFully(inp, r, 2) < 2 || r[0] != 0x05.toByte()) return Triple(false, "代理服务器：SOCKS5 协议不支持", "noSocks5")

                emit("verifyProgress", "auth")
                when (r[1].toInt()) {
                    0x02 -> {
                        if (user.isBlank()) return Triple(false, "代理服务器：需要认证但未提供用户名", "noUser")
                        val u = user.toByteArray(Charsets.UTF_8)
                        val p = pass.toByteArray(Charsets.UTF_8)
                        val pkt = ByteArray(3 + u.size + p.size)
                        pkt[0] = 0x01
                        pkt[1] = u.size.toByte()
                        System.arraycopy(u, 0, pkt, 2, u.size)
                        pkt[2 + u.size] = p.size.toByte()
                        System.arraycopy(p, 0, pkt, 3 + u.size, p.size)
                        out.write(pkt)
                        out.flush()
                        val a = ByteArray(2)
                        if (WpcControlClient.readFully(inp, a, 2) < 2 || a[1] != 0x00.toByte()) return Triple(false, "代理服务器：用户名或密码错误", "badAuth")
                    }
                    0x00 -> Unit
                    else -> return Triple(false, "代理服务器：不支持的认证方式", "badMethod")
                }
                Triple(true, "代理服务器连接正常，账号密码安全验证已通过", "ok")
            }
        } catch (e: Exception) {
            log(LogType.Error, "testSocks5", e.message ?: "")
            Triple(false, "代理服务器不可用，请检查网络后重试", "unavailable")
        }
    }

    // ———————————————— 实时监控 ————————————————

    private val onlineLock = Any()
    private var onlineAccumulatedMs = 0L
    private var onlineSinceMs = 0L
    private var onlineRunning = false
    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private var todayDate = today()

    /** 今日已累计（不含当前会话）。跨天则从 0 算起；跨零点时可为负，见 todayOnlineMs。 */
    private var savedTodayMs = if (cfg.lastRecordDate == todayDate) cfg.todayOnlineSeconds * 1000 else 0L

    private fun elapsedUnlocked(): Long =
        if (onlineRunning) onlineAccumulatedMs + (System.currentTimeMillis() - onlineSinceMs) else onlineAccumulatedMs

    /** 今日累计在线。跨过零点时把基数改成「负的已在线时长」，今日累计从零点重新算起，本次会话照常往上走。 */
    private fun todayOnlineMs(): Long = synchronized(onlineLock) {
        val d = today()
        if (d != todayDate) {
            todayDate = d
            savedTodayMs = -elapsedUnlocked()
        }
        savedTodayMs + elapsedUnlocked()
    }

    private fun commitTodayOnline() {
        todayOnlineMs()
        synchronized(onlineLock) {
            savedTodayMs += elapsedUnlocked()
            onlineAccumulatedMs = 0
            onlineSinceMs = System.currentTimeMillis()
            onlineRunning = false
        }
        cfg.todayOnlineSeconds = maxOf(0L, savedTodayMs / 1000)
        cfg.lastRecordDate = todayDate
        store.save()
    }

    fun pauseOnlineTime(): Boolean {
        synchronized(onlineLock) {
            if (onlineRunning) {
                onlineAccumulatedMs += System.currentTimeMillis() - onlineSinceMs
                onlineRunning = false
            }
        }
        return false
    }

    fun resumeOnlineTime(): Boolean {
        synchronized(onlineLock) {
            if (!onlineRunning) {
                onlineSinceMs = System.currentTimeMillis()
                onlineRunning = true
            }
        }
        return true
    }

    private fun startMonitoring() {
        stopMonitoring()
        synchronized(onlineLock) {
            onlineAccumulatedMs = 0
            onlineSinceMs = System.currentTimeMillis()
            onlineRunning = true
        }

        monitorJob = scope.launch {
            var tick = 0
            while (isActive) {
                val server = selected
                if (tick++ % 10 == 0 && server != null) {
                    // 另起一个协程测：不通时要等满超时，不能卡住每秒一次的推送
                    launch { lastDelay = Net.tcpDelay(server.serverIP, server.serverPort, TIMEOUT) }
                }

                val up = runCatching { kernel.trafficUp() }.getOrDefault(0L)
                val down = runCatching { kernel.trafficDown() }.getOrDefault(0L)
                val mem = runCatching { kernel.memoryInUse() }.getOrDefault(0L)
                val todayMs = todayOnlineMs()
                val sessionMs = synchronized(onlineLock) { elapsedUnlocked() }

                emit(
                    "stats",
                    JSONObject()
                        .put("upKBps", round1(up / 1024.0))
                        .put("downKBps", round1(down / 1024.0))
                        .put("memoryMB", round2(mem / 1024.0 / 1024.0))
                        .put("delayMs", lastDelay)
                        .put("onlineTime", hms(sessionMs))
                        .put("todayOnlineSeconds", todayMs / 1000.0)
                        .put("healthProgress", min(1.0, maxOf(0.0, todayMs / 1000.0 / HEALTH_SECONDS))),
                )

                delay(1000)
            }
        }
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun round1(v: Double) = (v * 10).roundToLong() / 10.0
    private fun round2(v: Double) = (v * 100).roundToLong() / 100.0

    private fun hms(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d:%02d".format(Locale.US, s / 3600, (s % 3600) / 60, s % 60)
    }

    // ———————————————— 手机专属 ————————————————

    fun platformInfo(): JSONObject {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return JSONObject()
            .put("platform", "android")
            .put("osVersion", "Android ${Build.VERSION.RELEASE}")
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("webViewMajor", webViewMajor())
            .put("batteryOptimized", !pm.isIgnoringBatteryOptimizations(context.packageName))
    }

    fun appProxyJson(): JSONObject = JSONObject()
        .put("mode", cfg.appProxyMode)
        .put("packages", JSONArray(cfg.appProxyPackages))

    fun setAppProxy(mode: String, packages: List<String>): Boolean {
        cfg.appProxyMode = if (mode == "selected") "selected" else "all"
        cfg.appProxyPackages = packages.filter { it.isNotBlank() && it != context.packageName }.distinct()
        store.save()
        return true
    }

    /** 有启动图标的应用（不含本应用）。图标画成 96px 的 PNG data URL。第一次取完缓存到进程结束。 */
    suspend fun listApps(): JSONArray = withContext(Dispatchers.IO) {
        appsCache?.let { return@withContext it }

        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val seen = HashSet<String>()
        val arr = JSONArray()

        for (ri in infos) {
            val ai = ri.activityInfo?.applicationInfo ?: continue
            val pkg = ai.packageName
            if (pkg == context.packageName || !seen.add(pkg)) continue
            val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            arr.put(
                JSONObject()
                    .put("pkg", pkg)
                    .put("label", ri.loadLabel(pm).toString())
                    .put("system", system)
                    .put("icon", runCatching { iconDataUrl(ri.loadIcon(pm)) }.getOrDefault("")),
            )
        }

        appsCache = arr
        arr
    }

    private fun iconDataUrl(d: Drawable): String {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        val bytes = ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        bmp.recycle()
        return "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun requestIgnoreBattery(): Boolean {
        val pkg = context.packageName
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(direct)
            true
        } catch (_: ActivityNotFoundException) {
            // 个别定制系统没有这个直达页：退回到电池优化的总列表
            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        } catch (e: Exception) {
            log(LogType.Error, "requestIgnoreBattery", e.message ?: "")
            false
        }
    }

    /** 打开外链：只放行 http / https —— 节点列表是明文传来的，被改成 intent:// 之类就能拉起任意应用。 */
    fun openExternal(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) {
            log(LogType.Warning, "openExternal", "已拦截非 http(s) 链接：$url")
            return false
        }
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            log(LogType.Error, "openExternal", e.message ?: "")
            false
        }
    }
}
