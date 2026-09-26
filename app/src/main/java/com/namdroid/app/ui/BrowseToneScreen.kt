package com.namdroid.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.namdroid.app.R
import com.namdroid.app.tone3000.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class BrowseMode { TONES, CREATORS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseToneScreen(
    oauthCallback: Uri?,
    onOAuthConsumed: () -> Unit,
    onBack: () -> Unit,
    onModelDownloaded: (File) -> String?,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val client = remember { Tone3000Client() }
    val session = remember { Tone3000Session(context.applicationContext, client) }
    val scope = rememberCoroutineScope()

    var connected by remember { mutableStateOf(session.hasSession()) }
    var user by remember { mutableStateOf<ToneUser?>(null) }
    var status by remember { mutableStateOf(if (connected) "Restaurando sesión…" else "Conectá tu cuenta para explorar tonos") }
    var browseMode by rememberSaveable { mutableStateOf(BrowseMode.TONES) }
    var query by rememberSaveable { mutableStateOf("") }
    var collection by remember { mutableStateOf<Tone3000Client.Collection?>(null) }
    var tones by remember { mutableStateOf<List<Tone>>(emptyList()) }
    var creators by remember { mutableStateOf<List<ToneCreator>>(emptyList()) }
    var selectedCreator by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCreatorLabel by rememberSaveable { mutableStateOf<String?>(null) }
    var searchPage by remember { mutableIntStateOf(1) }
    var canLoadMore by remember { mutableStateOf(true) }
    var showFilters by remember { mutableStateOf(false) }
    var sortMode by rememberSaveable { mutableStateOf("SMART") }
    var creatorSort by rememberSaveable { mutableStateOf("downloads") }
    var gearFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var minimumModels by rememberSaveable { mutableFloatStateOf(0f) }
    var verifiedOnly by rememberSaveable { mutableStateOf(false) }
    var expandedTone by remember { mutableStateOf<Long?>(null) }
    var models by remember { mutableStateOf<List<ToneModel>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
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

    val visibleTones by remember {
        derivedStateOf {
            val filtered = tones.filter { tone ->
                val collectionQueryMatches = collection == null || query.isBlank() ||
                    tone.title.contains(query.trim(), ignoreCase = true) || tone.creator.orEmpty().contains(query.trim(), ignoreCase = true)
                collectionQueryMatches && (gearFilter == null || tone.gear.equals(gearFilter, ignoreCase = true)) &&
                    tone.modelsCount >= minimumModels.toInt() && (!verifiedOnly || tone.creatorVerified)
            }
            when (sortMode) {
                "MODELS" -> filtered.sortedByDescending { it.modelsCount }
                "A_Z" -> filtered.sortedBy { it.title.lowercase() }
                else -> filtered
            }
        }
    }

    fun serverSort(): String? = when (sortMode) {
        "POPULAR" -> "downloads-all-time"
        "NEWEST" -> "newest"
        "TRENDING" -> "trending"
        else -> null
    }

    suspend fun loadCatalog(search: String = query, append: Boolean = false, creatorOverride: String? = selectedCreator) {
        val token = session.validAccessToken()
        if (token == null) { connected = false; status = "La sesión venció. Conectá TONE3000 otra vez."; return }
        loading = true
        try {
            if (user == null) user = client.getUser(token)
            if (collection != null) {
                tones = client.listTones(token, collection!!)
                searchPage = 1
                canLoadMore = false
            } else {
                val requestedPage = if (append) searchPage + 1 else 1
                val received = client.searchTones(token, search.trim(), requestedPage, 25, serverSort(), gearFilter, creatorOverride, verifiedOnly)
                tones = if (append) (tones + received).distinctBy { it.id } else received
                searchPage = requestedPage
                canLoadMore = received.size == 25
            }
            status = if (tones.isEmpty()) "No encontramos tonos con esos criterios" else "${tones.size} tonos cargados"
        } catch (e: Exception) { status = e.message ?: "No se pudo consultar TONE3000" }
        finally { loading = false }
    }

    suspend fun loadCreators(search: String = query) {
        val token = session.validAccessToken()
        if (token == null) { connected = false; status = "La sesión venció. Conectá TONE3000 otra vez."; return }
        loading = true
        try {
            if (user == null) user = client.getUser(token)
            creators = client.listCreators(token, search.trim(), creatorSort)
            status = if (creators.isEmpty()) "No encontramos perfiles" else "${creators.size} perfiles"
        } catch (e: Exception) { status = e.message ?: "No se pudieron cargar los perfiles" }
        finally { loading = false }
    }

    fun submitSearch() {
        focusManager.clearFocus()
        scope.launch { if (browseMode == BrowseMode.CREATORS) loadCreators() else loadCatalog() }
    }

    fun openCreator(username: String, label: String?) {
        selectedCreator = username
        selectedCreatorLabel = label ?: username
        browseMode = BrowseMode.TONES
        collection = null
        query = ""
        expandedTone = null
        scope.launch { loadCatalog("", creatorOverride = username) }
    }

    LaunchedEffect(Unit) { if (connected && oauthCallback == null) loadCatalog("") }
    LaunchedEffect(oauthCallback) {
        val uri = oauthCallback ?: return@LaunchedEffect
        val error = uri.getQueryParameter("error")
        val code = uri.getQueryParameter("code")
        val verifier = session.consumePending(uri.getQueryParameter("state"))
        try {
            when {
                error != null -> status = "Inicio de sesión cancelado: $error"
                code == null || verifier == null -> status = "Respuesta de acceso inválida o vencida"
                else -> { status = "Completando inicio de sesión…"; session.save(client.exchangeCodeForToken(code, verifier)); connected = true; loadCatalog("") }
            }
        } finally { onOAuthConsumed() }
    }

    fun startLogin() {
        val pkce = Pkce.generate()
        val state = Pkce.randomState()
        session.savePending(pkce.verifier, state)
        CustomTabsIntent.Builder().setShowTitle(false).build().launchUrl(context, Uri.parse(client.buildAuthorizeUrl(state, pkce.challenge)))
    }

    Column(Modifier.fillMaxSize().background(Carbon).safeDrawingPadding()) {
        CompactToneHeader(user, connected, onBack) {
            session.clear(); connected = false; user = null; tones = emptyList(); creators = emptyList()
        }
        if (!connected) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Card(Modifier.widthIn(max = 520.dp).padding(24.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Cloud, null, tint = ElectricBlue, modifier = Modifier.size(42.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("Conectar TONE3000", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text("Iniciá sesión una vez para usar el catálogo, tus favoritos y tus descargas.", color = MutedText)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = ::startLogin) { Text("INICIAR SESIÓN") }
                        Spacer(Modifier.height(8.dp)); Text(status, color = MutedText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            return@Column
        }

        CompactSearchBar(query, browseMode, loading, listOf(gearFilter != null, minimumModels > 0f, verifiedOnly, sortMode != "SMART").count { it }, { query = it }, ::submitSearch) { showFilters = true }
        CatalogNavigation(browseMode, collection,
            onExplore = { browseMode = BrowseMode.TONES; collection = null; selectedCreator = null; selectedCreatorLabel = null; query = ""; scope.launch { loadCatalog("", creatorOverride = null) } },
            onCreators = { browseMode = BrowseMode.CREATORS; collection = null; selectedCreator = null; selectedCreatorLabel = null; query = ""; scope.launch { loadCreators("") } },
            onCollection = { target -> browseMode = BrowseMode.TONES; collection = target; selectedCreator = null; selectedCreatorLabel = null; query = ""; scope.launch { loadCatalog("", creatorOverride = null) } },
        )
        if (selectedCreator != null) {
            Surface(color = ElectricBlue.copy(alpha = .12f), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
                Row(Modifier.heightIn(min = 34.dp).padding(start = 11.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Person, null, tint = ElectricBlue, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(7.dp))
                    Text("Tonos de ${selectedCreatorLabel ?: selectedCreator}", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1)
                    IconButton(onClick = { selectedCreator = null; selectedCreatorLabel = null; scope.launch { loadCatalog("", creatorOverride = null) } }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.Close, "Cerrar perfil", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(status, color = MutedText, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (browseMode == BrowseMode.TONES) Text("${visibleTones.size} visibles", color = MutedText, style = MaterialTheme.typography.labelSmall)
        }

        if (browseMode == BrowseMode.CREATORS) CreatorList(creators, loading) { openCreator(it.username, it.displayName) }
        else ToneList(
            tones = visibleTones, expandedTone = expandedTone, models = models, downloadingModelId = downloadingModelId,
            loading = loading, canLoadMore = collection == null && canLoadMore,
            onFavorite = { tone -> scope.launch {
                runCatching { val token = session.validAccessToken() ?: error("Sesión vencida"); client.setFavorite(token, tone.id, !tone.isFavorite) }
                    .onSuccess { tones = tones.map { if (it.id == tone.id) it.copy(isFavorite = !it.isFavorite) else it } }
                    .onFailure { status = it.message ?: "No se pudo actualizar el favorito" }
            } },
            onExpand = { tone ->
                if (expandedTone == tone.id) { expandedTone = null; models = emptyList() }
                else scope.launch {
                    expandedTone = tone.id; models = emptyList(); loading = true
                    try { val token = session.validAccessToken() ?: error("Sesión vencida"); models = client.getModelsForTone(token, tone.id); status = "${models.size} variantes disponibles" }
                    catch (e: Exception) { status = e.message ?: "No se pudieron cargar las variantes" }
                    finally { loading = false }
                }
            },
            onCreator = { tone -> tone.creatorUsername?.let { openCreator(it, tone.creator) } },
            onTry = { model -> scope.launch {
                downloadingModelId = model.id; status = "Descargando ${model.name}…"
                try { val dest = fetchModel(model); val error = onModelDownloaded(dest); status = if (error == null) "NAM activo: ${model.name}. Volvé a la pedalera para editarlo." else "No se pudo probar ${model.name}: $error" }
                catch (e: Exception) { status = e.message ?: "Falló la descarga" }
                finally { downloadingModelId = null }
            } },
            onDownload = { model -> scope.launch {
                downloadingModelId = model.id; status = "Descargando ${model.name}…"
                try { val dest = fetchModel(model); exportPath = dest.absolutePath; saveNam.launch(dest.name) }
                catch (e: Exception) { status = "Descarga fallida: ${e.message}" }
                finally { downloadingModelId = null }
            } },
            onLoadMore = { scope.launch { loadCatalog(append = true) } },
        )
    }

    if (showFilters) ModalBottomSheet(onDismissRequest = { showFilters = false }) {
        FilterSheet(browseMode, sortMode, creatorSort, gearFilter, minimumModels, verifiedOnly,
            if (browseMode == BrowseMode.CREATORS) creators.size else visibleTones.size,
            { sortMode = it }, { creatorSort = it }, { gearFilter = it }, { minimumModels = it }, { verifiedOnly = it },
            onClear = { sortMode = "SMART"; creatorSort = "downloads"; gearFilter = null; minimumModels = 0f; verifiedOnly = false },
            onApply = { showFilters = false; scope.launch { if (browseMode == BrowseMode.CREATORS) loadCreators() else loadCatalog() } },
        )
    }
}

@Composable
private fun CompactToneHeader(user: ToneUser?, connected: Boolean, onBack: () -> Unit, onLogout: () -> Unit) {
    var accountMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(48.dp).background(Panel).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) { Icon(Icons.Default.ArrowBack, "Volver") }
        androidx.compose.foundation.Image(painterResource(R.drawable.tone3000_official), "TONE3000", contentScale = ContentScale.Fit, modifier = Modifier.width(142.dp).height(30.dp))
        Spacer(Modifier.weight(1f))
        Box {
            Surface(onClick = { accountMenu = true }, color = PanelRaised, shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.height(34.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (user?.avatarUrl != null) AsyncImage(user.avatarUrl, null, modifier = Modifier.size(23.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    else Icon(Icons.Default.AccountCircle, null, modifier = Modifier.size(23.dp), tint = MutedText)
                    Spacer(Modifier.width(6.dp)); Text(user?.username ?: if (connected) "Cuenta" else "Offline", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(5.dp)); Box(Modifier.size(7.dp).background(if (connected) SignalGreen else Color.Gray, CircleShape))
                }
            }
            DropdownMenu(accountMenu, { accountMenu = false }) {
                DropdownMenuItem({ Text("Cerrar sesión") }, leadingIcon = { Icon(Icons.Default.Logout, null) }, onClick = { accountMenu = false; onLogout() })
            }
        }
    }
}

@Composable
private fun CompactSearchBar(value: String, mode: BrowseMode, loading: Boolean, activeFilters: Int, onValueChange: (String) -> Unit, onSearch: () -> Unit, onFilters: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value, onValueChange, singleLine = true,
            placeholder = { Text(if (mode == BrowseMode.CREATORS) "Buscar perfil o usuario" else "Buscar amplis, pedales o autores") },
            leadingIcon = { Icon(if (mode == BrowseMode.CREATORS) Icons.Default.PersonSearch else Icons.Default.Search, null) },
            trailingIcon = { IconButton(onClick = onSearch, enabled = !loading) { if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.ArrowForward, "Buscar") } },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSearch() }), modifier = Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        BadgedBox(badge = { if (activeFilters > 0) Badge { Text(activeFilters.toString()) } }) {
            FilledTonalIconButton(onClick = onFilters, modifier = Modifier.size(42.dp)) { Icon(Icons.Default.Tune, "Filtros") }
        }
    }
}

@Composable
private fun CatalogNavigation(browseMode: BrowseMode, collection: Tone3000Client.Collection?, onExplore: () -> Unit, onCreators: () -> Unit, onCollection: (Tone3000Client.Collection) -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(browseMode == BrowseMode.TONES && collection == null, onExplore, { Text("EXPLORAR") })
        FilterChip(browseMode == BrowseMode.CREATORS, onCreators, { Text("CREADORES") }, leadingIcon = { Icon(Icons.Default.Groups, null, modifier = Modifier.size(17.dp)) })
        FilterChip(collection == Tone3000Client.Collection.FAVORITES, { onCollection(Tone3000Client.Collection.FAVORITES) }, { Text("FAVORITOS") })
        FilterChip(collection == Tone3000Client.Collection.CREATED, { onCollection(Tone3000Client.Collection.CREATED) }, { Text("MIS TONOS") })
        FilterChip(collection == Tone3000Client.Collection.DOWNLOADED, { onCollection(Tone3000Client.Collection.DOWNLOADED) }, { Text("DESCARGADOS") })
    }
}

@Composable
private fun CreatorList(creators: List<ToneCreator>, loading: Boolean, onCreator: (ToneCreator) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (creators.isEmpty() && !loading) item { Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) { Text("Buscá un usuario o cambiá el orden de perfiles", color = MutedText) } }
        items(creators, key = { it.id }) { creator ->
            Card(onClick = { onCreator(creator) }, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(creator.avatarUrl, null, modifier = Modifier.size(48.dp).clip(CircleShape).background(PanelRaised), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(creator.displayName ?: creator.username, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (creator.verified) { Spacer(Modifier.width(4.dp)); Icon(Icons.Default.Verified, "Verificado", tint = ElectricBlue, modifier = Modifier.size(17.dp)) }
                        }
                        Text("@${creator.username}", color = MutedText, style = MaterialTheme.typography.labelSmall)
                        creator.bio?.let { Text(it, color = MutedText, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${creator.tonesCount} tonos", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                        Text("${creator.downloadsCount} descargas", color = MutedText, style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.width(5.dp)); Icon(Icons.Default.ChevronRight, null, tint = MutedText)
                }
            }
        }
    }
}

@Composable
private fun ToneList(tones: List<Tone>, expandedTone: Long?, models: List<ToneModel>, downloadingModelId: Long?, loading: Boolean, canLoadMore: Boolean,
    onFavorite: (Tone) -> Unit, onExpand: (Tone) -> Unit, onCreator: (Tone) -> Unit, onTry: (ToneModel) -> Unit, onDownload: (ToneModel) -> Unit, onLoadMore: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        items(tones, key = { it.id }) { tone ->
            ToneCard(tone, expandedTone == tone.id, if (expandedTone == tone.id) models else emptyList(), downloadingModelId,
                { onFavorite(tone) }, { onExpand(tone) }, { onCreator(tone) }, onTry, onDownload)
        }
        if (canLoadMore) item {
            OutlinedButton(onLoadMore, modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp), enabled = !loading) {
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else { Icon(Icons.Default.ExpandMore, null); Spacer(Modifier.width(6.dp)); Text("CARGAR 25 MÁS") }
            }
        }
    }
}

@Composable
private fun ToneCard(tone: Tone, expanded: Boolean, models: List<ToneModel>, downloadingModelId: Long?, onFavorite: () -> Unit, onExpand: () -> Unit,
    onCreator: () -> Unit, onTry: (ToneModel) -> Unit, onDownload: (ToneModel) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(12.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(tone.imageUrl, null, modifier = Modifier.size(62.dp).background(PanelRaised, RoundedCornerShape(9.dp)), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(tone.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${tone.gear?.uppercase() ?: "NAM"}  •  ${tone.format?.uppercase() ?: "NAM"} A2  •  ${tone.modelsCount} modelos", color = ElectricBlue, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    Row(Modifier.clickable(enabled = tone.creatorUsername != null, onClick = onCreator).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (tone.creatorAvatarUrl != null) AsyncImage(tone.creatorAvatarUrl, null, modifier = Modifier.size(18.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                        else Icon(Icons.Default.AccountCircle, null, modifier = Modifier.size(18.dp), tint = MutedText)
                        Spacer(Modifier.width(5.dp)); Text(tone.creator ?: "Creador TONE3000", color = MutedText, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (tone.creatorVerified) { Spacer(Modifier.width(3.dp)); Icon(Icons.Default.Verified, "Verificado", tint = ElectricBlue, modifier = Modifier.size(14.dp)) }
                    }
                }
                Column(horizontalAlignment = Alignment.End) { Text(tone.downloadsCount.toString(), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium); Text("descargas", color = MutedText, style = MaterialTheme.typography.labelSmall) }
                IconButton(onFavorite) { Icon(if (tone.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorito", tint = if (tone.isFavorite) Color(0xFFFF5C8A) else MutedText) }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MutedText)
            }
            if (expanded) {
                HorizontalDivider(color = Color(0xFF343B44))
                if (models.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth()) else models.forEach { model ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(model.name, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${model.size?.uppercase() ?: "NAM"}  •  A${model.architectureVersion ?: "2"}", color = MutedText, style = MaterialTheme.typography.labelSmall) }
                        Button({ onTry(model) }, enabled = downloadingModelId == null, contentPadding = PaddingValues(horizontal = 12.dp)) {
                            if (downloadingModelId == model.id) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else { Icon(Icons.Default.Bolt, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(3.dp)); Text("PROBAR") }
                        }
                        Spacer(Modifier.width(6.dp)); OutlinedIconButton({ onDownload(model) }, modifier = Modifier.size(40.dp), enabled = downloadingModelId == null) { Icon(Icons.Default.Download, "Descargar .nam") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterSheet(browseMode: BrowseMode, sortMode: String, creatorSort: String, gearFilter: String?, minimumModels: Float, verifiedOnly: Boolean, resultCount: Int,
    onSortMode: (String) -> Unit, onCreatorSort: (String) -> Unit, onGear: (String?) -> Unit, onMinimumModels: (Float) -> Unit, onVerified: (Boolean) -> Unit,
    onClear: () -> Unit, onApply: () -> Unit) {
    val gears = listOf(null to "TODOS", "amp" to "AMPLI", "amp-cab" to "AMP + CAB", "cabinet" to "CABINET", "pedal" to "PEDAL", "outboard" to "OUTBOARD", "spaces" to "ESPACIOS", "experimental" to "EXPERIMENTAL")
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        Text(if (browseMode == BrowseMode.CREATORS) "ORDENAR PERFILES" else "FILTRAR Y ORDENAR", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(12.dp)); Text("ORDEN", color = MutedText, style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            val options = if (browseMode == BrowseMode.CREATORS) listOf("downloads" to "DESCARGAS", "tones" to "TONOS", "favorites" to "FAVORITOS", "models" to "MODELOS")
            else listOf("SMART" to "INTELIGENTE", "TRENDING" to "TENDENCIA", "POPULAR" to "MÁS POPULARES", "NEWEST" to "NUEVOS", "MODELS" to "MÁS MODELOS", "A_Z" to "A–Z")
            options.forEach { (value, label) -> FilterChip(if (browseMode == BrowseMode.CREATORS) creatorSort == value else sortMode == value,
                { if (browseMode == BrowseMode.CREATORS) onCreatorSort(value) else onSortMode(value) }, { Text(label) }) }
        }
        if (browseMode == BrowseMode.TONES) {
            Spacer(Modifier.height(9.dp)); Row(verticalAlignment = Alignment.CenterVertically) { Text("SOLO CREADORES VERIFICADOS", color = MutedText, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f)); Switch(verifiedOnly, onVerified) }
            Text("TIPO DE EQUIPO", color = MutedText, style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) { gears.forEach { (value, label) -> FilterChip(gearFilter == value, { onGear(value) }, { Text(label) }) } }
            Spacer(Modifier.height(9.dp)); Text("MÍNIMO DE VARIANTES: ${minimumModels.toInt()}", color = MutedText, style = MaterialTheme.typography.labelMedium)
            Slider(minimumModels, onMinimumModels, valueRange = 0f..20f, steps = 19)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClear, modifier = Modifier.weight(1f)) { Text("LIMPIAR") }
            Button(onApply, modifier = Modifier.weight(1f)) { Text("APLICAR · $resultCount") }
        }
    }
}
