package app.termora.plugins.sync

import app.termora.*
import app.termora.AES.CBC.aesCBCDecrypt
import app.termora.AES.decodeBase64
import app.termora.AES.encodeBase64String
import app.termora.Application.ohMyJson
import app.termora.account.AccountManager
import app.termora.account.AccountOwner
import app.termora.database.DatabaseChangedExtension
import app.termora.database.OwnerType
import app.termora.highlight.KeywordHighlight
import app.termora.highlight.KeywordHighlightManager
import app.termora.keymap.Keymap
import app.termora.keymap.KeymapManager
import app.termora.keymgr.KeyManager
import app.termora.keymgr.OhKeyPair
import app.termora.macro.Macro
import app.termora.macro.MacroManager
import app.termora.snippet.Snippet
import app.termora.snippet.SnippetManager
import app.termora.tag.Tag
import app.termora.tag.TagManager
import kotlinx.serialization.json.JsonObject
import org.apache.commons.lang3.ArrayUtils
import org.slf4j.LoggerFactory
import javax.swing.SwingUtilities

abstract class SafetySyncer : Syncer {
    companion object {
        private val log = LoggerFactory.getLogger(SafetySyncer::class.java)
    }

    protected val description = "${Application.getName()} config"
    protected val httpClient get() = Application.httpClient
    protected val hostManager get() = HostManager.getInstance()
    protected val keyManager get() = KeyManager.getInstance()
    protected val keywordHighlightManager get() = KeywordHighlightManager.getInstance()
    protected val macroManager get() = MacroManager.getInstance()
    protected val keymapManager get() = KeymapManager.getInstance()
    protected val snippetManager get() = SnippetManager.getInstance()
    protected val deleteDataManager get() = DeleteDataManager.getInstance()
    protected val accountManager get() = AccountManager.getInstance()
    protected val tagManager get() = TagManager.getInstance()
    protected val accountOwner
        get() = AccountOwner(
            id = accountManager.getAccountId(),
            name = accountManager.getEmail(),
            type = OwnerType.User
        )

    protected fun decodeHosts(text: String, deletedData: List<DeletedData>, config: SyncConfig) {
        // aes key
        val key = if (config.legacyFormat) getLegacyKey(config) else byteArrayOf()
        val encryptedHosts = ohMyJson.decodeFromString<List<EncryptedHost>>(text)
        val hosts = hostManager.hosts().associateBy { it.id }

        for (encryptedHost in encryptedHosts) {
            val oldHost = hosts[encryptedHost.id]

            // 如果本地的修改时间大于云端时间，那么跳过
            if (oldHost != null) {
                if (oldHost.updateDate >= encryptedHost.updateDate) {
                    continue
                }
            }

            try {
                // aes iv
                val iv = getIv(encryptedHost.id)
                val host = Host(
                    id = encryptedHost.id,
                    name = encryptedHost.name.decodeSyncText(key, iv, config),
                    protocol = encryptedHost.protocol.decodeSyncText(key, iv, config),
                    host = encryptedHost.host.decodeSyncText(key, iv, config),
                    port = encryptedHost.port.decodeSyncText(key, iv, config).toIntOrNull() ?: 0,
                    username = encryptedHost.username.decodeSyncText(key, iv, config),
                    remark = encryptedHost.remark.decodeSyncText(key, iv, config),
                    authentication = ohMyJson.decodeFromString(
                        encryptedHost.authentication.decodeSyncText(key, iv, config)
                    ),
                    proxy = ohMyJson.decodeFromString(
                        encryptedHost.proxy.decodeSyncText(key, iv, config)
                    ),
                    options = ohMyJson.decodeFromString(
                        encryptedHost.options.decodeSyncText(key, iv, config)
                    ),
                    tunnelings = ohMyJson.decodeFromString(
                        encryptedHost.tunnelings.decodeSyncText(key, iv, config)
                    ),
                    sort = encryptedHost.sort,
                    parentId = encryptedHost.parentId.decodeSyncText(key, iv, config),
                    ownerId = encryptedHost.ownerId.decodeSyncText(key, iv, config),
                    creatorId = encryptedHost.creatorId.decodeSyncText(key, iv, config),
                    ownerType = OwnerType.User.name,
                    createDate = encryptedHost.createDate,
                    updateDate = encryptedHost.updateDate,
                )
                SwingUtilities.invokeLater { hostManager.addHost(host, DatabaseChangedExtension.Source.Sync) }
            } catch (e: Exception) {
                if (log.isWarnEnabled) {
                    log.warn("Decode host: ${encryptedHost.id} failed. error: {}", e.message, e)
                }
                throw e
            }
        }

        SwingUtilities.invokeLater {
            deletedData.forEach {
                hostManager.removeHost(it.id)
            }
        }
    }

