package app.termora.android.core

import app.termora.*
import java.io.*
import java.net.*
import java.nio.file.Files
import java.util.concurrent.*
import kotlin.test.*
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.jupiter.api.Test

class SshIntegrationTest {
    private fun server(root: java.nio.file.Path, publicKey: java.security.PublicKey? = null) =
        SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider(root.resolve("host-key"))
            passwordAuthenticator =
                org.apache.sshd.server.auth.password.PasswordAuthenticator { user, pass, _ ->
                    user == "test" && pass == "password"
                }
            publickeyAuthenticator =
                org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { user, key, _ ->
                    user == "test" &&
                        publicKey != null &&
                        key.encoded.contentEquals(publicKey.encoded)
                }
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            commandFactory =
                org.apache.sshd.server.command.CommandFactory { channel, command ->
                    org.apache.sshd.server.shell
                        .ProcessShellFactory("/bin/sh", "/bin/sh", "-c", command)
                        .createShell(channel)
                }
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            fileSystemFactory = VirtualFileSystemFactory(root)
            start()
        }

    private fun host(port: Int) =
        Host(
            name = "fixture",
            protocol = "SSH",
            host = "127.0.0.1",
            port = port,
            username = "test",
            authentication = Authentication(AuthenticationType.Password, "password"),
        )

    @Test
    fun passwordSftpExecAndHostKeyRejection() {
        val root = Files.createTempDirectory("termora-ssh-test")
        val server = server(root)
        try {
            val h = host(server.port)
            var verified = ""
            SshConnection(
                    { listOf(h) },
                    { null },
                    { name, pin ->
                        verified = "$name $pin"
                        true
                    },
                    { _, _ -> null },
                )
                .use { connection ->
                    connection.connect(h)
                    assertTrue(verified.startsWith("ssh:127.0.0.1:${server.port} SHA256:"))
                    assertEquals("hello", connection.exec("printf hello"))
                    SftpStore(connection).use { store ->
                        store.mkdir("/测试 folder")
                        val content = "hello 中文".toByteArray()
                        val path = "/测试 folder/file's.txt"
                        store.write(path, ByteArrayInputStream(content), content.size.toLong())
                        assertEquals("file's.txt", store.list("/测试 folder").single().name)
                        val out = ByteArrayOutputStream()
                        store.read(path, out)
                        assertContentEquals(content, out.toByteArray())
                        store.chmod(path, 384)
                        assertEquals(384, store.list("/测试 folder").single().permissions!! and 511)
                        store.rename(path, "/测试 folder/renamed.txt")
                        store.delete("/测试 folder/renamed.txt", false)
                        store.delete("/测试 folder", true)
                        val source = root.resolve("source file's.txt")
                        Files.writeString(source, "archive content")
                        val archive = root.resolve("archive's.tar.gz")
                        val extracted = root.resolve("extract folder")
                        Files.createDirectory(extracted)
                        store.archive(source.toString(), archive.toString(), false)
                        store.archive(archive.toString(), extracted.toString(), true)
                        assertEquals(
                            "archive content",
                            Files.readString(extracted.resolve(source.fileName)),
                        )
                    }
                }
            SshConnection({ listOf(h) }, { null }, { _, _ -> false }, { _, _ -> null }).use {
                assertFails { it.connect(h) }
            }
        } finally {
            server.stop(true)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun publicKeyAndJumpHostAuthenticateSeparately() {
        val key = Keys.generate("auth", false)
        val publicKey =
            AuthorizedKeyEntry.parseAuthorizedKeyEntry(Keys.publicSsh(key))
                .resolvePublicKey(null, PublicKeyEntryResolver.IGNORING)
        val root1 = Files.createTempDirectory("termora-jump")
        val root2 = Files.createTempDirectory("termora-target")
        val jumpServer = server(root1)
        val targetServer = server(root2, publicKey)
        try {
            val jump = host(jumpServer.port)
            val target =
                host(targetServer.port)
                    .copy(
                        authentication = Authentication(AuthenticationType.PublicKey, key.id),
                        options = Options(jumpHosts = listOf(jump.id)),
                    )
            val pins = mutableSetOf<String>()
            SshConnection(
                    { listOf(jump, target) },
                    { id -> if (id == key.id) Keys.sshPrivatePem(key) else null },
                    { id, _ ->
                        pins += id
                        true
                    },
                    { _, _ -> null },
                )
                .use { c ->
                    c.connect(target)
                    assertEquals("jump works", c.exec("printf 'jump works'"))
                    assertEquals(2, pins.size)
                }
        } finally {
            jumpServer.stop(true)
            targetServer.stop(true)
            root1.toFile().deleteRecursively()
            root2.toFile().deleteRecursively()
        }
    }

    @Test
    fun localRemoteAndSocksForwardRealTraffic() {
        val root = Files.createTempDirectory("termora-forward")
        val sshd = server(root)
        val h = host(sshd.port)
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { echo ->
            val workers = Executors.newCachedThreadPool()
            val accepting = workers.submit {
                while (!echo.isClosed) runCatching {
                    val s = echo.accept()
                    workers.execute {
                        s.use {
                            val i = DataInputStream(it.getInputStream())
                            val bytes = ByteArray(4)
                            i.readFully(bytes)
                            it.getOutputStream().write(bytes)
                        }
                    }
                }
            }
            try {
                SshConnection({ listOf(h) }, { null }, { _, _ -> true }, { _, _ -> null }).use { c
                    ->
                    c.connect(h)
                    val local =
                        c.forward(
                            Tunneling(
                                type = TunnelingType.Local,
                                sourcePort = 0,
                                destinationHost = "127.0.0.1",
                                destinationPort = echo.localPort,
                            ),
                        )
                    Socket("127.0.0.1", local).use { s ->
                        s.soTimeout = 5000
                        s.getOutputStream().write("test".toByteArray())
                        assertEquals(
                            "test",
                            String(
                                ByteArray(4).also {
                                    DataInputStream(s.getInputStream()).readFully(it)
                                },
                            ),
                        )
                    }
                    val remotePort = ServerSocket(0).use { it.localPort }
                    c.forward(
                        Tunneling(
                            type = TunnelingType.Remote,
                            sourceHost = "127.0.0.1",
                            sourcePort = remotePort,
                            destinationHost = "127.0.0.1",
                            destinationPort = echo.localPort,
                        ),
                    )
                    Socket("127.0.0.1", remotePort).use { s ->
                        s.soTimeout = 5000
                        s.getOutputStream().write("back".toByteArray())
                        assertEquals(
                            "back",
                            String(
                                ByteArray(4).also {
                                    DataInputStream(s.getInputStream()).readFully(it)
                                },
                            ),
                        )
                    }
                    val socks = c.forward(Tunneling(type = TunnelingType.Dynamic, sourcePort = 0))
                    Socket("127.0.0.1", socks).use { s ->
                        s.soTimeout = 5000
                        val i = DataInputStream(s.getInputStream())
                        val o = DataOutputStream(s.getOutputStream())
                        o.write(byteArrayOf(5, 1, 0))
                        o.flush()
                        assertContentEquals(byteArrayOf(5, 0), ByteArray(2).also(i::readFully))
                        o.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1))
                        o.writeShort(echo.localPort)
                        o.flush()
                        val reply = ByteArray(10).also(i::readFully)
                        assertEquals(0, reply[1].toInt())
                        o.write("sock".toByteArray())
                        o.flush()
                        assertEquals("sock", String(ByteArray(4).also(i::readFully)))
                    }
                }
            } finally {
                echo.close()
                accepting.cancel(true)
                workers.shutdownNow()
                sshd.stop(true)
                root.toFile().deleteRecursively()
            }
        }
    }
}
