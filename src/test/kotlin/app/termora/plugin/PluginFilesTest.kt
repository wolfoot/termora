package app.termora.plugin

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse

class PluginFilesTest {
    @Test
    fun extractsNestedFilesAndRejectsEscapes() = temporary { root ->
        val destination = root.resolve("plugin")
        PluginFiles.extract(archive("lib/插件.jar"), destination)
        assertEquals("test", Files.readString(destination.resolve("lib/插件.jar")))
        for (name in listOf("../outside", "/outside", "lib/../../outside", "C:/outside", "C:\\outside", "..\\outside", ".")) {
            assertFails(name) { PluginFiles.extract(archive(name), destination) }
            assertFalse(Files.exists(root.resolve("outside")))
        }
        assertFails { PluginFiles.extract(archive("lib/插件.jar"), destination) }
    }

    @Test
    fun rejectsSymbolicLinksAndUntrustedPluginIds() = temporary { root ->
        for (id in listOf("..", "../outside", "/outside", "C:outside", "a/b", "a\\b", "")) {
            assertFails { PluginFiles.directory(root, id) }
        }
        assertEquals(root.toRealPath().resolve("app.termora-sync"), PluginFiles.directory(root, "app.termora-sync"))
        if (System.getProperty("os.name").startsWith("Windows")) return@temporary
        val outside = Files.createDirectory(root.resolve("outside"))
        val destination = Files.createDirectory(root.resolve("plugin"))
        Files.createSymbolicLink(destination.resolve("linked"), outside)
        assertFails { PluginFiles.extract(archive("linked/marker"), destination) }
        assertFalse(Files.exists(outside.resolve("marker")))
        assertFails { PluginFiles.directory(destination, "linked") }
    }

    private fun archive(name: String): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use {
            it.putNextEntry(ZipEntry(name))
            it.write("test".toByteArray())
            it.closeEntry()
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    private fun temporary(test: (Path) -> Unit) {
        val directory = Files.createTempDirectory("termora-plugin-test")
        try {
            test(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
