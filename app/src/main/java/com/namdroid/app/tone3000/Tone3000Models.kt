package com.namdroid.app.tone3000

data class Tone(
    val id: Long,
    val title: String,
    val gear: String?,
    val format: String?,
    val creator: String?,
    val creatorUsername: String?,
    val creatorAvatarUrl: String?,
    val creatorVerified: Boolean,
    val imageUrl: String?,
    val modelsCount: Int,
    val downloadsCount: Int,
    val isFavorite: Boolean,
)

data class ToneCreator(
    val id: Long,
    val username: String,
    val displayName: String?,
    val avatarUrl: String?,
    val verified: Boolean,
    val bio: String?,
    val downloadsCount: Int,
    val favoritesCount: Int,
    val modelsCount: Int,
    val tonesCount: Int,
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
