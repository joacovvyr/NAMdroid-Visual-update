package com.namdroid.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview(name = "Phone landscape", widthDp = 780, heightDp = 360, showBackground = true)
@Preview(name = "Compact landscape", widthDp = 640, heightDp = 320, showBackground = true)
@Preview(name = "Tablet landscape", widthDp = 1024, heightDp = 600, showBackground = true)
@Composable
private fun StagePreview() {
    NamDroidTheme {
        StageWorkbench(
            rigName = "01 · Modern High Gain", blocks = defaultSignalChain(), selectedId = "amp",
            editing = false, running = true, scene = 0, bpm = 120,
            status = "Audio activo · 48000 Hz", inputDb = -18f, outputDb = -12f,
            onSelect = {}, onCloseEditor = {}, onMove = { _, _ -> }, onToggleBlock = {},
            onParameter = { _, _ -> }, onDelete = {}, onDropDelete = {},
            onUndoDelete = {}, onScene = {}, onSaveScene = {},
            onTap = {}, onRigs = {}, onTone = {}, onAudio = {}, onSettings = {},
            onAdd = {}, onTuner = {}, onLooper = {}, onPickNam = {}, onPickIr = {},
        )
    }
}
