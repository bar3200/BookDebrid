package com.freedify.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NativeMainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NativeAudiobookSearch.attach(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            BookDebridTheme {
                val model: BookDebridViewModel = viewModel()
                BookDebridApp(
                    model = model,
                    openLegacy = { startActivity(Intent(this, MainActivity::class.java)) },
                )
            }
        }
    }

    override fun onDestroy() {
        NativeAudiobookSearch.detach(this)
        super.onDestroy()
    }
}

enum class AppDestination { HOME, SEARCH, LIBRARY, SETTINGS }

data class NativeUiState(
    val hasApiKey: Boolean = false,
    val backendReady: Boolean = false,
    val loadingMessage: String = "Starting BookDebrid…",
    val destination: AppDestination = AppDestination.HOME,
    val books: List<Audiobook> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<Audiobook> = emptyList(),
    val searching: Boolean = false,
    val selectedBook: Audiobook? = null,
    val related: List<Audiobook> = emptyList(),
    val transferProgress: Float? = null,
    val transferMessage: String = "",
    val error: String? = null,
)

class BookDebridViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val secureSettings = SecureSettings(application)
    private val store = AudiobookStore.get(application)
    private val api = BookDebridApi()
    private val _state = MutableStateFlow(
        NativeUiState(hasApiKey = !secureSettings.getApiKey().isNullOrBlank(), books = store.books()),
    )
    val state: StateFlow<NativeUiState> = _state.asStateFlow()
    private val storeListener = { _state.value = _state.value.copy(books = store.books()) }

    init {
        store.addListener(storeListener)
        secureSettings.getApiKey()?.takeIf(String::isNotBlank)?.let(::startBackend)
    }

    override fun onCleared() {
        store.removeListener(storeListener)
        super.onCleared()
    }

    fun saveApiKey(key: String) {
        if (key.isBlank()) return
        secureSettings.saveApiKey(key.trim())
        _state.value = _state.value.copy(hasApiKey = true, error = null)
        startBackend(key.trim())
    }

    fun navigate(destination: AppDestination) {
        _state.value = _state.value.copy(destination = destination, selectedBook = null, error = null)
    }

    fun setSearchQuery(value: String) { _state.value = _state.value.copy(searchQuery = value) }

    fun search() {
        val query = _state.value.searchQuery.trim()
        if (query.isBlank() || !_state.value.backendReady) return
        viewModelScope.launch {
            _state.value = _state.value.copy(searching = true, error = null)
            runCatching {
                try {
                    api.search(query)
                } catch (error: ApiException) {
                    if (error.statusCode != 502) throw error
                    NativeAudiobookSearch.search(query)
                }
            }
                .onSuccess { _state.value = _state.value.copy(searching = false, searchResults = it) }
                .onFailure { _state.value = _state.value.copy(searching = false, error = it.userMessage()) }
        }
    }

    fun openBook(book: Audiobook) {
        _state.value = _state.value.copy(selectedBook = book, related = emptyList(), error = null)
        viewModelScope.launch {
            val detailed = runCatching {
                val base = if (book.magnetLink == null && book.chapters.isEmpty()) api.details(book.id) else book
                api.enrich(base)
            }.getOrElse { book }
            _state.value = _state.value.copy(selectedBook = detailed)
            if (store.book(detailed.id) != null) store.save(detailed.copy(chapters = store.book(detailed.id)?.chapters.orEmpty()))
            val related = runCatching { api.related(detailed) }.getOrDefault(emptyList())
            _state.value = _state.value.copy(related = related)
        }
    }

    fun closeBook() { _state.value = _state.value.copy(selectedBook = null, related = emptyList()) }

    fun saveBook() {
        _state.value.selectedBook?.let(store::save)
    }

    fun download() {
        val book = _state.value.selectedBook ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(transferProgress = 0f, transferMessage = "Starting transfer…", error = null)
            runCatching {
                api.download(book) { progress, message ->
                    _state.value = _state.value.copy(transferProgress = progress, transferMessage = message)
                }
            }.onSuccess { downloaded ->
                store.save(downloaded)
                _state.value = _state.value.copy(
                    selectedBook = downloaded,
                    transferProgress = null,
                    transferMessage = "",
                )
            }.onFailure {
                _state.value = _state.value.copy(transferProgress = null, error = it.userMessage())
            }
        }
    }

    fun removeBook(deleteFromAllDebrid: Boolean = false) {
        val book = _state.value.selectedBook ?: return
        store.remove(book.id)
        _state.value = _state.value.copy(selectedBook = null)
        if (deleteFromAllDebrid) viewModelScope.launch { runCatching { api.delete(book) } }
    }

    fun play(book: Audiobook, chapter: AudiobookChapter, fromBeginning: Boolean = false) {
        PlaybackService.play(
            getApplication<android.app.Application>(),
            book.id,
            chapter.id,
            fromBeginning,
        )
    }

    private fun startBackend(key: String) {
        _state.value = _state.value.copy(backendReady = false, loadingMessage = "Starting private audiobook service…")
        BackendManager.startOrUpdate(
            getApplication<android.app.Application>(),
            key,
            onReady = {
                _state.value = _state.value.copy(backendReady = true, loadingMessage = "", error = null)
                PlaybackService.ensureStarted(getApplication<android.app.Application>())
            },
            onError = { message -> _state.value = _state.value.copy(error = message, loadingMessage = "") },
        )
    }
}

