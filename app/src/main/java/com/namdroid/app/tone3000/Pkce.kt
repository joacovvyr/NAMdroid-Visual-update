package com.namdroid.app.tone3000

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/** Genera el par verifier/challenge que exige OAuth 2.0 + PKCE (RFC 7636). */
data class PkcePair(val verifier: String, val challenge: String)

object Pkce {
    fun generate(): PkcePair {
        val verifierBytes = ByteArray(64)
        SecureRandom().nextBytes(verifierBytes)
        val verifier = base64UrlNoPad(verifierBytes)

        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = base64UrlNoPad(digest)

        return PkcePair(verifier, challenge)
    }

    fun randomState(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return base64UrlNoPad(bytes)
    }

    private fun base64UrlNoPad(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
