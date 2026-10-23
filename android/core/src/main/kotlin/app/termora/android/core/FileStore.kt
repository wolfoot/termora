package app.termora.android.core

import app.termora.Host
import app.termora.ShellArguments
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.*
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.jcraft.jsch.ChannelSftp
import java.io.*
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.w3c.dom.Element

data class RemoteFile(
    val path: String,
    val name: String,
    val directory: Boolean,
    val size: Long = 0,
    val modified: Long = 0,
    val permissions: Int? = null,
)

interface FileStore : Closeable {
    fun list(path: String): List<RemoteFile>

    fun read(path: String, output: OutputStream)

    fun write(path: String, input: InputStream, size: Long)

    fun mkdir(path: String)

    fun delete(path: String, directory: Boolean)

    fun rename(from: String, to: String)
}

fun remotePath(parent: String, name: String): String {
    require(
        name.isNotBlank() &&
            name !in listOf(".", "..") &&
            '/' !in name &&
            '\\' !in name &&
            '\u0000' !in name,
    )
    return parent.trimEnd('/') + "/" + name
}

class SftpStore(private val ssh: SshConnection) : FileStore {
    private val channel = ssh.sftp()

    override fun list(path: String) =
        channel
            .ls(path)
            .map { it as ChannelSftp.LsEntry }
            .filter { it.filename != "." && it.filename != ".." }
            .map {
                RemoteFile(
                    remotePath(path, it.filename),
                    it.filename,
                    it.attrs.isDir,
                    it.attrs.size,
                    it.attrs.mTime.toLong() * 1000,
                    it.attrs.permissions,
                )
            }

    override fun read(path: String, output: OutputStream) {
        channel.get(path, output)
    }

    override fun write(path: String, input: InputStream, size: Long) {
        channel.put(input, path)
    }

    override fun mkdir(path: String) {
        channel.mkdir(path)
    }

    override fun delete(path: String, directory: Boolean) {
        if (directory) channel.rmdir(path) else channel.rm(path)
    }

    override fun rename(from: String, to: String) {
        channel.rename(from, to)
    }

    fun chmod(path: String, mode: Int) {
        require(mode in 0..4095)
        channel.chmod(mode, path)
    }

    fun archive(path: String, destination: String, extract: Boolean): String {
        val q = ShellArguments::quote
        val lower = path.lowercase()
        val cmd =
            if (extract)
                when {
                    lower.endsWith(".zip") -> "unzip -o ${q(path)} -d ${q(destination)}"
                    lower.endsWith(".tar.gz") || lower.endsWith(".tgz") ->
                        "tar -xzf ${q(path)} -C ${q(destination)}"
                    else -> "tar -xf ${q(path)} -C ${q(destination)}"
                }
            else {
                val parent = path.substringBeforeLast('/', ".").ifBlank { "/" }
                val name = path.substringAfterLast('/')
                if (destination.lowercase().endsWith(".zip"))
                    "cd ${q(parent)} && zip -r ${q(destination)} -- ${q(name)}"
                else "tar -czf ${q(destination)} -C ${q(parent)} -- ${q(name)}"
            }
        return ssh.exec(cmd, 120)
    }

    override fun close() {
        channel.disconnect()
    }
}

class FtpStore(host: Host) : FileStore {
    private val client =
        FTPClient().apply {
            connectTimeout = 15000
            defaultTimeout = 15000
            controlEncoding = "UTF-8"
            connect(host.host, host.port.takeIf { it > 0 } ?: 21)
            check(login(host.username.ifBlank { "anonymous" }, host.authentication.password)) {
                "FTP 登录失败"
            }
            enterLocalPassiveMode()
            setFileType(FTP.BINARY_FILE_TYPE)
        }

    override fun list(path: String) =
        client
            .listFiles(path)
            .filter { it.name != "." && it.name != ".." }
            .map {
                RemoteFile(
                    remotePath(path, it.name),
                    it.name,
                    it.isDirectory,
                    it.size,
                    it.timestamp?.timeInMillis ?: 0,
                )
            }

    override fun read(path: String, output: OutputStream) {
        check(client.retrieveFile(path, output)) { client.replyString }
    }

    override fun write(path: String, input: InputStream, size: Long) {
        check(client.storeFile(path, input)) { client.replyString }
    }

    override fun mkdir(path: String) {
        check(client.makeDirectory(path)) { client.replyString }
    }

    override fun delete(path: String, directory: Boolean) {
        check(if (directory) client.removeDirectory(path) else client.deleteFile(path)) {
            client.replyString
        }
    }

