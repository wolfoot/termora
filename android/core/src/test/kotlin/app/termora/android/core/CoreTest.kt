package app.termora.android.core

import app.termora.*
import app.termora.terminal.*
import java.io.*
import java.net.*
import java.time.Instant
import java.util.concurrent.*
import kotlin.test.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Test

class CoreTest {
    @Test
    fun terminalUsesDesktopAnsiParserAndAlternateBuffer() {
        val t = VisualTerminal()
        t.getTerminalModel().resize(24, 80)
        t.write("hello\r\n\u001b[31mred\u001b[0m\r\n中文")
        assertTrue(t.getDocument().getText().contains("hello"))
        assertTrue(t.getDocument().getText().contains("中文"))
        assertNotEquals(0, t.getDocument().getScreenLine(2).chars().first().second.foreground)
        t.write("\u001b[?1049hother")
        assertTrue(t.getTerminalModel().isAlternateScreenBuffer())
        assertTrue(t.getDocument().getText().contains("other"))
        t.write("\u001b[?1049l")
        assertFalse(t.getTerminalModel().isAlternateScreenBuffer())
        assertTrue(t.getDocument().getText().contains("hello"))
        t.getTerminalModel().resize(30, 100)
        assertEquals(100, t.getTerminalModel().getCols())
        t.close()
    }

    @Test
    fun secureSyncIsWireCompatibleWithDesktopAndTamperFails() {
        val host =
            Host(
                name = "服务器",
                protocol = "SSH",
                host = "example.test",
                port = 22,
                authentication = Authentication(AuthenticationType.Password, "secret"),
                options = Options(jumpHosts = listOf("jump")),
                updateDate = 42,
            )
        val vault = Vault(hosts = listOf(host))
        val files = vault.syncFiles()
        val list = wireJson.decodeFromString<List<EncryptedHost>>(files.getValue("Hosts"))
        assertEquals("22", list.single().port)
        assertEquals(
            "secret",
            wireJson.decodeFromString<Authentication>(list.single().authentication).password,
        )
        assertEquals(host.options, vault.merge(files).hosts.single().options)
        val password = "independent-test-password"
        val encrypted =
            SyncEncryption.encryptWithPassword(password, files.getValue("Hosts"), "Hosts")
        assertTrue(encrypted.startsWith("termora-pbe-v2:"))
        assertFalse(encrypted.contains("secret"))
        assertEquals(
            files.getValue("Hosts"),
            SyncEncryption.decryptWithPassword(password, encrypted, "Hosts"),
        )
        assertFails { SyncEncryption.decryptWithPassword(password, encrypted, "Tags") }
        assertFails { SyncEncryption.decryptWithPassword("incorrect-password", encrypted, "Hosts") }
        val deleted =
            Vault(hosts = listOf(host), deleted = listOf(DeletedData(host.id, "Host", 43)))
                .merge(files)
        assertTrue(deleted.hosts.isEmpty())
    }

    @Test
    fun rsaAndEd25519KeysRoundTripDesktopDerAndSshPem() {
        for (ed in listOf(false, true)) {
            val key = Keys.generate("test", ed)
            assertTrue(Keys.publicSsh(key).startsWith(if (ed) "ssh-ed25519 " else "ssh-rsa "))
            val imported = Keys.import("imported", Keys.privatePem(key))
            assertEquals(key.privateKey, imported.privateKey)
            assertEquals(key.publicKey, imported.publicKey)
        }
    }

    @Test
    fun ecdsaKeysRemainCompatibleWithDesktop() {
        val key = Keys.generateEcdsa("ECDSA")
        assertEquals("ECDSA-SHA2-NISTP256", key.type)
        assertTrue(Keys.publicSsh(key).startsWith("ecdsa-sha2-nistp256 "))
        val imported = Keys.import("copy", Keys.privatePem(key))
        assertEquals(key.publicKey, imported.publicKey)
        val a =
            org.bouncycastle.crypto.util.PrivateKeyFactory.createKey(
                java.util.Base64.getDecoder().decode(key.privateKey),
            ) as org.bouncycastle.crypto.params.ECPrivateKeyParameters
        val b =
            org.bouncycastle.crypto.util.PrivateKeyFactory.createKey(
                java.util.Base64.getDecoder().decode(imported.privateKey),
            ) as org.bouncycastle.crypto.params.ECPrivateKeyParameters
        assertEquals(a.d, b.d)
        val jsch = com.jcraft.jsch.JSch()
        jsch.addIdentity("ECDSA", Keys.sshPrivatePem(key), null, null)
        assertEquals(1, jsch.identityRepository.identities.size)
    }

