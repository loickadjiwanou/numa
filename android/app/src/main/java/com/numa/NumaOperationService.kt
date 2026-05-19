package com.numa

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest

/**
 * Service qui exécute les opérations PANIC et RESTORE.
 * Toutes les opérations longues tournent dans des coroutines Kotlin (Dispatchers.IO).
 */
class NumaOperationService : Service() {

    companion object {
        const val ACTION_PANIC   = "com.numa.ACTION_PANIC"
        const val ACTION_RESTORE = "com.numa.ACTION_RESTORE"

        private const val PREFS_NAME      = "numa_prefs"
        private const val KEY_START_TIME  = "panic_start_time"

        fun startPanic(context: Context) {
            context.startService(Intent(context, NumaOperationService::class.java).apply {
                action = ACTION_PANIC
            })
        }

        fun startRestore(context: Context) {
            context.startService(Intent(context, NumaOperationService::class.java).apply {
                action = ACTION_RESTORE
            })
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var vibrator: VolumeKeyReceiver
    private var operationWakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        vibrator = VolumeKeyReceiver(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PANIC   -> serviceScope.launch {
                acquireOperationLocks()
                try { doPanic() } finally { releaseOperationLocks(); stopSelf(startId) }
            }
            ACTION_RESTORE -> serviceScope.launch {
                acquireOperationLocks()
                try { doRestore() } finally { releaseOperationLocks(); stopSelf(startId) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseOperationLocks()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ─── WakeLock + WifiLock pendant l'opération ─────────────────────────────

    private fun acquireOperationLocks() {
        // Garde le CPU actif pendant toute l'opération (zip, chiffrement, upload/download)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        operationWakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Numa::OperationWakeLock"
        ).also { it.acquire(10 * 60 * 1000L) } // timeout max 10 min

        // Garde le WiFi actif pendant l'upload/download même écran éteint
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Numa::WifiLock")
            .also { it.acquire() }
    }

    private fun releaseOperationLocks() {
        operationWakeLock?.let { if (it.isHeld) it.release() }
        operationWakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── PANIC ────────────────────────────────────────────────────────────────

    private suspend fun doPanic() {
        // Vérification expiration avant toute chose
        if (isExpired()) {
            showToast("numa expiré")
            vibrator.vibrateError()
            return
        }

        val folderName    = BuildConfig.FOLDER_NAME
        val encryptionKey = BuildConfig.ENCRYPTION_KEY
        val wipePasses    = BuildConfig.WIPE_PASSES.toIntOrNull() ?: 7

        val sourceFolder  = File("/storage/emulated/0/$folderName")
        val cacheDir      = applicationContext.cacheDir
        val zipFile       = File(cacheDir, "numa_backup_${System.currentTimeMillis()}.zip")
        val encryptedFile = File(cacheDir, "numa_enc_${System.currentTimeMillis()}.bin")
        val remoteKey     = sha256OfString(folderName)

        if (BuildConfig.DEBUG) android.util.Log.d("NumaOp", "PANIC démarré : dossier=$folderName")

        // 1. Permission stockage externe (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            if (BuildConfig.DEBUG) android.util.Log.e("NumaOp", "MANAGE_EXTERNAL_STORAGE non accordée")
            vibrator.vibrateError()
            return
        }

        // 2. Vérification dossier source
        if (!sourceFolder.exists() || !sourceFolder.isDirectory) {
            if (BuildConfig.DEBUG) android.util.Log.w("NumaOp", "Dossier source introuvable : ${sourceFolder.absolutePath}")
            vibrator.vibrateError()
            return
        }

        val fileCount = sourceFolder.walkTopDown().count { it.isFile }
        if (BuildConfig.DEBUG) android.util.Log.d("NumaOp", "Fichiers trouvés dans Safe : $fileCount")

        // 3. Zip
        if (!ZipManager.zipFolder(sourceFolder.absolutePath, zipFile)) {
            if (BuildConfig.DEBUG) android.util.Log.e("NumaOp", "Zip échoué")
            zipFile.delete()
            vibrator.vibrateError()
            return
        }

        // 4. Chiffrement AES-256-GCM
        if (!CryptoManager.encryptFile(zipFile, encryptedFile, encryptionKey)) {
            if (BuildConfig.DEBUG) android.util.Log.e("NumaOp", "Chiffrement échoué")
            SecureWipe.wipeFile(zipFile, wipePasses)
            encryptedFile.delete()
            vibrator.vibrateError()
            return
        }

        SecureWipe.wipeFile(zipFile, wipePasses)

        // 5. Upload R2
        val uploader = buildUploader()
        val uploaded = uploader.uploadFile(encryptedFile, remoteKey)
        SecureWipe.wipeFile(encryptedFile, wipePasses)

        if (!uploaded) {
            if (BuildConfig.DEBUG) android.util.Log.e("NumaOp", "Upload échoué — dossier local préservé")
            vibrator.vibrateError()
            return
        }

        // 6. Effacement sécurisé du dossier Safe
        val wiped = SecureWipe.wipeDirectory(sourceFolder, wipePasses)
        if (!wiped && BuildConfig.DEBUG) android.util.Log.w("NumaOp", "Effacement du dossier incomplet")

        // 7. Enregistre l'heure du premier PANIC (pour l'expiration)
        recordFirstPanic()

        // 8. Succès
        vibrator.vibrateSuccess()
        showToast("numa panic ok")
        if (BuildConfig.DEBUG) android.util.Log.d("NumaOp", "PANIC terminé avec succès")
    }

    // ─── RESTORE ──────────────────────────────────────────────────────────────

    private suspend fun doRestore() {
        // Vérification expiration avant toute chose
        if (isExpired()) {
            showToast("numa expiré")
            vibrator.vibrateError()
            return
        }

        val folderName    = BuildConfig.FOLDER_NAME
        val encryptionKey = BuildConfig.ENCRYPTION_KEY
        val wipePasses    = BuildConfig.WIPE_PASSES.toIntOrNull() ?: 7

        val destFolder    = "/storage/emulated/0/$folderName"
        val cacheDir      = applicationContext.cacheDir
        val encryptedFile = File(cacheDir, "numa_enc_restore_${System.currentTimeMillis()}.bin")
        val zipFile       = File(cacheDir, "numa_restore_${System.currentTimeMillis()}.zip")
        val remoteKey     = sha256OfString(folderName)

        if (BuildConfig.DEBUG) android.util.Log.d("NumaOp", "RESTORE démarré : dossier=$folderName")

        // 1. Téléchargement depuis R2
        val uploader = buildUploader()
        if (!uploader.downloadFile(remoteKey, encryptedFile)) {
            if (BuildConfig.DEBUG) android.util.Log.e("NumaOp", "Téléchargement échoué")
            encryptedFile.delete()
            vibrator.vibrateError()
            return
        }

        // 2. Déchiffrement
        if (!CryptoManager.decryptFile(encryptedFile, zipFile, encryptionKey)) {
            if (BuildConfig.DEBUG) android.util.Log.e("NumaOp", "Déchiffrement échoué")
            SecureWipe.wipeFile(encryptedFile, wipePasses)
            zipFile.delete()
            vibrator.vibrateError()
            return
        }

        SecureWipe.wipeFile(encryptedFile, wipePasses)

        // 3. Dézip
        if (!ZipManager.unzipFile(zipFile, destFolder)) {
            if (BuildConfig.DEBUG) android.util.Log.e("NumaOp", "Dézip échoué")
            SecureWipe.wipeFile(zipFile, wipePasses)
            vibrator.vibrateError()
            return
        }

        SecureWipe.wipeFile(zipFile, wipePasses)

        // 4. Succès
        vibrator.vibrateSuccess()
        showToast("numa restore ok")
        if (BuildConfig.DEBUG) android.util.Log.d("NumaOp", "RESTORE terminé avec succès")
    }

    // ─── Expiration ───────────────────────────────────────────────────────────

    /**
     * Enregistre l'heure du premier PANIC réussi dans SharedPreferences.
     * N'écrase pas si déjà enregistré.
     */
    private fun recordFirstPanic() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_START_TIME, 0L) == 0L) {
            prefs.edit().putLong(KEY_START_TIME, System.currentTimeMillis()).apply()
            if (BuildConfig.DEBUG) android.util.Log.d("NumaOp", "Période de validité démarrée")
        }
    }

    /**
     * Retourne true si la période de validité est dépassée.
     * Si VALID_PERIOD est vide ou non parsable, pas d'expiration.
     */
    private fun isExpired(): Boolean {
        val startTime = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_START_TIME, 0L)
        if (startTime == 0L) return false   // premier PANIC pas encore fait

        val validMs = parsePeriod(BuildConfig.VALID_PERIOD)
        if (validMs <= 0L) return false     // pas de limite configurée

        val elapsed = System.currentTimeMillis() - startTime
        val expired = elapsed > validMs
        if (expired && BuildConfig.DEBUG)
            android.util.Log.w("NumaOp", "Période expirée (${elapsed / 1000}s > ${validMs / 1000}s)")
        return expired
    }

