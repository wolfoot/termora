package app.termora.android.core

import app.termora.*
import app.termora.keymgr.OhKeyPair
import app.termora.macro.Macro
import app.termora.snippet.Snippet
import app.termora.tag.Tag
import java.io.*
import java.net.*
import java.security.*
import java.security.spec.*
import java.util.Base64
import javax.crypto.*
import javax.crypto.spec.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class AccountState(
    val server: String,
    val id: String,
    val email: String,
    val refreshToken: String,
    val secrets: Map<String, String>,
    val since: Long = 0,
    val records: Map<String, CloudRecord> = emptyMap(),
)

@Serializable
data class CloudRecord(
    val id: String,
    val ownerId: String,
    val ownerType: String = "User",
    val type: String,
    val data: String,
    val version: Long = 0,
    val deleted: Boolean = false,
)

object AccountSync {
    private val b64 = Base64.getDecoder()

    private fun rsa(key: PrivateKey, data: ByteArray): ByteArray =
        Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
            .apply {
                init(
                    Cipher.DECRYPT_MODE,
                    key,
                    OAEPParameterSpec(
                        "SHA-256",
                        "MGF1",
                        MGF1ParameterSpec.SHA256,
                        PSource.PSpecified.DEFAULT,
                    ),
                )
            }
            .doFinal(data)

    private fun cbc(key: ByteArray, iv: ByteArray, data: ByteArray) =
        Cipher.getInstance("AES/CBC/PKCS5Padding")
            .apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
            .doFinal(data)

    private fun call(
        server: String,
        path: String,
        token: String = "",
        method: String = "GET",
        body: JsonObject? = null,
    ): JsonObject {
        require(server.toHttpUrl().isHttps) { "账号服务器必须使用 HTTPS" }
        val request =
            Request.Builder()
                .url(server.trimEnd('/') + path)
                .method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
                .apply { if (token.isNotEmpty()) header("Authorization", "Bearer $token") }
                .build()
        networkClient.newCall(request).execute().use { r ->
            check(r.isSuccessful) { "账号服务 HTTP ${r.code}" }
            val text =
                r.body
                    ?.byteStream()
                    ?.use { it.readLimited(16 * 1024 * 1024).toString(Charsets.UTF_8) }
                    .orEmpty()
            return if (text.isBlank()) JsonObject(emptyMap())
            else wireJson.parseToJsonElement(text).jsonObject
        }
    }

    private fun token(server: String, refresh: String) =
        call(server, "/v1/token", refresh, "POST", buildJsonObject { put("refreshToken", refresh) })

    fun login(server: String, refresh: String, password: String): AccountState {
        val salt = call(server, "/v1/client/system").getValue("salt").jsonPrimitive.content
        val tokens = token(server, refresh)
        val me = call(server, "/v1/users/me", tokens.getValue("accessToken").jsonPrimitive.content)
        val email = me.getValue("email").jsonPrimitive.content
        fun derive(bits: Int) =
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
                .generateSecret(
                    PBEKeySpec(password.toCharArray(), "$salt:$email".toByteArray(), 1024, bits),
                )
                .encoded
        val priv =
            KeyFactory.getInstance("RSA")
                .generatePrivate(
                    PKCS8EncodedKeySpec(
                        cbc(
                            derive(256),
                            derive(128),
                            b64.decode(me.getValue("privateKey").jsonPrimitive.content),
                        ),
                    ),
                )
        val id = me.getValue("id").jsonPrimitive.content
        val secrets =
            mutableMapOf(
                id to
                    Base64.getEncoder()
                        .encodeToString(
                            rsa(priv, b64.decode(me.getValue("secretKey").jsonPrimitive.content)),
                        ),
            )
        me["teams"]?.jsonArray?.forEach { team ->
            val t = team.jsonObject
            secrets[t.getValue("id").jsonPrimitive.content] =
                Base64.getEncoder()
                    .encodeToString(
                        rsa(priv, b64.decode(t.getValue("secretKey").jsonPrimitive.content)),
                    )
        }
        return AccountState(
            server,
            id,
            email,
            tokens.getValue("refreshToken").jsonPrimitive.content,
            secrets,
        )
    }

