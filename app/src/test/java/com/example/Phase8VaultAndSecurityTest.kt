package com.example

import com.example.data.engine.PathSecuritySanitizer
import com.example.data.engine.VaultAuthMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class Phase8VaultAndSecurityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testPathSecuritySanitizer_PathTraversalPrevention() {
        // Zip Slip / Directory Traversal attempts
        val traversal1 = "../../etc/passwd"
        assertEquals("passwd", PathSecuritySanitizer.sanitizeFileName(traversal1))

        val traversal2 = "..\\..\\windows\\system32\\cmd.exe"
        assertEquals("cmd.exe", PathSecuritySanitizer.sanitizeFileName(traversal2))

        val traversal3 = "%2e%2e%2f%2e%2e%2fetc%2fshadow"
        assertEquals("shadow", PathSecuritySanitizer.sanitizeFileName(traversal3))

        val traversal4 = "normal_dir/sub_dir/movie.mp4"
        assertEquals("movie.mp4", PathSecuritySanitizer.sanitizeFileName(traversal4))

        val traversal5 = "....//....//exploit.apk"
        assertEquals("exploit.apk", PathSecuritySanitizer.sanitizeFileName(traversal5))
    }

    @Test
    fun testPathSecuritySanitizer_IllegalCharactersAndNullBytes() {
        val illegalName = "my<bad>file:name?with*pipe|and\"quote.mp4"
        val sanitized = PathSecuritySanitizer.sanitizeFileName(illegalName)
        assertFalse(sanitized.contains('<'))
        assertFalse(sanitized.contains('>'))
        assertFalse(sanitized.contains(':'))
        assertFalse(sanitized.contains('?'))
        assertFalse(sanitized.contains('*'))
        assertFalse(sanitized.contains('|'))
        assertFalse(sanitized.contains('"'))
        assertTrue(sanitized.endsWith(".mp4"))

        // Null byte injection
        val nullByteName = "innocent.pdf\u0000.exe"
        val nullSanitized = PathSecuritySanitizer.sanitizeFileName(nullByteName)
        assertFalse(nullSanitized.contains('\u0000'))
    }

    @Test
    fun testPathSecuritySanitizer_IsSafeDestination() {
        val baseDir = tempFolder.newFolder("safe_downloads")

        assertTrue(PathSecuritySanitizer.isSafeDestination(baseDir, "file.zip"))
        assertTrue(PathSecuritySanitizer.isSafeDestination(baseDir, "nested/file.zip"))
        assertTrue(PathSecuritySanitizer.isSafeDestination(baseDir, "../../escape.zip"))
    }

    @Test
    fun testVaultAuthMode_EnumValuesAndMapping() {
        assertEquals(VaultAuthMode.BIOMETRIC_ONLY, VaultAuthMode.fromId("BIOMETRIC_ONLY"))
        assertEquals(VaultAuthMode.PIN_ONLY, VaultAuthMode.fromId("PIN_ONLY"))
        assertEquals(VaultAuthMode.BIOMETRIC_AND_PIN, VaultAuthMode.fromId("BIOMETRIC_AND_PIN"))
        assertEquals(VaultAuthMode.PIN_ONLY, VaultAuthMode.fromId("INVALID_MODE"))

        assertEquals("Sadece Cihaz Biyometriği", VaultAuthMode.BIOMETRIC_ONLY.titleTr)
        assertEquals("Sadece Özel PIN / Şifre", VaultAuthMode.PIN_ONLY.titleTr)
        assertEquals("İkisi Birden (Çift Aşama)", VaultAuthMode.BIOMETRIC_AND_PIN.titleTr)
    }

    @Test
    fun testAes256Gcm_EncryptionAndDecryptionConsistency() {
        val originalData = "Zireael Secret Vault Protected Media Content 123456789".toByteArray(Charsets.UTF_8)

        // 256-bit AES key
        val keyBytes = ByteArray(32).apply { SecureRandom().nextBytes(this) }
        val secretKey = SecretKeySpec(keyBytes, "AES")

        // 12-byte IV (96-bit standard)
        val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }

        // Encrypt
        val encryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        encryptCipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val ciphertext = encryptCipher.doFinal(originalData)

        assertNotEquals(String(originalData), String(ciphertext))

        // Decrypt
        val decryptCipher = Cipher.getInstance("AES/GCM/NoPadding")
        decryptCipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val decryptedData = decryptCipher.doFinal(ciphertext)

        assertEquals(String(originalData), String(decryptedData))
    }

    @Test
    fun testPinHashing_SaltedSha256() {
        val pin = "1234"
        val salt = ByteArray(16).apply { SecureRandom().nextBytes(this) }

        val hash1 = hashPin(pin, salt)
        val hash2 = hashPin(pin, salt)
        val wrongPinHash = hashPin("9999", salt)

        assertEquals(hash1, hash2)
        assertNotEquals(hash1, wrongPinHash)
    }

    private fun hashPin(pin: String, salt: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        val digest = md.digest(pin.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
