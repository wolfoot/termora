package app.termora.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.termora.android.core.*
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The only persisted configuration is authenticated ciphertext; the wrapping key is non-exportable.
 */
class VaultStore(context: Context, name: String = "vault") {
    private val file = AtomicFile(File(context.filesDir, "$name.gcm"))
    private val key: SecretKey

    init {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        key =
            (ks.getKey("termora.$name", null) as? SecretKey)
                ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                    .apply {
                        init(
                            KeyGenParameterSpec.Builder(
                                    "termora.$name",
                                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                                )
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .setKeySize(256)
                                .build(),
                        )
                    }
                    .generateKey()
    }

    @Synchronized
    fun load(): Vault {
        if (!file.baseFile.exists()) return Vault()
        val bytes = file.readFully()
        require(bytes.size in 28..(32 * 1024 * 1024))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        c.updateAAD("Termora Android vault v1".toByteArray())
        return wireJson.decodeFromString(
            c.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8),
        )
    }

    @Synchronized
    fun save(vault: Vault) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        c.updateAAD("Termora Android vault v1".toByteArray())
        val bytes = c.iv + c.doFinal(wireJson.encodeToString(vault).toByteArray())
        val out = file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }
}
