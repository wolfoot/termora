package app.termora.android.core

import app.termora.*
import com.qcloud.cos.auth.*
import com.qcloud.cos.http.HttpMethodName
import java.io.*
import java.time.Instant
import java.util.Date
import kotlin.test.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.*
import org.junit.jupiter.api.Test

class FileProtocolsTest {
    private fun tlsServer(block: (MockWebServer, OkHttpClient) -> Unit) {
        val cert =
            HeldCertificate.Builder()
                .commonName("localhost")
                .addSubjectAlternativeName("localhost")
                .build()
        val serverCert = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCert =
            HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        MockWebServer().use { s ->
            s.useHttps(serverCert.sslSocketFactory(), false)
            s.start()
            val c =
                networkClient
                    .newBuilder()
                    .sslSocketFactory(clientCert.sslSocketFactory(), clientCert.trustManager)
                    .build()
            block(s, c)
        }
    }

    @Test
    fun webdavUsesHttpsDepthOneAndStreamsFileContent() = tlsServer { server, client ->
        val host =
            Host(
                name = "DAV",
                protocol = "WebDAV",
                host = server.url("/dav/").toString(),
                username = "u",
                authentication = Authentication(AuthenticationType.Password, "p"),
            )
        WebDavStore(host, client).use { store ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(207)
                    .setBody(
                        """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response><d:response><d:href>/dav/hello.txt</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>5</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""",
                    ),
            )
            assertEquals("hello.txt", store.list("/").single().name)
            val req = server.takeRequest()
            assertEquals("PROPFIND", req.method)
            assertEquals("1", req.getHeader("Depth"))
            assertEquals("/dav/", req.path)
            server.enqueue(MockResponse().setBody("hello"))
            val out = ByteArrayOutputStream()
            store.read("/hello.txt", out)
            assertEquals("hello", out.toString())
            server.takeRequest()
            server.enqueue(MockResponse().setResponseCode(201))
            store.write("/upload.txt", ByteArrayInputStream("content".toByteArray()), 7)
            assertEquals("content", server.takeRequest().body.readUtf8())
            server.enqueue(MockResponse().setResponseCode(201))
            store.mkdir("/dir")
            assertEquals("MKCOL", server.takeRequest().method)
            server.enqueue(MockResponse().setResponseCode(201))
            store.rename("/upload.txt", "/renamed.txt")
            assertEquals(
                server.url("/dav/renamed.txt").toString(),
                server.takeRequest().getHeader("Destination"),
            )
            server.enqueue(MockResponse().setResponseCode(204))
            store.delete("/renamed.txt", false)
            assertEquals("DELETE", server.takeRequest().method)
        }
    }

    @Test
    fun allObjectStoresListPaginateSignAndTransferSpecialNames() {
        for (protocol in listOf("S3", "OSS", "COS", "OBS")) tlsServer { server, client ->
            val host =
                Host(
                    name = protocol,
                    protocol = protocol,
                    host = server.url("/").toString(),
                    username = "test-id",
                    authentication = Authentication(AuthenticationType.Password, "test-secret"),
                    options =
                        Options(
                            extras =
                                mapOf(
                                    "bucket" to "bucket",
                                    "region" to "cn-hangzhou",
                                    "pathStyle" to "true",
                                ),
                        ),
                )
            ObjectStore(host, client).use { store ->
                server.enqueue(
                    MockResponse()
                        .setBody(
                            "<ListBucketResult><Contents><Key>hello.txt</Key><Size>5</Size></Contents><IsTruncated>true</IsTruncated><NextMarker>hello.txt</NextMarker></ListBucketResult>",
                        ),
                )
                server.enqueue(
                    MockResponse()
                        .setBody(
                            "<ListBucketResult><CommonPrefixes><Prefix>folder/</Prefix></CommonPrefixes><IsTruncated>false</IsTruncated></ListBucketResult>",
                        ),
                )
                val files = store.list("/")
                assertEquals(2, files.size)
                assertTrue(files.any { it.directory })
                val first = server.takeRequest()
                assertTrue(
                    first
                        .getHeader("Authorization")!!
                        .startsWith(
                            when (protocol) {
                                "S3" -> "AWS4-HMAC-SHA256"
                                "OSS" -> "OSS4-HMAC-SHA256"
                                "COS" -> "q-sign-algorithm=sha1"
                                else -> "OBS "
                            },
                        ),
                )
                assertTrue(server.takeRequest().path!!.contains("marker=hello.txt"))
                server.enqueue(MockResponse().setResponseCode(200))
                store.write("/中文 $+' file.txt", ByteArrayInputStream("hello".toByteArray()), 5)
                val upload = server.takeRequest()
                assertEquals("hello", upload.body.readUtf8())
                assertTrue(upload.path!!.contains("%24%2B%27"))
                assertEquals("PUT", upload.method)
                server.enqueue(MockResponse().setBody("hello"))
                val out = ByteArrayOutputStream()
                store.read("/hello.txt", out)
                assertEquals("hello", out.toString())
                server.takeRequest()
                server.enqueue(MockResponse().setResponseCode(204))
                store.delete("/hello.txt", false)
                assertEquals("DELETE", server.takeRequest().method)
            }
        }
    }

    @Test
    fun cosSignerMatchesOfficialSdkForUnicodeAndQueryParameters() {
        val now = Instant.parse("2026-10-14T00:00:00Z")
        val url =
            okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("bucket.cos.ap-shanghai.myqcloud.com")
                .addPathSegment("中文 file+name.txt")
                .addQueryParameter("prefix", "a + b")
                .addQueryParameter("marker", "中文")
                .build()
        val actual =
            ObjectSigner.sign(
                "COS",
                "GET",
                url,
                "bucket",
                "ap-shanghai",
                "test-id",
                "test-secret",
                emptyMap(),
                now,
            )
        val expected =
            COSSigner()
                .buildAuthorizationStr(
                    HttpMethodName.GET,
                    "/中文 file+name.txt",
                    mapOf("host" to url.host),
                    mapOf("prefix" to "a + b", "marker" to "中文"),
                    BasicCOSCredentials("test-id", "test-secret"),
                    Date.from(now.minusSeconds(60)),
                    Date.from(now.plusSeconds(900)),
                    true,
                )
        assertEquals(expected, actual["authorization"])
    }
}
