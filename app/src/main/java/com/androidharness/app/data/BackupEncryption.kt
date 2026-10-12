package com.androidharness.app.data

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object BackupEncryption {
    const val MIN_PASSWORD_LENGTH = 6
    const val MAX_PASSWORD_LENGTH = 1024
    const val MAX_BYTES = 4 * 1024 * 1024
    private const val ITERATIONS = 600_000
    private val magic = byteArrayOf(72, 65, 72, 66, 1)
    private const val HEADER_SIZE = 33

    fun encrypt(plain: ByteArray, password: CharArray): ByteArray {
        require(password.size in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH) { "Use a password between $MIN_PASSWORD_LENGTH and $MAX_PASSWORD_LENGTH characters." }
        require(plain.size <= MAX_BYTES - HEADER_SIZE - 16) { "Backup is too large." }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val header = magic + salt + nonce
        return header + crypt(Cipher.ENCRYPT_MODE, plain, password, header)
    }

    fun decrypt(file: ByteArray, password: CharArray): ByteArray {
        require(file.size in (HEADER_SIZE + 16)..MAX_BYTES) { "Invalid backup size." }
        require(file.copyOfRange(0, magic.size).contentEquals(magic)) { "Unsupported settings backup." }
        require(password.size in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH) { "Incorrect password or damaged backup." }
        return try {
            crypt(Cipher.DECRYPT_MODE, file.copyOfRange(HEADER_SIZE, file.size), password, file.copyOfRange(0, HEADER_SIZE))
        } catch (_: java.security.GeneralSecurityException) {
            throw IllegalArgumentException("Incorrect password or damaged backup.")
        }
    }

    private fun crypt(mode: Int, input: ByteArray, password: CharArray, header: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, header.copyOfRange(5, 21), ITERATIONS, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, header.copyOfRange(21, 33)))
            cipher.updateAAD(header)
            cipher.doFinal(input)
        } finally { key.fill(0) }
    }
}
