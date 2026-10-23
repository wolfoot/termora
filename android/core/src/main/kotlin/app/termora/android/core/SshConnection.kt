package app.termora.android.core

import app.termora.*
import com.jcraft.jsch.*
import java.io.*
import java.net.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A connection owns every jump session, channel and listener; close releases all of them. */
class SshConnection(
    private val hosts: () -> List<Host>,
    private val key: (String) -> ByteArray?,
    private val verify: (String, String) -> Boolean,
    private val prompt: (String, Boolean) -> String?,
) : Closeable {
    private val sessions = mutableListOf<Session>()
    private val listeners = mutableListOf<Closeable>()
    val activeForwards = java.util.concurrent.CopyOnWriteArrayList<Pair<Tunneling, Int>>()
    lateinit var session: Session
        private set

    private var shell: ChannelShell? = null
    private var output: OutputStream? = null
    private val writes = Executors.newSingleThreadExecutor()

    fun connect(host: Host) {
        require(host.protocol.equals("SSH", true) || host.protocol.equals("SFTP", true))
        val chain =
            host.options.jumpHosts.map { id ->
                hosts().firstOrNull { it.id == id } ?: error("跳板机不存在: $id")
            } + host
        require(chain.distinctBy { it.id }.size == chain.size && chain.size <= 16) { "跳板机循环或层数过多" }
        try {
            chain.forEachIndexed { index, item ->
                val jsch = JSch()
                if (item.authentication.type == AuthenticationType.PublicKey) {
                    val bytes = key(item.authentication.password) ?: error("找不到私钥")
                    val pass = item.options.extras["keyPassphrase"]?.toByteArray()
                    jsch.addIdentity(item.name, bytes, null, pass)
                }
                val originalPort = item.port.takeIf { it > 0 } ?: 22
                val localPort =
                    if (index == 0) originalPort
                    else sessions.last().setPortForwardingL("127.0.0.1", 0, item.host, originalPort)
                val s =
                    jsch.getSession(
                        item.username,
                        if (index == 0) item.host else "127.0.0.1",
                        localPort,
                    )
                s.hostKeyAlias = "${item.host}:$originalPort"
                s.setConfig("StrictHostKeyChecking", "yes")
                when (item.authentication.type) {
                    AuthenticationType.Password ->
                        s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
                    AuthenticationType.PublicKey ->
                        s.setConfig("PreferredAuthentications", "publickey")
                    AuthenticationType.KeyboardInteractive ->
                        s.setConfig("PreferredAuthentications", "keyboard-interactive")
                    else -> Unit
                }

                jsch.hostKeyRepository =
                    object : HostKeyRepository {
                        override fun check(host: String, bytes: ByteArray): Int =
                            if (verify("ssh:$host", fingerprint(bytes))) HostKeyRepository.OK
                            else HostKeyRepository.CHANGED

                        override fun add(hostkey: HostKey, userinfo: UserInfo?) {}

                        override fun remove(host: String?, type: String?) {}

                        override fun remove(host: String?, type: String?, key: ByteArray?) {}

                        override fun getKnownHostsRepositoryID() = "Android vault"

                        override fun getHostKey() = emptyArray<HostKey>()

                        override fun getHostKey(host: String?, type: String?) =
                            emptyArray<HostKey>()
                    }
                var secret = item.authentication.password
                s.userInfo =
                    object : UserInfo, UIKeyboardInteractive {
                        override fun getPassword() = secret

                        override fun getPassphrase() = item.options.extras["keyPassphrase"]

                        override fun promptPassword(message: String): Boolean {
                            secret = prompt(message, true) ?: return false
                            return true
                        }

                        override fun promptPassphrase(message: String) = false

                        override fun promptYesNo(message: String) = false

                        override fun showMessage(message: String) {}

                        override fun promptKeyboardInteractive(
                            destination: String,
                            name: String,
                            instruction: String,
                            prompts: Array<String>,
                            echo: BooleanArray,
                        ): Array<String>? {
                            val answers = prompts.mapIndexed { i, p ->
                                prompt("$instruction\n$p", !echo[i]) ?: return null
                            }
                            return answers.toTypedArray()
                        }
                    }
                if (item.authentication.type == AuthenticationType.Password) s.setPassword(secret)
                if (index == 0)
                    when (item.proxy.type) {
                        ProxyType.HTTP ->
                            s.setProxy(
                                ProxyHTTP(item.proxy.host, item.proxy.port).apply {
                                    setUserPasswd(item.proxy.username, item.proxy.password)
                                },
                            )
                        ProxyType.SOCKS5 ->
                            s.setProxy(
                                ProxySOCKS5(item.proxy.host, item.proxy.port).apply {
                                    setUserPasswd(item.proxy.username, item.proxy.password)
                                },
                            )
                        else -> Unit
                    }
                s.serverAliveInterval = item.options.heartbeatInterval.coerceAtLeast(5) * 1000
                sessions += s
                s.connect(20_000)
            }
            session = sessions.last()
            host.tunnelings.forEach(::forward)
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    fun startShell(
        host: Host,
        onText: (String) -> Unit,
        onClosed: (String?) -> Unit,
        rows: Int = 24,
        cols: Int = 80,
    ) {
        val c = session.openChannel("shell") as ChannelShell
        c.setPtyType("xterm-256color", cols, rows, 0, 0)
        host.options.envs().forEach { (k, v) -> c.setEnv(k, v) }
        val input = c.inputStream
        output = c.outputStream
        shell = c
        c.connect(20_000)
        Thread(
                {
                    try {
                        val reader = InputStreamReader(input, charset(host.options.encoding))
                        val buf = CharArray(4096)
                        var scriptIndex = 0
                        var tail = ""
                        if (host.options.startupCommand.isNotEmpty())
                            write(
                                (host.options.startupCommand + "\r").toByteArray(
                                    charset(host.options.encoding),
                                ),
                            )
                        while (true) {
                            val n = reader.read(buf)
                            if (n < 0) break
                            val text = String(buf, 0, n)
                            onText(text)
                            tail = (tail + text).takeLast(16384)
                            while (scriptIndex < host.options.loginScripts.size) {
                                val script = host.options.loginScripts[scriptIndex]
                                val matches =
                                    if (script.regex)
                                        Regex(
                                                script.expect,
                                                if (script.matchCase) emptySet()
                                                else setOf(RegexOption.IGNORE_CASE),
                                            )
                                            .containsMatchIn(tail)
                                    else tail.contains(script.expect, !script.matchCase)
                                if (!matches) break
                                write(
                                    script.send
                                        .replace("\\r", "\r")
                                        .replace("\\n", "\n")
                                        .toByteArray(charset(host.options.encoding)),
                                )
                                scriptIndex++
                                tail = ""
                            }
                        }
                        onClosed(null)
                    } catch (e: Exception) {
                        onClosed(e.message)
                    }
                },
                "ssh-reader",
            )
            .start()
    }

    fun write(bytes: ByteArray) {
        writes.execute {
            output?.let {
                it.write(bytes)
                it.flush()
            }
        }
    }

    fun resize(rows: Int, cols: Int) {
        shell?.setPtySize(cols, rows, 0, 0)
    }

    fun exec(command: String, timeoutSeconds: Long = 30): String {
        val c = session.openChannel("exec") as ChannelExec
        val errors = ByteArrayOutputStream()
        c.setCommand(command)
        c.setErrStream(errors)
        val input = c.inputStream
        val bytes = ByteArrayOutputStream()
        try {
            c.connect(15_000)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            val buf = ByteArray(8192)
            while (!c.isClosed || input.available() > 0) {
                if (input.available() > 0) {
                    val n = input.read(buf)
                    if (n > 0) bytes.write(buf, 0, n)
                } else Thread.sleep(10)
                require(bytes.size() <= 16 * 1024 * 1024) { "命令输出过大" }
                check(System.nanoTime() < deadline) { "命令超时" }
            }
            check(c.exitStatus == 0) {
                errors.toString("UTF-8").ifBlank { "远程命令失败 (${c.exitStatus})" }
            }
            return bytes.toString("UTF-8")
        } finally {
            c.disconnect()
        }
    }

    fun sftp() = (session.openChannel("sftp") as ChannelSftp).apply { connect(15_000) }

    fun forward(t: Tunneling): Int {
        val allocated =
            when (t.type) {
                TunnelingType.Local ->
                    session.setPortForwardingL(
                        t.sourceHost.ifBlank { "127.0.0.1" },
                        t.sourcePort,
                        t.destinationHost,
                        t.destinationPort,
                    )
                TunnelingType.Remote -> {
                    require(t.sourcePort in 1..65535) { "远程监听端口必须大于 0" }
                    session.setPortForwardingR(
                        t.sourceHost.ifBlank { "127.0.0.1" },
                        t.sourcePort,
                        t.destinationHost,
                        t.destinationPort,
                    )
                    t.sourcePort
                }
                TunnelingType.Dynamic ->
                    SocksProxy(session, t.sourceHost.ifBlank { "127.0.0.1" }, t.sourcePort)
                        .also { listeners += it }
                        .port
            }

        activeForwards.add(t to allocated)
        return allocated
    }

    override fun close() {
        listeners.forEach { runCatching { it.close() } }
        shell?.disconnect()
        sessions.reversed().forEach { it.disconnect() }
        writes.shutdownNow()
    }

    companion object {
        init {
            JSch.setConfig("signature.ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        }

        fun fingerprint(bytes: ByteArray) =
            "SHA256:" +
                java.util.Base64.getEncoder()
                    .withoutPadding()
                    .encodeToString(
                        java.security.MessageDigest.getInstance("SHA-256").digest(bytes),
                    )
    }
}

/** SOCKS5 CONNECT only; every socket is retained for deterministic shutdown. */
class SocksProxy(private val session: Session, host: String, port: Int) : Closeable {
    private val server = ServerSocket().apply { bind(InetSocketAddress(host, port)) }
    val port
        get() = server.localPort

    private val closed = AtomicBoolean(false)
    private val clients = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())
    private val workers = Executors.newCachedThreadPool()

    init {
        workers.execute {
            while (!closed.get()) try {
                val socket = server.accept()
                clients += socket
                workers.execute { handle(socket) }
            } catch (e: IOException) {
                if (!closed.get()) close()
            }
        }
    }

    private fun handle(socket: Socket) {
        var channel: ChannelDirectTCPIP? = null
        try {
            socket.soTimeout = 15_000
            val i = DataInputStream(socket.getInputStream())
            val o = socket.getOutputStream()
            require(i.readUnsignedByte() == 5)
            val methods = ByteArray(i.readUnsignedByte())
            i.readFully(methods)
            if (0.toByte() !in methods) {
                o.write(byteArrayOf(5, -1))
                return
            }
            o.write(byteArrayOf(5, 0))
            o.flush()
            require(i.readUnsignedByte() == 5)
            val cmd = i.readUnsignedByte()
            require(i.readUnsignedByte() == 0)
            val target =
                when (i.readUnsignedByte()) {
                    1 -> InetAddress.getByAddress(ByteArray(4).also(i::readFully)).hostAddress!!
                    3 ->
                        String(
                            ByteArray(i.readUnsignedByte()).also(i::readFully),
                            Charsets.US_ASCII,
                        )
                    4 -> InetAddress.getByAddress(ByteArray(16).also(i::readFully)).hostAddress!!
                    else -> error("SOCKS 地址类型")
                }
            val port = i.readUnsignedShort()
            if (cmd != 1) {
                o.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
                return
            }
            channel =
                (session.openChannel("direct-tcpip") as ChannelDirectTCPIP).apply {
                    setHost(target)
                    setPort(port)
                    setOrgIPAddress(socket.inetAddress.hostAddress)
                    setOrgPort(socket.port)
                }
            val remote = channel.inputStream
            val writer = channel.outputStream
            channel.connect(15_000)
            socket.soTimeout = 0
            o.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            o.flush()
            workers.execute {
                runCatching {
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = i.read(buffer)
                        if (n < 0) break
                        writer.write(buffer, 0, n)
                        writer.flush()
                    }
                    writer.close()
                }
            }
            remote.copyTo(o)
        } catch (e: Exception) {
            runCatching {
                socket.getOutputStream().write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
            }
        } finally {
            channel?.disconnect()
            clients -= socket
            socket.close()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            server.close()
            synchronized(clients) { clients.toList().forEach { runCatching { it.close() } } }
            workers.shutdownNow()
        }
    }
}
