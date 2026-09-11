package com.namdroid.app.ui

import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ParameterSpec(val key: String, val label: String, val range: ClosedFloatingPointRange<Float>, val default: Float, val unit: String = "%", val engineParam: Int = 0)

enum class BlockType(val label: String, val shortLabel: String, val category: String, val color: Color, val engineId: Int?, val parameters: List<ParameterSpec>) {
    INPUT("Input", "IN", "I/O", SignalGreen, null, listOf(ParameterSpec("level", "Gain", -24f..24f, 0f, "dB"))),
    COMPRESSOR("Studio Comp", "COMP", "Dynamics", Color(0xFFFFD166), 8, listOf(
        ParameterSpec("threshold", "Threshold", -60f..0f, -18f, "dB", 0), ParameterSpec("ratio", "Ratio", 1f..12f, 4f, ":1", 1), ParameterSpec("makeup", "Makeup", 0f..18f, 3f, "dB", 2))),
    GATE("Noise Gate", "GATE", "Dynamics", Color(0xFF52D273), 1, listOf(
        ParameterSpec("threshold", "Threshold", -80f..-20f, -52f, "dB", 0), ParameterSpec("release", "Release", 20f..500f, 120f, "ms", 1))),
    DRIVE("Green Drive", "DRV", "Distortion", WarmOrange, 2, listOf(
        ParameterSpec("gain", "Gain", 0f..100f, 45f, "%", 0), ParameterSpec("tone", "Tone", 0f..100f, 55f, "%", 1), ParameterSpec("level", "Level", -12f..12f, 0f, "dB", 2))),
    AMP("NAM Amplifier", "NAM", "Amplifier", ElectricBlue, 3, listOf(
        ParameterSpec("input", "Input / Drive", -18f..18f, 0f, "dB", 0),
        ParameterSpec("bass", "Bass", -12f..12f, 0f, "dB", 1),
        ParameterSpec("mid", "Mid Gain", -12f..12f, 0f, "dB", 2),
        ParameterSpec("midfreq", "Mid Frequency", 150f..4000f, 750f, "Hz", 3),
        ParameterSpec("midq", "Mid Q", 0.3f..4f, 0.8f, "Q", 4),
        ParameterSpec("treble", "Treble", -12f..12f, 0f, "dB", 5),
        ParameterSpec("presence", "Presence", -12f..12f, 0f, "dB", 6),
        ParameterSpec("resonance", "Resonance", -12f..12f, 0f, "dB", 7),
        ParameterSpec("lowcut", "Low Cut", 20f..250f, 35f, "Hz", 8),
        ParameterSpec("highcut", "High Cut", 3000f..20000f, 18000f, "Hz", 9),
        ParameterSpec("output", "Output", -24f..18f, 0f, "dB", 10))),
    IR("IR Cabinet", "IR", "Cabinet", Color(0xFF4CC9F0), 7, listOf(
        ParameterSpec("level", "Level", -18f..12f, 0f, "dB", 0), ParameterSpec("lowcut", "Low Cut", 20f..300f, 70f, "Hz", 1), ParameterSpec("highcut", "High Cut", 3000f..20000f, 12000f, "Hz", 2))),
    EQ("Three Band EQ", "EQ", "Equalizer", Color(0xFFC77DFF), 4, listOf(
        ParameterSpec("low", "Low", -12f..12f, 0f, "dB", 0), ParameterSpec("mid", "Mid", -12f..12f, 0f, "dB", 1), ParameterSpec("high", "High", -12f..12f, 0f, "dB", 2))),
    CHORUS("Dimension Chorus", "CHO", "Modulation", Color(0xFF7BDFF2), 9, listOf(
        ParameterSpec("rate", "Rate", 0.05f..8f, 1.2f, "Hz", 0), ParameterSpec("depth", "Depth", 0f..100f, 45f, "%", 1), ParameterSpec("mix", "Mix", 0f..100f, 30f, "%", 2))),
    DELAY("Digital Delay", "DLY", "Delay", Color(0xFF40C9C6), 5, listOf(
        ParameterSpec("time", "Time", 40f..1500f, 360f, "ms", 0), ParameterSpec("feedback", "Feedback", 0f..92f, 38f, "%", 1), ParameterSpec("mix", "Mix", 0f..100f, 28f, "%", 2))),
    REVERB("Plate Reverb", "RVB", "Reverb", Color(0xFFEF6EAE), 6, listOf(
        ParameterSpec("decay", "Decay", 0.2f..12f, 2.8f, "s", 0), ParameterSpec("tone", "Tone", 0f..100f, 55f, "%", 1), ParameterSpec("mix", "Mix", 0f..100f, 22f, "%", 2))),
    OUTPUT("Output", "OUT", "I/O", Color(0xFFE0E6EC), null, listOf(ParameterSpec("level", "Level", -24f..24f, 0f, "dB"))),
}