    protected fun encodeHosts(): String {
        val encryptedHosts = mutableListOf<EncryptedHost>()
        for (host in hostManager.hosts()) {

            val encryptedHost = EncryptedHost()
            encryptedHost.id = host.id
            encryptedHost.name = host.name
            encryptedHost.protocol = host.protocol
            encryptedHost.host = host.host
            encryptedHost.port = "${host.port}"
            encryptedHost.username = host.username
            encryptedHost.remark = host.remark
            encryptedHost.authentication = ohMyJson.encodeToString(host.authentication)
            encryptedHost.proxy = ohMyJson.encodeToString(host.proxy)
            encryptedHost.options =
                ohMyJson.encodeToString(host.options)
            encryptedHost.tunnelings =
                ohMyJson.encodeToString(host.tunnelings)
            encryptedHost.sort = host.sort
            encryptedHost.parentId = host.parentId
            encryptedHost.ownerId = host.ownerId
            encryptedHost.creatorId = host.creatorId
            encryptedHost.createDate = host.createDate
            encryptedHost.updateDate = host.updateDate
            encryptedHosts.add(encryptedHost)
        }

        return ohMyJson.encodeToString(encryptedHosts)

    }

    protected fun encodeDeletedData(config: SyncConfig): String {
        return ohMyJson.encodeToString(deleteDataManager.getDeletedData())
    }

    protected fun decodeDeletedData(text: String, config: SyncConfig): List<DeletedData> {
        val deletedData = ohMyJson.decodeFromString<List<DeletedData>>(text).toMutableList()
        // 和本地融合
        deletedData.addAll(deleteDataManager.getDeletedData())
        return deletedData
    }

    protected fun decodeSnippets(text: String, deletedData: List<DeletedData>, config: SyncConfig) {
        // aes key
        val key = if (config.legacyFormat) getLegacyKey(config) else byteArrayOf()
        val encryptedSnippets = ohMyJson.decodeFromString<List<Snippet>>(text)
        val snippets = snippetManager.snippets().associateBy { it.id }

        for (encryptedSnippet in encryptedSnippets) {
            val oldHost = snippets[encryptedSnippet.id]

            // 如果一样，则无需配置
            if (oldHost != null) {
                if (oldHost.updateDate >= encryptedSnippet.updateDate) {
                    continue
                }
            }

            try {
                // aes iv
                val iv = getIv(encryptedSnippet.id)
                val snippet = encryptedSnippet.copy(
                    name = encryptedSnippet.name.decodeSyncText(key, iv, config),
                    parentId = encryptedSnippet.parentId.decodeSyncText(key, iv, config),
                    snippet = encryptedSnippet.snippet.decodeSyncText(key, iv, config),
                )
                SwingUtilities.invokeLater { snippetManager.addSnippet(snippet) }
            } catch (e: Exception) {
                if (log.isWarnEnabled) {
                    log.warn("Decode snippet: ${encryptedSnippet.id} failed. error: {}", e.message, e)
                }
                throw e
            }
        }

        SwingUtilities.invokeLater {
            deletedData.forEach {
                snippetManager.removeSnippet(it.id)
            }
        }
    }

    protected fun encodeSnippets(): String {
        return ohMyJson.encodeToString(snippetManager.snippets())
    }

