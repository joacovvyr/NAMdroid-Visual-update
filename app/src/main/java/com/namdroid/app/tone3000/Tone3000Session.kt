package com.namdroid.app.tone3000

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persistencia cifrada y renovacion automatica de la sesion OAuth. */
class Tone3000Session(context: Context, private val client: Tone3000Client) {
    private val prefs: SharedPreferences = runCatching {
        val key = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "tone3000_session_v1",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        context.getSharedPreferences("tone3000_session_fallback", Context.MODE_PRIVATE)
    }
    private val mutex = Mutex()

    fun hasSession(): Boolean = prefs.getString(REFRESH_TOKEN, null) != null ||
        prefs.getString(ACCESS_TOKEN, null) != null

    fun savePending(verifier: String, state: String) {
        prefs.edit().putString(VERIFIER, verifier).putString(STATE, state).apply()
    }

    fun consumePending(callbackState: String?): String? {
        val expected = prefs.getString(STATE, null)
        val verifier = prefs.getString(VERIFIER, null)
        if (callbackState == null || expected == null || callbackState != expected) return null
        prefs.edit().remove(STATE).remove(VERIFIER).apply()
        return verifier
    }

    fun save(tokens: Tokens) {
        val editor = prefs.edit()
            .putString(ACCESS_TOKEN, tokens.accessToken)
            .putLong(EXPIRES_AT, tokens.expiresAtEpochSeconds ?: 0L)
        tokens.refreshToken?.let { editor.putString(REFRESH_TOKEN, it) }
        editor.apply()
    }

    suspend fun validAccessToken(): String? = mutex.withLock {
        val access = prefs.getString(ACCESS_TOKEN, null) ?: return@withLock null
        val expiresAt = prefs.getLong(EXPIRES_AT, 0L)
        val now = System.currentTimeMillis() / 1000
        if (expiresAt == 0L || now < expiresAt - 60) return@withLock access

        val refresh = prefs.getString(REFRESH_TOKEN, null) ?: return@withLock null
        return@withLock try {
            val tokens = client.refreshTokens(refresh)
            save(tokens)
            tokens.accessToken
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val ACCESS_TOKEN = "access_token"
        const val REFRESH_TOKEN = "refresh_token"
        const val EXPIRES_AT = "expires_at"
        const val VERIFIER = "pkce_verifier"
        const val STATE = "oauth_state"
    }
}
