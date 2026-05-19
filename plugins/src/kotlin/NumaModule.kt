package com.numa

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.Promise
import java.security.SecureRandom

/**
 * Module React Native Bridge.
 * Expose startService(), stopService() et generateKey() au JavaScript.
 */
class NumaModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "NumaModule"

    /** Démarre NumaService depuis le JS (ex: première activation). */
    @ReactMethod
    fun startService(promise: Promise) {
        try {
            NumaService.start(reactContext)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("START_SERVICE_ERROR", e.message, e)
        }
    }

    /** Stoppe NumaService depuis le JS. */
    @ReactMethod
    fun stopService(promise: Promise) {
        try {
            reactContext.stopService(
                android.content.Intent(reactContext, NumaService::class.java)
            )
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("STOP_SERVICE_ERROR", e.message, e)
        }
    }

    /**
     * Génère une ENCRYPTION_KEY aléatoire (64 caractères hexadécimaux = 256 bits).
     * À utiliser une seule fois lors du setup pour générer la clé à stocker dans .env.
     */
    @ReactMethod
    fun generateKey(promise: Promise) {
        try {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            val hexKey = bytes.joinToString("") { "%02x".format(it) }
            promise.resolve(hexKey)
        } catch (e: Exception) {
            promise.reject("GENERATE_KEY_ERROR", e.message, e)
        }
    }
}
