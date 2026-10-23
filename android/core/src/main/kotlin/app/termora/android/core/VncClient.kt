package app.termora.android.core

import java.io.*
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** RFB 3.3/3.7/3.8. Raw and CopyRect are requested explicitly, so no decoder fallback is needed. */
class VncClient(
    private val frame: (Int, Int, IntArray) -> Unit,
    private val clipboard: (String) -> Unit,
) : Closeable {
    private val socket = Socket()
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    private val closed = AtomicBoolean(false)
    private var width = 0
    private var height = 0
    private var pixels = IntArray(0)

    fun connect(host: String, port: Int, password: String) {
        try {
            socket.connect(InetSocketAddress(host, port), 15000)
            socket.soTimeout = 15000
            input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val version = ByteArray(12).also(input::readFully).toString(Charsets.US_ASCII)
            require(version.matches(Regex("RFB 003\\.00[378]\\n"))) { "不支持 RFB 版本 $version" }
            val minor = version.substring(8, 11).toInt()
            val chosen = if (minor >= 8) 8 else if (minor >= 7) 7 else 3
            output.write("RFB 003.00$chosen\n".toByteArray(Charsets.US_ASCII))
            output.flush()
            val security =
                if (chosen == 3) input.readInt()
                else {
                    val count = input.readUnsignedByte()
                    require(count > 0) { reason() }
                    val types = ByteArray(count).also(input::readFully)
                    val selected =
                        if (2.toByte() in types) 2
                        else if (1.toByte() in types) 1 else error("VNC 服务器要求不支持的认证方式")
                    output.writeByte(selected)
                    output.flush()
                    selected
                }
            require(security in 1..2) { if (security == 0) reason() else "不支持 VNC 认证方式 $security" }
            if (security == 2) {
                val challenge = ByteArray(16).also(input::readFully)
                val key = ByteArray(8)
                password.toByteArray(Charsets.ISO_8859_1).take(8).forEachIndexed { n, b ->
                    key[n] = Integer.reverse(b.toInt() and 255).ushr(24).toByte()
                }
                val cipher =
                    Cipher.getInstance("DES/ECB/NoPadding").apply {
                        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
                    }
                output.write(cipher.doFinal(challenge))
                output.flush()
                key.fill(0)
            }
            if (security == 2 || chosen == 8)
                check(input.readInt() == 0) { if (chosen == 8) reason() else "VNC 认证失败" }
            output.writeByte(1)
            output.flush()
            val w = input.readUnsignedShort()
            val h = input.readUnsignedShort()
            input.skipFully(16)
            resize(w, h)
            reason() // server desktop name
            // Client pixel format: little endian true-colour 32bpp, depth24, R16 G8 B0.
            output.write(
                byteArrayOf(
                    0,
                    0,
                    0,
                    0,
                    32,
                    24,
                    0,
                    1,
                    0,
                    (-1).toByte(),
                    0,
                    (-1).toByte(),
                    0,
                    (-1).toByte(),
                    16,
                    8,
                    0,
                    0,
                    0,
                    0,
                ),
            )
            output.writeByte(2)
            output.writeByte(0)
            output.writeShort(3)
            output.writeInt(0)
            output.writeInt(1)
            output.writeInt(-223)
            output.flush()
            socket.soTimeout = 0
            request(false)
            while (!closed.get()) when (input.readUnsignedByte()) {
                0 -> update()
                2 -> Unit // bell
                3 -> {
                    input.skipFully(3)
                    clipboard(reason())
                }
                else -> error("VNC 服务器消息异常")
            }
        } finally {
            close()
        }
    }

    private fun reason(): String {
        val len = input.readInt()
        require(len in 0..(1024 * 1024))
        return ByteArray(len).also(input::readFully).toString(Charsets.UTF_8)
    }

    private fun resize(w: Int, h: Int) {
        require(w in 1..8192 && h in 1..8192 && w.toLong() * h <= 16_777_216)
        width = w
        height = h
        pixels = IntArray(w * h) { 0xff000000.toInt() }
    }

    private fun update() {
        input.readByte()
        val count = input.readUnsignedShort()
        require(count <= 16384)
        repeat(count) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val w = input.readUnsignedShort()
            val h = input.readUnsignedShort()
            val encoding = input.readInt()
            if (encoding == -223) {
                resize(w, h)
                return@repeat
            }
            require(x + w <= width && y + h <= height)
            when (encoding) {
                0 ->
                    repeat(h) { row ->
                        repeat(w) { col ->
                            val b = input.readUnsignedByte()
                            val g = input.readUnsignedByte()
                            val r = input.readUnsignedByte()
                            input.readByte()
                            pixels[(y + row) * width + x + col] =
                                0xff000000.toInt() or (r shl 16) or (g shl 8) or b
                        }
                    }
                1 -> {
                    val sx = input.readUnsignedShort()
                    val sy = input.readUnsignedShort()
                    require(sx + w <= width && sy + h <= height)
                    val copy = IntArray(w * h)
                    repeat(h) {
                        pixels.copyInto(
                            copy,
                            it * w,
                            (sy + it) * width + sx,
                            (sy + it) * width + sx + w,
                        )
                    }
                    repeat(h) { copy.copyInto(pixels, (y + it) * width + x, it * w, it * w + w) }
                }
                else -> error("服务器发送未请求的编码 $encoding")
            }
        }
        frame(width, height, pixels.copyOf())
        request(true)
    }

    @Synchronized
    private fun request(incremental: Boolean) {
        if (closed.get()) return
        output.writeByte(3)
        output.writeByte(if (incremental) 1 else 0)
        output.writeShort(0)
        output.writeShort(0)
        output.writeShort(width)
        output.writeShort(height)
        output.flush()
    }

    @Synchronized
    fun pointer(x: Int, y: Int, buttons: Int) {
        if (!::output.isInitialized || closed.get()) return
        output.writeByte(5)
        output.writeByte(buttons)
        output.writeShort(x.coerceIn(0, width - 1))
        output.writeShort(y.coerceIn(0, height - 1))
        output.flush()
    }

    @Synchronized
    fun key(code: Int, down: Boolean) {
        if (!::output.isInitialized || closed.get()) return
        output.writeByte(4)
        output.writeByte(if (down) 1 else 0)
        output.writeShort(0)
        output.writeInt(code)
        output.flush()
    }

    @Synchronized
    fun paste(text: String) {
        val data = text.toByteArray(Charsets.UTF_8)
        require(data.size <= 1024 * 1024)
        output.writeByte(6)
        output.write(byteArrayOf(0, 0, 0))
        output.writeInt(data.size)
        output.write(data)
        output.flush()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) socket.close()
    }

    private fun DataInputStream.skipFully(n: Int) {
        val data = ByteArray(n)
        readFully(data)
    }
}
