package com.wpe64.wpc.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * 对着<b>真的 WPE</b> 跑控制通道与令牌认证 —— 假服务器（WpcControlClientTest）验的是「照协议写的对不对」，
 * 这里验的是「与 WPE 的实现对不对得上」。
 *
 * 平时跳过。要跑时先起 WPE 那边的联调宿主（WinsockPacketEditor 仓库 tools/tests/WpcInteropHost.cs，用法写在文件头），
 * 它打印 READY socks=<端口> echo=<端口>，然后：
 *
 *   WPC_INTEROP_SOCKS=<端口> WPC_INTEROP_ECHO=<端口> ./gradlew testDebugUnitTest --tests "*InteropTest*" --no-daemon
 *
 * 宿主里的账号：interop / pw，限 1 台设备。
 */
class InteropTest {

    private val socks = System.getenv("WPC_INTEROP_SOCKS")?.toIntOrNull()
    private val echo = System.getenv("WPC_INTEROP_ECHO")?.toIntOrNull()

    private fun readN(ins: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        assertEquals("对端提前关闭", n, WpcControlClient.readFully(ins, b, n))
        return b
    }

    /** 数据连接：问候（只报 02）→ 用户名 + 令牌 → CONNECT 127.0.0.1:echo。返回认证应答的第二个字节（0 = 通过）。 */
    private fun openData(user: String, pass: String, target: Int, block: (InputStream, OutputStream) -> Unit): Int {
        Socket("127.0.0.1", socks!!).use { s ->
            s.soTimeout = 5000
            val ins = s.getInputStream()
            val out = s.getOutputStream()

            out.write(byteArrayOf(0x05, 0x01, 0x02))
            val greet = readN(ins, 2)
            assertArrayEquals(byteArrayOf(0x05, 0x02), greet)

            val u = user.toByteArray()
            val p = pass.toByteArray()
            out.write(byteArrayOf(0x01, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
            val auth = readN(ins, 2)
            if (auth[1].toInt() != 0) return auth[1].toInt()

            out.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 127, 0, 0, 1, (target shr 8).toByte(), (target and 0xFF).toByte()))
            val rep = readN(ins, 10)
            assertEquals("CONNECT 应答", 0, rep[1].toInt())

            block(ins, out)
            return 0
        }
    }

    @Test
    fun realWpeControlChannelAndTokenDataConnection() {
        assumeTrue("设置 WPC_INTEROP_SOCKS / WPC_INTEROP_ECHO 才跑（见文件头）", socks != null && echo != null)

        // ① 协商 → 注册 → 令牌
        val (mode, c) = WpcControlClient.negotiate("127.0.0.1", socks!!, 3000, { println("[client] $it") }, pingIntervalMs = 500)
        assertEquals(WpcControlClient.Mode.Control, mode)
        val control = c!!
        val r = control.register("interop", "pw", "android-interop-0001", "1.0", "Android 14", 3000)
        assertEquals(WpcControlClient.RegisterCode.OK, r.code)
        assertTrue("令牌形状 wpc1.…：${r.token}", r.token.startsWith("wpc1.") && r.token.length > 20)

        // ② 保活：真 WPE 回 Pong，控制连接不能被判死
        var closedReason: String? = null
        control.onClosed = { closedReason = it }
        control.startKeepAlive()
        Thread.sleep(1600)
        assertEquals("有 Pong 就不该断", null, closedReason)

        // ③ 令牌当密码的数据连接：认证通过、CONNECT 通、数据原样回显
        val payload = "hello-wpe-from-android".toByteArray()
        val authCode = openData("interop", r.token, echo!!) { ins, out ->
            out.write(payload)
            assertArrayEquals(payload, readN(ins, payload.size))
        }
        assertEquals("令牌认证", 0, authCode)

        // ④ 密码错 → BadCredential
        WpcControlClient.negotiate("127.0.0.1", socks, 3000, {}).second!!.use { c2 ->
            assertEquals(WpcControlClient.RegisterCode.BadCredential, c2.register("interop", "nope", "android-interop-0002", "1.0", "Android 14", 3000).code)
        }

        // ⑤ 账号限 1 台设备，第二台 → DeviceLimit
        WpcControlClient.negotiate("127.0.0.1", socks, 3000, {}).second!!.use { c3 ->
            assertEquals(WpcControlClient.RegisterCode.DeviceLimit, c3.register("interop", "pw", "android-interop-0003", "1.0", "Android 14", 3000).code)
        }

        // ⑥ 同一台设备重注册（手机切网后的静默重连）：成功并换新令牌，不算新设备
        val (_, again) = WpcControlClient.negotiate("127.0.0.1", socks, 3000, {})
        val r2 = again!!.register("interop", "pw", "android-interop-0001", "1.0", "Android 14", 3000)
        assertEquals(WpcControlClient.RegisterCode.OK, r2.code)
        assertTrue("换了新令牌", r2.token != r.token)

        // ⑦ 新令牌可用、旧令牌作废（WPE 踢掉了旧控制连接）
        Thread.sleep(300)
        assertEquals("新令牌", 0, openData("interop", r2.token, echo) { ins, out ->
            out.write(payload)
            assertArrayEquals(payload, readN(ins, payload.size))
        })
        assertEquals("旧令牌被拒 01 01", 1, openData("interop", r.token, echo) { _, _ -> })

        again.close()
        control.close()
    }
}
