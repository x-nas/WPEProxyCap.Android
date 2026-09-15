package com.wpe64.wpc.bridge

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import com.wpe64.wpc.service.ProxyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON-RPC 桥 —— 与 Windows 版 App/Bridge/WebBridge.cs 同一套报文，前端 bridge.ts 两边通用：
 *   请求 {id, method, args} · 应答 {type:'result', id, ok, result|error} · 推送 {type:'event', name, data}
 *
 * 传输是 androidx.webkit 的 WebMessageListener：MainActivity 注册时只允许本地资源的来源
 * （https://appassets.androidplatform.net），网页里注入的对象叫 wpcNative，两个方向都是 JSON 文本。
 *
 * ⚠️ 往回推的通道（JavaScriptReplyProxy）只有在收到过一条消息之后才拿得到，所以前端一加载先发 __hello；
 *    在那之前产生的事件先排队（封顶 300 条，丢最旧的）。JavaScriptReplyProxy.postMessage 必须在主线程调。
 *
 * 这里只做路由与序列化，业务都在 ProxyService。
 */
class Bridge(
    private val svc: ProxyService,
    private val scope: CoroutineScope,
) : WebViewCompat.WebMessageListener {

    companion object {
        const val JS_OBJECT = "wpcNative"
        const val ORIGIN = "https://appassets.androidplatform.net"
        private const val QUEUE_CAP = 300
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var reply: JavaScriptReplyProxy? = null
    private val queue = ArrayDeque<String>()

    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        if (!isMainFrame || sourceOrigin.toString().trimEnd('/') != ORIGIN) return

        reply = replyProxy
        val text = message.data ?: return
        val req = runCatching { JSONObject(text) }.getOrNull() ?: return
        val id = req.optString("id")
        val method = req.optString("method")
        val args = req.optJSONObject("args") ?: JSONObject()

        if (method == "__hello") {
            flushQueue()
            return
        }

        scope.launch {
            val resp = JSONObject().put("type", "result").put("id", id)
            try {
                resp.put("ok", true).put("result", dispatch(method, args) ?: JSONObject.NULL)
            } catch (e: Throwable) {
                resp.put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
            }
            send(resp.toString())
        }
    }

    /** ProxyService 推事件（可能来自后台线程）。 */
    fun pushEvent(name: String, data: Any?) {
        val msg = JSONObject().put("type", "event").put("name", name).put("data", data ?: JSONObject.NULL).toString()
        send(msg)
    }

    private fun send(text: String) {
        main.post {
            val r = reply
            if (r == null) {
                if (queue.size >= QUEUE_CAP) queue.removeFirst()
                queue.addLast(text)
            } else {
                runCatching { r.postMessage(text) }
            }
        }
    }

    private fun flushQueue() {
        main.post {
            val r = reply ?: return@post
            while (queue.isNotEmpty()) runCatching { r.postMessage(queue.removeFirst()) }
        }
    }

    private fun JSONObject.str(name: String): String = optString(name, "")

    private fun JSONObject.optBool(name: String): Boolean? = if (has(name) && !isNull(name)) optBoolean(name) else null

    /** 方法路由表 —— 与 Windows 版 WebBridge.Dispatch 对照；窗口控制那几个手机上没有，另加了手机专属的五个。 */
    private suspend fun dispatch(method: String, args: JSONObject): Any? = when (method) {
        // ——— 只读查询 ———
        "getState" -> svc.stateJson()
        "checkWpeServer" -> svc.checkWpeServer()
        "checkSubscriberServer" -> svc.checkSubscriberServer()
        "getServers" -> svc.getServers()
        "testServerDelays" -> svc.testServerDelays()
        "getNotices" -> svc.getNotices()
        "getMihomoVersion" -> svc.kernelVersion()
        "getOsVersion" -> svc.osVersion()
        "readAgreement" -> svc.readAgreement(args.str("type"))

        // ——— 动作 ———
        "setSubscriber" -> svc.setSubscriber(args.str("name"))
        "updateAccount" -> svc.updateAccount(args.str("username"), args.str("password"), args.optBoolean("remember", true))
        "selectServer" -> svc.selectServer(args.str("serverId"))
        "connect" -> svc.connect(args.str("username"), args.str("password"))
        "disconnect" -> svc.disconnect()
        "verifyProxy" -> svc.verifyProxy()
        "pauseOnlineTime" -> svc.pauseOnlineTime()
        "resumeOnlineTime" -> svc.resumeOnlineTime()
        "openExternal" -> svc.openExternal(args.str("url"))

        // ——— 界面偏好 ———
        "setLanguage" -> svc.setLanguage(args.str("language"))
        "setAppearance" -> svc.setAppearance(
            if (args.has("mode") && !args.isNull("mode")) args.optString("mode") else null,
            args.optBool("isDark"),
            args.optBool("scan"),
        )

        // ——— 手机专属 ———
        "getPlatformInfo" -> svc.platformInfo()
        "getApps" -> svc.listApps()
        "getAppProxy" -> svc.appProxyJson()
        "setAppProxy" -> {
            val arr = args.optJSONArray("packages") ?: JSONArray()
            svc.setAppProxy(args.str("mode"), List(arr.length()) { arr.optString(it) })
        }
        "requestIgnoreBattery" -> svc.requestIgnoreBattery()

        else -> throw IllegalArgumentException("未知方法: $method")
    }
}