    @Test
    fun xmlRejectsEntitiesAndPathsRejectTraversal() {
        assertFails {
            safeXml(
                "<!DOCTYPE x [<!ENTITY y SYSTEM 'file:///etc/passwd'>]><x>&y;</x>".toByteArray(),
            )
        }
        for (name in listOf("..", "../x", "a/b", "a\\b", "\u0000")) assertFails {
            remotePath("/safe", name)
        }
        assertEquals("/safe/中文 file's.txt", remotePath("/safe", "中文 file's.txt"))
    }

    @Test
    fun awsSignatureMatchesPublishedExample() {
        val signed =
            ObjectSigner.sign(
                "S3",
                "GET",
                "https://examplebucket.s3.amazonaws.com/test.txt".toHttpUrl(),
                "examplebucket",
                "us-east-1",
                "AKIAIOSFODNN7EXAMPLE",
                "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                mapOf("range" to "bytes=0-9"),
                Instant.parse("2013-05-24T00:00:00Z"),
            )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            signed["authorization"],
        )
    }

    @Test
    fun vncHandshakePixelsClipboardAndPointerAreRealProtocolMessages() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val pool = Executors.newSingleThreadExecutor()
            val frame = CompletableFuture<IntArray>()
            val clip = CompletableFuture<String>()
            val pointer = CompletableFuture<Pair<Int, Int>>()
            val client =
                VncClient(
                    { w, h, pixels ->
                        assertEquals(2, w)
                        assertEquals(1, h)
                        frame.complete(pixels)
                    },
                    { clip.complete(it) },
                )
            val worker = pool.submit {
                runCatching { client.connect("127.0.0.1", server.localPort, "") }
            }
            server.accept().use { s ->
                s.soTimeout = 5000
                val i = DataInputStream(s.getInputStream())
                val o = DataOutputStream(s.getOutputStream())
                o.write("RFB 003.008\n".toByteArray())
                o.flush()
                assertEquals("RFB 003.008\n", String(ByteArray(12).also(i::readFully)))
                o.write(byteArrayOf(1, 1))
                o.flush()
                assertEquals(1, i.readUnsignedByte())
                o.writeInt(0)
                o.flush()
                assertEquals(1, i.readUnsignedByte())
                o.writeShort(2)
                o.writeShort(1)
                o.write(ByteArray(16))
                o.writeInt(4)
                o.write("test".toByteArray())
                o.flush()
                i.readFully(ByteArray(20))
                assertEquals(2, i.readUnsignedByte())
                i.readByte()
                val n = i.readUnsignedShort()
                repeat(n) { i.readInt() }
                i.readFully(ByteArray(10))
                o.writeByte(0)
                o.writeByte(0)
                o.writeShort(1)
                o.writeShort(0)
                o.writeShort(0)
                o.writeShort(2)
                o.writeShort(1)
                o.writeInt(0)
                o.write(byteArrayOf(0, 0, (-1).toByte(), 0, 0, (-1).toByte(), 0, 0))
                o.flush()
                assertContentEquals(
                    intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt()),
                    frame.get(5, TimeUnit.SECONDS),
                )
                i.readFully(ByteArray(10))
                o.writeByte(3)
                o.write(ByteArray(3))
                o.writeInt(5)
                o.write("hello".toByteArray())
                o.flush()
                assertEquals("hello", clip.get(5, TimeUnit.SECONDS))
                client.pointer(1, 0, 1)
                assertEquals(5, i.readUnsignedByte())
                assertEquals(1, i.readUnsignedByte())
                assertEquals(1, i.readUnsignedShort())
                assertEquals(0, i.readUnsignedShort())
                client.key(0xff0d, true)
                assertEquals(4, i.readUnsignedByte())
                assertEquals(1, i.readUnsignedByte())
                i.readShort()
                assertEquals(0xff0d, i.readInt())
            }
            client.close()
            worker.get(5, TimeUnit.SECONDS)
            pool.shutdownNow()
        }
    }
}
