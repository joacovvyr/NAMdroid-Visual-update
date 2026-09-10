package com.namdroid.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun StageRigLibrary(rigs: List<RigPreset>, active: Int, select: (Int) -> Unit, create: () -> Unit, delete: (Int) -> Unit, move: (Int, Int) -> Unit, rename: () -> Unit, export: () -> Unit, import: () -> Unit, close: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp), color = Carbon, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(close) { Icon(Icons.Default.ArrowBack, "Volver") }
                    Text("RIGS / SETLIST", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    OutlinedTextField(query, { query = it }, modifier = Modifier.widthIn(max = 220.dp), singleLine = true, placeholder = { Text("Buscar rig") }, leadingIcon = { Icon(Icons.Default.Search, null) })
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                    itemsIndexed(rigs, key = { _, rig -> rig.id }) { index, rig ->
                        if (rig.name.contains(query, ignoreCase = true)) {
                            Row(Modifier.fillMaxWidth().background(if (active == index) ElectricBlue.copy(alpha = .12f) else Panel, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                TextButton({ select(index) }, Modifier.weight(1f).heightIn(min = 60.dp)) {
                                    Text("%02d".format(index + 1), color = MutedText)
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        Text(rig.name, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White, fontWeight = FontWeight.Bold)
                                        Text("${rig.blocks.size} bloques · ${rig.bpm} BPM", Modifier.fillMaxWidth(), color = MutedText)
                                    }
                                    Text(if (active == index) "ACTUAL" else "CARGAR")
                                }
                                IconButton({ move(index, index - 1) }, enabled = index > 0 && query.isBlank()) { Icon(Icons.Default.KeyboardArrowUp, "Subir en setlist") }
                                IconButton({ move(index, index + 1) }, enabled = index < rigs.lastIndex && query.isBlank()) { Icon(Icons.Default.KeyboardArrowDown, "Bajar en setlist") }
                                IconButton({ pendingDelete = rig.id }, enabled = rigs.size > 1) { Icon(Icons.Default.DeleteOutline, "Eliminar rig") }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(create) { Text("Nuevo rig") }
                    OutlinedButton(rename) { Text("Renombrar actual") }
                    OutlinedButton(import) { Text("Importar respaldo") }
                    OutlinedButton(export) { Text("Exportar rigs + modelos") }
                }
            }
        }
    }
    pendingDelete?.let { id ->
        val index = rigs.indexOfFirst { it.id == id }
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("Eliminar rig") }, text = { Text("¿Eliminar ${rigs.getOrNull(index)?.name.orEmpty()}? Podés exportar un respaldo antes de borrarlo.") }, confirmButton = { Button({ if (index >= 0) delete(index); pendingDelete = null }) { Text("Eliminar") } }, dismissButton = { TextButton({ pendingDelete = null }) { Text("Cancelar") } })
    }
}
