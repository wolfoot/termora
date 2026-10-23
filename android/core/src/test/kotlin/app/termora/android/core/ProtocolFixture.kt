package app.termora.android.core

import java.nio.file.Files
import kotlinx.serialization.json.*
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory

/** Loopback-only server for disposable emulator tests; never accesses real host configuration. */
object ProtocolFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = Files.createTempDirectory("termora-device-ssh")
        val provider = SimpleGeneratorHostKeyProvider(root.resolve("host-key"))
        val server =
            SshServer.setUpDefaultServer().apply {
                host = "127.0.0.1"
                port = 0
                keyPairProvider = provider
                passwordAuthenticator = PasswordAuthenticator { u, p, _ ->
                    u == "test" && p == "fixture-password"
                }
                shellFactory =
                    object : ProcessShellFactory("/bin/sh", "sh", "-i") {
                        override fun createShell(
                            channel: org.apache.sshd.server.channel.ChannelSession,
                        ): org.apache.sshd.server.command.Command {
                            channel.environment.ptyModes[
                                    org.apache.sshd.common.channel.PtyMode.ICRNL] = 1
                            return super.createShell(channel)
                        }
                    }
                commandFactory =
                    org.apache.sshd.server.command.CommandFactory { channel, command ->
                        org.apache.sshd.server.shell
                            .ProcessShellFactory("/bin/sh", "/bin/sh", "-c", command)
                            .createShell(channel)
                    }
                forwardingFilter = AcceptAllForwardingFilter.INSTANCE
                subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
                fileSystemFactory = VirtualFileSystemFactory(root)
                start()
            }
        val pin =
            KeyUtils.getFingerPrint(
                org.apache.sshd.common.digest.BuiltinDigests.sha256,
                provider.loadKeys(null).first().public,
            )
        java.io
            .File(args[0])
            .writeText(
                buildJsonObject {
                    put("port", server.port)
                    put("pin", pin)
                }
                    .toString(),
            )
        Runtime.getRuntime()
            .addShutdownHook(
                Thread {
                    server.stop(true)
                    root.toFile().deleteRecursively()
                },
            )
        println("Disposable SSH/SFTP fixture is ready on loopback port ${server.port}")
        java.util.concurrent.CountDownLatch(1).await()
    }
}
