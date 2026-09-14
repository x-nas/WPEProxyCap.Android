package com.wpe64.wpc.core

import org.json.JSONArray
import org.json.JSONObject

/*
  契约模型 —— 与 WPE 的 ClassObject/ServerInfo.cs、RuleInfo.cs、NoticeInfo.cs，
  以及 Windows 版 WPC 的 App/Models/Models.cs 逐项对应。

  ⚠️ RuleType / RuleAction 的<b>顺序就是数值</b>（WPE 那边是 C# 枚举，按整数序列化），
  改顺序等于改协议。与 Models.cs 对照着看。
*/

enum class RuleType(val keyword: String) {
    DOMAIN("DOMAIN"),
    DOMAIN_SUFFIX("DOMAIN-SUFFIX"),
    DOMAIN_KEYWORD("DOMAIN-KEYWORD"),
    DOMAIN_REGEX("DOMAIN-REGEX"),
    GEOIP("GEOIP"),
    GEOSITE("GEOSITE"),
    IP_CIDR("IP-CIDR"),
    IP_CIDR6("IP-CIDR6"),
    SRC_IP_CIDR("SRC-IP-CIDR"),
    SRC_PORT("SRC-PORT"),
    DST_PORT("DST-PORT"),
    PROCESS_NAME("PROCESS-NAME"),
    PROCESS_PATH("PROCESS-PATH"),
    NETWORK("NETWORK"),
    RULE_SET("RULE-SET"),
    MATCH("MATCH"),
    AND("AND"),
    OR("OR"),
    NOT("NOT"),
    SUB_RULE("SUB-RULE"),
    IN_PORT("IN-PORT"),
    UI_EX("UI-EX"),
    COMMAND("COMMAND"),
    DEVICE_NAME("DEVICE-NAME");

    companion object {
        /** 数字按序号，字符串按枚举名或关键字（DOMAIN_SUFFIX / DOMAIN-SUFFIX 都认）。认不出来返回 null。 */
        fun parse(raw: Any?): RuleType? = when (raw) {
            is Number -> entries.getOrNull(raw.toInt())
            is String -> raw.trim().let { s ->
                s.toIntOrNull()?.let { entries.getOrNull(it) }
                    ?: entries.firstOrNull { it.name.equals(s, true) || it.keyword.equals(s, true) }
            }
            else -> null
        }
    }
}

enum class RuleAction {
    PROXY,
    REJECT,
    DIRECT;

    companion object {
        fun parse(raw: Any?): RuleAction? = when (raw) {
            is Number -> entries.getOrNull(raw.toInt())
            is String -> raw.trim().let { s -> s.toIntOrNull()?.let { entries.getOrNull(it) } ?: entries.firstOrNull { it.name.equals(s, true) } }
            else -> null
        }
    }
}

/** 一条节点规则。type 为 null 表示 WPE 发来了这个客户端不认识的类型值（原值留在 typeRaw 里写日志用）。 */
data class RuleInfo(
    val type: RuleType?,
    val typeRaw: String,
    val argument: String,
    val action: RuleAction?,
)

data class ServerInfo(
    /** 客户端自己算的标识（前端选节点用）：「地址:端口/名称」，重名时加 #2、#3。刷新前后同一个节点仍是同一个 Id */
    val serverId: String,
    val serverName: String,
    val serverIP: String,
    val serverPort: Int,
    val forgotURL: String,
    val registerURL: String,
    val verifyURL: String,
    val rules: List<RuleInfo>,
)

data class NoticeInfo(
    val noticeType: Int,
    val noticeTitle: String,
    val noticeContent: String,
    val noticeMore: String,
    val noticeTime: String,
)

data class SubscriberInfo(val name: String, val ipAddress: String, val port: Int)

object Parse {

    /** WPE 的 /ProxyCap/GetServerList：一个 ServerInfo 数组。 */
    fun servers(json: String): List<ServerInfo> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val raw = ArrayList<ServerInfo>()
        arr.forEachObject { o ->
            val rules = ArrayList<RuleInfo>()
            (o.arrCI("ServerRInfo") ?: o.arrCI("Rules"))?.forEachObject { r ->
                val t = r.rawCI("RType")
                rules += RuleInfo(
                    type = RuleType.parse(t),
                    typeRaw = t?.toString() ?: "",
                    argument = r.strCI("RArgument"),
                    action = RuleAction.parse(r.rawCI("RAction")),
                )
            }
            raw += ServerInfo(
                serverId = "",
                serverName = o.strCI("ServerName"),
                serverIP = o.strCI("ServerIP"),
                serverPort = o.intCI("ServerPort"),
                forgotURL = o.strCI("ForgotURL"),
                registerURL = o.strCI("RegisterURL"),
                verifyURL = o.strCI("VerifyURL"),
                rules = rules,
            )
        }
        return assignIds(raw)
    }

    /** 与 Windows 版 ProxyService.GetServerListAsync 同一条规则：按「地址 + 名称」算，重名追加 #n。 */
    fun assignIds(list: List<ServerInfo>): List<ServerInfo> {
        val used = HashSet<String>()
        return list.map { s ->
            val base = "${s.serverIP}:${s.serverPort}/${s.serverName}"
            var id = base
            var n = 2
            while (!used.add(id)) id = "$base#${n++}"
            s.copy(serverId = id)
        }
    }

    /** WPE 的 /ProxyCap/GetNoticeList：一个 NoticeInfo 数组。 */
    fun notices(json: String): List<NoticeInfo> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return noticeArray(arr)
    }

    private fun noticeArray(arr: JSONArray): List<NoticeInfo> {
        val out = ArrayList<NoticeInfo>()
        arr.forEachObject { o ->
            out += NoticeInfo(
                noticeType = o.intCI("NoticeType"),
                noticeTitle = o.strCI("NoticeTitle"),
                noticeContent = o.strCI("NoticeContent"),
                noticeMore = o.strCI("NoticeMore"),
                noticeTime = o.strCI("NoticeTime"),
            )
        }
        return out
    }

    /** 订阅服务器 api/Subscriber 的 ApiResponse<SubscriberInfo>：Data 为 null 表示订阅号不存在或已过期。 */
    fun subscriber(json: String): SubscriberInfo? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val d = o.objCI("Data") ?: return null
        val ip = d.strCI("IPAddress")
        val port = d.intCI("Port")
        if (ip.isEmpty() || port <= 0) return null
        return SubscriberInfo(d.strCI("SubscriberName"), ip, port)
    }

    /** 订阅服务器 api/Notices 的 ApiResponse<List<NoticeInfo>>。 */
    fun globalNotices(json: String): List<NoticeInfo> {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        return o.arrCI("Data")?.let { noticeArray(it) } ?: emptyList()
    }
}
