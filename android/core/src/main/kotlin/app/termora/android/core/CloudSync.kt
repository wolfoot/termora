package app.termora.android.core

import app.termora.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

object CloudSync {
    private val known =
        setOf(
            "Hosts",
            "Tags",
            "Snippets",
            "KeyPairs",
            "Macros",
            "DeletedData",
            "KeywordHighlights",
            "Keymaps",
        )

    private fun encrypted(vault: Vault, password: String) =
        vault
            .syncFiles()
            .filterKeys { it in known }
            .mapValues { SyncEncryption.encryptWithPassword(password, it.value, it.key) }

    private fun decrypt(files: Map<String, String>, password: String): Map<String, String> {
        require(files.keys.all { it in known }) { "未知同步文件" }
        return files.mapValues { SyncEncryption.decryptWithPassword(password, it.value, it.key) }
    }

    fun synchronize(vault: Vault): Vault {
        val config = vault.sync
        require(config.password.length >= 12 && config.password != config.token) {
            "独立加密密码至少 12 个字符，不能与令牌相同"
        }
        require(config.endpoint.toHttpUrl().isHttps) { "同步服务器必须使用 HTTPS" }
        val pulled = pull(config)
        val merged = vault.merge(decrypt(pulled, config.password))
        val id = push(config, encrypted(merged, config.password))
        return merged.copy(sync = config.copy(gistId = id))
    }

    private fun request(config: SyncSettings, method: String, body: String? = null): Request {
        val url =
            if (config.provider == "GitHub")
                "${config.endpoint.trimEnd('/')}/gists${if(config.gistId.isBlank())"" else "/${config.gistId}"}"
            else config.endpoint
        val auth =
            if (config.provider == "GitHub") "Bearer ${config.token}"
            else Credentials.basic(config.username, config.token)
        return Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .header("Accept", "application/json")
            .method(method, body?.toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun pull(config: SyncSettings): Map<String, String> {
        if (config.provider == "GitHub" && config.gistId.isBlank()) return emptyMap()
        networkClient.newCall(request(config, "GET")).execute().use { r ->
            if (r.code == 404) return emptyMap()
            check(r.isSuccessful) { "同步读取失败 HTTP ${r.code}" }
            val body = r.body!!
            require(body.contentLength() <= 16 * 1024 * 1024)
            val json =
                wireJson
                    .parseToJsonElement(
                        body.byteStream().use {
                            it.readLimited(16 * 1024 * 1024).toString(Charsets.UTF_8)
                        },
                    )
                    .jsonObject
            if (config.provider != "GitHub")
                return json.mapValues { it.value.jsonPrimitive.content }
            return json.getValue("files").jsonObject.mapValues { (_, file) ->
                val item = file.jsonObject
                require(item["truncated"]?.jsonPrimitive?.booleanOrNull != true) {
                    "Gist 文件已截断，请改用 WebDAV"
                }
                item.getValue("content").jsonPrimitive.content
            }
        }
    }

    private fun push(config: SyncSettings, files: Map<String, String>): String {
        val body =
            if (config.provider == "GitHub")
                buildJsonObject {
                    put("public", false)
                    put("description", "Termora config")
                    putJsonObject("files") {
                        files.forEach { (name, content) ->
                            putJsonObject(name) { put("content", content) }
                        }
                    }
                }
            else JsonObject(files.mapValues { JsonPrimitive(it.value) })
        val method =
            if (config.provider != "GitHub") "PUT"
            else if (config.gistId.isBlank()) "POST" else "PATCH"
        networkClient.newCall(request(config, method, body.toString())).execute().use { r ->
            check(r.isSuccessful) { "同步上传失败 HTTP ${r.code}" }
            return if (config.provider == "GitHub")
                wireJson
                    .parseToJsonElement(
                        r.body!!.byteStream().use {
                            it.readLimited(16 * 1024 * 1024).toString(Charsets.UTF_8)
                        },
                    )
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive
                    .content
            else config.gistId
        }
    }
}

fun Host.toSyncHost() =
    EncryptedHost(
        id,
        name,
        protocol,
        host,
        port.toString(),
        username,
        remark,
        wireJson.encodeToString(authentication),
        wireJson.encodeToString(proxy),
        wireJson.encodeToString(options),
        wireJson.encodeToString(tunnelings),
        sort,
        deleted,
        parentId,
        ownerId,
        creatorId,
        createDate,
        updateDate,
    )

fun EncryptedHost.toHost() =
    Host(
        id = id,
        name = name,
        protocol = protocol,
        host = host,
        port = port.toIntOrNull() ?: 0,
        username = username,
        remark = remark,
        authentication = wireJson.decodeFromString(authentication),
        proxy = wireJson.decodeFromString(proxy),
        options = wireJson.decodeFromString(options),
        tunnelings = wireJson.decodeFromString(tunnelings),
        sort = sort,
        deleted = deleted,
        parentId = parentId,
        ownerId = ownerId,
        creatorId = creatorId,
        createDate = createDate,
        updateDate = updateDate,
    )
