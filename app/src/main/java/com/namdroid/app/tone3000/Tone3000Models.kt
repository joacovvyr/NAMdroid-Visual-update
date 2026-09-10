package com.namdroid.app.tone3000

data class Tone(
    val id: Long,
    val title: String,
    val gear: String?,
    val format: String?,
    val creator: String?,
    val imageUrl: String?,
    val modelsCount: Int,
    val downloadsCount: Int,
    val isFavorite: Boolean,
)

data class ToneModel(
    val id: Long,
    val toneId: Long,
    val name: String,
    val modelUrl: String,
    val size: String?,
    val architectureVersion: String?,
)

data class Tokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochSeconds: Long?,
)

data class ToneUser(
    val username: String,
    val displayName: String?,
    val avatarUrl: String?,
)
