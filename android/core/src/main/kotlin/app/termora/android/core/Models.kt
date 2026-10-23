package app.termora.android.core

import app.termora.*
import app.termora.keymgr.OhKeyPair
import app.termora.macro.Macro
import app.termora.snippet.Snippet
import app.termora.tag.Tag
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

val wireJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable data class MobileSettings(val fontSize: Float = 14f, val letterSpacing: Float = 0f)

@Serializable
data class SyncSettings(
    val provider: String = "WebDAV",
    val endpoint: String = "",
    val username: String = "",
    val token: String = "",
    val password: String = "",
    val gistId: String = "",
)

@Serializable
data class Vault(
    val hosts: List<Host> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    val keys: List<OhKeyPair> = emptyList(),
    val macros: List<Macro> = emptyList(),
    val deleted: List<DeletedData> = emptyList(),
    val pins: Map<String, String> = emptyMap(),
    val sync: SyncSettings = SyncSettings(),
    val settings: MobileSettings = MobileSettings(),
    // Preserve desktop-only sync data without trying to load desktop plugins.
    val account: AccountState? = null,
    val otherFiles: Map<String, String> = emptyMap(),
) {
    fun syncFiles(): Map<String, String> =
        otherFiles +
            mapOf(
                "Hosts" to wireJson.encodeToString(hosts.map { it.toSyncHost() }),
                "Tags" to wireJson.encodeToString(tags),
                "Snippets" to wireJson.encodeToString(snippets),
                "KeyPairs" to wireJson.encodeToString(keys),
                "Macros" to wireJson.encodeToString(macros),
                "DeletedData" to wireJson.encodeToString(deleted),
            )

    fun merge(files: Map<String, String>): Vault {
        fun <T> mergeList(
            local: List<T>,
            remote: List<T>,
            id: (T) -> String,
            time: (T) -> Long,
        ): List<T> = (local + remote).groupBy(id).values.map { it.maxBy(time) }
        val removals =
            mergeList(
                deleted,
                files["DeletedData"]?.let { wireJson.decodeFromString<List<DeletedData>>(it) }
                    ?: emptyList(),
                { it.type + it.id },
                { it.deleteDate },
            )
        fun deleted(id: String, type: String, time: Long) = removals.any {
            it.id == id && it.type == type && it.deleteDate >= time
        }
        return copy(
            hosts =
                mergeList(
                        hosts,
                        files["Hosts"]?.let {
                            wireJson.decodeFromString<List<EncryptedHost>>(it).map { it.toHost() }
                        } ?: emptyList(),
                        { it.id },
                        { it.updateDate },
                    )
                    .filterNot { deleted(it.id, "Host", it.updateDate) },
            tags =
                mergeList(
                        tags,
                        files["Tags"]?.let { wireJson.decodeFromString<List<Tag>>(it) }
                            ?: emptyList(),
                        { it.id },
                        { it.updateDate },
                    )
                    .filterNot { deleted(it.id, "Tag", it.updateDate) },
            snippets =
                mergeList(
                        snippets,
                        files["Snippets"]?.let { wireJson.decodeFromString<List<Snippet>>(it) }
                            ?: emptyList(),
                        { it.id },
                        { it.updateDate },
                    )
                    .filterNot { deleted(it.id, "Snippet", it.updateDate) },
            keys =
                mergeList(
                        keys,
                        files["KeyPairs"]?.let { wireJson.decodeFromString<List<OhKeyPair>>(it) }
                            ?: emptyList(),
                        { it.id },
                        { it.updateDate },
                    )
                    .filterNot { deleted(it.id, "KeyPair", it.updateDate) },
            macros =
                mergeList(
                        macros,
                        files["Macros"]?.let { wireJson.decodeFromString<List<Macro>>(it) }
                            ?: emptyList(),
                        { it.id },
                        { it.updateDate },
                    )
                    .filterNot { deleted(it.id, "Macro", it.updateDate) },
            deleted = removals,
            otherFiles =
                otherFiles +
                    files.filterKeys {
                        it !in
                            setOf("Hosts", "Tags", "Snippets", "KeyPairs", "Macros", "DeletedData")
                    },
        )
    }
}
