package com.wpe64.wpc.core

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 控制通道客户端对着一个假 WPE 跑。假服务器的报文照 WPE 的 Operate.cs（WPCConfig.Device）实现：
 * 问候 → 05 80 → Register 帧 → RegisterResult 帧 → Ping 帧 → Pong 帧。
 * 真 WPE 的联调在 tools/interop（C# 起 WPE 的 SOCKS5 服务，这边连过去）。
 */
class WpcControlClientTest {

    private val servers = ArrayList<ServerSocket>()

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.close() } }
    }

    private fun readN(ins: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        DataInputStream(ins).readFully(b)
        return b
    }

    private fun writeFrame(out: OutputStream, type: Int, json: String) {
        val p = json.toByteArray()
        out.write(byteArrayOf(0x57, 0x01, type.toByte(), (p.size shr 8).toByte(), (p.size and 0xFF).toByte()) + p)
        out.flush()
    }

    private fun readFrame(ins: InputStream): Pair<Int, String> {
        val h = readN(ins, 5)
        assertEquals(0x57, h[0].toInt())
        assertEquals(0x01, h[1].toInt())
        val len = ((h[3].toInt() and 0xFF) shl 8) or (h[4].toInt() and 0xFF)
        return (h[2].toInt() and 0xFF) to String(readN(ins, len))
    }

    /** 起一个只接一条连接的假服务器，handler 在它自己的线程里跑。 */
    private fun fakeServer(handler: (Socket) -> Unit): Int {
        val ss = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        servers += ss
        Thread {
            runCatching { ss.accept().use { handler(it) } }
        }.apply { isDaemon = true; start() }
        return ss.localPort
    }

    @Test
    fun negotiateRegisterPingPong() {
        val registerJson = AtomicReference<JSONObject>()
        val pings = AtomicInteger()
        val port = fakeServer { s ->
            val ins = s.getInputStream()
            val out = s.getOutputStream()
            assertTrue(readN(ins, 4).contentEquals(byteArrayOf(0x05, 0x02, 0x02, 0x80.toByte())))
            out.write(byteArrayOf(0x05, 0x80.toByte()))
            out.flush()

            val (t, body) = readFrame(ins)
            assertEquals(0x01, t)
            registerJson.set(JSONObject(body))
            writeFrame(out, 0x81, """{"code":0,"token":"wpc1.TOKEN","message":""}""")

            while (true) {
                val (pt, _) = readFrame(ins)
                if (pt == 0x02) {
                    pings.incrementAndGet()
                    writeFrame(out, 0x82, "{}")
                }
            }
        }

        val (mode, c) = WpcControlClient.negotiate("127.0.0.1", port, 2000, {}, pingIntervalMs = 100, pongGraceMs = 1000)
        assertEquals(WpcControlClient.Mode.Control, mode)
        val client = c!!

        val r = client.register("player", "pw", "0123456789abcdef0123456789abcdef", "1.0", "Android 14", 2000)
        assertEquals(WpcControlClient.RegisterCode.OK, r.code)
        assertEquals("wpc1.TOKEN", client.token)

        val req = registerJson.get()
        assertEquals("player", req.getString("user"))
        assertEquals("pw", req.getString("pass"))
        assertEquals("0123456789abcdef0123456789abcdef", req.getString("device"))
        assertEquals("1.0", req.getString("version"))
        assertEquals("WPC", req.getString("client"))
        assertEquals("Android 14", req.getString("os"))

        val closed = CountDownLatch(1)
        client.onClosed = { closed.countDown() }
        client.startKeepAlive()
        Thread.sleep(450)
        assertTrue("每 100ms Ping 一次，服务器应当收到好几次", pings.get() >= 3)
        assertEquals("有 Pong 回来就不该判死", 1L, closed.count)

        client.close()
        assertTrue(!client.isConnected)
    }

    @Test
    fun rejectedRegistrationReportsTheCode() {
        val port = fakeServer { s ->
            readN(s.getInputStream(), 4)
            s.getOutputStream().write(byteArrayOf(0x05, 0x80.toByte()))
            readFrame(s.getInputStream())
            writeFrame(s.getOutputStream(), 0x81, """{"code":4,"token":"","message":""}""")
        }
        val (_, c) = WpcControlClient.negotiate("127.0.0.1", port, 2000, {})
        val r = c!!.register("u", "p", "device-0001", "1.0", "Android 14", 2000)
        assertEquals(WpcControlClient.RegisterCode.DeviceLimit, r.code)
        assertNull(c.token)
        c.close()
    }

    @Test
    fun legacyServerFallsBackToPlainMode() {
        // 老版 WPE：只认 02，选用户名密码
        val port = fakeServer { s ->
            readN(s.getInputStream(), 4)
            s.getOutputStream().write(byteArrayOf(0x05, 0x02))
            Thread.sleep(500)
        }
        val (mode, c) = WpcControlClient.negotiate("127.0.0.1", port, 2000, {})
        assertEquals(WpcControlClient.Mode.Legacy, mode)
        assertNull(c)
    }

    @Test
    fun silentServerIsLegacyNotUnreachable() {
        // 老版 WPE 且没开认证：连得上但不回包
        val port = fakeServer { s -> readN(s.getInputStream(), 4); Thread.sleep(4000) }
        val (mode, _) = WpcControlClient.negotiate("127.0.0.1", port, 1000, {})
        assertEquals(WpcControlClient.Mode.Legacy, mode)
    }

    @Test
    fun closedPortIsUnreachable() {
        val ss = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port = ss.localPort
        ss.close()
        val (mode, c) = WpcControlClient.negotiate("127.0.0.1", port, 1000, {})
        assertEquals(WpcControlClient.Mode.Unreachable, mode)
        assertNull(c)
    }

    @Test
    fun serverClosingFiresOnClosed() {
        val port = fakeServer { s ->
            readN(s.getInputStream(), 4)
            s.getOutputStream().write(byteArrayOf(0x05, 0x80.toByte()))
            readFrame(s.getInputStream())
            writeFrame(s.getOutputStream(), 0x81, """{"code":0,"token":"wpc1.T","message":""}""")
            Thread.sleep(200)
            // 关掉：模拟 WPE 重启 / 同设备的新注册把旧连接踢掉
        }
        val (_, c) = WpcControlClient.negotiate("127.0.0.1", port, 2000, {}, pingIntervalMs = 10_000)
        val client = c!!
        assertEquals(WpcControlClient.RegisterCode.OK, client.register("u", "p", "device-0001", "1.0", "Android 14", 2000).code)

        val reason = AtomicReference<String>()
        val latch = CountDownLatch(1)
        client.onClosed = { reason.set(it); latch.countDown() }
        client.startKeepAlive()
        assertTrue("对端关闭要在读循环里立刻发现", latch.await(3, TimeUnit.SECONDS))
        assertEquals("服务器关闭了控制连接", reason.get())
    }

    @Test
    fun missingPongsAreTreatedAsDead() {
        val port = fakeServer { s ->
            readN(s.getInputStream(), 4)
            s.getOutputStream().write(byteArrayOf(0x05, 0x80.toByte()))
            readFrame(s.getInputStream())
            writeFrame(s.getOutputStream(), 0x81, """{"code":0,"token":"wpc1.T","message":""}""")
            // 半开连接：收 Ping 但从不回 Pong
            while (true) readFrame(s.getInputStream())
        }
        val (_, c) = WpcControlClient.negotiate("127.0.0.1", port, 2000, {}, pingIntervalMs = 100, pongGraceMs = 350)
        val client = c!!
        client.register("u", "p", "device-0001", "1.0", "Android 14", 2000)

        val reason = AtomicReference<String>()
        val latch = CountDownLatch(1)
        client.onClosed = { reason.set(it); latch.countDown() }
        client.startKeepAlive()
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertEquals("服务器长时间没有应答", reason.get())
    }
}
