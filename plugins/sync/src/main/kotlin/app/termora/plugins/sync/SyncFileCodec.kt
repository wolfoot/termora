package app.termora.plugins.sync

import app.termora.SyncEncryption

/** Encrypt complete files, including IDs, timestamps and deletion records, before sending them. */
internal object SyncFileCodec {
    private val filenames = setOf("Hosts", "Tags", "Snippets", "KeyPairs", "KeywordHighlights", "Macros", "Keymaps", "DeletedData")

    private fun validatePassword(config: SyncConfig) {
        require(config.encryptionPassword.length >= 12 && config.encryptionPassword.isNotBlank()) {
            SyncI18n.getString("termora.settings.sync.encryption-password-required")
        }
        require(config.encryptionPassword != config.token) {
            SyncI18n.getString("termora.settings.sync.encryption-password-independent")
        }
    }

    fun encode(files: List<GistFile>, config: SyncConfig): List<GistFile> {
        validatePassword(config)
        return files.map {
            require(it.filename in filenames) { "Unknown sync file" }
            it.copy(content = SyncEncryption.encryptWithPassword(config.encryptionPassword, it.content, it.filename), legacy = false)
        }
    }

    fun decode(files: List<GistFile>, config: SyncConfig): List<GistFile> {
        validatePassword(config)
        val selected = mutableSetOf("DeletedData")
        if (SyncRange.Hosts in config.ranges) selected.addAll(listOf("Hosts", "Tags"))
        if (SyncRange.KeyPairs in config.ranges) selected.add("KeyPairs")
        if (SyncRange.Snippets in config.ranges) selected.add("Snippets")
        if (SyncRange.KeywordHighlights in config.ranges) selected.add("KeywordHighlights")
        if (SyncRange.Macros in config.ranges) selected.add("Macros")
        if (SyncRange.Keymap in config.ranges) selected.add("Keymaps")
        val knownFiles = files.filter { it.filename in selected }
        require(knownFiles.map { it.filename }.distinct().size == knownFiles.size) { "Duplicate sync files" }
        // Verify every file before callers apply any changes or deletion records.
        return knownFiles.map {
            if (SyncEncryption.isPasswordEncrypted(it.content)) {
                it.copy(content = SyncEncryption.decryptWithPassword(config.encryptionPassword, it.content, it.filename), legacy = false)
            } else {
                require(config.allowLegacyImport && !it.content.startsWith("termora-")) {
                    SyncI18n.getString("termora.settings.sync.legacy-import-required")
                }
                it.copy(legacy = true)
            }
        }
    }
}
