package app.termora

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Versioned envelopes; legacy formats are read only. */
object SyncEncryption {
    private const val KEY_PREFIX = "termora-gcm-v2:"
    private const val PASSWORD_PREFIX = "termora-pbe-v2:"
    private const val NONCE_SIZE = 12
    private const val SALT_SIZE = 16
    private const val TAG_SIZE = 16
    private const val ITERATIONS = 210_000
    private val random = SecureRandom()

    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

    private fun crypt(mode: Int, key: ByteArray, nonce: ByteArray, data: ByteArray, context: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE * 8, nonce))
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(data)
    }

    fun encrypt(key: ByteArray, data: ByteArray, context: String): String {
        val nonce = randomBytes(NONCE_SIZE)
        val ciphertext = crypt(Cipher.ENCRYPT_MODE, key, nonce, data, KEY_PREFIX + context)
        return KEY_PREFIX + Base64.getEncoder().encodeToString(nonce + ciphertext)
    }

    fun decrypt(key: ByteArray, data: String, context: String): ByteArray {
        require(data.startsWith(KEY_PREFIX)) { "Unsupported sync encryption format" }
        val payload = Base64.getDecoder().decode(data.removePrefix(KEY_PREFIX))
        require(payload.size >= NONCE_SIZE + TAG_SIZE) { "Truncated sync ciphertext" }
        return crypt(Cipher.DECRYPT_MODE, key, payload.copyOfRange(0, NONCE_SIZE),
            payload.copyOfRange(NONCE_SIZE, payload.size), KEY_PREFIX + context)
    }

    fun decryptAccount(key: ByteArray, data: String, id: String, ownerId: String): ByteArray {
        if (data.startsWith(KEY_PREFIX)) return decrypt(key, data, "$ownerId:$id")
        // Original account data was authenticated GCM with a nonce derived from the object ID.
        // New writes always use random nonces and bind both the owner and object ID.
        val nonce = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)).copyOf(NONCE_SIZE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE * 8, nonce))
        return cipher.doFinal(Base64.getDecoder().decode(data))
    }

    fun isPasswordEncrypted(data: String) = data.startsWith(PASSWORD_PREFIX)

    private fun passwordKey(password: String, salt: ByteArray): ByteArray {
        require(password.length >= 12 && password.isNotBlank()) { "A separate sync encryption password of at least 12 characters is required" }
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
        } finally {
            chars.fill('\u0000')
            spec.clearPassword()
        }
    }

    fun encryptWithPassword(password: String, data: String, context: String): String {
        val salt = randomBytes(SALT_SIZE)
        val key = passwordKey(password, salt)
        return try {
            val nonce = randomBytes(NONCE_SIZE)
            val ciphertext = crypt(Cipher.ENCRYPT_MODE, key, nonce, data.toByteArray(Charsets.UTF_8), PASSWORD_PREFIX + context)
            PASSWORD_PREFIX + Base64.getEncoder().encodeToString(salt + nonce + ciphertext)
        } finally {
            key.fill(0)
        }
    }

    fun decryptWithPassword(password: String, data: String, context: String): String {
        require(isPasswordEncrypted(data)) { "Unsupported sync encryption format" }
        val payload = Base64.getDecoder().decode(data.removePrefix(PASSWORD_PREFIX))
        require(payload.size >= SALT_SIZE + NONCE_SIZE + TAG_SIZE) { "Truncated sync ciphertext" }
        val key = passwordKey(password, payload.copyOfRange(0, SALT_SIZE))
        return try {
            crypt(Cipher.DECRYPT_MODE, key, payload.copyOfRange(SALT_SIZE, SALT_SIZE + NONCE_SIZE),
                payload.copyOfRange(SALT_SIZE + NONCE_SIZE, payload.size), PASSWORD_PREFIX + context)
                .toString(Charsets.UTF_8)
        } finally {
            key.fill(0)
        }
    }
}
