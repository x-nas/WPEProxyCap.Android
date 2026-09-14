package com.wpe64.wpc.core

import org.json.JSONArray
import org.json.JSONObject

/*
  两个服务端的应答大小写不统一：
    · WPE 的 ProxyCap 接口         Json.NET 默认设置 → PascalCase（ServerName、ServerRInfo、RType…）
  ⚠️ Kotlin 的块注释会嵌套：注释里写路径时别出现「斜杠星号」连在一起（会开一层新注释、整个文件编译不过）。
    · 订阅服务器 api/Subscriber    ASP.NET Core      → camelCase（success、data、ipAddress…）
  Windows 版用 Newtonsoft 反序列化，属性名匹配天然不分大小写；这里照做，按不分大小写的键取值。
*/

internal fun JSONObject.keyCI(name: String): String? {
    if (has(name)) return name
    val it = keys()
    while (it.hasNext()) {
        val k = it.next()
        if (k.equals(name, ignoreCase = true)) return k
    }
    return null
}

internal fun JSONObject.strCI(name: String): String {
    val k = keyCI(name) ?: return ""
    return if (isNull(k)) "" else optString(k, "")
}

internal fun JSONObject.intCI(name: String, def: Int = 0): Int {
    val k = keyCI(name) ?: return def
    return if (isNull(k)) def else optInt(k, def)
}

internal fun JSONObject.boolCI(name: String): Boolean {
    val k = keyCI(name) ?: return false
    return optBoolean(k, false)
}

internal fun JSONObject.objCI(name: String): JSONObject? = keyCI(name)?.let { optJSONObject(it) }

internal fun JSONObject.arrCI(name: String): JSONArray? = keyCI(name)?.let { optJSONArray(it) }

/** 原始值（数字或字符串都可能：枚举在 Json.NET 默认是数字，开了 StringEnumConverter 就是名字）。 */
internal fun JSONObject.rawCI(name: String): Any? = keyCI(name)?.let { if (isNull(it)) null else opt(it) }

internal inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
    for (i in 0 until length()) {
        optJSONObject(i)?.let(block)
    }
}
