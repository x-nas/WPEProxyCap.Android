package com.wpe64.wpc.core

import org.json.JSONObject
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 与 WPE 的控制通道（跑在 SOCKS5 端口上）。与 Windows 版 App/Services/WpcControlClient.cs <b>逐项对应</b>。
 *
 * 【协议】问候里报私有方法 0x80（RFC 1928 留给私有用途）：
 *   WPC → WPE   05 02 02 80          （用户名密码 + WPC 私有；老版 WPE 只认 02，新版 WPE 优先选 80）
 *   WPE → WPC   05 80                （之后这条连接只走控制帧）
 *   控制帧      'W'(0x57) 0x01 type len(u16 大端) payload(UTF-8 JSON)
 *     WPC → WPE   0x01 Register { user, pass, device, version, client, os }、0x02 Ping
 *     WPE → WPC   0x81 RegisterResult { code, token, message }、0x82 Pong
 * 注册成功拿到令牌（wpc1.…），写进 mihomo 配置当 SOCKS5 密码。<b>这条连接本身就是在线状态</b>：它断，令牌立刻作废。
 *
 * 【新老 WPE 的判别】05 80 → 注册；05 02 / 05 FF / 没应答 → 普通模式（密码原样用）；连不上 → Unreachable。
 *
 * 【保活】注册成功后一个读线程持续收帧（对端一关立刻知道），一个线程每 30 秒 Ping，45 秒没有 Pong 判死。
 * WPE 那边控制连接 5 分钟收不到帧才判死（WPCConfig.Device.ControlIdleTimeout），手机短暂冻结时有余量。
 *
 * 纯 JVM 代码（不依赖 Android），单元测试直接跑。
 */