private fun Throwable.userMessage(): String = message?.removePrefix("AllDebrid Error: ")
    ?: "Something went wrong. Please try again."

@Composable
private fun BookDebridTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFAEB4FF),
            secondary = Color(0xFFFFB3C8),
            background = Color(0xFF101116),
            surface = Color(0xFF191A22),
            surfaceVariant = Color(0xFF242631),
        ),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookDebridApp(model: BookDebridViewModel, openLegacy: () -> Unit) {
    val state by model.state.collectAsState()
    val playback by PlaybackService.playback.collectAsState()
    var showPlayer by remember { mutableStateOf(false) }

    if (!state.hasApiKey) {
        ApiKeyScreen(model::saveApiKey)
        return
    }
    if (!state.backendReady && state.error == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(state.loadingMessage)
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.selectedBook?.title ?: "BookDebrid", maxLines = 1) },
                navigationIcon = {
                    if (state.selectedBook != null) IconButton(onClick = model::closeBook) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding()) {
                if (playback.chapterId.isNotBlank()) MiniPlayer(playback) { showPlayer = true }
                NavigationBar {
                    listOf(
                        Triple(AppDestination.HOME, Icons.Rounded.Home, "Home"),
                        Triple(AppDestination.SEARCH, Icons.Rounded.Search, "Search"),
                        Triple(AppDestination.LIBRARY, Icons.AutoMirrored.Rounded.LibraryBooks, "My books"),
                        Triple(AppDestination.SETTINGS, Icons.Rounded.Settings, "Settings"),
                    ).forEach { (destination, icon, label) ->
                        NavigationBarItem(
                            selected = state.destination == destination && state.selectedBook == null,
                            onClick = { model.navigate(destination) },
                            icon = { Icon(icon, label) },
                            label = { Text(label, maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (state.selectedBook != null) {
                BookDetails(state, model)
            } else when (state.destination) {
                AppDestination.HOME -> HomeScreen(state, model)
                AppDestination.SEARCH -> SearchScreen(state, model)
                AppDestination.LIBRARY -> LibraryScreen(state.books, model)
                AppDestination.SETTINGS -> SettingsScreen(model::saveApiKey, openLegacy)
            }
            (state.error ?: playback.error)?.let { message ->
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(16.dp),
                ) { Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer) }
            }
        }
    }
    if (showPlayer) FullPlayer(playback) { showPlayer = false }
}

@Composable
private fun ApiKeyScreen(onSave: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.AutoStories, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text("Welcome to BookDebrid", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Your private audiobook library, powered by AllDebrid. The key is encrypted with Android Keystore.",
            Modifier.padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text("AllDebrid API key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onSave(key) }, enabled = key.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Text("Open my library")
        }
    }
}

@Composable
private fun HomeScreen(state: NativeUiState, model: BookDebridViewModel) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Text("Your next chapter", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Continue where you left off or discover something new.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.books.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp)) {
                        Icon(Icons.Rounded.AutoStories, null, Modifier.size(42.dp))
                        Text("Build your library", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
                        Text("Search for an audiobook, send it to AllDebrid, and its chapters will appear here.")
                        Button(onClick = { model.navigate(AppDestination.SEARCH) }, modifier = Modifier.padding(top = 12.dp)) {
                            Icon(Icons.Rounded.Search, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Find a book")
                        }
                    }
                }
            }
        } else {
            item { Text("Recently added", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(state.books.take(10), key = { it.id }) { BookCover(it) { model.openBook(it) } }
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(state: NativeUiState, model: BookDebridViewModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = model::setSearchQuery,
            label = { Text("Title, author, or AudiobookBay URL") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = model::search,
            enabled = state.searchQuery.isNotBlank() && !state.searching,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        ) { Text(if (state.searching) "Searching…" else "Search audiobooks") }
        if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(state.searchResults, key = { it.id }) { BookRow(it) { model.openBook(it) } }
        }
    }
}

@Composable
private fun LibraryScreen(books: List<Audiobook>, model: BookDebridViewModel) {
    if (books.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Your downloaded books will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { items(books, key = { it.id }) { BookRow(it) { model.openBook(it) } } }
}

@Composable
private fun BookDetails(state: NativeUiState, model: BookDebridViewModel) {
    val book = state.selectedBook ?: return
    var confirmCloudDelete by remember(book.id) { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.Top) {
                Cover(book.coverUrl, Modifier.size(width = 128.dp, height = 190.dp))
                Column(Modifier.padding(start = 18.dp).weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(book.author, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (book.rating != null) Text("★ %.2f  ·  %,d ratings".format(book.rating, book.ratingsCount ?: 0))
                    if (book.genres.isNotEmpty()) Text(book.genres.take(3).joinToString(" · "), modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        if (book.description.isNotBlank()) item { Text(book.description, style = MaterialTheme.typography.bodyLarge) }
        item {
            if (book.chapters.isEmpty()) {
                Button(onClick = model::download, enabled = state.transferProgress == null, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Download, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.transferProgress == null) "Download to AllDebrid" else "Downloading…")
                }
                OutlinedButton(onClick = model::saveBook, modifier = Modifier.fillMaxWidth()) { Text("Save to My Books") }
            } else {
                Button(onClick = { model.play(book, book.chapters.first(), fromBeginning = true) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Play from beginning")
                }
            }
        }
        state.transferProgress?.let { progress ->
            item {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(state.transferMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (book.chapters.isNotEmpty()) {
            item { Text("${book.chapters.size} chapters", style = MaterialTheme.typography.titleLarge) }
            items(book.chapters, key = { it.id }) { chapter ->
                ChapterRow(book, chapter) { model.play(book, chapter) }
            }
            item {
                OutlinedButton(onClick = model::download, enabled = state.transferProgress == null, modifier = Modifier.fillMaxWidth()) {
                    Text("Refresh chapters from AllDebrid")
                }
                OutlinedButton(onClick = { model.removeBook(false) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.DeleteOutline, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Remove from My Books")
                }
                if (!confirmCloudDelete) {
                    OutlinedButton(onClick = { confirmCloudDelete = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Delete from AllDebrid…")
                    }
                } else {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Delete the AllDebrid transfer too?", fontWeight = FontWeight.SemiBold)
                            Text("This removes the book from My Books and your AllDebrid cloud.")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                OutlinedButton(onClick = { confirmCloudDelete = false }) { Text("Cancel") }
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    onClick = { model.removeBook(true) },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                ) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
        if (state.related.isNotEmpty()) {
            item { Text("Books like this", style = MaterialTheme.typography.titleLarge) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(state.related, key = { it.id }) { BookCover(it) { model.openBook(it) } }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(onSave: (String) -> Unit, openLegacy: () -> Unit) {
    var replacementKey by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("AllDebrid", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item {
            OutlinedTextField(
                value = replacementKey,
                onValueChange = { replacementKey = it },
                label = { Text("Replace API key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Button(onClick = { onSave(replacementKey); replacementKey = "" }, enabled = replacementKey.isNotBlank()) {
                Text("Save encrypted key")
            }
        }
        item { Text("Advanced", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp)) }
        item {
            OutlinedButton(onClick = openLegacy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.MoreHoriz, null)
                Spacer(Modifier.width(8.dp))
                Text("Open legacy Freedify interface")
            }
        }
        item { Text("Use the legacy interface for music, podcasts, backups, and any audiobook data not yet imported.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun BookCover(book: Audiobook, onClick: () -> Unit) {
    Column(Modifier.width(138.dp).clickable(onClick = onClick)) {
        Cover(book.coverUrl, Modifier.size(width = 138.dp, height = 204.dp))
        Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        Text(book.author, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BookRow(book: Audiobook, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(book.coverUrl, Modifier.size(width = 64.dp, height = 92.dp))
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text(book.author, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                if (book.chapters.isNotEmpty()) Text("${book.chapters.size} chapters", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun Cover(url: String, modifier: Modifier) {
    val model = BookDebridApi.imageUrl(url)
    var failed by remember(model) { mutableStateOf(false) }
    Surface(modifier.clip(RoundedCornerShape(12.dp)), color = MaterialTheme.colorScheme.surfaceVariant) {
        if (model.isBlank() || failed) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.AutoStories, null, Modifier.size(38.dp))
        } else AsyncImage(
            model = model,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onError = { failed = true },
        )
    }
}

@Composable
private fun ChapterRow(book: Audiobook, chapter: AudiobookChapter, play: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = play).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(21.dp)), contentAlignment = Alignment.Center) {
            Text(chapter.number.toString())
        }
        Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
            Text(chapter.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            chapter.effectiveDurationSeconds?.let { Text(formatDuration(it), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Icon(Icons.Rounded.PlayArrow, "Play ${chapter.title}")
    }
}

@Composable
private fun MiniPlayer(playback: NativePlaybackState, open: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 4.dp) {
        Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = open)) {
                Text(playback.title.ifBlank { "Audiobook" }, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(playback.bookTitle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            IconButton(onClick = { PlaybackService.seekRelative(-10_000) }) { Icon(Icons.Rounded.Replay10, "Back 10 seconds") }
            IconButton(onClick = { PlaybackService.toggle() }) {
                Icon(if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playback.playing) "Pause" else "Play", Modifier.size(34.dp))
            }
            IconButton(onClick = { PlaybackService.next() }) { Icon(Icons.Rounded.SkipNext, "Next chapter") }
        }
    }
}

@Composable
private fun FullPlayer(playback: NativePlaybackState, close: () -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp).navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = close) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close player") }
                    Text("Now playing", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(22.dp))
                Cover(playback.coverUrl, Modifier.fillMaxWidth(0.78f).aspectRatio(2f / 3f))
                Spacer(Modifier.height(24.dp))
                Text(playback.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(playback.bookTitle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                val duration = playback.durationMs.coerceAtLeast(1L)
                Slider(
                    value = playback.positionMs.coerceIn(0L, duration).toFloat(),
                    onValueChange = { PlaybackService.seekTo(it.toLong()) },
                    valueRange = 0f..duration.toFloat(),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatDuration(playback.positionMs / 1000.0))
                    Text("−${formatDuration((duration - playback.positionMs).coerceAtLeast(0) / 1000.0)}")
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { PlaybackService.seekRelative(-10_000) }) { Icon(Icons.Rounded.Replay10, "Back 10 seconds", Modifier.size(32.dp)) }
                    IconButton(onClick = PlaybackService::toggle, modifier = Modifier.size(72.dp)) {
                        Icon(if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(58.dp))
                    }
                    IconButton(onClick = PlaybackService::next) { Icon(Icons.Rounded.SkipNext, "Next chapter", Modifier.size(36.dp)) }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)) { speed ->
                        OutlinedButton(
                            onClick = { PlaybackService.setSpeed(speed) },
                            colors = if (playback.speed == speed) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            else ButtonDefaults.outlinedButtonColors(),
                        ) { Text("${speed}×") }
                    }
                }
            }
        }
    }
}

private fun formatDuration(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val remainder = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainder) else "%d:%02d".format(minutes, remainder)
}