    protected fun decodeKeys(text: String, deletedData: List<DeletedData>, config: SyncConfig) {
        // aes key
        val key = if (config.legacyFormat) getLegacyKey(config) else byteArrayOf()
        val encryptedKeys = ohMyJson.decodeFromString<List<OhKeyPair>>(text)
        val keys = keyManager.getOhKeyPairs().associateBy { it.id }

        for (encryptedKey in encryptedKeys) {
            val k = keys[encryptedKey.id]
            if (k != null) {
                if (k.updateDate > encryptedKey.updateDate) {
                    continue
                }
            }

            try {
                // aes iv
                val iv = getIv(encryptedKey.id)
                val keyPair = OhKeyPair(
                    id = encryptedKey.id,
                    publicKey = encryptedKey.publicKey.decodeSyncKey(key, iv, config),
                    privateKey = encryptedKey.privateKey.decodeSyncKey(key, iv, config),
                    type = encryptedKey.type.decodeSyncText(key, iv, config),
                    name = encryptedKey.name.decodeSyncText(key, iv, config),
                    remark = encryptedKey.remark.decodeSyncText(key, iv, config),
                    length = encryptedKey.length,
                    sort = encryptedKey.sort,
                    updateDate = encryptedKey.updateDate,
                )
                SwingUtilities.invokeLater { keyManager.addOhKeyPair(keyPair, accountOwner) }
            } catch (e: Exception) {
                if (log.isWarnEnabled) {
                    log.warn("Decode key: ${encryptedKey.id} failed. error: {}", e.message, e)
                }
                throw e
            }
        }

        SwingUtilities.invokeLater {
            deletedData.forEach {
                keyManager.removeOhKeyPair(it.id)
            }
        }
    }

    protected fun encodeKeys(): String {
        return ohMyJson.encodeToString(keyManager.getOhKeyPairs())
    }

    protected fun decodeKeywordHighlights(text: String, deletedData: List<DeletedData>, config: SyncConfig) {
        // aes key
        val key = if (config.legacyFormat) getLegacyKey(config) else byteArrayOf()
        val encryptedKeywordHighlights = ohMyJson.decodeFromString<List<KeywordHighlight>>(text)
        val keywordHighlights = keywordHighlightManager.getKeywordHighlights().associateBy { it.id }

        for (e in encryptedKeywordHighlights) {
            val keywordHighlight = keywordHighlights[e.id]
            if (keywordHighlight != null) {
                if (keywordHighlight.updateDate >= e.updateDate) {
                    continue
                }
            }

            try {
                // aes iv
                val iv = getIv(e.id)
                keywordHighlightManager.addKeywordHighlight(
                    e.copy(
                        keyword = e.keyword.decodeSyncText(key, iv, config),
                        description = e.description.decodeSyncText(key, iv, config),
                    ), accountOwner
                )
            } catch (ex: Exception) {
                if (log.isWarnEnabled) {
                    log.warn("Decode KeywordHighlight: ${e.id} failed. error: {}", ex.message, ex)
                }
                throw ex
            }
        }

        SwingUtilities.invokeLater {
            deletedData.forEach {
                keywordHighlightManager.removeKeywordHighlight(it.id)
            }
        }
    }

    protected fun encodeKeywordHighlights(): String {
        return ohMyJson.encodeToString(accountManager.getOwnerIds().flatMap { keywordHighlightManager.getKeywordHighlights(it) })
    }


