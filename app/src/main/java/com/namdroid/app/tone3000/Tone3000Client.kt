package com.namdroid.app.tone3000

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Cliente minimo de la API v1 de TONE3000 (https://tone3000.com/api).
 *
 * Endpoints usados (confirmados en la doc oficial, sep-2026):
 *   GET  /oauth/authorize   -> pantalla de login/consentimiento (se abre en el navegador)
 *   POST /oauth/token       -> canje del "code" por un access_token
 *   GET  /tones/search      -> buscar tonos publicos
 *   GET  /models?tone_id=   -> variantes descargables de un tono
 *
 * IMPORTANTE: TONE3000 no permite acceso anonimo. Sin un access_token valido
 * (login OAuth del usuario) ninguna de estas llamadas va a funcionar.
 */
class Tone3000Client(
    private val publishableKey: String = Tone3000Config.PUBLISHABLE_KEY,
    private val redirectUri: String = Tone3000Config.REDIRECT_URI,
    private val baseUrl: String = Tone3000Config.BASE_URL,
) {
    enum class Collection(val path: String) { FAVORITES("favorited"), CREATED("created"), DOWNLOADED("downloaded") }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** OAuth normal: autentica, vuelve a la app y deja el catalogo en UI nativa. */
    fun buildAuthorizeUrl(state: String, codeChallenge: String): String {
        return "$baseUrl/oauth/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", publishableKey)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("code_challenge", codeChallenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
            .build().toString()
    }

    suspend fun exchangeCodeForToken(code: String, codeVerifier: String): Tokens =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("code_verifier", codeVerifier)
                .add("redirect_uri", redirectUri)
                .add("client_id", publishableKey)
                .build()
            executeTokenRequest(body)
        }

    suspend fun refreshTokens(refreshToken: String): Tokens = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", publishableKey)
            .build()
        executeTokenRequest(body)
    }

    private fun executeTokenRequest(body: FormBody): Tokens {
        val request = Request.Builder().url("$baseUrl/oauth/token").post(body).build()
        http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw Tone3000Exception("Sesion TONE3000 invalida (${resp.code}): $raw")
            val json = JSONObject(raw.ifEmpty { "{}" })
            return Tokens(
                accessToken = json.getString("access_token"),
                refreshToken = json.optNullableString("refresh_token"),
                expiresAtEpochSeconds = json.optLong("expires_in", 0L).takeIf { it > 0 }
                    ?.let { System.currentTimeMillis() / 1000 + it },
            )
        }
    }

    suspend fun getUser(accessToken: String): ToneUser = withContext(Dispatchers.IO) {
        val json = getJson("$baseUrl/user", accessToken)
        ToneUser(
            username = json.optString("username").ifBlank { "usuario" },
            displayName = json.optNullableString("display_name"),
            avatarUrl = json.optNullableString("avatar_url"),
        )
    }

    suspend fun searchTones(accessToken: String, query: String): List<Tone> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/tones/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", query)
                .addQueryParameter("page", "1")
                .addQueryParameter("page_size", "25")
                .addQueryParameter("sort", if (query.isBlank()) "trending" else "best-match")
                .addQueryParameter("format", "nam")
                .addQueryParameter("architecture", "2")
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $accessToken")
                .build()

            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw Tone3000Exception("Busqueda fallo (${resp.code}): ${resp.body?.string()}")
                }
                val json = JSONObject(resp.body?.string() ?: "{}")
                parseTones(json)
            }
        }

    suspend fun listTones(accessToken: String, collection: Collection): List<Tone> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/tones/${collection.path}".toHttpUrl().newBuilder().addQueryParameter("page_size", "100").build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").build()
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw Tone3000Exception("TONE3000 respondio ${response.code}: $raw")
            parseTones(JSONObject(raw.ifEmpty { "{}" }))
        }
    }

    suspend fun setFavorite(accessToken: String, toneId: Long, favorite: Boolean) = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url("$baseUrl/tones/$toneId/favorite").header("Authorization", "Bearer $accessToken")
        val request = if (favorite) builder.put(ByteArray(0).toRequestBody(null)).build() else builder.delete().build()
        http.newCall(request).execute().use { response -> if (!response.isSuccessful) throw Tone3000Exception("Favorite failed (${response.code})") }
    }

    suspend fun getModelsForTone(accessToken: String, toneId: Long): List<ToneModel> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/models".toHttpUrl().newBuilder()
                    .addQueryParameter("tone_id", toneId.toString())
                    .addQueryParameter("page_size", "300")
                    .addQueryParameter("architecture", "2")
                    .build())
                .header("Authorization", "Bearer $accessToken")
                .build()

            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw Tone3000Exception("Listado de modelos fallo (${resp.code}): ${resp.body?.string()}")
                }
                val json = JSONObject(resp.body?.string() ?: "{}")
                val data = json.optJSONArray("data") ?: return@use emptyList()
                (0 until data.length()).map { i ->
                    val m = data.getJSONObject(i)
                    ToneModel(
                        id = m.getLong("id"),
                        toneId = m.optLong("tone_id", toneId),
                        name = m.optString("name").ifBlank { "Modelo #${m.optLong("id")}" },
                        modelUrl = m.getString("model_url"),
                        size = m.optNullableString("size"),
                        architectureVersion = m.optNullableString("architecture_version"),
                    )
                }
            }
        }

    /** Descarga el .nam autenticado y lo guarda en [destFile]. Devuelve destFile. */
    suspend fun downloadModel(accessToken: String, model: ToneModel, destFile: File): File =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(model.modelUrl)
                .header("Authorization", "Bearer $accessToken")
                .build()

            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw Tone3000Exception("Descarga fallo (${resp.code})")
                }
                destFile.parentFile?.mkdirs()
                val temporary = File.createTempFile("nam_download_", ".part", destFile.parentFile)
                try {
                    FileOutputStream(temporary).use { out ->
                        resp.body?.byteStream()?.copyTo(out)
                            ?: throw Tone3000Exception("Respuesta sin cuerpo")
                    }
                    val json = try { JSONObject(temporary.readText()) } catch (_: Exception) {
                        throw Tone3000Exception("El servidor no devolvió un archivo NAM JSON válido")
                    }
                    if (json.optString("architecture").isBlank()) {
                        throw Tone3000Exception("La descarga no contiene una arquitectura NAM")
                    }
                    if (!temporary.renameTo(destFile)) throw Tone3000Exception("No se pudo guardar el modelo")
                } finally {
                    temporary.delete()
                }
                destFile
            }
        }


    private fun getJson(url: String, accessToken: String): JSONObject {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").build()
        http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw Tone3000Exception("TONE3000 respondio ${resp.code}: $raw")
            return JSONObject(raw.ifEmpty { "{}" })
        }
    }

    private fun parseTones(json: JSONObject): List<Tone> {
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val tone = data.getJSONObject(i)
            Tone(
                id = tone.getLong("id"), title = tone.optString("title").ifBlank { "Tono #${tone.optLong("id")}" },
                gear = tone.optNullableString("gear"), format = tone.optNullableString("format"),
                creator = tone.optJSONObject("user")?.let { it.optNullableString("display_name") ?: it.optNullableString("username") },
                imageUrl = tone.optJSONArray("images")?.optString(0)?.takeIf { it.isNotBlank() },
                modelsCount = tone.optInt("models_count", 0), downloadsCount = tone.optInt("downloads_count", 0), isFavorite = tone.optBoolean("is_favorite", false),
            )
        }
    }
}

private fun JSONObject.optNullableString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

class Tone3000Exception(message: String) : Exception(message)
