package app.termora.plugins.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SyncFileCodecTest {
    private val config = SyncConfig(SyncType.GitHub, "provider-token", "gist", emptyMap(),
        encryptionPassword = "independent encryption password")

    @Test
    fun authenticatesCredentialsAndDeletionMetadataBeforeImport() {
        val files = listOf(GistFile("Hosts", "[{\"password\":\"secret\"}]"), GistFile("DeletedData", "[{\"id\":\"record\"}]"))
        val encrypted = SyncFileCodec.encode(files, config)
        assertEquals(files, SyncFileCodec.decode(encrypted, config))
        assertEquals(files, SyncFileCodec.decode(encrypted, config.copy(token = "rotated provider token")))
        assertNotEquals(encrypted, SyncFileCodec.encode(files, config))
        assertFails { SyncFileCodec.decode(encrypted, config.copy(encryptionPassword = "wrong password")) }
        assertFails { SyncFileCodec.decode(listOf(encrypted.first().copy(filename = "DeletedData")), config) }
        assertFails { SyncFileCodec.decode(listOf(encrypted.first(), encrypted.first()), config) }
        assertFails { SyncFileCodec.encode(files, config.copy(encryptionPassword = config.token)) }
        assertFails { SyncFileCodec.encode(files, config.copy(encryptionPassword = "")) }
        assertFails { SyncFileCodec.decode(listOf(encrypted.first(), GistFile("DeletedData", "[]")), config) }
    }

    @Test
    fun legacyImportIsExplicitAndNeverUsedByNewWrites() {
        val legacy = listOf(GistFile("Hosts", "[]"), GistFile("DeletedData", "[]"))
        assertFails { SyncFileCodec.decode(legacy, config) }
        assertTrue(SyncFileCodec.decode(legacy, config.copy(allowLegacyImport = true)).all { it.legacy })
        val secure = SyncFileCodec.encode(legacy, config.copy(allowLegacyImport = true))
        assertEquals(legacy, SyncFileCodec.decode(secure, config))
        assertFails { SyncFileCodec.decode(listOf(GistFile("Hosts", "termora-pbe-v2:AA==")),
            config.copy(allowLegacyImport = true)) }
        assertFails { SyncFileCodec.decode(listOf(GistFile("Hosts", "termora-pbe-v99:AA==")),
            config.copy(allowLegacyImport = true)) }
    }

    @Test
    fun aPartialMigrationDoesNotImportUnselectedLegacyFiles() {
        val hostsOnly = config.copy(ranges = setOf(SyncRange.Hosts))
        val selected = listOf(GistFile("Hosts", "[]"), GistFile("DeletedData", "[]"))
        val remote = SyncFileCodec.encode(selected, hostsOnly) + GistFile("KeyPairs", "legacy private keys")
        assertEquals(selected, SyncFileCodec.decode(remote, hostsOnly))
        assertFails { SyncFileCodec.decode(remote, config) }
    }

    @Test
    fun protectsEverySupportedDataCategory() {
        val allRanges = config.copy(ranges = SyncRange.entries.toSet())
        val names = listOf("Hosts", "Tags", "Snippets", "KeyPairs", "KeywordHighlights", "Macros", "Keymaps", "DeletedData")
        val files = names.map { GistFile(it, "[]") }
        assertEquals(files, SyncFileCodec.decode(SyncFileCodec.encode(files, allRanges), allRanges))
    }
}
