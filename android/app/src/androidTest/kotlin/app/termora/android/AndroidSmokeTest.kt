package app.termora.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.termora.Host
import app.termora.android.core.*
import com.freerdp.freerdpcore.services.LibFreeRDP
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSmokeTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun testKeystoreVaultPersistsOnlyCiphertext() {
        val context = instrumentation.targetContext
        val store = VaultStore(context, "test-vault")
        val v =
            Vault(
                hosts =
                    listOf(Host(name = "secret-fixture", protocol = "SSH", host = "example.test")),
            )
        store.save(v)
        assertEquals("secret-fixture", store.load().hosts.single().name)
        assertFalse(
            File(context.filesDir, "test-vault.gcm")
                .readBytes()
                .toString(Charsets.ISO_8859_1)
                .contains("secret-fixture"),
        )
        store.save(Vault())
    }

    @Test
    fun testEmbeddedRdpLoadsAndParsesConnectionOptions() {
        assertTrue(LibFreeRDP.getVersion().contains("3.32.2"))
        val id = LibFreeRDP.newInstance(instrumentation.targetContext)
        assertTrue(id != 0L)
        try {
            assertTrue(
                LibFreeRDP.freerdp_parse_arguments(
                    id,
                    arrayOf(
                        "Termora",
                        "/v:127.0.0.1",
                        "/port:3389",
                        "/gdi:sw",
                        "/u:test",
                        "/size:1280x720",
                        "/bpp:32",
                        "/kbd:unicode:on",
                        "/clipboard",
                        "/disp",
                        "/audio-mode:0",
                        "/sound",
                        "/gfx:AVC444",
                        "/log-level:ERROR",
                    ),
                ),
            )
        } finally {
            LibFreeRDP.freeInstance(id)
        }
    }

    @Test
    fun testAndroid9Ed25519OpenSshKeyAndAnsiModel() {
        val key = Keys.generate("Android", true)
        val jsch = com.jcraft.jsch.JSch()
        jsch.addIdentity("android", Keys.sshPrivatePem(key), null, null)
        assertEquals(1, jsch.identityRepository.identities.size)
        val t = app.termora.terminal.VisualTerminal()
        t.write("hello\r\n\u001b[32m中文\u001b[0m")
        assertTrue(t.getDocument().getText().contains("中文"))
        t.close()
    }

    @Test
    fun testAndroidSshSftpAndTerminalScreen() {
        val args = InstrumentationRegistry.getArguments()
        val port = args.getString("fixturePort")?.toIntOrNull() ?: 0
        org.junit.Assume.assumeTrue("Provide a disposable fixture server", port > 0)
        val pin = args.getString("fixturePin")!!
        val host =
            Host(
                name = "Android fixture",
                protocol = "SSH",
                host = "10.0.2.2",
                port = port,
                username = "test",
                authentication =
                    app.termora.Authentication(
                        app.termora.AuthenticationType.Password,
                        "fixture-password",
                    ),
            )
        SshConnection({ listOf(host) }, { null }, { _, actual -> actual == pin }, { _, _ -> null })
            .use { c ->
                c.connect(host)
                assertEquals("Android SSH works", c.exec("printf 'Android SSH works'"))
                SftpStore(c).use { s ->
                    val bytes = "中文 SFTP works".toByteArray()
                    s.write(
                        "/android-test.txt",
                        java.io.ByteArrayInputStream(bytes),
                        bytes.size.toLong(),
                    )
                    val output = java.io.ByteArrayOutputStream()
                    s.read("/android-test.txt", output)
                    assertTrue(bytes.contentEquals(output.toByteArray()))
                    s.delete("/android-test.txt", false)
                }
            }
        var original = Vault()
        instrumentation.runOnMainSync {
            Repository.init(instrumentation.targetContext)
            original = Repository.value
            Repository.update {
                it.copy(hosts = it.hosts + host, pins = it.pins + ("ssh:10.0.2.2:$port" to pin))
            }
        }
        val activity =
            instrumentation.startActivitySync(
                android.content
                    .Intent(instrumentation.targetContext, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
        try {
            fun views(v: android.view.View): List<android.view.View> =
                listOf(v) +
                    (if (v is android.view.ViewGroup)
                        (0 until v.childCount).flatMap { views(v.getChildAt(it)) }
                    else emptyList())
            instrumentation.runOnMainSync {
                val button =
                    views(activity.window.decorView)
                        .filterIsInstance<android.widget.Button>()
                        .first { it.text.toString().contains("Android fixture") }
                button.performClick()
            }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
            while (
                System.nanoTime() < deadline &&
                    ConnectionService.sessions.values.filterIsInstance<MobileSession>().none {
                        it.status == "已连接"
                    }
            ) Thread.sleep(100)
            val session =
                ConnectionService.sessions.values.filterIsInstance<MobileSession>().first {
                    it.host.id == host.id
                }
            assertEquals("已连接", session.status)
            session.send("printf 'TERMORA_ANDROID_SCREEN_OK\\n'\r".toByteArray())
            var found = false
            val screenDeadline =
                System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
            while (!found && System.nanoTime() < screenDeadline) {
                instrumentation.runOnMainSync {
                    found =
                        session.terminal
                            .getDocument()
                            .getText()
                            .contains("TERMORA_ANDROID_SCREEN_OK")
                }
                Thread.sleep(100)
            }
            assertTrue("Remote shell output must reach the ANSI document", found)
            instrumentation.runOnMainSync {
                val view =
                    views(activity.window.decorView).filterIsInstance<TerminalView>().single()
                assertTrue(view.width > 0 && view.height > 0)
                assertTrue(session.terminal.getTerminalModel().getCols() >= 10)
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                val file =
                    File(
                        instrumentation.targetContext.getExternalFilesDir(null),
                        "terminal-test.png",
                    )
                file.outputStream().use {
                    image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        } finally {
            ConnectionService.sessions.values
                .filterIsInstance<MobileSession>()
                .filter { it.host.id == host.id }
                .forEach {
                    ConnectionService.sessions.remove(it.id)
                    it.close()
                }
            instrumentation.runOnMainSync {
                Repository.update { original }
                activity.finish()
            }
        }
    }
}
