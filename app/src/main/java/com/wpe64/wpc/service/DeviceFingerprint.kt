package com.wpe64.wpc.service

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

/**
 * 本机的设备指纹：注册到 WPE 时当设备的唯一标识，WPE 据此数「限制设备数」和判在线。
 * 对应 Windows 版 App/Services/DeviceFingerprint.cs —— 输出同一个形状（SHA-256 前 16 字节的小写十六进制，32 个字符），
 * WPE 那边只要求 8–64 个 [A-Za-z0-9._-]。
 *
 * 【成分】Settings.Secure.ANDROID_ID：同一台设备、同一个用户、同一把签名密钥下稳定；恢复出厂或换签名密钥会变。
 * 极少数定制系统拿不到（或是那个著名的坏值 9774d56d682e549c）时，退回到本应用第一次运行时生成并保存的随机 UUID。
 * <b>只传哈希，不传原始 ID。</b>
 *
 * ⚠️ 换签名密钥发版 = 所有用户变成新设备（可能撞上设备数上限）。发布签名的密钥永远用同一把。
 */
object DeviceFingerprint {

    @Volatile private var cached: String? = null

    fun id(context: Context): String = cached ?: compute(context).also { cached = it }

    @SuppressLint("HardwareIds")
    private fun compute(context: Context): String {
        val androidId = runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) }.getOrNull().orEmpty()

        val source = if (androidId.isNotBlank() && androidId != "9774d56d682e549c") {
            "android:$androidId"
        } else {
            val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)
            val saved = prefs.getString("uuid", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("uuid", it).apply() }
            "uuid:$saved"
        }

        return hash(source + "|pkg:" + context.packageName)
    }

    internal fun hash(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }
}
