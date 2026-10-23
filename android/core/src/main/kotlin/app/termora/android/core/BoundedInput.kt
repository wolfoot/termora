package app.termora.android.core

import java.io.ByteArrayOutputStream
import java.io.InputStream

fun InputStream.readLimited(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        require(out.size() + n <= limit) { "响应内容超过允许大小" }
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
