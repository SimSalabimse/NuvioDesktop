package com.nuvio.app.features.analyzeddetails

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal data class DerivedKeys(
    val master: ByteArray,
    val encKey: ByteArray,
    val authToken: ByteArray,
    val keyCheck: ByteArray,
)

internal data class RoutingAad(
    val formatVersion: Int,
    val spaceId: String,
    val blobId: String,
    val deviceId: String,
    val kind: String,
)

internal object SyncCrypto {
    const val SALT_BYTES: Int = 16
    const val NONCE_BYTES: Int = 12
    const val KEY_BYTES: Int = 32
    const val KEY_CHECK_BYTES: Int = 16

    private const val ENC_INFO = "nuvio-sync-v1 enc"
    private const val AUTH_INFO = "nuvio-sync-v1 auth"
    private const val CHECK_INFO = "nuvio-sync-v1 check"

    fun normalizePassphrase(passphrase: String): String = Normalizer.normalize(passphrase, Normalizer.Form.NFC)

    fun deriveKeys(passphrase: String, salt: ByteArray): DerivedKeys {
        require(salt.size == SALT_BYTES)
        val master = pbkdf2(normalizePassphrase(passphrase), salt, KDF_ITERATIONS)
        val encKey = hmacBlock(master, ENC_INFO)
        val authToken = hmacBlock(master, AUTH_INFO)
        val check = hmacBlock(master, CHECK_INFO).copyOf(KEY_CHECK_BYTES)
        return DerivedKeys(master, encKey, authToken, check)
    }

    fun associatedDataText(routing: RoutingAad): String =
        "nuvio-sync/${routing.formatVersion}\n${routing.spaceId}\n${routing.blobId}\n${routing.deviceId}\n${routing.kind}\n"

    fun associatedData(routing: RoutingAad): ByteArray = associatedDataText(routing).encodeToByteArray()

    fun randomNonce(random: SecureRandom = SecureRandom()): ByteArray {
        val nonce = ByteArray(NONCE_BYTES)
        random.nextBytes(nonce)
        return nonce
    }

    fun randomSalt(random: SecureRandom = SecureRandom()): ByteArray {
        val salt = ByteArray(SALT_BYTES)
        random.nextBytes(salt)
        return salt
    }

    fun joinCode(spaceId: String, salt: ByteArray, keyCheck: ByteArray): String =
        "nuvio-sync-v1:$spaceId:${encodeB64Url(salt)}:${encodeB64Url(keyCheck)}"

    fun bearerToken(authToken: ByteArray): String = encodeB64Url(authToken)

    fun encrypt(encKey: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        require(encKey.size == KEY_BYTES && nonce.size == NONCE_BYTES)
        require(plaintext.size <= MAX_PLAINTEXT_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    fun decrypt(encKey: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray? {
        if (encKey.size != KEY_BYTES || nonce.size != NONCE_BYTES || ciphertext.size < 16) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertext)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
        if (left.size != right.size) return false
        var diff = 0
        for (index in left.indices) diff = diff or (left[index].toInt() xor right[index].toInt())
        return diff == 0
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256Hex(text: String): String = encodeHex(sha256(text.encodeToByteArray()))

    fun encodeHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun decodeHex(value: String): ByteArray? {
        if (value.length % 2 != 0 || !value.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return ByteArray(value.length / 2) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    fun encodeB64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun encodeB64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decodeB64(value: String): ByteArray? {
        if (!Regex("^[A-Za-z0-9+/]*={0,2}$").matches(value) || value.length % 4 != 0) return null
        return try {
            val decoded = Base64.getDecoder().decode(value)
            if (Base64.getEncoder().encodeToString(decoded) != value) null else decoded
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun decodeB64Url(value: String): ByteArray? {
        if (!Regex("^[A-Za-z0-9_-]+$").matches(value)) return null
        val pad = "=".repeat((4 - value.length % 4) % 4)
        return try {
            Base64.getUrlDecoder().decode(value + pad)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun pbkdf2(passphrase: String, salt: ByteArray, iterations: Int): ByteArray {
        val chars = passphrase.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, KEY_BYTES * 8)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    private fun hmacBlock(master: ByteArray, label: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(master, "HmacSHA256"))
        mac.update(label.encodeToByteArray())
        mac.update(byteArrayOf(0x01))
        return mac.doFinal()
    }
}

private typealias GeneralSecurityException = java.security.GeneralSecurityException
