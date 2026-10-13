package app.termora

import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class SyncEncryptionTest {
    private val key = ByteArray(32) { it.toByte() }
    private val data = "SSH password and private key: 中国 😄".toByteArray()

    @Test
    fun accountRecordsUseFreshNoncesAndAuthenticateIdentity() {
        val first = SyncEncryption.encrypt(key, data, "owner:record")
        val second = SyncEncryption.encrypt(key, data, "owner:record")
        assertNotEquals(first, second)
        assertContentEquals(data, SyncEncryption.decryptAccount(key, first, "record", "owner"))
        assertFails { SyncEncryption.decryptAccount(key, first, "other-record", "owner") }
        assertFails { SyncEncryption.decryptAccount(key, first, "record", "other-owner") }
        assertFails { SyncEncryption.decrypt(ByteArray(32), first, "owner:record") }
        assertFails { SyncEncryption.decrypt(key, corrupt(first), "owner:record") }
        assertFails { SyncEncryption.decrypt(key, "termora-gcm-v2:AA==", "owner:record") }
    }

    @Test
    fun readsExistingAccountCiphertextWithoutWritingTheOldFormat() {
        val iv = MessageDigest.getInstance("SHA-256").digest("record".toByteArray()).copyOf(12)
        val legacy = Base64.getEncoder().encodeToString(AES.GCM.encrypt(key, iv, data))
        assertContentEquals(data, SyncEncryption.decryptAccount(key, legacy, "record", "owner"))
        assertFails { SyncEncryption.decryptAccount(key, corrupt(legacy), "record", "owner") }
    }

    @Test
    fun passwordEnvelopesAuthenticateAllDataAndFileType() {
        val password = "separate encryption password"
        val plaintext = "{\"deleted\":false,\"privateKey\":\"secret\"}"
        val first = SyncEncryption.encryptWithPassword(password, plaintext, "Hosts")
        val second = SyncEncryption.encryptWithPassword(password, plaintext, "Hosts")
        assertNotEquals(first, second)
        assertEquals(plaintext, SyncEncryption.decryptWithPassword(password, first, "Hosts"))
        assertFails { SyncEncryption.decryptWithPassword("wrong password", first, "Hosts") }
        assertFails { SyncEncryption.decryptWithPassword(password, first, "DeletedData") }
        assertFails { SyncEncryption.decryptWithPassword(password, corrupt(first), "Hosts") }
        assertFails { SyncEncryption.decryptWithPassword(password, "termora-pbe-v2:AA==", "Hosts") }
    }

    private fun corrupt(envelope: String): String {
        val prefix = envelope.substringBeforeLast(':', "").let { if (it.isEmpty()) "" else "$it:" }
        val bytes = Base64.getDecoder().decode(envelope.removePrefix(prefix))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        return prefix + Base64.getEncoder().encodeToString(bytes)
    }
}