data class PedalBlock(val id: String = UUID.randomUUID().toString(), val type: BlockType, val enabled: Boolean = true, val parameters: Map<String, Float> = type.parameters.associate { it.key to it.default }, val assetPath: String? = null, val assetName: String? = null)
data class RigScene(val name: String, val enabledByBlock: Map<String, Boolean>, val parametersByBlock: Map<String, Map<String, Float>> = emptyMap())
data class RigPreset(val id: String = UUID.randomUUID().toString(), val name: String = "New Rig", val bpm: Int = 120, val blocks: List<PedalBlock> = defaultSignalChain(), val scenes: List<RigScene> = List(4) { RigScene(('A'.code + it).toChar().toString(), emptyMap()) })

fun defaultSignalChain() = listOf(
    PedalBlock(id = "input", type = BlockType.INPUT), PedalBlock(type = BlockType.GATE), PedalBlock(type = BlockType.DRIVE, enabled = false),
    PedalBlock(id = "amp", type = BlockType.AMP), PedalBlock(type = BlockType.IR, enabled = false), PedalBlock(type = BlockType.EQ),
    PedalBlock(type = BlockType.DELAY, enabled = false), PedalBlock(type = BlockType.REVERB, enabled = false), PedalBlock(id = "output", type = BlockType.OUTPUT))

fun RigPreset.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("name", name); put("bpm", bpm)
    put("blocks", JSONArray().apply { blocks.forEach { put(it.toJson()) } })
    put("scenes", JSONArray().apply { scenes.forEach { scene -> put(JSONObject().apply { put("name", scene.name); put("enabled", JSONObject(scene.enabledByBlock)); put("parameters", JSONObject().apply { scene.parametersByBlock.forEach { (id, values) -> put(id, JSONObject(values)) } }) }) } })
}

private fun PedalBlock.toJson() = JSONObject().apply {
    put("id", id); put("type", type.name); put("enabled", enabled); put("parameters", JSONObject(parameters)); put("assetPath", assetPath); put("assetName", assetName)
}

fun rigFromJson(json: JSONObject): RigPreset {
    val blocks = json.getJSONArray("blocks").let { array -> (0 until array.length()).map { index ->
        val item = array.getJSONObject(index); val type = BlockType.valueOf(item.getString("type")); val values = item.optJSONObject("parameters")
        val params = type.parameters.associate { spec ->
            val value = values?.optDouble(spec.key, spec.default.toDouble())?.toFloat() ?: spec.default
            spec.key to (if (value.isFinite()) value.coerceIn(spec.range) else spec.default)
        }
        PedalBlock(item.getString("id"), type, item.optBoolean("enabled", true), params, item.optString("assetPath").takeIf { it.isNotBlank() && it != "null" }, item.optString("assetName").takeIf { it.isNotBlank() && it != "null" })
    } }
    val scenesArray = json.optJSONArray("scenes")
    val scenes = if (scenesArray == null) emptyList() else (0 until scenesArray.length()).map { index ->
        val scene = scenesArray.getJSONObject(index); val enabled = scene.optJSONObject("enabled"); val map = mutableMapOf<String, Boolean>()
        enabled?.keys()?.forEach { key -> map[key] = enabled.optBoolean(key) }
        val parameterMap = mutableMapOf<String, Map<String, Float>>(); scene.optJSONObject("parameters")?.let { all -> all.keys().forEach { id -> val values = all.optJSONObject(id); val blockValues = mutableMapOf<String, Float>(); values?.keys()?.forEach { key -> blockValues[key] = values.optDouble(key).toFloat() }; parameterMap[id] = blockValues } }
        RigScene(scene.optString("name", ('A'.code + index).toChar().toString()), map, parameterMap)
    }
    val normalizedScenes = List(4) { index -> scenes.getOrNull(index) ?: RigScene(('A'.code + index).toChar().toString(), emptyMap()) }
    require(blocks.isNotEmpty()) { "El rig no contiene bloques" }
    require(blocks.map { it.id }.distinct().size == blocks.size) { "El rig contiene identificadores repetidos" }
    require(blocks.count { it.type == BlockType.INPUT } == 1) { "El rig debe tener una entrada" }
    require(blocks.count { it.type == BlockType.OUTPUT } == 1) { "El rig debe tener una salida" }
    require(blocks.count { it.type == BlockType.AMP } <= 1) { "El rig admite un amplificador" }
    require(blocks.count { it.type == BlockType.IR } <= 1) { "El rig admite un cabinet IR" }
    return RigPreset(json.optString("id", UUID.randomUUID().toString()), json.optString("name", "Imported Rig"), json.optInt("bpm", 120).coerceIn(30, 300), blocks, normalizedScenes)
}