    protected fun decodeTags(text: String, deletedData: List<DeletedData>, config: SyncConfig) {
        // aes key
        val key = if (config.legacyFormat) getLegacyKey(config) else byteArrayOf()
        // Older Git sync versions accidentally wrote Hosts into the Tags file.
        val encryptedTags = if (config.legacyFormat) {
            runCatching { ohMyJson.decodeFromString<List<Tag>>(text) }.getOrDefault(emptyList())
        } else {
            ohMyJson.decodeFromString<List<Tag>>(text)
        }
        val tags = accountManager.getOwnerIds().map { tagManager.getTags(it) }
            .flatten().associateBy { it.id }

        for (e in encryptedTags) {
            val tag = tags[e.id]
            if (tag != null) {
                if (tag.updateDate >= e.updateDate) {
                    continue
                }
            }

            try {
                // aes iv
                val iv = getIv(e.id)

                tagManager.addTag(
                    e.copy(
                        text = e.text.decodeSyncText(key, iv, config),
                    ), accountOwner
                )
            } catch (ex: Exception) {
                if (log.isWarnEnabled) {
                    log.warn("Decode Tag: ${e.id} failed. error: {}", ex.message, ex)
                }
                throw ex
            }
        }

        SwingUtilities.invokeLater {
            deletedData.forEach {
                tagManager.removeTag(it.id)
            }
        }
    }

    protected fun encodeTags(): String {
        return ohMyJson.encodeToString(accountManager.getOwnerIds().flatMap { tagManager.getTags(it) })
    }

    protected fun decodeMacros(text: String, deletedData: List<DeletedData>, config: SyncConfig) {
        // aes key
        val key = if (config.legacyFormat) getLegacyKey(config) else byteArrayOf()
        val encryptedMacros = ohMyJson.decodeFromString<List<Macro>>(text)
        val macros = macroManager.getMacros().associateBy { it.id }
        for (e in encryptedMacros) {
            val macro = macros[e.id]
            if (macro != null) {
                if (macro.updateDate >= e.updateDate) {
                    continue
                }
            }

            try {
                // aes iv
                val iv = getIv(e.id)
                macroManager.addMacro(
                    e.copy(
                        name = e.name.decodeSyncText(key, iv, config),
                        macro = e.macro.decodeSyncText(key, iv, config),
                    )
                )
            } catch (ex: Exception) {
                if (log.isWarnEnabled) {
                    log.warn("Decode Macro: ${e.id} failed. error: {}", ex.message, ex)
                }
                throw ex
            }
        }

        SwingUtilities.invokeLater {
            deletedData.forEach {
                macroManager.removeMacro(it.id)
            }
        }
    }

    protected fun encodeMacros(): String {
        return ohMyJson.encodeToString(macroManager.getMacros())
    }

    protected fun decodeKeymaps(text: String, deletedData: List<DeletedData>, config: SyncConfig) {

        val localKeymaps = keymapManager.getKeymaps().associateBy { it.name }
        val remoteKeymaps = ohMyJson.decodeFromString<List<JsonObject>>(text).mapNotNull { Keymap.fromJSON(it) }
        for (keymap in remoteKeymaps) {
            val localKeymap = localKeymaps[keymap.name]
            if (localKeymap != null) {
                if (localKeymap.updateDate > keymap.updateDate) {
                    continue
                }
            }
            keymapManager.addKeymap(keymap)
        }

        SwingUtilities.invokeLater {
            deletedData.forEach {
                keymapManager.removeKeymap(it.id)
            }
        }
    }

    protected fun encodeKeymaps(): String {
        val keymaps = mutableListOf<JsonObject>()
        for (keymap in keymapManager.getKeymaps()) {
            // 只读的是内置的
            if (keymap.isReadonly) {
                continue
            }
            keymaps.add(keymap.toJSONObject())
        }

        return ohMyJson.encodeToString(keymaps)
    }

    protected open fun getLegacyKey(config: SyncConfig): ByteArray {
        return ArrayUtils.subarray(config.token.padEnd(16, '0').toByteArray(), 0, 16)
    }

    private fun String.decodeSyncText(key: ByteArray, iv: ByteArray, config: SyncConfig): String {
        return if (config.legacyFormat) decodeBase64().aesCBCDecrypt(key, iv).decodeToString() else this
    }

    private fun String.decodeSyncKey(key: ByteArray, iv: ByteArray, config: SyncConfig): String {
        return if (config.legacyFormat) decodeBase64().aesCBCDecrypt(key, iv).encodeBase64String() else this
    }

    protected fun getIv(id: String): ByteArray {
        return ArrayUtils.subarray(id.padEnd(16, '0').toByteArray(), 0, 16)
    }
}
