package com.wpe64.wpc.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/*
  HTTP 那几跳。与 Windows 版 ProxyService 的网络方法对应：
    订阅服务器   http://www.wpe64.com:8081/api/Subscriber?Name=…   头 X-Api-Key
                http://www.wpe64.com:8081/api/Notices
    WPE         http://{IP}:{Port}/ProxyCap/GetServerList、/ProxyCap/GetNoticeList   免认证

  ⚠️ 全是明文 HTTP：Android 9 起默认禁止，靠 res/xml/network_security_config.xml 整体放开。
  ⚠️ X-Api-Key 只发给订阅服务器（WPE 那边的 ProxyCap 接口不校验它，发过去没用）。
  ⚠️ Kotlin 的块注释会嵌套：注释里别让「斜杠星号」连在一起出现。
  纯 JVM 代码，单元测试可以对着本机起的假服务器跑。
*/
class WpcApi(
    private val apiKey: String,
    private val subscribeBase: String = SUBSCRIBE_BASE,
    private val timeoutMs: Int = 5000,
) {
    companion object {
        const val SUBSCRIBE_BASE = "http://www.wpe64.com:8081"
        const val WPE_SITE_HOST = "www.wpe64.com"
        const val WPE_SITE_PORT = 80
    }

    sealed interface SubscribeOutcome {
        data class Ok(val info: SubscriberInfo) : SubscribeOutcome
        /** 订阅号不存在或已过期（服务端 Data = null） */
        data object Invalid : SubscribeOutcome
        /** 连不上 / 非 2xx / 超时 */
        data class Network(val detail: String) : SubscribeOutcome
    }

    fun subscribe(name: String): SubscribeOutcome {
        return try {
            val (code, body) = get("$subscribeBase/api/Subscriber?Name=" + URLEncoder.encode(name, "UTF-8"), withKey = true)
            if (code !in 200..299) return SubscribeOutcome.Network("HTTP $code")
            Parse.subscriber(body)?.let { SubscribeOutcome.Ok(it) } ?: SubscribeOutcome.Invalid
        } catch (e: Exception) {
            SubscribeOutcome.Network(e.message ?: e.javaClass.simpleName)
        }
    }

    fun globalNotices(): List<NoticeInfo> {
        val (code, body) = get("$subscribeBase/api/Notices", withKey = true)
        return if (code in 200..299) Parse.globalNotices(body) else emptyList()
    }

    fun serverList(ip: String, port: Int): List<ServerInfo> {
        val (code, body) = get("http://${hostPart(ip)}:$port/ProxyCap/GetServerList", withKey = false)
        return if (code in 200..299) Parse.servers(body) else emptyList()
    }

    fun serverNotices(ip: String, port: Int): List<NoticeInfo> {
        val (code, body) = get("http://${hostPart(ip)}:$port/ProxyCap/GetNoticeList", withKey = false)
        return if (code in 200..299) Parse.notices(body) else emptyList()
    }

    /** IPv6 字面量在 URL 里要加方括号。 */
    private fun hostPart(ip: String): String = if (':' in ip && !ip.startsWith("[")) "[$ip]" else ip

    private fun get(url: String, withKey: Boolean): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "WPEProxyCap-Android")
            if (withKey && apiKey.isNotEmpty()) conn.setRequestProperty("X-Api-Key", apiKey)

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { String(it.readBytes(), StandardCharsets.UTF_8) } ?: ""
            return code to body
        } catch (e: IOException) {
            throw e
        } finally {
            conn.disconnect()
        }
    }
}

object Net {
    /**
     * 延迟：TCP 建连耗时（毫秒），不通返回 -1。
     * Windows 版先 ICMP Ping、不通再 TCP；手机上普通应用发不了 ICMP，直接用 TCP。
     */
    fun tcpDelay(host: String, port: Int, timeoutMs: Int): Long {
        if (host.isEmpty() || port <= 0) return -1
        return try {
            Socket().use { s ->
                val t0 = System.nanoTime()
                s.connect(InetSocketAddress(host, port), timeoutMs)
                (System.nanoTime() - t0) / 1_000_000
            }
        } catch (_: Exception) {
            -1
        }
    }
}
