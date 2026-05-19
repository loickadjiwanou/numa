package com.numa

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Compression / décompression ZIP avec préservation de la structure de dossiers.
 */
object ZipManager {

    private const val BUFFER_SIZE = 8 * 1024  // 8 KB

    /**
     * Zippe récursivement [folderPath] dans [outputZip].
     * Les entrées ZIP sont relatives à [folderPath] (ex: "photos/img.jpg").
     */
    fun zipFolder(folderPath: String, outputZip: File): Boolean {
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) return false

        return try {
            ZipOutputStream(FileOutputStream(outputZip).buffered()).use { zos ->
                folder.walkTopDown().forEach { file ->
                    if (file.isFile) {
                        val entryName = file.relativeTo(folder).path
                        zos.putNextEntry(ZipEntry(entryName))
                        FileInputStream(file).use { fis ->
                            val buf = ByteArray(BUFFER_SIZE)
                            var read: Int
                            while (fis.read(buf).also { read = it } != -1) {
                                zos.write(buf, 0, read)
                            }
                        }
                        zos.closeEntry()
                    }
                }
            }
            true
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.e("ZipManager", "zipFolder failed", e)
            outputZip.delete()
            false
        }
    }

    /**
     * Dézippe [zipFile] vers [destFolder].
     * Crée les sous-dossiers si nécessaire. Protège contre le path traversal.
     */
    fun unzipFile(zipFile: File, destFolder: String): Boolean {
        val dest = File(destFolder)
        dest.mkdirs()

        return try {
            ZipInputStream(FileInputStream(zipFile).buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(dest, entry.name)

                    // Protection path traversal
                    val canonicalDest = dest.canonicalPath
                    val canonicalOut  = outFile.canonicalPath
                    if (!canonicalOut.startsWith(canonicalDest + File.separator)) {
                        if (BuildConfig.DEBUG)
                            android.util.Log.w("ZipManager", "Path traversal détecté : ${entry.name}")
                        entry = zis.nextEntry
                        continue
                    }

                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            val buf = ByteArray(BUFFER_SIZE)
                            var read: Int
                            while (zis.read(buf).also { read = it } != -1) {
                                fos.write(buf, 0, read)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.e("ZipManager", "unzipFile failed", e)
            false
        }
    }
}