    fun synchronize(vault: Vault): Vault {
        val account = vault.account ?: error("请先登录账号")
        val tokens = token(account.server, account.refreshToken)
        val access = tokens.getValue("accessToken").jsonPrimitive.content
        val records = account.records.toMutableMap()
        var since = account.since
        var after = ""
        val cursors = mutableSetOf<String>()
        while (true) {
            val changes =
                call(
                    account.server,
                    "/v1/data/changes?since=$since&after=${ObjectSigner.encode(after)}&limit=100",
                    access,
                )
            val list = changes.getValue("changes").jsonArray
            for (change in list) {
                val c = change.jsonObject
                val id = c.getValue("objectId").jsonPrimitive.content
                val owner = c.getValue("ownerId").jsonPrimitive.content
                val ownerType = c.getValue("ownerType").jsonPrimitive.content
                if (c["deleted"]?.jsonPrimitive?.booleanOrNull == true) {
                    val previous =
                        records[id] ?: localRecords(vault, account.id).firstOrNull { it.id == id }
                    if (previous != null)
                        records[id] =
                            previous.copy(
                                deleted = true,
                                data = "",
                                version = c.getValue("version").jsonPrimitive.long,
                            )
                    continue
                }
                val record =
                    wireJson.decodeFromJsonElement<CloudRecord>(
                        call(
                            account.server,
                            "/v1/data/$id?ownerId=${ObjectSigner.encode(owner)}&ownerType=${ObjectSigner.encode(ownerType)}",
                            access,
                        ),
                    )
                require(
                    record.id == id && record.ownerId == owner && record.ownerType == ownerType,
                ) {
                    "账号数据归属异常"
                }
                val key = b64.decode(account.secrets[owner] ?: error("缺少账号或团队解密密钥"))
                val plain =
                    if (record.deleted) ""
                    else
                        SyncEncryption.decryptAccount(key, record.data, id, owner)
                            .toString(Charsets.UTF_8)
                records[id] = record.copy(data = plain)
            }
            since = changes.getValue("since").jsonPrimitive.long
            after = changes.getValue("after").jsonPrimitive.content
            if (list.size < 100) break
            check(cursors.add("$since:$after")) { "账号分页异常" }
        }
        val merged = mergeRecords(vault, records.values)
        val local = localRecords(merged, account.id)
        for (row in local) {
            val remote = records[row.id]
            if (remote?.data == row.data && remote.deleted == row.deleted) continue
            val owner = row.ownerId
            val secret = b64.decode(account.secrets[owner] ?: error("缺少密钥"))
            val ownerType = row.ownerType
            if (row.deleted) {
                call(
                    account.server,
                    "/v1/data/${row.id}?ownerId=${ObjectSigner.encode(owner)}&ownerType=${ObjectSigner.encode(ownerType)}",
                    access,
                    "DELETE",
                )
                records[row.id] = row
                continue
            }
            val payload = buildJsonObject {
                put("objectId", row.id)
                put("ownerId", owner)
                put("ownerType", ownerType)
                put("version", remote?.version ?: 0)
                put("type", row.type)
                put(
                    "data",
                    SyncEncryption.encrypt(secret, row.data.toByteArray(), "$owner:${row.id}"),
                )
            }
            val pushed = call(account.server, "/v1/data/push", access, "POST", payload)
            records[row.id] = row.copy(version = pushed.getValue("version").jsonPrimitive.long)
        }
        return merged.copy(
            account =
                account.copy(
                    refreshToken = tokens.getValue("refreshToken").jsonPrimitive.content,
                    since = since,
                    records = records,
                ),
        )
    }

    private fun localRecords(v: Vault, owner: String): List<CloudRecord> {
        fun row(
            id: String,
            type: String,
            data: String,
            own: String = owner,
            ownerType: String = "User",
        ): CloudRecord {
            val old = v.account?.records?.get(id)
            return CloudRecord(
                id,
                old?.ownerId ?: own.ifBlank { owner }.let { if (it == "0") owner else it },
                old?.ownerType ?: ownerType.ifBlank { "User" },
                type,
                data,
            )
        }
        return v.hosts
            .filterNot { it.deleted }
            .map { row(it.id, "Host", wireJson.encodeToString(it), it.ownerId, it.ownerType) } +
            v.tags.map { row(it.id, "Tag", wireJson.encodeToString(it)) } +
            v.keys.map { row(it.id, "KeyPair", wireJson.encodeToString(it)) } +
            v.snippets.map { row(it.id, "Snippet", wireJson.encodeToString(it)) } +
            v.macros.map { row(it.id, "Macro", wireJson.encodeToString(it)) } +
            v.deleted.map {
                val old = v.account?.records?.get(it.id)
                CloudRecord(
                    it.id,
                    old?.ownerId ?: owner,
                    old?.ownerType ?: "User",
                    type = it.type,
                    data = "",
                    deleted = true,
                )
            }
    }

