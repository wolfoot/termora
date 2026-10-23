package app.termora.android.core

import app.termora.keymgr.OhKeyPair
import app.termora.randomUUID
import java.io.*
import java.security.KeyPairGenerator
import java.util.Base64
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.crypto.params.*
import org.bouncycastle.crypto.util.*

object Keys {
    private fun pem(label: String, bytes: ByteArray) =
        "-----BEGIN $label-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(bytes) +
            "\n-----END $label-----\n"

    fun privatePem(key: OhKeyPair): ByteArray =
        pem("PRIVATE KEY", Base64.getDecoder().decode(key.privateKey)).toByteArray()

    fun sshPrivatePem(key: OhKeyPair): ByteArray {
        val param = PrivateKeyFactory.createKey(Base64.getDecoder().decode(key.privateKey))
        return when (param) {
            is Ed25519PrivateKeyParameters ->
                pem("OPENSSH PRIVATE KEY", OpenSSHPrivateKeyUtil.encodePrivateKey(param))
                    .toByteArray()
            is ECPrivateKeyParameters ->
                pem(
                        "EC PRIVATE KEY",
                        PrivateKeyInfoFactory.createPrivateKeyInfo(param)
                            .parsePrivateKey()
                            .toASN1Primitive()
                            .encoded,
                    )
                    .toByteArray()
            else -> privatePem(key)
        }
    }

    fun publicSsh(key: OhKeyPair): String {
        val param = PublicKeyFactory.createKey(Base64.getDecoder().decode(key.publicKey))
        val blob = OpenSSHPublicKeyUtil.encodePublicKey(param)
        val input = DataInputStream(ByteArrayInputStream(blob))
        val prefix = ByteArray(input.readInt()).also(input::readFully).toString(Charsets.US_ASCII)
        return prefix + " " + Base64.getEncoder().encodeToString(blob) + " " + key.name
    }

    fun generate(name: String, ed25519: Boolean): OhKeyPair {
        val privateKey =
            if (ed25519) {
                val param = Ed25519PrivateKeyParameters(java.security.SecureRandom())
                PrivateKeyInfoFactory.createPrivateKeyInfo(param).encoded
            } else
                KeyPairGenerator.getInstance("RSA")
                    .apply { initialize(3072) }
                    .generateKeyPair()
                    .private
                    .encoded
        return fromDer(name, privateKey)
    }

    fun generateEcdsa(name: String): OhKeyPair =
        fromDer(
            name,
            KeyPairGenerator.getInstance("EC")
                .apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }
                .generateKeyPair()
                .private
                .encoded,
        )

    fun import(name: String, bytes: ByteArray, password: String = ""): OhKeyPair {
        val text = bytes.toString(Charsets.US_ASCII).trim()
        require(bytes.size <= 1024 * 1024)
        val param =
            when {
                text.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----") ->
                    OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(decodePem(text))
                text.startsWith("-----BEGIN RSA PRIVATE KEY-----") ->
                    PrivateKeyFactory.createKey(
                        PrivateKeyInfo(
                            org.bouncycastle.asn1.x509.AlgorithmIdentifier(
                                org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.rsaEncryption,
                                org.bouncycastle.asn1.DERNull.INSTANCE,
                            ),
                            org.bouncycastle.asn1.pkcs.RSAPrivateKey.getInstance(decodePem(text)),
                        ),
                    )
                text.startsWith("-----BEGIN PRIVATE KEY-----") ->
                    PrivateKeyFactory.createKey(decodePem(text))
                text.startsWith("-----BEGIN ENCRYPTED PRIVATE KEY-----") -> {
                    val info = org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo(decodePem(text))
                    val provider =
                        org.bouncycastle.openssl.jcajce
                            .JceOpenSSLPKCS8DecryptorProviderBuilder()
                            .setProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
                            .build(password.toCharArray())
                    PrivateKeyFactory.createKey(info.decryptPrivateKeyInfo(provider))
                }
                else -> error("支持 RSA/Ed25519 的 PEM、PKCS#8 和未加密 OpenSSH 私钥")
            }
        return fromDer(name, PrivateKeyInfoFactory.createPrivateKeyInfo(param).encoded)
    }

    private fun decodePem(text: String) =
        Base64.getDecoder()
            .decode(text.lines().filterNot { it.startsWith("-----") }.joinToString(""))

    private fun fromDer(name: String, der: ByteArray): OhKeyPair {
        val param = PrivateKeyFactory.createKey(der)
        val public =
            when (param) {
                is RSAPrivateCrtKeyParameters ->
                    RSAKeyParameters(false, param.modulus, param.publicExponent)
                is Ed25519PrivateKeyParameters -> param.generatePublicKey()
                is ECPrivateKeyParameters ->
                    ECPublicKeyParameters(param.parameters.g.multiply(param.d), param.parameters)
                else -> error("支持 RSA、Ed25519 和命名曲线 ECDSA")
            }
        return OhKeyPair(
            randomUUID(),
            Base64.getEncoder()
                .encodeToString(
                    SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(public).encoded,
                ),
            Base64.getEncoder().encodeToString(der),
            when (param) {
                is Ed25519PrivateKeyParameters -> "ED25519"
                is ECPrivateKeyParameters -> "ECDSA-SHA2-NISTP${param.parameters.curve.fieldSize}"
                else -> "RSA"
            },
            name,
            "",
            when (param) {
                is RSAPrivateCrtKeyParameters -> param.modulus.bitLength()
                is ECPrivateKeyParameters -> param.parameters.curve.fieldSize
                else -> 256
            },
            System.currentTimeMillis(),
        )
    }
}
