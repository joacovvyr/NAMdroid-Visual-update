package com.namdroid.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.namdroid.app.tone3000.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun BrowseToneScreen(
    oauthCallback: Uri?,
    onOAuthConsumed: () -> Unit,
    onBack: () -> Unit,
    onModelDownloaded: (File) -> String?,
) {
    val context = LocalContext.current
    val client = remember { Tone3000Client() }
    val session = remember { Tone3000Session(context.applicationContext, client) }
    val scope = rememberCoroutineScope()
    var connected by remember { mutableStateOf(session.hasSession()) }
    var user by remember { mutableStateOf<ToneUser?>(null) }
    var status by remember { mutableStateOf(if (connected) "Restoring session..." else "Connect your account once to browse and load tones") }
    var query by remember { mutableStateOf("") }
    var collection by remember { mutableStateOf<Tone3000Client.Collection?>(null) }
    var tones by remember { mutableStateOf<List<Tone>>(emptyList()) }
    var expandedTone by remember { mutableStateOf<Long?>(null) }
    var models by remember { mutableStateOf<List<ToneModel>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
    var downloadingModelId by remember { mutableStateOf<Long?>(null) }
    var exportPath by rememberSaveable { mutableStateOf<String?>(null) }
    val saveNam = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val path = exportPath
        exportPath = null
        if (uri == null) status = "Guardado cancelado. El modelo sigue disponible en la app."
        else if (path != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        File(path).inputStream().use { input -> input.copyTo(output) }
                    } ?: error("No se pudo abrir el destino")
                }
                status = "NAM guardado. Cargalo desde LOCAL y guardá tus ajustes como rig."
            } catch (e: Exception) { status = "No se pudo guardar: ${e.message}" }
        }
    }

    suspend fun fetchModel(model: ToneModel): File {
        val token = session.validAccessToken() ?: error("Sesión vencida. Volvé a conectar tu cuenta.")
        val safeName = model.name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80).removeSuffix(".nam")
        return client.downloadModel(token, model, File(context.filesDir, "nam_models/t3k_${model.id}_$safeName.nam"))
    }

    suspend fun loadCatalog(search: String = query) {
        val token = session.validAccessToken()
        if (token == null) {
            connected = false
            status = "Session expired. Connect TONE3000 again."
            return
        }
        loading = true
        try {
            if (user == null) user = client.getUser(token)
            tones = collection?.let { client.listTones(token, it) } ?: client.searchTones(token, search.trim())
            status = if (tones.isEmpty()) "No tones found" else "${tones.size} NAM A2 tones"
        } catch (e: Exception) {
            status = e.message ?: "TONE3000 request failed"
        } finally { loading = false }
    }

    LaunchedEffect(Unit) {
        if (connected && oauthCallback == null) loadCatalog("")
    }

    LaunchedEffect(oauthCallback) {
        val uri = oauthCallback ?: return@LaunchedEffect
        val error = uri.getQueryParameter("error")
        val code = uri.getQueryParameter("code")
        val verifier = session.consumePending(uri.getQueryParameter("state"))
        try {
            when {
                error != null -> status = "Sign-in cancelled: $error"
                code == null || verifier == null -> status = "Invalid or expired OAuth callback"
                else -> {
                    status = "Completing secure sign-in..."
                    session.save(client.exchangeCodeForToken(code, verifier))
                    connected = true
                    loadCatalog("")
                }
            }
        } finally { onOAuthConsumed() }
    }

    fun startLogin() {
        val pkce = Pkce.generate()
        val state = Pkce.randomState()
        session.savePending(pkce.verifier, state)
        CustomTabsIntent.Builder().setShowTitle(false).build()
            .launchUrl(context, Uri.parse(client.buildAuthorizeUrl(state, pkce.challenge)))
    }

    Column(Modifier.fillMaxSize().background(Carbon).safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().height(54.dp).background(Panel).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
            Column {
                Text("TONE3000", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                Text("NAM A2 LIBRARY", color = MutedText, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.weight(1f))
            user?.let {
                Column(horizontalAlignment = Alignment.End) {
                    Text(it.displayName ?: it.username, fontWeight = FontWeight.SemiBold)
                    Text("@${it.username}", color = MutedText, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.width(10.dp))
            }
            Box(Modifier.size(9.dp).background(if (connected) SignalGreen else Color.Gray, RoundedCornerShape(50)))
        }

        if (!connected) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Card(Modifier.widthIn(max = 520.dp).padding(24.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Cloud, null, tint = ElectricBlue, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(14.dp))
                        Text("Connect TONE3000", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Sign in once. NAMDroid securely keeps and refreshes your session so your library stays ready.", color = MutedText)
                        Spacer(Modifier.height(18.dp))
                        Button(onClick = ::startLogin) { Text("CONTINUE TO SIGN IN") }
                        Spacer(Modifier.height(10.dp))
                        Text(status, color = MutedText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            return@Column
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search amps, pedals, creators...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { collection = null; scope.launch { loadCatalog() } }),
                modifier = Modifier.weight(1f).height(46.dp),
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { collection = null; scope.launch { loadCatalog() } }, enabled = !loading) {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Search, "Buscar")
            }
            Box {
                IconButton(onClick = { filterMenu = true }) { Icon(Icons.Default.FilterList, "Colección") }
                DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                    val feeds = listOf(null to "Explorar", Tone3000Client.Collection.FAVORITES to "Favoritos", Tone3000Client.Collection.CREATED to "Mis tonos", Tone3000Client.Collection.DOWNLOADED to "Descargados")
                    feeds.forEach { (feed, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            leadingIcon = { if (collection == feed) Icon(Icons.Default.Check, null) },
                            onClick = { filterMenu = false; collection = feed; scope.launch { loadCatalog(if (feed == null) query else "") } },
                        )
                    }
                }
            }
            IconButton(onClick = { session.clear(); connected = false; user = null; tones = emptyList() }) {
                Icon(Icons.Default.Logout, "Sign out", tint = MutedText)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(when (collection) {
                Tone3000Client.Collection.FAVORITES -> "FAVORITOS"
                Tone3000Client.Collection.CREATED -> "MIS TONOS"
                Tone3000Client.Collection.DOWNLOADED -> "DESCARGADOS"
                null -> "EXPLORAR"
            }, color = ElectricBlue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(10.dp))
            Text(status, color = MutedText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tones, key = { it.id }) { tone ->
                ToneCard(
                    tone = tone,
                    expanded = expandedTone == tone.id,
                    models = if (expandedTone == tone.id) models else emptyList(),
                    downloadingModelId = downloadingModelId,
                    onFavorite = {
                        scope.launch {
                            runCatching { val token = session.validAccessToken() ?: error("Session expired"); client.setFavorite(token, tone.id, !tone.isFavorite) }
                                .onSuccess { tones = tones.map { if (it.id == tone.id) it.copy(isFavorite = !it.isFavorite) else it } }
                                .onFailure { status = it.message ?: "Could not update favorite" }
                        }
                    },
                    onExpand = {
                        if (expandedTone == tone.id) {
                            expandedTone = null; models = emptyList()
                        } else scope.launch {
                            expandedTone = tone.id; models = emptyList(); loading = true
                            try {
                                val token = session.validAccessToken() ?: error("Session expired")
                                models = client.getModelsForTone(token, tone.id)
                                status = "${models.size} model variants"
                            } catch (e: Exception) { status = e.message ?: "Could not load variants" }
                            finally { loading = false }
                        }
                    },
                    onTry = { model ->
                        scope.launch {
                            downloadingModelId = model.id
                            status = "Downloading ${model.name}..."
                            try {
                                val dest = fetchModel(model)
                                val loadError = onModelDownloaded(dest)
                                status = if (loadError == null) "NAM activo: ${model.name}. Volvé a la pedalera para editarlo."
                                    else "No se pudo probar ${model.name}: $loadError"
                            } catch (e: Exception) { status = e.message ?: "Download failed" }
                            finally { downloadingModelId = null }
                        }
                    },
                    onDownload = { model ->
                        scope.launch {
                            downloadingModelId = model.id
                            status = "Descargando ${model.name}…"
                            try {
                                val dest = fetchModel(model)
                                exportPath = dest.absolutePath
                                saveNam.launch(dest.name)
                            } catch (e: Exception) { status = "Descarga fallida: ${e.message}" }
                            finally { downloadingModelId = null }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ToneCard(
    tone: Tone,
    expanded: Boolean,
    models: List<ToneModel>,
    downloadingModelId: Long?,
    onFavorite: () -> Unit,
    onExpand: () -> Unit,
    onTry: (ToneModel) -> Unit,
    onDownload: (ToneModel) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = tone.imageUrl,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp).background(PanelRaised, RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(tone.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${tone.gear?.uppercase() ?: "NAM"}  •  ${tone.format?.uppercase() ?: "NAM"} A2", color = ElectricBlue, style = MaterialTheme.typography.labelSmall)
                    Text("by ${tone.creator ?: "TONE3000 creator"}  •  ${tone.downloadsCount} downloads", color = MutedText, style = MaterialTheme.typography.bodySmall)
                }
                FilledTonalButton(onClick = onExpand) { Text(if (expanded) "CLOSE" else "${tone.modelsCount} MODELS") }
                IconButton(onClick = onFavorite) { Icon(if (tone.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorite", tint = if (tone.isFavorite) Color(0xFFFF5C8A) else MutedText) }
            }
            if (expanded) {
                HorizontalDivider(color = Color(0xFF343B44))
                if (models.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
                else models.forEach { model ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(model.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${model.size?.uppercase() ?: "NAM"}  •  A${model.architectureVersion ?: "2"}", color = MutedText, style = MaterialTheme.typography.labelSmall)
                        }
                        Button(onClick = { onTry(model) }, enabled = downloadingModelId == null) {
                            if (downloadingModelId == model.id) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else { Icon(Icons.Default.Bolt, null); Spacer(Modifier.width(4.dp)); Text("TRY") }
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { onDownload(model) }, enabled = downloadingModelId == null) {
                            Icon(Icons.Default.Download, "Descargar archivo NAM")
                            Text(" .nam")
                        }
                    }
                }
            }
        }
    }
}