    override fun rename(from: String, to: String) {
        check(client.rename(from, to)) { client.replyString }
    }

    override fun close() {
        runCatching { client.logout() }
        client.disconnect()
    }
}

class SmbStore(host: Host) : FileStore {
    private val client = SMBClient()
    private val connection = client.connect(host.host, host.port.takeIf { it > 0 } ?: 445)
    private val session =
        connection.authenticate(
            when (host.username) {
                "Guest" -> AuthenticationContext.guest()
                "Anonymous" -> AuthenticationContext.anonymous()
                else ->
                    AuthenticationContext(
                        host.username,
                        host.authentication.password.toCharArray(),
                        host.options.extras["smb.domain"].orEmpty(),
                    )
            },
        )
    private val share =
        session.connectShare(host.options.extras["smb.share"] ?: error("填写 SMB 共享名")) as DiskShare

    private fun p(path: String) = path.trim('/').replace('/', '\\')

    override fun list(path: String) =
        share
            .list(p(path))
            .filter { it.fileName !in listOf(".", "..") }
            .map {
                RemoteFile(
                    remotePath(path, it.fileName),
                    it.fileName,
                    it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L,
                    it.endOfFile,
                    it.lastWriteTime.toDate().time,
                )
            }

    private fun open(path: String, write: Boolean) =
        share.openFile(
            p(path),
            setOf(if (write) AccessMask.GENERIC_WRITE else AccessMask.GENERIC_READ),
            setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            if (write) SMB2CreateDisposition.FILE_OVERWRITE_IF else SMB2CreateDisposition.FILE_OPEN,
            setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )

    override fun read(path: String, output: OutputStream) {
        open(path, false).use { file -> file.inputStream.use { it.copyTo(output) } }
    }

    override fun write(path: String, input: InputStream, size: Long) {
        open(path, true).use { file -> file.outputStream.use { input.copyTo(it) } }
    }

    override fun mkdir(path: String) {
        share.mkdir(p(path))
    }

    override fun delete(path: String, directory: Boolean) {
        if (directory) share.rmdir(p(path), false) else share.rm(p(path))
    }

    override fun rename(from: String, to: String) {
        if (share.folderExists(p(from)))
            share
                .openDirectory(
                    p(from),
                    setOf(AccessMask.DELETE),
                    setOf(FileAttributes.FILE_ATTRIBUTE_DIRECTORY),
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    setOf(SMB2CreateOptions.FILE_DIRECTORY_FILE),
                )
                .use { it.rename(p(to)) }
        else
            share
                .openFile(
                    p(from),
                    setOf(AccessMask.DELETE),
                    setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
                )
                .use { it.rename(p(to)) }
    }

    override fun close() {
        share.close()
        session.close()
        connection.close()
        client.close()
    }
}

val networkClient =
    OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

private fun streamBody(input: InputStream, size: Long) =
    object : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()

        override fun contentLength() = size

        override fun writeTo(sink: BufferedSink) {
            val buf = ByteArray(65536)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                sink.write(buf, 0, n)
            }
        }
    }

fun safeXml(bytes: ByteArray): Element {
    require(bytes.size <= 8 * 1024 * 1024) { "XML 过大" }
    val text =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    require('\u0000' !in text) { "XML 必须使用 UTF-8 编码" }
    require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "禁止 XML 外部实体" }
    val factory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
    val builder = factory.newDocumentBuilder()
    builder.setEntityResolver { _, _ -> throw IllegalArgumentException("禁止 XML 外部实体") }
    return builder.parse(ByteArrayInputStream(bytes)).documentElement
}

fun Element.children(name: String): List<Element> =
    (0 until childNodes.length)
        .mapNotNull { childNodes.item(it) as? Element }
        .filter { it.localName == name || it.tagName == name }

fun Element.value(name: String) = children(name).firstOrNull()?.textContent.orEmpty()

private fun Response.xml(): Element =
    body!!.byteStream().use { input ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            require(out.size() + n <= 8 * 1024 * 1024)
            out.write(buf, 0, n)
        }
        safeXml(out.toByteArray())
    }

private fun Response.checked(): Response {
    if (!isSuccessful) {
        val code = code
        close()
        error("HTTP $code")
    }
    return this
}

class WebDavStore(host: Host, private val client: OkHttpClient = networkClient) : FileStore {
    private val base = (host.options.extras["endpoint"] ?: host.host).trimEnd('/').toHttpUrl()

    init {
        require(base.isHttps) { "WebDAV 使用 HTTPS" }
    }

    private val auth = Credentials.basic(host.username, host.authentication.password)

    private fun url(path: String) = base.newBuilder().addPathSegments(path.trim('/')).build()

