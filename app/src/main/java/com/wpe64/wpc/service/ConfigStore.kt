package com.wpe64.wpc.service

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 持久化配置，对应 Windows 版的 %LocalAppData%\WPEProxyCap\config.json（App/Services/ConfigStore.cs），
 * 落在应用私有目录 files/config.json（先写 .tmp 再改名，写到一半被杀也不会坏掉旧文件）。
 *
 * 与 Windows 版的差别：
 *   · 密码不存明文：用 Android Keystore 里的 AES-GCM 密钥加密（密钥不出安全硬件，卸载即失效）；
 *   · 多了手机专属的几项：选中的节点（「始终开启 VPN」在没有界面时要知道连哪个）、分应用代理。
 */
class ConfigStore(context: Context) {

    class AppConfig {
        var subscriberName = ""
        var subscriberIP = ""
        var subscriberPort = 0
        /** yyyy-MM-dd HH:mm:ss，与 Windows 版推给界面的格式一致；空串 = 从未更新 */
        var subscriberTime = ""
        var userName = ""
        var passWord = ""
        var rememberAccount = true
        /** yyyy-MM-dd */
        var lastRecordDate = ""
        var todayOnlineSeconds = 0L
        var language = "zh-CN"
        var themeMode = "dark"
        var isDark = true
        var scanLine = true
        var selectedServerId = ""
        var appProxyMode = "all"
        var appProxyPackages: List<String> = emptyList()
    }

    private val file = File(context.filesDir, "config.json")
    private val lock = Any()

    val config = AppConfig()

    init {
        load()
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val o = JSONObject(file.readText())
            config.subscriberName = o.optString("subscriberName", "")
            config.subscriberIP = o.optString("subscriberIP", "")
            config.subscriberPort = o.optInt("subscriberPort", 0)
            config.subscriberTime = o.optString("subscriberTime", "")
            config.userName = o.optString("userName", "")
            config.passWord = SecretBox.open(o.optString("passWordSealed", ""))
            config.rememberAccount = o.optBoolean("rememberAccount", true)
            config.lastRecordDate = o.optString("lastRecordDate", "")
            config.todayOnlineSeconds = o.optLong("todayOnlineSeconds", 0L)
            config.language = o.optString("language", "zh-CN")
            config.themeMode = o.optString("themeMode", "dark")
            config.isDark = o.optBoolean("isDark", true)
            config.scanLine = o.optBoolean("scanLine", true)
            config.selectedServerId = o.optString("selectedServerId", "")
            config.appProxyMode = o.optString("appProxyMode", "all")
            config.appProxyPackages = o.optJSONArray("appProxyPackages")?.let { a -> List(a.length()) { a.optString(it) }.filter { it.isNotEmpty() } } ?: emptyList()
        } catch (_: Exception) {
            // 读坏了就用默认值；下一次 save 覆盖掉
        }
    }

    fun save() {
        synchronized(lock) {
            val c = config
            val o = JSONObject()
                .put("subscriberName", c.subscriberName)
                .put("subscriberIP", c.subscriberIP)
                .put("subscriberPort", c.subscriberPort)
                .put("subscriberTime", c.subscriberTime)
                .put("userName", c.userName)
                .put("passWordSealed", SecretBox.seal(c.passWord))
                .put("rememberAccount", c.rememberAccount)
                .put("lastRecordDate", c.lastRecordDate)
                .put("todayOnlineSeconds", c.todayOnlineSeconds)
                .put("language", c.language)
                .put("themeMode", c.themeMode)
                .put("isDark", c.isDark)
                .put("scanLine", c.scanLine)
                .put("selectedServerId", c.selectedServerId)
                .put("appProxyMode", c.appProxyMode)
                .put("appProxyPackages", JSONArray(c.appProxyPackages))

            try {
                val tmp = File(file.path + ".tmp")
                tmp.writeText(o.toString(2))
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            } catch (_: Exception) {
                // 写盘失败只是这次没记住
            }
        }
    }
}

/** 账号密码的加解密：Android Keystore 里的 AES-256-GCM。 */
internal object SecretBox {
    private const val ALIAS = "wpc-account"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.ENCRYPT_MODE, key())
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }

    fun open(sealed: String): String {
        if (sealed.isEmpty()) return ""
        return try {
            val (iv, ct) = sealed.split(':', limit = 2)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}
