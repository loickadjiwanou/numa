package com.numa

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * AES-256-GCM file encryption / decryption.
 *
 * Format fichier chiffré :
 *   [12 bytes IV] [ciphertext + 16 bytes GCM auth tag]
 */
object CryptoManager {

    private const val ALGORITHM      = "AES/GCM/NoPadding"
    private const val KEY_SIZE_BYTES = 32   // 256 bits
    private const val IV_SIZE_BYTES  = 12   // 96 bits — recommandé pour GCM
    private const val GCM_TAG_BITS   = 128
    private const val BUFFER_SIZE    = 8 * 1024  // 8 KB

    /**
     * Chiffre [source] vers [dest].
     * @param hexKey  clé AES-256 encodée en hexadécimal (64 caractères).
     */
    fun encryptFile(source: File, dest: File, hexKey: String): Boolean {
        return try {
            val key    = hexToKey(hexKey)
            val iv     = ByteArray(IV_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance(ALGORITHM).apply {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }

            FileInputStream(source).use { fis ->
                FileOutputStream(dest).use { fos ->
                    // Écriture de l'IV en tête
                    fos.write(iv)

                    val buf = ByteArray(BUFFER_SIZE)
                    var read: Int
                    while (fis.read(buf).also { read = it } != -1) {
                        val enc = cipher.update(buf, 0, read)
                        if (enc != null) fos.write(enc)
                    }
                    // Finalisation : ajoute le GCM auth tag (16 bytes)
                    fos.write(cipher.doFinal())
                }
            }
            true
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.e("CryptoManager", "encryptFile failed", e)
            dest.delete()
            false
        }
    }

    /**
     * Déchiffre [source] vers [dest].
     * @param hexKey  clé AES-256 encodée en hexadécimal (64 caractères).
     */
    fun decryptFile(source: File, dest: File, hexKey: String): Boolean {
        return try {
            val key = hexToKey(hexKey)

            FileInputStream(source).use { fis ->
                // Lecture de l'IV
                val iv = ByteArray(IV_SIZE_BYTES)
                if (fis.read(iv) != IV_SIZE_BYTES) error("Fichier chiffré invalide : IV tronqué")

                val cipher = Cipher.getInstance(ALGORITHM).apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                }

                FileOutputStream(dest).use { fos ->
                    val buf = ByteArray(BUFFER_SIZE)
                    var read: Int
                    while (fis.read(buf).also { read = it } != -1) {
                        val dec = cipher.update(buf, 0, read)
                        if (dec != null) fos.write(dec)
                    }
                    fos.write(cipher.doFinal())
                }
            }
            true
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.e("CryptoManager", "decryptFile failed", e)
            dest.delete()
            false
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun hexToKey(hex: String): SecretKeySpec {
        require(hex.length == KEY_SIZE_BYTES * 2) {
            "La clé doit faire ${KEY_SIZE_BYTES * 2} caractères hexadécimaux"
        }
        val bytes = ByteArray(KEY_SIZE_BYTES) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return SecretKeySpec(bytes, "AES")
    }
}
