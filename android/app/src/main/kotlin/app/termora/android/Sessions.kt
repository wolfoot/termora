package app.termora.android

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import app.termora.*
import app.termora.android.core.*
import app.termora.terminal.VisualTerminal
import com.freerdp.freerdpcore.services.*
import java.io.Closeable
import java.lang.ref.WeakReference
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

object Repository {
    lateinit var store: VaultStore
    @Volatile
    var value = Vault()
        private set

    @Synchronized
    fun init(context: Context) {
        if (!::store.isInitialized) {
            store = VaultStore(context.applicationContext)
            value = store.load()
        }
    }

    @Synchronized
    fun update(change: (Vault) -> Vault) {
        val next = change(value)
        store.save(next)
        value = next
    }
}

object UiBridge {
    var activity = WeakReference<MainActivity>(null)
    val main = Handler(Looper.getMainLooper())
    val io = Executors.newCachedThreadPool()

    fun ask(
        title: String,
        text: String,
        secret: Boolean = false,
        confirm: Boolean = false,
    ): String? {
        check(Looper.myLooper() != Looper.getMainLooper())
        val future = CompletableFuture<String?>()
        main.post {
            val a = activity.get()
            if (a == null || a.isFinishing || a.isDestroyed) future.complete(null)
            else a.answerDialog(title, text, secret, confirm) { future.complete(it) }
        }
        return try {
            future.get(120, TimeUnit.SECONDS)
        } catch (e: Exception) {
            null
        }
    }

    fun verify(id: String, fingerprint: String): Boolean {
        val pin = Repository.value.pins[id]
        if (pin != null) return pin == fingerprint
        if (ask("确认服务器身份", "$id\n\n$fingerprint\n\n请核对指纹后再信任此服务器。", confirm = true) == null)
            return false
        Repository.update { it.copy(pins = it.pins + (id to fingerprint)) }
        return true
    }

    fun ssh() =
        SshConnection(
            { Repository.value.hosts },
            { id -> Repository.value.keys.firstOrNull { it.id == id }?.let(Keys::sshPrivatePem) },
            ::verify,
            { message, secret -> ask("SSH 认证", message, secret) },
        )
}

class MobileSession(val host: Host, private val context: Context) : Closeable {
    val id = randomUUID()
    val terminal = VisualTerminal()
    var ssh: SshConnection? = null
        private set

    var vnc: VncClient? = null
        private set

    var rdp = 0L
        private set

    @Volatile
    var bitmap: Bitmap? = null
        private set

    private val frameLock = Any()
    @Volatile
    var status = "连接中"
        private set

    @Volatile var observer: (() -> Unit)? = null
    private val closed = AtomicBoolean(false)
    private val recorded = java.io.ByteArrayOutputStream()
    var recording = false
    @Volatile private var rows = 24
    @Volatile private var cols = 80

    fun resize(rows: Int, cols: Int) {
        this.rows = rows
        this.cols = cols
        ssh?.resize(rows, cols)
    }

    val writer = Executors.newSingleThreadExecutor()

    fun notifyView() {
        UiBridge.main.post { observer?.invoke() }
    }

