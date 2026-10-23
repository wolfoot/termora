package app.termora

import java.util.Base64
import java.util.UUID

fun randomUUID() = UUID.randomUUID().toString().replace("-", "")

object AES {
    fun String.decodeBase64(): ByteArray = Base64.getDecoder().decode(this)
}

object TerminalPlatform {
    var bell: () -> Unit = {}
    var clipboard: (String) -> Unit = {}
    var assertThread: () -> Unit = {}
}

fun assertEventDispatchThread() = TerminalPlatform.assertThread()
