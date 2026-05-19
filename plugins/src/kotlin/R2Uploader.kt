package com.numa

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Client Cloudflare R2 (compatible AWS S3).
 * Implémente AWS Signature V4 manuellement.
 */
class R2Uploader(
    private val accountId: String,
    private val accessKeyId: String,
    private val secretAccessKey: String,
    private val bucketName: String,
    private val endpoint: String,
) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    // ─── Upload ───────────────────────────────────────────────────────────────

    /**
     * Upload [localFile] sur R2 sous la clé [remoteKey].
     * Vérifie l'ETag (MD5) retourné par R2.
     * @return true si l'upload et la vérification ont réussi.
     */
    fun uploadFile(localFile: File, remoteKey: String): Boolean {
        return try {
            val bytes    = localFile.readBytes()
            val md5B64   = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("MD5").digest(bytes)
            )
            val md5Hex   = MessageDigest.getInstance("MD5").digest(bytes)
                .joinToString("") { "%02x".format(it) }

            val now       = Date()
            val dateStamp = dateStampFormatter().format(now)   // YYYYMMDD
            val amzDate   = amzDateFormatter().format(now)     // YYYYMMDDTHHMMSSZ

            val url     = "$endpoint/$bucketName/$remoteKey"
            val urlObj  = URL(url)
            val host    = urlObj.host
            val path    = "/${bucketName}/$remoteKey"

            val payloadHash = sha256Hex(bytes)

            val signedHeaders = "content-md5;host;x-amz-content-sha256;x-amz-date"
            val canonicalHeaders = buildString {
                append("content-md5:$md5B64\n")
                append("host:$host\n")
                append("x-amz-content-sha256:$payloadHash\n")
                append("x-amz-date:$amzDate\n")
            }

            val canonicalRequest = buildString {
                append("PUT\n")
                append("$path\n")
                append("\n")                    // query string vide
                append(canonicalHeaders)
                append("\n")
                append(signedHeaders)
                append("\n")
                append(payloadHash)
            }

            val authHeader = buildAuthHeader(
                method         = "PUT",
                canonicalRequest = canonicalRequest,
                signedHeaders  = signedHeaders,
                dateStamp      = dateStamp,
                amzDate        = amzDate,
            )

            val body: RequestBody = bytes.toRequestBody("application/octet-stream".toMediaType())

            val request = Request.Builder()
                .url(url)
                .header("Host",                   host)
                .header("Content-MD5",            md5B64)
                .header("x-amz-date",             amzDate)
                .header("x-amz-content-sha256",   payloadHash)
                .header("Authorization",          authHeader)
                .put(body)
                .build()

            val response = client.newCall(request).execute()
            response.use { r ->
                if (!r.isSuccessful) {
                    if (BuildConfig.DEBUG)
                        android.util.Log.e("R2Uploader", "Upload HTTP ${r.code}: ${r.body?.string()}")
                    return false
                }
                // Vérification ETag (R2 retourne l'ETag entre guillemets)
                val etag = r.header("ETag")?.trim('"')
                if (etag != null && etag != md5Hex) {
                    if (BuildConfig.DEBUG)
                        android.util.Log.e("R2Uploader", "ETag mismatch: attendu=$md5Hex, reçu=$etag")
                    return false
                }
                true
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.e("R2Uploader", "uploadFile failed", e)
            false
        }
    }

    // ─── Download ─────────────────────────────────────────────────────────────

    /**
     * Télécharge [remoteKey] depuis R2 vers [localDest].
     */
    fun downloadFile(remoteKey: String, localDest: File): Boolean {
        return try {
            val now       = Date()
            val dateStamp = dateStampFormatter().format(now)
            val amzDate   = amzDateFormatter().format(now)

            val url    = "$endpoint/$bucketName/$remoteKey"
            val urlObj = URL(url)
            val host   = urlObj.host
            val path   = "/$bucketName/$remoteKey"

            val payloadHash   = sha256Hex(ByteArray(0)) // GET : corps vide
            val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
            val canonicalHeaders = buildString {
                append("host:$host\n")
                append("x-amz-content-sha256:$payloadHash\n")
                append("x-amz-date:$amzDate\n")
            }

            val canonicalRequest = buildString {
                append("GET\n")
                append("$path\n")
                append("\n")
                append(canonicalHeaders)
                append("\n")
                append(signedHeaders)
                append("\n")
                append(payloadHash)
            }

            val authHeader = buildAuthHeader(
                method           = "GET",
                canonicalRequest = canonicalRequest,
                signedHeaders    = signedHeaders,
                dateStamp        = dateStamp,
                amzDate          = amzDate,
            )

            val request = Request.Builder()
                .url(url)
                .header("Host",                  host)
                .header("x-amz-date",            amzDate)
                .header("x-amz-content-sha256",  payloadHash)
                .header("Authorization",         authHeader)
                .get()
                .build()

            val response = client.newCall(request).execute()
            response.use { r ->
                if (!r.isSuccessful) {
                    if (BuildConfig.DEBUG)
                        android.util.Log.e("R2Uploader", "Download HTTP ${r.code}: ${r.body?.string()}")
                    return false
                }
                r.body?.byteStream()?.use { input ->
                    FileOutputStream(localDest).use { output ->
                        input.copyTo(output)
                    }
                } ?: return false
                true
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.e("R2Uploader", "downloadFile failed", e)
            false
        }
    }

    // ─── AWS Signature V4 ─────────────────────────────────────────────────────

    private fun buildAuthHeader(
        method: String,
        canonicalRequest: String,
        signedHeaders: String,
        dateStamp: String,
        amzDate: String,
    ): String {
        val region        = "auto"          // Cloudflare R2 utilise "auto"
        val service       = "s3"
        val credentialScope = "$dateStamp/$region/$service/aws4_request"

        val stringToSign = buildString {
            append("AWS4-HMAC-SHA256\n")
            append("$amzDate\n")
            append("$credentialScope\n")
            append(sha256Hex(canonicalRequest.toByteArray()))
        }

        val signingKey = getSigningKey(secretAccessKey, dateStamp, region, service)
        val signature  = hmacSha256Hex(signingKey, stringToSign.toByteArray())

        return "AWS4-HMAC-SHA256 " +
            "Credential=$accessKeyId/$credentialScope, " +
            "SignedHeaders=$signedHeaders, " +
            "Signature=$signature"
    }

    private fun getSigningKey(
        secret: String,
        dateStamp: String,
        region: String,
        service: String,
    ): ByteArray {
        val kDate    = hmacSha256(("AWS4$secret").toByteArray(), dateStamp.toByteArray())
        val kRegion  = hmacSha256(kDate, region.toByteArray())
        val kService = hmacSha256(kRegion, service.toByteArray())
        return hmacSha256(kService, "aws4_request".toByteArray())
    }

    // ─── Utilitaires cryptographiques ─────────────────────────────────────────

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hmacSha256Hex(key: ByteArray, data: ByteArray): String =
        hmacSha256(key, data).joinToString("") { "%02x".format(it) }

    // ─── Formatters de date ───────────────────────────────────────────────────

    private fun dateStampFormatter() = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun amzDateFormatter() = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
}
