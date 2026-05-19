package com.numa

import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

/**
 * Effacement sécurisé multi-passes (style DoD 5220.22-M étendu).
 *
 * Passes dans l'ordre :
 *   1. 0x00
 *   2. 0xFF
 *   3. 0x00
 *   4. Aléatoire (CSPRNG)
 *   5. 0xAA
 *   6. 0x55
 *   7. Aléatoire (CSPRNG)
 */
object SecureWipe {

    private const val BUFFER_SIZE = 64 * 1024  // 64 KB
    private val rng = SecureRandom()

    /**
     * Efface un fichier en [passes] passes puis le supprime.
     * @return true si toutes les passes et la suppression ont réussi.
     */
    fun wipeFile(file: File, passes: Int = 7): Boolean {
        if (!file.exists() || !file.isFile) return true
        return try {
            val len = file.length()
            RandomAccessFile(file, "rws").use { raf ->
                repeat(passes) { pass ->
                    raf.seek(0)
                    writePass(raf, len, pass)
                    // fsync obligatoire après chaque passe
                    raf.channel.force(true)
                }
            }
            // Renommage aléatoire avant suppression finale
            val randomName = buildString {
                repeat(16) { append(('a'..'z').random()) }
            }
            val renamed = File(file.parent, randomName)
            file.renameTo(renamed)
            renamed.delete()
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.e("SecureWipe", "wipeFile failed: ${file.name}", e)
            false
        }
    }

    /**
     * Efface récursivement tous les fichiers d'un dossier, puis le supprime.
     */
    fun wipeDirectory(dir: File, passes: Int = 7): Boolean {
        if (!dir.exists()) return true
        var ok = true
        dir.walkBottomUp().forEach { f ->
            if (f.isFile) {
                ok = wipeFile(f, passes) && ok
            } else if (f.isDirectory && f != dir) {
                ok = f.delete() && ok
            }
        }
        ok = dir.delete() && ok
        return ok
    }

    // ─── Internes ─────────────────────────────────────────────────────────────

    private fun writePass(raf: RandomAccessFile, fileLen: Long, passIndex: Int) {
        val buf = ByteArray(BUFFER_SIZE)
        var remaining = fileLen

        while (remaining > 0) {
            val chunk = minOf(remaining, BUFFER_SIZE.toLong()).toInt()
            fillBuffer(buf, chunk, passIndex)
            raf.write(buf, 0, chunk)
            remaining -= chunk
        }
    }

    private fun fillBuffer(buf: ByteArray, len: Int, passIndex: Int) {
        when (passIndex % 7) {
            0    -> buf.fill(0x00.toByte(), 0, len)
            1    -> buf.fill(0xFF.toByte(), 0, len)
            2    -> buf.fill(0x00.toByte(), 0, len)
            3    -> rng.nextBytes(buf.also { if (it.size != len) { /* déjà la bonne taille */ } }
                        .also { System.arraycopy(ByteArray(len).also { rng.nextBytes(it) }, 0, it, 0, len) }
                   ).let { /* nextBytes déjà appelé */ }
            4    -> buf.fill(0xAA.toByte(), 0, len)
            5    -> buf.fill(0x55.toByte(), 0, len)
            6    -> {
                val tmp = ByteArray(len)
                rng.nextBytes(tmp)
                System.arraycopy(tmp, 0, buf, 0, len)
            }
        }
    }
}