    private fun mergeRecords(vault: Vault, rows: Collection<CloudRecord>): Vault {
        var v = vault
        for (r in rows) {
            if (r.deleted) {
                v =
                    v.merge(
                        mapOf(
                            "DeletedData" to
                                wireJson.encodeToString(
                                    listOf(DeletedData(r.id, r.type, System.currentTimeMillis())),
                                ),
                        ),
                    )
                continue
            }
            val file =
                when (r.type) {
                    "Host" ->
                        "Hosts" to
                            wireJson.encodeToString(
                                listOf(
                                    wireJson
                                        .decodeFromString<Host>(r.data)
                                        .copy(ownerId = r.ownerId, ownerType = r.ownerType)
                                        .toSyncHost(),
                                ),
                            )
                    "Tag" ->
                        "Tags" to
                            wireJson.encodeToString(listOf(wireJson.decodeFromString<Tag>(r.data)))
                    "KeyPair" ->
                        "KeyPairs" to
                            wireJson.encodeToString(
                                listOf(wireJson.decodeFromString<OhKeyPair>(r.data)),
                            )
                    "Snippet" ->
                        "Snippets" to
                            wireJson.encodeToString(
                                listOf(wireJson.decodeFromString<Snippet>(r.data)),
                            )
                    "Macro" ->
                        "Macros" to
                            wireJson.encodeToString(
                                listOf(wireJson.decodeFromString<Macro>(r.data)),
                            )
                    else -> continue
                }
            v = v.merge(mapOf(file))
        }
        return v
    }

    /**
     * Uses the same encrypted browser callback as the desktop, with bounded loopback HTTP parsing.
     */
    class BrowserLogin(private val server: String) : Closeable {
        private val socket =
            ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 180_000 }
        private val pair =
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val url: String

        init {
            require(server.toHttpUrl().isHttps)
            val redirect =
                "/device?callback=http://127.0.0.1:${socket.localPort}/callback&from=device&publicKey=${pair.public.encoded.joinToString(""){"%02x".format(it)}}&format=hex&device=termora&device-version=2.0.0-beta.16"
            url =
                server.trimEnd('/') +
                    "/v1/client/redirect?to=login&from=device&redirect=" +
                    ObjectSigner.encode(redirect)
        }

        fun await(): AccountState {
            val deadline = System.nanoTime() + 180_000_000_000L
            while (System.nanoTime() < deadline) socket.accept().use { s ->
                s.soTimeout = 5000
                val i = BufferedInputStream(s.getInputStream())
                fun line(): String {
                    val out = ByteArrayOutputStream()
                    while (true) {
                        val c = i.read()
                        if (c < 0) throw EOFException()
                        if (c == 10) break
                        require(out.size() < 8192)
                        if (c != 13) out.write(c)
                    }
                    return out.toString("US-ASCII")
                }
                val first = line()
                val headers = mutableMapOf<String, String>()
                var total = 0
                while (true) {
                    val l = line()
                    if (l.isEmpty()) break
                    total += l.length
                    require(total < 32768)
                    headers[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim()
                }
                val base = server.toHttpUrl()
                val origin =
                    "${base.scheme}://${base.host}" +
                        (if (base.port != 443) ":${base.port}" else "")
                require(headers["origin"] == null || headers["origin"] == origin) { "登录回调来源异常" }
                require(first.contains(" /callback "))
                val o = s.getOutputStream()
                if (first.startsWith("OPTIONS ")) {
                    o.write(
                        "HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: $origin\r\nAccess-Control-Allow-Methods: POST, OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                            .toByteArray(),
                    )
                    return@use
                }
                require(first.startsWith("POST "))
                val length = headers["content-length"]?.toIntOrNull() ?: error("回调长度缺失")
                require(length in 1..65536)
                val bytes = ByteArray(length)
                DataInputStream(i).readFully(bytes)
                val json = wireJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                fun hex(name: String) =
                    json.getValue(name).jsonPrimitive.content.let { hex ->
                        require(hex.length % 2 == 0)
                        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    }
                val key = rsa(pair.private, hex("secretKey"))
                val iv = rsa(pair.private, hex("secretIv"))
                val password = cbc(key, iv, hex("password")).toString(Charsets.UTF_8)
                val refresh = cbc(key, iv, hex("refreshToken")).toString(Charsets.UTF_8)
                o.write(
                    "HTTP/1.1 200 OK\r\nAccess-Control-Allow-Origin: $origin\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK"
                        .toByteArray(),
                )
                o.flush()
                return login(server, refresh, password)
            }
            error("账号登录超时")
        }

        override fun close() {
            socket.close()
        }
    }
}
