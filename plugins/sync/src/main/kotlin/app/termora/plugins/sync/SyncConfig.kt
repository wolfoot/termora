package app.termora.plugins.sync

enum class SyncType {
    GitLab,
    GitHub,
    Gitee,
    WebDAV,
}

enum class SyncPolicy {
    Manual,
    OnChange,
}

enum class SyncRange {
    Hosts,
    KeyPairs,
    KeywordHighlights,
    Macros,
    Keymap,
    Snippets,
}

data class SyncConfig(
    val type: SyncType,
    val token: String,
    val gistId: String,
    val options: Map<String, String>,
    val ranges: Set<SyncRange> = setOf(SyncRange.Hosts, SyncRange.KeyPairs, SyncRange.KeywordHighlights),
    val encryptionPassword: String = "",
    val allowLegacyImport: Boolean = false,
    internal val legacyFormat: Boolean = false,
)

data class GistFile(
    val filename: String,
    val content: String,
    internal val legacy: Boolean = false,
)

data class GistResponse(
    val config: SyncConfig,
    val gists: List<GistFile>
)
