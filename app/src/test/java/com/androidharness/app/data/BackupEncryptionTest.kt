package com.androidharness.app.data

import org.junit.Assert.*
import org.junit.Test

class BackupEncryptionTest {
    private fun password() = "a long unique backup password".toCharArray()

    @Test fun roundTrip() {
        val plain = "private-api-key-and-settings".toByteArray()
        val encrypted = BackupEncryption.encrypt(plain, password())
        assertArrayEquals(plain, BackupEncryption.decrypt(encrypted, password()))
        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains("private-api-key"))
    }

    @Test fun randomSaltAndNonce() {
        val plain = "same data".toByteArray()
        assertFalse(BackupEncryption.encrypt(plain, password()).contentEquals(BackupEncryption.encrypt(plain, password())))
    }

    @Test fun wrongPasswordIsRejected() {
        val encrypted = BackupEncryption.encrypt(byteArrayOf(1, 2), password())
        assertThrows(IllegalArgumentException::class.java) { BackupEncryption.decrypt(encrypted, "wrong password here".toCharArray()) }
    }

    @Test fun tamperingIsRejected() {
        val encrypted = BackupEncryption.encrypt(byteArrayOf(1, 2), password())
        for (position in listOf(0, 5, 21, 33, encrypted.lastIndex)) {
            val altered = encrypted.copyOf()
            altered[position] = (altered[position].toInt() xor 1).toByte()
            assertThrows(IllegalArgumentException::class.java) { BackupEncryption.decrypt(altered, password()) }
        }
    }

    @Test fun truncationAndOversizeAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { BackupEncryption.decrypt(ByteArray(20), password()) }
        assertThrows(IllegalArgumentException::class.java) { BackupEncryption.decrypt(ByteArray(BackupEncryption.MAX_BYTES + 1), password()) }
        assertThrows(IllegalArgumentException::class.java) { BackupEncryption.encrypt(ByteArray(BackupEncryption.MAX_BYTES), password()) }
    }

    @Test fun shortPasswordIsRejected() {
        for (short in listOf("", "12345")) {
            assertThrows(IllegalArgumentException::class.java) { BackupEncryption.encrypt(byteArrayOf(1), short.toCharArray()) }
        }
    }

    @Test fun sixCharacterPasswordRoundTrip() {
        val plain = "private-api-key-and-settings".toByteArray()
        val encrypted = BackupEncryption.encrypt(plain, "abc123".toCharArray())
        assertArrayEquals(plain, BackupEncryption.decrypt(encrypted, "abc123".toCharArray()))
        assertThrows(IllegalArgumentException::class.java) { BackupEncryption.decrypt(encrypted, "12345".toCharArray()) }
    }
}
