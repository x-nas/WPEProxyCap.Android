package com.wpe64.wpc.service

import com.wpe64.wpc.wpccore.LogSink
import com.wpe64.wpc.wpccore.Wpccore

/**
 * mihomo 内核的门面。ProxyService 只认这个接口 —— 真实实现是 gomobile 生成的 Wpccore（core/wpccore.go），
 * 单元测试里可以换成假的。
 */
interface Kernel {
    fun version(): String
    fun setHomeDir(dir: String)

    /** 只解析不应用：空串 = 能用，否则是错误文字 */
    fun validate(configYaml: String): String

    /** 启动；tunFd 是 VpnService 建好的隧道描述符。失败抛异常 */
    fun start(configYaml: String, tunFd: Int)

    /** 不重建隧道换配置（控制通道重连后换令牌）。失败抛异常 */
    fun reload(configYaml: String, tunFd: Int)

    fun stop()
    fun trafficUp(): Long
    fun trafficDown(): Long
    fun memoryInUse(): Long
    fun setLogSink(sink: ((level: String, message: String) -> Unit)?)
}

object MihomoKernel : Kernel {
    override fun version(): String = Wpccore.version()
    override fun setHomeDir(dir: String) = Wpccore.setHomeDir(dir)
    override fun validate(configYaml: String): String = Wpccore.validate(configYaml) ?: ""
    override fun start(configYaml: String, tunFd: Int) = Wpccore.start(configYaml, tunFd.toLong())
    override fun reload(configYaml: String, tunFd: Int) = Wpccore.reload(configYaml, tunFd.toLong())
    override fun stop() = Wpccore.stop()
    override fun trafficUp(): Long = Wpccore.trafficUp()
    override fun trafficDown(): Long = Wpccore.trafficDown()
    override fun memoryInUse(): Long = Wpccore.memoryInUse()

    override fun setLogSink(sink: ((level: String, message: String) -> Unit)?) {
        Wpccore.setLogSink(
            sink?.let { s ->
                object : LogSink {
                    override fun onLog(level: String, message: String) = s(level, message)
                }
            },
        )
    }
}