    /**
     * Parse une durée du type "3hrs", "2jrs", "1smns", "2mois", "1ans".
     * Retourne la durée en millisecondes, ou 0 si non parsable.
     */
    private fun parsePeriod(period: String): Long {
        if (period.isBlank()) return 0L
        return try {
            val regex = Regex("""^(\d+)(mins|hrs|jrs|smns|mois|ans)$""")
            val match = regex.find(period.trim().lowercase()) ?: return 0L
            val value = match.groupValues[1].toLong()
            when (match.groupValues[2]) {
                "mins" -> value * 60_000L
                "hrs"  -> value * 3_600_000L
                "jrs"  -> value * 86_400_000L
                "smns" -> value * 7 * 86_400_000L
                "mois" -> value * 30L * 86_400_000L
                "ans"  -> value * 365L * 86_400_000L
                else   -> 0L
            }
        } catch (_: Exception) { 0L }
    }

    // ─── Toast ────────────────────────────────────────────────────────────────

    /**
     * Demande à NumaAccessibilityService d'afficher un toast.
     * Les toasts depuis un service background sont bloqués sur Android 12+,
     * mais l'AccessibilityService (contexte système) peut les afficher.
     */
    private fun showToast(message: String) {
        val intent = Intent(NumaAccessibilityService.ACTION_SHOW_TOAST).apply {
            setPackage(packageName)
            putExtra(NumaAccessibilityService.EXTRA_MESSAGE, message)
        }
        sendBroadcast(intent)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun buildUploader() = R2Uploader(
        accountId       = BuildConfig.R2_ACCOUNT_ID,
        accessKeyId     = BuildConfig.R2_ACCESS_KEY_ID,
        secretAccessKey = BuildConfig.R2_SECRET_ACCESS_KEY,
        bucketName      = BuildConfig.R2_BUCKET_NAME,
        endpoint        = BuildConfig.R2_ENDPOINT,
    )

    private fun sha256OfString(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