    fun start() {
        UiBridge.io.execute {
            try {
                when (host.protocol.uppercase()) {
                    "SSH",
                    "SFTP" -> {
                        val connection = UiBridge.ssh()
                        ssh = connection
                        connection.connect(host)
                        if (closed.get()) {
                            connection.close()
                            return@execute
                        }
                        if (host.protocol.equals("SSH", true))
                            connection.startShell(
                                host,
                                { text ->
                                    UiBridge.main.post {
                                        terminal.write(text)
                                        notifyView()
                                    }
                                },
                                { error ->
                                    status = error ?: "已断开"
                                    notifyView()
                                },
                                rows,
                                cols,
                            )
                        status = "已连接"
                        notifyView()
                    }
                    "VNC",
                    "RDP" -> {
                        var address = host.host
                        var port =
                            host.port.takeIf { it > 0 }
                                ?: if (host.protocol.equals("VNC", true)) 5900 else 3389
                        if (host.options.jumpHosts.isNotEmpty()) {
                            val jump =
                                Repository.value.hosts.firstOrNull {
                                    it.id == host.options.jumpHosts.last()
                                } ?: error("跳板机不存在")
                            val connection = UiBridge.ssh()
                            ssh = connection
                            connection.connect(
                                jump.copy(
                                    options =
                                        jump.options.copy(
                                            jumpHosts = host.options.jumpHosts.dropLast(1),
                                        ),
                                ),
                            )
                            port =
                                connection.forward(
                                    Tunneling(
                                        type = TunnelingType.Local,
                                        sourcePort = 0,
                                        destinationHost = address,
                                        destinationPort = port,
                                    ),
                                )
                            address = "127.0.0.1"
                        }
                        if (closed.get()) {
                            ssh?.close()
                            return@execute
                        }
                        if (host.protocol.equals("VNC", true)) {
                            val client =
                                VncClient(
                                    { w, h, pixels ->
                                        synchronized(frameLock) {
                                            if (!closed.get()) {
                                                val b =
                                                    bitmap?.takeIf {
                                                        it.width == w && it.height == h
                                                    }
                                                        ?: Bitmap.createBitmap(
                                                                w,
                                                                h,
                                                                Bitmap.Config.ARGB_8888,
                                                            )
                                                            .also { bitmap = it }
                                                b.setPixels(pixels, 0, w, 0, 0, w, h)
                                            }
                                        }
                                        status = "已连接"
                                        notifyView()
                                    },
                                    { text ->
                                        UiBridge.main.post {
                                            UiBridge.activity.get()?.remoteClipboard(text)
                                        }
                                    },
                                )
                            vnc = client
                            client.connect(address, port, host.authentication.password)
                            status = "已断开"
                            notifyView()
                        } else startRdp(address, port)
                    }
                }
            } catch (e: Exception) {
                status = e.message ?: "连接失败"
                notifyView()
                ssh?.close()
            }
        }
    }

    private fun startRdp(address: String, port: Int) {
        synchronized(frameLock) {
            if (closed.get()) return
            rdp = LibFreeRDP.newInstance(context.applicationContext)
            check(rdp != 0L)
            LibFreeRDP.attach(
                rdp,
                object : RdpListener() {
                    override fun OnSettingsChanged(width: Int, height: Int, bpp: Int) =
                        OnGraphicsResize(width, height, bpp)

                    override fun OnGraphicsResize(width: Int, height: Int, bpp: Int) {
                        require(
                            width in 1..8192 &&
                                height in 1..8192 &&
                                width.toLong() * height <= 16_777_216,
                        )
                        synchronized(frameLock) {
                            if (!closed.get())
                                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        }
                        notifyView()
                    }

                    override fun OnGraphicsUpdate(x: Int, y: Int, width: Int, height: Int) {
                        synchronized(frameLock) {
                            bitmap?.let { LibFreeRDP.updateGraphics(rdp, it, x, y, width, height) }
                        }
                        notifyView()
                    }

                    override fun OnAuthenticate(
                        username: StringBuilder,
                        domain: StringBuilder,
                        password: StringBuilder,
                    ): Boolean {
                        val text =
                            host.authentication.password.ifBlank {
                                UiBridge.ask("RDP 登录", "密码", true) ?: return false
                            }
                        username.replace(0, username.length, host.username)
                        domain.replace(
                            0,
                            domain.length,
                            host.options.extras["rdp.domain"].orEmpty(),
                        )
                        password.replace(0, password.length, text)
                        return true
                    }

                    override fun OnVerifiyCertificateEx(
                        server: String,
                        port: Long,
                        commonName: String,
                        subject: String,
                        issuer: String,
                        fingerprint: String,
                        flags: Long,
                    ): Int =
                        if (
                            UiBridge.verify(
                                "rdp:${host.host}:${host.port.takeIf {it>0} ?: 3389}",
                                fingerprint,
                            )
                        )
                            1
                        else 0

                    override fun OnVerifyChangedCertificateEx(
                        server: String,
                        port: Long,
                        commonName: String,
                        subject: String,
                        issuer: String,
                        fingerprint: String,
                        oldSubject: String,
                        oldIssuer: String,
                        oldFingerprint: String,
                        flags: Long,
                    ): Int =
                        if (
                            UiBridge.verify(
                                "rdp:${host.host}:${host.port.takeIf {it>0} ?: 3389}",
                                fingerprint,
                            )
                        )
                            1
                        else 0

                    override fun OnRemoteClipboardChanged(data: String) {
                        UiBridge.main.post { UiBridge.activity.get()?.remoteClipboard(data) }
                    }
                },
            )
            val args =
                arrayOf(
                    "Termora",
                    "/gdi:sw",
                    "/v:$address",
                    "/port:$port",
                    "/u:${host.username}",
                    "/p:${host.authentication.password}",
                    "/d:${host.options.extras["rdp.domain"].orEmpty()}",
                    "/size:${host.options.extras["rdp.width"] ?: "1280"}x${host.options.extras["rdp.height"] ?: "720"}",
                    "/bpp:32",
                    "/kbd:unicode:on",
                    "/clipboard",
                    "/disp",
                    "/audio-mode:0",
                    "/sound",
                    "/gfx:AVC444",
                    "/log-level:ERROR",
                )
            check(LibFreeRDP.freerdp_parse_arguments(rdp, args)) { "RDP 参数无效" }
            check(LibFreeRDP.connect(rdp)) { "RDP 连接失败" }
        }
    }

