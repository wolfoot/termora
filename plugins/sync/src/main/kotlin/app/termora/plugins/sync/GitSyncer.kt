package app.termora.plugins.sync

import app.termora.Application.ohMyJson
import app.termora.DeletedData
import app.termora.ResponseException
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import org.apache.commons.io.IOUtils
import org.slf4j.LoggerFactory

abstract class GitSyncer : SafetySyncer() {
    companion object {
        private val log = LoggerFactory.getLogger(GitSyncer::class.java)
    }

    override fun pull(config: SyncConfig): GistResponse {

        if (log.isInfoEnabled) {
            log.info("Type: ${config.type} , Gist: ${config.gistId} Pull...")
        }

        val response = httpClient.newCall(newPullRequestBuilder(config).build()).execute()
        if (response.isSuccessful.not()) {
            IOUtils.closeQuietly(response)
            throw ResponseException(response.code, response)
        }

        val rawResponse = parsePullResponse(response, config)
        val gistResponse = rawResponse.copy(gists = SyncFileCodec.decode(rawResponse.gists, config))
        val deletedData = mutableListOf<DeletedData>()

        // DeletedData
        gistResponse.gists.findLast { it.filename == "DeletedData" }
            ?.let { deletedData.addAll(decodeDeletedData(it.content, config)) }

        // decode hosts
        if (config.ranges.contains(SyncRange.Hosts)) {
            gistResponse.gists.findLast { it.filename == "Tags" }?.let {
                decodeTags(it.content, deletedData.filter { e -> e.type == "Tag" }, config.copy(legacyFormat = it.legacy))
            }
            gistResponse.gists.findLast { it.filename == "Hosts" }?.let {
                decodeHosts(it.content, deletedData.filter { e -> e.type == "Host" }, config.copy(legacyFormat = it.legacy))
            }
        }

        // decode keys
        if (config.ranges.contains(SyncRange.KeyPairs)) {
            gistResponse.gists.findLast { it.filename == "KeyPairs" }?.let {
                decodeKeys(it.content, deletedData.filter { e -> e.type == "KeyPair" }, config.copy(legacyFormat = it.legacy))
            }
        }

        // decode keyword highlights
        if (config.ranges.contains(SyncRange.KeywordHighlights)) {
            gistResponse.gists.findLast { it.filename == "KeywordHighlights" }?.let {
                decodeKeywordHighlights(it.content, deletedData.filter { e -> e.type == "KeywordHighlight" }, config.copy(legacyFormat = it.legacy))
            }
        }

        // decode macros
        if (config.ranges.contains(SyncRange.Macros)) {
            gistResponse.gists.findLast { it.filename == "Macros" }?.let {
                decodeMacros(it.content, deletedData.filter { e -> e.type == "Macro" }, config.copy(legacyFormat = it.legacy))
            }
        }

        // decode keymaps
        if (config.ranges.contains(SyncRange.Keymap)) {
            gistResponse.gists.findLast { it.filename == "Keymaps" }?.let {
                decodeKeymaps(it.content, deletedData.filter { e -> e.type == "Keymap" }, config.copy(legacyFormat = it.legacy))
            }
        }

        // decode Snippets
        if (config.ranges.contains(SyncRange.Snippets)) {
            gistResponse.gists.findLast { it.filename == "Snippets" }?.let {
                decodeSnippets(it.content, deletedData.filter { e -> e.type == "Snippet" }, config.copy(legacyFormat = it.legacy))
            }
        }


        if (log.isInfoEnabled) {
            log.info("Type: ${config.type} , Gist: ${config.gistId} Pulled")
        }

        return gistResponse
    }


    override fun push(config: SyncConfig): GistResponse {

        if (log.isInfoEnabled) {
            log.info("Type: ${config.type} , Gist: ${config.gistId} Push...")
        }

        val gistFiles = mutableListOf<GistFile>()

        // Hosts
        if (config.ranges.contains(SyncRange.Hosts)) {
            val hostsContent = encodeHosts()
            gistFiles.add(GistFile("Hosts", hostsContent))

            val tagsContent = encodeTags()
            gistFiles.add(GistFile("Tags", tagsContent))
        }


        // Snippets
        if (config.ranges.contains(SyncRange.Snippets)) {
            val snippetsContent = encodeSnippets()
            gistFiles.add(GistFile("Snippets", snippetsContent))
        }

        // KeyPairs
        if (config.ranges.contains(SyncRange.KeyPairs)) {
            val keysContent = encodeKeys()
            gistFiles.add(GistFile("KeyPairs", keysContent))
        }

        // Highlights
        if (config.ranges.contains(SyncRange.KeywordHighlights)) {
            val keywordHighlightsContent = encodeKeywordHighlights()
            gistFiles.add(GistFile("KeywordHighlights", keywordHighlightsContent))
        }

        // Macros
        if (config.ranges.contains(SyncRange.Macros)) {
            val macrosContent = encodeMacros()
            gistFiles.add(GistFile("Macros", macrosContent))
        }

        // Keymap
        if (config.ranges.contains(SyncRange.Keymap)) {
            val keymapsContent = encodeKeymaps()
            gistFiles.add(GistFile("Keymaps", keymapsContent))
        }

        if (gistFiles.isEmpty()) {
            throw IllegalArgumentException("No gist files found")
        }

        val deletedData = encodeDeletedData(config)
        gistFiles.add(GistFile("DeletedData", deletedData))

        val request = newPushRequestBuilder(SyncFileCodec.encode(gistFiles, config), config).build()

        try {
            return parsePushResponse(httpClient.newCall(request).execute(), config)
        } finally {
            if (log.isInfoEnabled) {
                log.info("Type: ${config.type} , Gist: ${config.gistId} Pushed")
            }
        }
    }

    open fun parsePullResponse(response: Response, config: SyncConfig): GistResponse {
        return GistResponse(config, emptyList())
    }

    open fun parsePushResponse(response: Response, config: SyncConfig): GistResponse {
        if (!response.isSuccessful) {
            throw ResponseException(response.code, response)
        }

        val gistResponse = GistResponse(config, emptyList())
        val text = parseResponse(response)
        val json = ohMyJson.parseToJsonElement(text).jsonObject

        return gistResponse.copy(
            config = config.copy(gistId = json.getValue("id").jsonPrimitive.content)
        )
    }

    open fun parseResponse(response: Response): String {
        return response.use { resp -> resp.body?.use { it.string() } }
            ?: throw ResponseException(response.code, response)
    }

    abstract fun newPullRequestBuilder(config: SyncConfig): Request.Builder

    abstract fun newPushRequestBuilder(gistFiles: List<GistFile>, config: SyncConfig): Request.Builder
}