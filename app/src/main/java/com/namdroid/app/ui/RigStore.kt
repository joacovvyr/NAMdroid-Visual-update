package com.namdroid.app.ui

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class RigStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("namdroid_rigs_v1", Context.MODE_PRIVATE)
    fun activeRigId(): String? = prefs.getString("active_rig_id", null)
    fun selectRig(id: String) { prefs.edit().putString("active_rig_id", id).apply() }
    fun loadAll(): List<RigPreset> = runCatching {
        val array = JSONArray(prefs.getString(KEY_RIGS, "[]")); (0 until array.length()).map { rigFromJson(array.getJSONObject(it)) }
    }.getOrElse { emptyList() }.ifEmpty { listOf(RigPreset(name = "NAMDroid Starter")) }
    fun saveAll(rigs: List<RigPreset>) { prefs.edit().putString(KEY_RIGS, JSONArray().apply { rigs.forEach { put(it.toJson()) } }.toString()).apply() }
    fun export(rigs: List<RigPreset>): String = JSONObject().apply {
        put("format", "namdroid-rigs"); put("version", 2); put("rigs", JSONArray().apply { rigs.forEach { put(it.toJson()) } })
        put("assets", JSONArray().apply { rigs.flatMap { it.blocks }.mapNotNull { it.assetPath }.distinct().forEach { path ->
            val file = File(path); if (file.isFile) put(JSONObject().apply { put("path", path); put("name", file.name); put("data", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)) })
        } })
    }.toString(2)
    fun import(raw: String): List<RigPreset> {
        val root = JSONObject(raw); require(root.optString("format") == "namdroid-rigs") { "Not a NAMDroid rig backup" }
        val remap = mutableMapOf<String, String>(); val assets = root.optJSONArray("assets")
        if (assets != null) for (index in 0 until assets.length()) { val asset = assets.getJSONObject(index); val original = asset.getString("path"); val safe = asset.optString("name", "asset_$index").replace(Regex("[^a-zA-Z0-9._-]"), "_"); val file = File(appContext.filesDir, "restored_assets/${System.currentTimeMillis()}_${index}_$safe"); file.parentFile?.mkdirs(); file.writeBytes(Base64.decode(asset.getString("data"), Base64.DEFAULT)); remap[original] = file.absolutePath }
        val array = root.getJSONArray("rigs")
        require(array.length() > 0) { "El respaldo no contiene rigs" }
        return (0 until array.length()).map { rigFromJson(array.getJSONObject(it)) }.map { rig -> rig.copy(blocks = rig.blocks.map { block -> block.assetPath?.let { path -> remap[path]?.let { block.copy(assetPath = it) } } ?: block }) }
    }
    private companion object { const val KEY_RIGS = "rigs" }
}