    private fun request(
        path: String,
        method: String,
        body: RequestBody? = null,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val target =
            url(path).let {
                if (method == "PROPFIND" && !it.encodedPath.endsWith('/'))
                    it.newBuilder().addPathSegment("").build()
                else it
            }
        val b = Request.Builder().url(target).header("Authorization", auth).method(method, body)
        headers.forEach { b.header(it.key, it.value) }
        return client.newCall(b.build()).execute().checked()
    }

    override fun list(path: String): List<RemoteFile> {
        val body =
            """<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
                .toRequestBody("application/xml".toMediaType())
        return request(path, "PROPFIND", body, mapOf("Depth" to "1")).use { r ->
            val root = r.xml()
            root.children("response").mapNotNull { e ->
                val href = e.value("href")
                val parsed = base.resolve(href) ?: return@mapNotNull null
                val target = parsed.encodedPath.trimEnd('/')
                if (target == url(path).encodedPath.trimEnd('/')) return@mapNotNull null
                val name =
                    parsed.pathSegments.filter { it.isNotEmpty() }.lastOrNull()
                        ?: return@mapNotNull null
                val prop =
                    e.children("propstat")
                        .firstOrNull { it.value("status").contains(" 200 ") }
                        ?.children("prop")
                        ?.firstOrNull() ?: return@mapNotNull null
                RemoteFile(
                    remotePath(path, name),
                    name,
                    prop.children("resourcetype").any { it.children("collection").isNotEmpty() },
                    prop.value("getcontentlength").toLongOrNull() ?: 0,
                )
            }
        }
    }

    override fun read(path: String, output: OutputStream) {
        request(path, "GET").use { it.body!!.byteStream().use { i -> i.copyTo(output) } }
    }

    override fun write(path: String, input: InputStream, size: Long) {
        request(path, "PUT", streamBody(input, size)).close()
    }

    override fun mkdir(path: String) {
        request(path, "MKCOL", ByteArray(0).toRequestBody(null)).close()
    }

    override fun delete(path: String, directory: Boolean) {
        request(path, "DELETE").close()
    }

    override fun rename(from: String, to: String) {
        request(from, "MOVE", null, mapOf("Destination" to url(to).toString(), "Overwrite" to "F"))
            .close()
    }

    override fun close() {}
}

/** Small REST clients avoid loading desktop JAXB/AWT SDKs on Android. */
class ObjectStore(private val host: Host, private val client: OkHttpClient = networkClient) :
    FileStore {
    private val protocol = host.protocol.uppercase()
    private val bucket = host.options.extras["bucket"] ?: error("填写存储桶")
    private val region = host.options.extras["region"].orEmpty().ifBlank { "us-east-1" }
    private val base = (host.options.extras["endpoint"] ?: host.host).trimEnd('/').toHttpUrl()

    init {
        require(base.isHttps) { "对象存储使用 HTTPS" }
        require(bucket.isNotBlank()) { "填写存储桶" }
    }

    private val pathStyle = host.options.extras["pathStyle"] == "true"

    private fun key(path: String) = path.trimStart('/')

    private fun url(path: String, query: Map<String, String>): HttpUrl =
        base
            .newBuilder()
            .apply {
                if (pathStyle) addPathSegment(bucket)
                else if (
                    !host.options.extras.getOrDefault("customDomain", "false").toBoolean() &&
                        !base.host.startsWith("$bucket.")
                )
                    host("$bucket.${base.host}")
                addEncodedPathSegments(
                    key(path).split('/').joinToString("/") { ObjectSigner.encode(it) },
                )
                query.forEach { addQueryParameter(it.key, it.value) }
            }
            .build()

    private fun request(
        path: String,
        method: String,
        body: RequestBody? = null,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val url = url(path, query)
        val signed =
            ObjectSigner.sign(
                protocol,
                method,
                url,
                bucket,
                region,
                host.username,
                host.authentication.password,
                headers,
                Instant.now(),
            )
        val builder = Request.Builder().url(url).method(method, body)
        signed.forEach { builder.header(it.key, it.value) }
        return client.newCall(builder.build()).execute().checked()
    }

    override fun list(path: String): List<RemoteFile> {
        val prefix = key(path).let { if (it.isBlank()) "" else it.trimEnd('/') + "/" }
        val result = mutableListOf<RemoteFile>()
        var marker = ""
        val seen = mutableSetOf<String>()
        do {
            val q = mutableMapOf("prefix" to prefix, "delimiter" to "/", "max-keys" to "1000")
            if (marker.isNotEmpty()) q["marker"] = marker
            val xml = request("/", "GET", query = q).use { it.xml() }
            xml.children("CommonPrefixes").forEach {
                val p = it.value("Prefix")
                result += RemoteFile("/$p", p.trimEnd('/').substringAfterLast('/'), true)
            }
            xml.children("Contents").forEach {
                val k = it.value("Key")
                if (k != prefix)
                    result +=
                        RemoteFile(
                            "/$k",
                            k.substringAfterLast('/'),
                            k.endsWith('/'),
                            it.value("Size").toLongOrNull() ?: 0,
                        )
            }
            if (xml.value("IsTruncated") != "true") break
            marker =
                xml.value("NextMarker").ifBlank {
                    xml.children("Contents").lastOrNull()?.value("Key").orEmpty()
                }
            check(marker.isNotBlank() && seen.add(marker)) { "对象存储分页异常" }
            require(result.size <= 100_000) { "目录对象数量过大" }
        } while (true)
        return result.distinctBy { it.path }
    }

    override fun read(path: String, output: OutputStream) {
        request(path, "GET").use { it.body!!.byteStream().use { i -> i.copyTo(output) } }
    }

    override fun write(path: String, input: InputStream, size: Long) {
        request(path, "PUT", streamBody(input, size)).close()
    }

    override fun mkdir(path: String) {
        request(
                path.trimEnd('/') + "/",
                "PUT",
                ByteArray(0).toRequestBody("application/octet-stream".toMediaType()),
            )
            .close()
    }

    override fun delete(path: String, directory: Boolean) {
        request(path, "DELETE").close()
    }

    override fun rename(from: String, to: String) {
        require(!from.endsWith('/')) { "对象存储文件夹需逐个移动" }
        val prefix =
            when (protocol) {
                "OSS" -> "x-oss"
                "OBS" -> "x-obs"
                "COS" -> "x-cos"
                else -> "x-amz"
            }
        request(
                to,
                "PUT",
                ByteArray(0).toRequestBody("application/octet-stream".toMediaType()),
                headers =
                    mapOf(
                        "$prefix-copy-source" to
                            "/${ObjectSigner.encode(bucket)}/${key(from).split('/').joinToString("/"){ObjectSigner.encode(it)}}",
                    ),
            )
            .use {
                val root = it.xml()
                check(root.localName != "Error") { "对象复制失败" }
            }
        delete(from, false)
    }

    override fun close() {}
}

object ObjectSigner {
    fun encode(s: String) =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")

    private fun hash(algorithm: String, s: String) =
        MessageDigest.getInstance(algorithm).digest(s.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    private fun hmac(algorithm: String, key: ByteArray, s: String) =
        Mac.getInstance(algorithm)
            .apply { init(SecretKeySpec(key, algorithm)) }
            .doFinal(s.toByteArray())

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun sign(
        protocol: String,
        method: String,
        url: HttpUrl,
        bucket: String,
        region: String,
        id: String,
        secret: String,
        extra: Map<String, String>,
        now: Instant,
    ): Map<String, String> {
        val headers = extra.mapKeys { it.key.lowercase() }.toMutableMap()
        headers["host"] =
            if (url.port == 443 && url.isHttps || url.port == 80 && !url.isHttps) url.host
            else "${url.host}:${url.port}"
        if (method == "PUT") headers.putIfAbsent("content-type", "application/octet-stream")
        val query =
            (0 until url.querySize)
                .map {
                    encode(url.queryParameterName(it)) to
                        encode(url.queryParameterValue(it).orEmpty())
                }
                .sortedWith(compareBy({ it.first }, { it.second }))
                .joinToString("&") { "${it.first}=${it.second}" }
        when (protocol) {
            "S3" -> {
                val date =
                    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                        .withZone(ZoneOffset.UTC)
                        .format(now)
                val day = date.take(8)
                val payload = if (method == "PUT") "UNSIGNED-PAYLOAD" else hash("SHA-256", "")
                headers["x-amz-date"] = date
                headers["x-amz-content-sha256"] = payload
                val names = headers.keys.sorted().joinToString(";")
                val canonical =
                    "$method\n${url.encodedPath}\n$query\n" +
                        headers.toSortedMap().entries.joinToString("") {
                            "${it.key}:${it.value.trim()}\n"
                        } +
                        "\n$names\n$payload"
                val scope = "$day/$region/s3/aws4_request"
                var k = hmac("HmacSHA256", ("AWS4" + secret).toByteArray(), day)
                for (part in listOf(region, "s3", "aws4_request")) k = hmac("HmacSHA256", k, part)
                val signature =
                    hex(
                        hmac(
                            "HmacSHA256",
                            k,
                            "AWS4-HMAC-SHA256\n$date\n$scope\n${hash("SHA-256",canonical)}",
                        ),
                    )
                headers["authorization"] =
                    "AWS4-HMAC-SHA256 Credential=$id/$scope, SignedHeaders=$names, Signature=$signature"
            }
            "COS" -> {
                val time = "${now.epochSecond-60};${now.epochSecond+900}"
                val signKey = hex(hmac("HmacSHA1", secret.toByteArray(), time))
                val hp =
                    headers.toSortedMap().entries.joinToString("&") {
                        "${encode(it.key)}=${encode(it.value)}"
                    }
                val cosQuery =
                    (0 until url.querySize)
                        .map {
                            encode(url.queryParameterName(it)).lowercase() to
                                encode(url.queryParameterValue(it).orEmpty())
                        }
                        .sortedWith(compareBy({ it.first }, { it.second }))
                        .joinToString("&") { "${it.first}=${it.second}" }
                val canonical =
                    "${method.lowercase()}\n/${url.pathSegments.joinToString("/")}\n$cosQuery\n$hp\n"
                val signature =
                    hex(
                        hmac(
                            "HmacSHA1",
                            signKey.toByteArray(),
                            "sha1\n$time\n${hash("SHA-1",canonical)}\n",
                        ),
                    )
                val names = headers.keys.sorted().joinToString(";")
                val params =
                    (0 until url.querySize)
                        .map { encode(url.queryParameterName(it)).lowercase() }
                        .sorted()
                        .joinToString(";")
                headers["authorization"] =
                    "q-sign-algorithm=sha1&q-ak=$id&q-sign-time=$time&q-key-time=$time&q-header-list=$names&q-url-param-list=$params&q-signature=$signature"
            }
            "OSS" -> {
                val date =
                    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                        .withZone(ZoneOffset.UTC)
                        .format(now)
                val day = date.take(8)
                headers["x-oss-date"] = date
                headers["x-oss-content-sha256"] = "UNSIGNED-PAYLOAD"
                val additional =
                    headers.keys
                        .filter {
                            it !in setOf("content-type", "content-md5") && !it.startsWith("x-oss-")
                        }
                        .sorted()
                        .joinToString(";")
                val path =
                    url.pathSegments.joinToString("/").let {
                        if (it.startsWith("$bucket/")) it.removePrefix("$bucket/") else it
                    }
                val uri =
                    "/" + encode(bucket) + "/" + path.split('/').joinToString("/") { encode(it) }
                val canonical =
                    "$method\n$uri\n$query\n" +
                        headers.toSortedMap().entries.joinToString("") {
                            "${it.key}:${it.value.trim()}\n"
                        } +
                        "\n$additional\nUNSIGNED-PAYLOAD"
                val scope = "$day/$region/oss/aliyun_v4_request"
                var k = hmac("HmacSHA256", ("aliyun_v4" + secret).toByteArray(), day)
                for (part in listOf(region, "oss", "aliyun_v4_request")) k =
                    hmac("HmacSHA256", k, part)
                val signature =
                    hex(
                        hmac(
                            "HmacSHA256",
                            k,
                            "OSS4-HMAC-SHA256\n$date\n$scope\n${hash("SHA-256",canonical)}",
                        ),
                    )
                headers["authorization"] =
                    "OSS4-HMAC-SHA256 Credential=$id/$scope, AdditionalHeaders=$additional, Signature=$signature"
            }
            "OBS" -> {
                val prefix = if (protocol == "OSS") "x-oss-" else "x-obs-"
                val date =
                    DateTimeFormatter.ofPattern(
                            "EEE, dd MMM yyyy HH:mm:ss 'GMT'",
                            java.util.Locale.US,
                        )
                        .withZone(ZoneOffset.UTC)
                        .format(now)
                headers["date"] = date
                val custom =
                    headers
                        .filterKeys { it.startsWith(prefix) }
                        .toSortedMap()
                        .entries
                        .joinToString("") { "${it.key}:${it.value.trim()}\n" }
                val path =
                    url.pathSegments.joinToString("/").let {
                        if (it.startsWith("$bucket/")) it.removePrefix("$bucket/") else it
                    }
                val canonical =
                    "$method\n\n${headers["content-type"].orEmpty()}\n$date\n$custom/$bucket/$path"
                headers["authorization"] =
                    "$protocol $id:${Base64.getEncoder().encodeToString(hmac("HmacSHA1",secret.toByteArray(),canonical))}"
            }
            else -> error("未知对象存储类型")
        }
        return headers
    }
}
