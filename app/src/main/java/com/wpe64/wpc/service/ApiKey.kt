package com.wpe64.wpc.service

import com.wpe64.wpc.BuildConfig

/**
 * 订阅服务器的 X-Api-Key。构建时拆成两段异或存进 BuildConfig（见 app/build.gradle.kts），运行时拼回。
 * 与 Windows 版 ProxyService.ApiKey() 同一种做法：只挡住「解压 APK 搜字符串」这种低成本获取，挡不住反编译。
 * 换 key 时改 local.properties 的 wpc.apiKey 重新构建，并同步服务端 appsettings.json 的 ValidApiKeys。
 */
object ApiKey {
    fun get(): String = decode(BuildConfig.WPC_KEY_A, BuildConfig.WPC_KEY_B)

    fun decode(a: ByteArray, b: ByteArray): String {
        require(a.size == b.size) { "key parts differ in length" }
        return String(ByteArray(a.size) { i -> (a[i].toInt() xor b[i].toInt()).toByte() }, Charsets.US_ASCII)
    }
}