    fun connected() {
        status = "已连接"
        notifyView()
    }

    fun disconnected(error: Boolean) {
        status = if (error) LibFreeRDP.freerdp_get_last_error_string(rdp) else "已断开"
        notifyView()
    }

    fun drawFrame(draw: (Bitmap) -> Unit) {
        synchronized(frameLock) { bitmap?.let(draw) }
    }

    fun send(bytes: ByteArray) {
        if (recording)
            synchronized(recorded) {
                if (recorded.size() + bytes.size <= 1024 * 1024) recorded.write(bytes)
            }
        ssh?.write(bytes)
    }

    fun takeRecording(): ByteArray =
        synchronized(recorded) { recorded.toByteArray().also { recorded.reset() } }

    fun desktopPointer(x: Int, y: Int, down: Boolean, button: Int = 1) {
        writer.execute {
            if (closed.get()) return@execute
            runCatching {
                vnc?.pointer(
                    x,
                    y,
                    if (down) {
                        if (button == 2) 4 else 1
                    } else 0,
                )
                if (rdp != 0L)
                    LibFreeRDP.sendCursorEvent(
                        rdp,
                        x,
                        y,
                        if (button == 1) {
                            if (down) 0x9000 else 0x1000
                        } else {
                            if (down) 0xa000 else 0x2000
                        },
                    )
            }
        }
    }

    fun desktopMove(x: Int, y: Int, buttons: Int = 0) {
        writer.execute {
            if (closed.get()) return@execute
            runCatching {
                vnc?.pointer(x, y, buttons)
                if (rdp != 0L) LibFreeRDP.sendCursorEvent(rdp, x, y, 0x0800)
            }
        }
    }

    fun desktopKey(code: Int, down: Boolean) {
        if (closed.get()) return
        writer.execute {
            if (closed.get()) return@execute
            runCatching {
                vnc?.key(code, down)
                if (rdp != 0L) LibFreeRDP.sendKeyEvent(rdp, code, down)
            }
        }
    }

    fun desktopText(text: String) {
        writer.execute {
            if (closed.get()) return@execute
            runCatching {
                text.codePoints().forEach { cp ->
                    if (vnc != null) {
                        val code = if (cp <= 255) cp else 0x01000000 or cp
                        vnc?.key(code, true)
                        vnc?.key(code, false)
                    } else if (rdp != 0L) {
                        Character.toChars(cp).forEach {
                            LibFreeRDP.sendUnicodeKeyEvent(rdp, it.code, true)
                            LibFreeRDP.sendUnicodeKeyEvent(rdp, it.code, false)
                        }
                    }
                }
            }
        }
    }

    fun paste(text: String) {
        if (host.protocol.equals("SSH", true)) send(text.toByteArray())
        else
            writer.execute {
                if (closed.get()) return@execute
                runCatching {
                    vnc?.paste(text)
                    if (rdp != 0L) LibFreeRDP.sendClipboardData(rdp, text)
                }
            }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        observer = null
        vnc?.close()
        ssh?.close()
        writer.shutdown()
        UiBridge.io.execute {
            writer.awaitTermination(10, TimeUnit.SECONDS)
            if (rdp != 0L) {
                LibFreeRDP.cancelConnection(rdp)
                LibFreeRDP.freeInstance(rdp)
                LibFreeRDP.detach(rdp)
                rdp = 0
            }
            writer.shutdownNow()
        }
        UiBridge.main.post { terminal.close() }
    }
}