class WpcControlClient private constructor(
    private val socket: Socket,
    private val log: (String) -> Unit,
    private val pingIntervalMs: Long,
    private val pongGraceMs: Long,
) : Closeable {

    enum class Mode { Control, Legacy, Unreachable }

    /** 与 WPE 的 WPCConfig.Device.RegisterCode 一致；100 以上是客户端本地的。 */
    enum class RegisterCode(val value: Int) {
        OK(0), BadCredential(1), Expired(2), Disabled(3), DeviceLimit(4), BadRequest(5), AuthOff(6), Protocol(100), Timeout(101);

        companion object {
            fun of(v: Int): RegisterCode = entries.firstOrNull { it.value == v } ?: Protocol
        }
    }

    data class RegisterResult(val code: RegisterCode, val token: String, val message: String)

    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()

    @Volatile private var lastPong = System.nanoTime()
    @Volatile var token: String? = null
        private set

    private var reader: Thread? = null
    private var pinger: Thread? = null

    /** 连接断了（对端关闭、Pong 超时、写失败）。参数是原因；主动 close() 不触发。 */
    @Volatile var onClosed: ((String) -> Unit)? = null

    val isConnected: Boolean get() = !closed.get() && socket.isConnected && !socket.isClosed

    companion object {
        const val MAGIC = 0x57
        const val VERSION = 0x01
        const val TYPE_REGISTER = 0x01
        const val TYPE_PING = 0x02
        const val TYPE_REGISTER_RESULT = 0x81
        const val TYPE_PONG = 0x82
        const val MAX_FRAME = 4096

        const val PING_INTERVAL_MS = 30_000L
        const val PONG_GRACE_MS = 45_000L

        fun negotiate(
            host: String,
            port: Int,
            timeoutMs: Int,
            log: (String) -> Unit,
            pingIntervalMs: Long = PING_INTERVAL_MS,
            pongGraceMs: Long = PONG_GRACE_MS,
            socketFactory: () -> Socket = { Socket() },
        ): Pair<Mode, WpcControlClient?> {
            val s = socketFactory()
            return try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.getOutputStream().apply { write(byteArrayOf(0x05, 0x02, 0x02, 0x80.toByte())); flush() }

                s.soTimeout = minOf(timeoutMs, 3000)
                val reply = ByteArray(2)
                val got = try { readFully(s.getInputStream(), reply, 2) } catch (_: SocketTimeoutException) { 0 }

                if (got == 2 && reply[0] == 0x05.toByte() && reply[1] == 0x80.toByte()) {
                    s.soTimeout = 0
                    Mode.Control to WpcControlClient(s, log, pingIntervalMs, pongGraceMs)
                } else {
                    // 05 02 / 05 FF / 没应答：都按普通模式，控制连接用不上
                    runCatching { s.close() }
                    Mode.Legacy to null
                }
            } catch (e: Exception) {
                log("negotiate: ${e.message}")
                runCatching { s.close() }
                Mode.Unreachable to null
            }
        }

        internal fun readFully(stream: InputStream, buf: ByteArray, count: Int): Int {
            var off = 0
            while (off < count) {
                val n = stream.read(buf, off, count - off)
                if (n < 0) break
                off += n
            }
            return off
        }
    }

    // ———————————————— 注册 ————————————————

    fun register(user: String, pass: String, device: String, version: String, os: String, timeoutMs: Int): RegisterResult {
        return try {
            val req = JSONObject()
                .put("user", user)
                .put("pass", pass)
                .put("device", device)
                .put("version", version)
                .put("client", "WPC")
                .put("os", os)

            socket.soTimeout = timeoutMs
            writeFrame(TYPE_REGISTER, req.toString())
            val (type, payload) = readFrame()
            socket.soTimeout = 0

            if (type != TYPE_REGISTER_RESULT) return RegisterResult(RegisterCode.Protocol, "", "unexpected frame $type")

            val res = JSONObject(String(payload, StandardCharsets.UTF_8))
            val code = RegisterCode.of(res.optInt("code", RegisterCode.Protocol.value))
            val tok = res.optString("token", "")
            val msg = res.optString("message", "")

            if (code == RegisterCode.OK && tok.isNotEmpty()) {
                token = tok
                lastPong = System.nanoTime()
            }
            RegisterResult(code, tok, msg)
        } catch (_: SocketTimeoutException) {
            RegisterResult(RegisterCode.Timeout, "", "timeout")
        } catch (e: Exception) {
            log("register: ${e.message}")
            RegisterResult(RegisterCode.Protocol, "", e.message ?: "")
        }
    }

    // ———————————————— 保活 ————————————————

    fun startKeepAlive() {
        reader = Thread({
            try {
                while (!closed.get()) {
                    val (type, _) = readFrame()
                    if (type == TYPE_PONG) lastPong = System.nanoTime()
                }
            } catch (e: Exception) {
                fireClosed(if (e is EOFException) "服务器关闭了控制连接" else (e.message ?: "读取失败"))
            }
        }, "wpc-control-read").apply { isDaemon = true; start() }

        pinger = Thread({
            try {
                while (!closed.get()) {
                    Thread.sleep(pingIntervalMs)
                    if (closed.get()) return@Thread
                    if ((System.nanoTime() - lastPong) / 1_000_000 > pongGraceMs) {
                        fireClosed("服务器长时间没有应答")
                        return@Thread
                    }
                    writeFrame(TYPE_PING, "{}")
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                fireClosed(e.message ?: "发送失败")
            }
        }, "wpc-control-ping").apply { isDaemon = true; start() }
    }

    private fun fireClosed(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        pinger?.interrupt()
        onClosed?.invoke(reason)
    }

    /** 主动关闭：不触发 onClosed。 */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        pinger?.interrupt()
    }

    // ———————————————— 帧读写 ————————————————

    private fun writeFrame(type: Int, json: String) {
        val payload = json.toByteArray(StandardCharsets.UTF_8)
        require(payload.size <= MAX_FRAME - 5) { "control frame too long" }
        val frame = ByteArray(5 + payload.size)
        frame[0] = MAGIC.toByte()
        frame[1] = VERSION.toByte()
        frame[2] = type.toByte()
        frame[3] = (payload.size shr 8).toByte()
        frame[4] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, frame, 5, payload.size)
        synchronized(writeLock) {
            output.write(frame)
            output.flush()
        }
    }

    private fun readFrame(): Pair<Int, ByteArray> {
        val head = ByteArray(5)
        if (readFully(input, head, 5) < 5) throw EOFException()
        if (head[0] != MAGIC.toByte() || head[1] != VERSION.toByte()) throw IOException("bad control frame")

        val len = ((head[3].toInt() and 0xFF) shl 8) or (head[4].toInt() and 0xFF)
        if (len > MAX_FRAME) throw IOException("control frame too long")

        val payload = ByteArray(len)
        if (len > 0 && readFully(input, payload, len) < len) throw EOFException()
        return (head[2].toInt() and 0xFF) to payload
    }
}
