package com.freedify.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NativeMainActivity : ComponentActivity() {
    @Volatile private var keepSplashOnScreen = true
    private var pendingUiQaExport: String? = null
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    private val uiQaExportDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val payload = pendingUiQaExport
        pendingUiQaExport = null
        if (uri != null && payload != null) {
            val saved = runCatching {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(payload) }
                    ?: error("Could not open the selected file")
            }.isSuccess
            Toast.makeText(
                this,
                if (saved) "UI QA data exported" else "Could not export UI QA data",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { keepSplashOnScreen }
        NativeAudiobookSearch.attach(this)
        LegacyAudiobookImporter.attach(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            BookDebridTheme {
                val model: BookDebridViewModel = viewModel()
                BookDebridApp(
                    model = model,
                    openLegacy = { startActivity(Intent(this, MainActivity::class.java)) },
                    exportUiQaData = {
                        pendingUiQaExport = UiQaExport.create(applicationContext)
                        uiQaExportDocument.launch("bookdebrid-ui-qa.json")
                    },
                    onStartupResolved = { keepSplashOnScreen = false },
                )
            }
        }
    }

    override fun onDestroy() {
        NativeAudiobookSearch.detach(this)
        LegacyAudiobookImporter.detach(this)
        super.onDestroy()
    }
}

enum class AppDestination { HOME, SEARCH, LIBRARY, SETTINGS }

enum class AudiobookSearchMode(val label: String, val fieldLabel: String) {
    TITLE("Title", "Book title"),
    AUTHOR("Author", "Author name"),
    GENRE("Genre", "Genre or format, e.g. thriller or full cast"),
    URL("URL", "AudiobookBay book URL"),
}

data class NativeUiState(
    val hasApiKey: Boolean = false,
    val backendReady: Boolean = false,
    val loadingMessage: String = "Starting BookDebrid…",
    val destination: AppDestination = AppDestination.HOME,
    val books: List<Audiobook> = emptyList(),
    val searchQuery: String = "",
    val searchMode: AudiobookSearchMode = AudiobookSearchMode.TITLE,
    val searchResults: List<Audiobook> = emptyList(),
    val searching: Boolean = false,
    val homeRecommendations: List<Audiobook> = emptyList(),
    val recommendationSeedTitle: String = "",
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
    private val storeListener = {
        _state.value = _state.value.copy(books = store.books())
        loadHomeRecommendations()
    }

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

    fun setSearchMode(mode: AudiobookSearchMode) {
        _state.value = _state.value.copy(searchMode = mode, searchQuery = "", searchResults = emptyList(), error = null)
    }

    fun search() {
        val query = _state.value.searchQuery.trim()
        if (query.isBlank() || !_state.value.backendReady) return
        val mode = _state.value.searchMode
        if (mode == AudiobookSearchMode.URL && !query.matches(Regex("https?://(www\\.)?audiobookbay\\.[^/]+/.+", RegexOption.IGNORE_CASE))) {
            _state.value = _state.value.copy(error = "Paste a complete AudiobookBay book URL")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(searching = true, error = null)
            runCatching {
                val results = searchWithFallbacks(query, mode)
                rankSearchResults(results, query, mode)
            }
                .onSuccess { _state.value = _state.value.copy(searching = false, searchResults = it) }
                .onFailure { _state.value = _state.value.copy(searching = false, error = it.userMessage()) }
        }
    }

    fun openBook(book: Audiobook) {
        _state.value = _state.value.copy(selectedBook = book, related = emptyList(), error = null)
        viewModelScope.launch {
            val detailed = runCatching {
                val catalogBook = if (book.isCatalogBook()) {
                    searchAudiobookBay("${book.title} ${book.author}")
                        .let { findAvailabilityMatch(book, it) }
                        ?: book
                } else book
                val base = if (
                    !catalogBook.isCatalogBook() &&
                    catalogBook.magnetLink == null &&
                    catalogBook.chapters.isEmpty()
                ) api.details(catalogBook.id) else catalogBook
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

    fun download() = refreshDownload(rescan = false)

    fun rescan() {
        viewModelScope.launch {
            val selectedId = _state.value.selectedBook?.id
            // The former WebView may already have richer titles cached for an
            // existing book. Recover those before refreshing AllDebrid so the
            // merge below can preserve them if the M4B now yields only numbers.
            runCatching { LegacyAudiobookImporter.importSavedLibrary() }
            selectedId?.let(store::book)?.let { recovered ->
                _state.value = _state.value.copy(selectedBook = recovered)
            }
            refreshDownload(rescan = true)
        }
    }

    private fun refreshDownload(rescan: Boolean) {
        val book = _state.value.selectedBook ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                transferProgress = 0f,
                transferMessage = if (rescan) "Reading embedded chapter metadata…" else "Starting transfer…",
                error = null,
            )
            runCatching {
                val downloadable = if (!rescan && book.isCatalogBook()) {
                    val match = searchAudiobookBay("${book.title} ${book.author}")
                        .let { findAvailabilityMatch(book, it) }
                        ?: throw ApiException(
                            "No downloadable AudiobookBay match was found for this catalog book. Try title search or paste its AudiobookBay URL.",
                        )
                    val details = if (match.magnetLink == null) api.details(match.id) else match
                    details.copy(
                        coverUrl = book.coverUrl.ifBlank { details.coverUrl },
                        description = book.description.ifBlank { details.description },
                        genres = normalizeAudiobookGenres(book.genres + details.genres),
                        rating = book.rating ?: details.rating,
                        ratingsCount = book.ratingsCount ?: details.ratingsCount,
                    )
                } else book
                api.download(downloadable, rescan = rescan) { progress, message ->
                    _state.value = _state.value.copy(transferProgress = progress, transferMessage = message)
                }
            }.onSuccess { downloaded ->
                val previous = store.book(downloaded.id) ?: book
                val refreshed = preserveDescriptiveChapterTitles(previous, downloaded)
                val unchangedGenericScan = rescan &&
                    refreshed.chapters.all(::isGenericChapterTitle) &&
                    refreshed.chapters.map { it.title } == previous.chapters.map { it.title }
                store.save(refreshed)
                _state.value = _state.value.copy(
                    selectedBook = refreshed,
                    transferProgress = null,
                    transferMessage = "",
                    error = if (unchangedGenericScan) {
                        "Rescan found chapter markers, but this file exposed only numbered labels."
                    } else null,
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

    fun resume(book: Audiobook) {
        val snapshot = store.snapshotForBook(book.id)
        val chapter = book.chapters.firstOrNull { it.id == snapshot.chapterId }
            ?: book.chapters.firstOrNull()
            ?: return
        play(book, chapter)
    }

    fun resumeChapter(book: Audiobook): AudiobookChapter? {
        val chapterId = store.snapshotForBook(book.id).chapterId
        return book.chapters.firstOrNull { it.id == chapterId } ?: book.chapters.firstOrNull()
    }

    fun resumePosition(book: Audiobook): Long = store.snapshotForBook(book.id).positionMs

    private suspend fun searchWithFallbacks(query: String, mode: AudiobookSearchMode): List<Audiobook> {
        if (mode == AudiobookSearchMode.URL) return api.search(query)

        // Genre is a catalog concept, not an AudiobookBay free-text result
        // type. Prefer Open Library for it, then use ABB only as a fallback.
        if (mode == AudiobookSearchMode.GENRE) {
            val catalog = runCatching { api.catalogSearch(query, mode) }.getOrDefault(emptyList())
            if (catalog.isNotEmpty()) return catalog
        }

        val available = searchAudiobookBay(query)
        if (available.isNotEmpty()) return available
        return runCatching { api.catalogSearch(query, mode) }.getOrDefault(emptyList())
    }

    private suspend fun searchAudiobookBay(query: String): List<Audiobook> {
        val serverResults = runCatching { api.search(query) }.getOrDefault(emptyList())
        if (serverResults.isNotEmpty()) return serverResults
        return runCatching { NativeAudiobookSearch.search(query) }.getOrDefault(emptyList())
    }

    private fun startBackend(key: String) {
        _state.value = _state.value.copy(backendReady = false, loadingMessage = "Starting private audiobook service…")
        BackendManager.startOrUpdate(
            getApplication<android.app.Application>(),
            key,
            onReady = {
                _state.value = _state.value.copy(backendReady = true, loadingMessage = "", error = null)
                PlaybackService.ensureStarted(getApplication<android.app.Application>())
                loadHomeRecommendations()
            },
            onError = { message -> _state.value = _state.value.copy(error = message, loadingMessage = "") },
        )
    }

    private fun loadHomeRecommendations() {
        if (!_state.value.backendReady) return
        val seed = store.books().firstOrNull { it.genres.isNotEmpty() } ?: store.books().firstOrNull()
        if (seed == null) {
            _state.value = _state.value.copy(homeRecommendations = emptyList(), recommendationSeedTitle = "")
            return
        }
        viewModelScope.launch {
            val libraryIds = store.books().mapTo(mutableSetOf()) { it.id }
            val recommendations = runCatching { api.related(seed) }.getOrDefault(emptyList())
                .filterNot { it.id in libraryIds }
                .distinctBy { it.title.lowercase() }
                .take(10)
            _state.value = _state.value.copy(
                homeRecommendations = recommendations,
                recommendationSeedTitle = seed.title,
            )
        }
    }

    private fun preserveDescriptiveChapterTitles(previous: Audiobook, refreshed: Audiobook): Audiobook {
        if (previous.chapters.isEmpty()) return refreshed
        val chapters = refreshed.chapters.mapIndexed { index, chapter ->
            val prior = previous.chapters.minByOrNull { candidate ->
                kotlin.math.abs(candidate.startSeconds - chapter.startSeconds)
            }?.takeIf { kotlin.math.abs(it.startSeconds - chapter.startSeconds) < 2.0 }
                ?: previous.chapters.getOrNull(index)
            if (isGenericChapterTitle(chapter) && prior != null && !isGenericChapterTitle(prior)) {
                chapter.copy(title = prior.title)
            } else chapter
        }
        return refreshed.copy(chapters = chapters)
    }
}

private fun Throwable.userMessage(): String = message?.removePrefix("AllDebrid Error: ")
    ?: "Something went wrong. Please try again."

private fun Audiobook.isCatalogBook(): Boolean =
    id.startsWith("/works/") || id.startsWith("openlibrary:")

private fun findAvailabilityMatch(book: Audiobook, candidates: List<Audiobook>): Audiobook? {
    fun normalized(value: String) = value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    val wanted = normalized(book.title)
    if (wanted.isBlank()) return null
    val wantedTerms = wanted.split(' ').filter { it.length > 1 }.toSet()
    return candidates.maxByOrNull { candidate ->
        val title = normalized(candidate.title)
        val titleTerms = title.split(' ').filter { it.length > 1 }.toSet()
        when {
            title == wanted -> 100
            title.contains(wanted) || wanted.contains(title) -> 80
            wantedTerms.isNotEmpty() -> 10 * wantedTerms.intersect(titleTerms).size / wantedTerms.size
            else -> 0
        }
    }?.takeIf { candidate ->
        val titleTerms = normalized(candidate.title).split(' ').filter { it.length > 1 }.toSet()
        normalized(candidate.title) == wanted ||
            normalized(candidate.title).contains(wanted) ||
            wanted.contains(normalized(candidate.title)) ||
            wantedTerms.intersect(titleTerms).size * 2 >= wantedTerms.size.coerceAtLeast(1)
    }
}

private fun rankSearchResults(
    books: List<Audiobook>,
    query: String,
    mode: AudiobookSearchMode,
): List<Audiobook> {
    if (mode == AudiobookSearchMode.URL) return books
    val terms = query.lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
    fun score(book: Audiobook): Int {
        val field = when (mode) {
            AudiobookSearchMode.TITLE -> book.title
            AudiobookSearchMode.AUTHOR -> book.author
            AudiobookSearchMode.GENRE -> book.genres.joinToString(" ")
            AudiobookSearchMode.URL -> ""
        }.lowercase()
        return terms.fold(0) { total, term -> total +
            when {
                field == query.lowercase() -> 12
                field.startsWith(query.lowercase()) -> 8
                field.contains(term) -> 3
                else -> 0
            }
        }
    }
    return books.withIndex().sortedWith(
        compareByDescending<IndexedValue<Audiobook>> { score(it.value) }.thenBy { it.index },
    ).map { it.value }
}

@Composable
private fun BookDebridTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF8AB4FF),
            onPrimary = Color(0xFF002A5F),
            primaryContainer = Color(0xFF173F73),
            onPrimaryContainer = Color(0xFFD7E3FF),
            secondary = Color(0xFF70D1FF),
            secondaryContainer = Color(0xFF164B63),
            onSecondaryContainer = Color(0xFFC2E9FF),
            background = Color(0xFF101116),
            surface = Color(0xFF191A22),
            surfaceVariant = Color(0xFF242631),
        ),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookDebridApp(
    model: BookDebridViewModel,
    openLegacy: () -> Unit,
    exportUiQaData: () -> Unit,
    onStartupResolved: () -> Unit,
) {
    val state by model.state.collectAsState()
    val playback by PlaybackService.playback.collectAsState()
    var showPlayer by remember { mutableStateOf(false) }

    LaunchedEffect(state.hasApiKey, state.backendReady, state.error) {
        if (!state.hasApiKey || state.backendReady || state.error != null) onStartupResolved()
    }

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
            if (state.selectedBook != null) {
                TopAppBar(
                    title = { Text("Book details", maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = model::closeBook) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
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
                AppDestination.SETTINGS -> SettingsScreen(model::saveApiKey, openLegacy, exportUiQaData)
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
            if (state.homeRecommendations.isNotEmpty()) {
                item {
                    Column {
                        Text("Books you might like", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Inspired by ${state.recommendationSeedTitle}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(state.homeRecommendations, key = { it.id }) { recommendation ->
                            BookCover(recommendation) { model.openBook(recommendation) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(state: NativeUiState, model: BookDebridViewModel) {
    var searchModeExpanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Search by", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            OutlinedButton(
                onClick = { searchModeExpanded = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(state.searchMode.label, Modifier.weight(1f))
                Icon(Icons.Rounded.ExpandMore, "Choose search type")
            }
            DropdownMenu(
                expanded = searchModeExpanded,
                onDismissRequest = { searchModeExpanded = false },
            ) {
                AudiobookSearchMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.label) },
                        onClick = {
                            model.setSearchMode(mode)
                            searchModeExpanded = false
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = model::setSearchQuery,
            label = { Text(state.searchMode.fieldLabel) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.searchMode == AudiobookSearchMode.GENRE) {
            val typedGenre = state.searchQuery.trim()
            val suggestions = CANONICAL_AUDIOBOOK_GENRES
                .filter { typedGenre.isBlank() || it.contains(typedGenre, ignoreCase = true) }
                .sortedBy { !it.startsWith(typedGenre, ignoreCase = true) }
                .take(8)
            if (suggestions.isNotEmpty()) {
                Text(
                    "Genre & format suggestions",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(suggestions) { genre ->
                        FilterChip(
                            selected = genre.equals(state.searchQuery, ignoreCase = true),
                            onClick = { model.setSearchQuery(genre) },
                            label = { Text(genre) },
                        )
                    }
                }
            }
        }
        Button(
            onClick = model::search,
            enabled = state.searchQuery.isNotBlank() && !state.searching,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        ) {
            Text(
                if (state.searching) "Searching…"
                else if (state.searchMode == AudiobookSearchMode.URL) "Open audiobook"
                else "Search by ${state.searchMode.label.lowercase()}",
            )
        }
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
    ) {
        item { Text("My Books", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        items(books, key = { it.id }) { BookRow(it) { model.openBook(it) } }
    }
}

@Composable
private fun BookDetails(state: NativeUiState, model: BookDebridViewModel) {
    val book = state.selectedBook ?: return
    var chaptersExpanded by remember(book.id) { mutableStateOf(false) }
    var showBookOptions by remember(book.id) { mutableStateOf(false) }
    val resumeChapter = model.resumeChapter(book)
    val resumePosition = model.resumePosition(book)
    val inLibrary = state.books.any { it.id == book.id }
    val description = cleanAudiobookDescription(book.description)
    val numberedOnly = book.chapters.size > 1 && book.chapters.all(::isGenericChapterTitle)
    val namedChapterPreview = book.chapters
        .filterNot(::isGenericChapterTitle)
        .map { it.title }
        .distinct()
        .take(3)

    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(16.dp)) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val compact = maxWidth < 340.dp
                        val coverWidth = if (compact) 80.dp else 96.dp
                        val coverHeight = if (compact) 120.dp else 144.dp
                        val gap = if (compact) 12.dp else 16.dp
                        Row(verticalAlignment = Alignment.Top) {
                            Cover(book.coverUrl, Modifier.size(width = coverWidth, height = coverHeight))
                            Column(Modifier.padding(start = gap).weight(1f)) {
                                Text(
                                    book.title,
                                    style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = if (compact) 4 else 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    book.author,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 5.dp),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                book.rating?.let { rating ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.padding(top = 12.dp),
                                    ) {
                                        Text(
                                            "★ %.2f".format(rating),
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                    }
                                    book.ratingsCount?.let {
                                        Text(
                                            "${compactCount(it)} ratings",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (book.genres.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 14.dp),
                        ) {
                            items(book.genres.take(6)) { genre ->
                                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                                    Text(genre, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            if (book.chapters.isEmpty()) {
                Button(
                    onClick = model::download,
                    enabled = state.transferProgress == null,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Rounded.Download, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.transferProgress == null) "Download to AllDebrid" else "Downloading…")
                }
                if (!inLibrary) TextButton(onClick = model::saveBook, modifier = Modifier.fillMaxWidth()) { Text("Save to My Books") }
            } else {
                Button(
                    onClick = { model.resume(book) },
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (resumePosition > 5_000L || resumeChapter != book.chapters.first()) "Resume listening" else "Play")
                }
                resumeChapter?.let {
                    Text(
                        chapterDisplayTitle(book, it),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
                TextButton(
                    onClick = { model.play(book, book.chapters.first(), fromBeginning = true) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start from the beginning") }
            }
        }

        state.transferProgress?.let { progress ->
            item {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(state.transferMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (state.related.isNotEmpty()) {
            item {
                Text("Books like this", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("More audiobooks you may enjoy", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(state.related, key = { it.id }) { BookCover(it) { model.openBook(it) } }
                }
            }
        }

        if (description.isNotBlank()) item {
            Column {
                Text("About this book", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            }
        }

        if (book.chapters.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth().clickable { chaptersExpanded = !chaptersExpanded },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Chapters", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (book.chapters.size == 1) "Full audiobook" else "${book.chapters.size} chapter markers",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                if (chaptersExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                if (chaptersExpanded) "Hide chapters" else "Show chapters",
                            )
                        }
                        if (numberedOnly) Text(
                            "This M4B contains numbered chapter markers but no descriptive chapter titles.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        else if (namedChapterPreview.isNotEmpty()) Text(
                            "Includes ${namedChapterPreview.joinToString(" · ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            if (book.chapters.size > 1) TextButton(
                                onClick = { chaptersExpanded = !chaptersExpanded },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (chaptersExpanded) "Hide chapter list" else "Choose a chapter") }
                            TextButton(
                                onClick = model::rescan,
                                enabled = state.transferProgress == null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("Rescan chapter metadata")
                            }
                        }
                    }
                }
            }
            if (chaptersExpanded && book.chapters.size > 1) items(book.chapters, key = { it.id }) { chapter ->
                ChapterRow(book, chapter) { model.play(book, chapter) }
            }
        }

        if (inLibrary) {
            item {
                HorizontalDivider()
                TextButton(onClick = { showBookOptions = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.MoreHoriz, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Manage this book")
                }
            }
        }
    }

    if (showBookOptions) BookManagementDialog(
        onDismiss = { showBookOptions = false },
        onRemove = { model.removeBook(false) },
        onDeleteCloud = { model.removeBook(true) },
    )
}

@Composable
private fun BookManagementDialog(onDismiss: () -> Unit, onRemove: () -> Unit, onDeleteCloud: () -> Unit) {
    var confirmation by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("Manage book", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (confirmation == null) {
                    TextButton(onClick = { confirmation = "library" }, modifier = Modifier.fillMaxWidth()) {
                        Text("Remove from My Books", modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = { confirmation = "cloud" }, modifier = Modifier.fillMaxWidth()) {
                        Text("Delete AllDebrid files", color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Done") }
                } else {
                    Text(
                        if (confirmation == "cloud")
                            "This permanently removes the AllDebrid files and the book from My Books."
                        else "This removes the book from My Books. Its AllDebrid files will remain.",
                        modifier = Modifier.padding(vertical = 14.dp),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { confirmation = null }) { Text("Cancel") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = if (confirmation == "cloud") onDeleteCloud else onRemove,
                            colors = if (confirmation == "cloud")
                                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            else ButtonDefaults.buttonColors(),
                        ) { Text(if (confirmation == "cloud") "Delete" else "Remove") }
                    }
                }
            }
        }
    }
}

private fun compactCount(value: Long): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
    value >= 1_000 -> "%.1fK".format(value / 1_000.0)
    else -> value.toString()
}

@Composable
private fun SettingsScreen(
    onSave: (String) -> Unit,
    openLegacy: () -> Unit,
    exportUiQaData: () -> Unit,
) {
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
            OutlinedButton(onClick = exportUiQaData, modifier = Modifier.fillMaxWidth()) {
                Text("Export UI QA data")
            }
        }
        item {
            Text(
                "Exports device layout details and book display metadata only. API keys and AllDebrid links are excluded.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
                if (book.chapters.isNotEmpty()) Text(
                    if (book.chapters.size == 1) "Full audiobook" else "${book.chapters.size} chapters",
                    style = MaterialTheme.typography.labelMedium,
                )
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
            Text(chapterDisplayTitle(book, chapter), maxLines = 2, overflow = TextOverflow.Ellipsis)
            chapter.effectiveDurationSeconds?.let { Text(formatDuration(it), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Icon(Icons.Rounded.PlayArrow, "Play ${chapter.title}")
    }
}

@Composable
private fun MiniPlayer(playback: NativePlaybackState, open: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 4.dp) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = open)) {
                Text(playback.title.ifBlank { "Audiobook" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(playback.bookTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            IconButton(onClick = { PlaybackService.seekRelative(-10_000) }, modifier = Modifier.size(42.dp)) { Icon(Icons.Rounded.Replay10, "Back 10 seconds") }
            Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                if (playback.buffering) {
                    CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
                } else IconButton(onClick = { PlaybackService.toggle() }) {
                    Icon(if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playback.playing) "Pause" else "Play", Modifier.size(30.dp))
                }
            }
            IconButton(onClick = { PlaybackService.next() }, modifier = Modifier.size(42.dp)) { Icon(Icons.Rounded.SkipNext, "Next chapter") }
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
                Spacer(Modifier.height(10.dp))
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Cover(playback.coverUrl, Modifier.fillMaxWidth(0.54f).aspectRatio(2f / 3f))
                }
                Spacer(Modifier.height(10.dp))
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
                    Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalIconButton(
                        onClick = { PlaybackService.seekRelative(-10_000) },
                        modifier = Modifier.size(60.dp),
                    ) { Icon(Icons.Rounded.Replay10, "Back 10 seconds", Modifier.size(32.dp)) }
                    Box(Modifier.size(82.dp), contentAlignment = Alignment.Center) {
                        if (playback.buffering) {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = CircleShape,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.size(50.dp), strokeWidth = 5.dp)
                                }
                            }
                        } else FilledIconButton(onClick = PlaybackService::toggle, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                if (playback.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                if (playback.playing) "Pause" else "Play",
                                Modifier.size(48.dp),
                            )
                        }
                    }
                    FilledTonalIconButton(onClick = PlaybackService::next, modifier = Modifier.size(60.dp)) {
                        Icon(Icons.Rounded.SkipNext, "Next chapter", Modifier.size(34.dp))
                    }
                }
                val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
                val currentSpeedIndex = speeds.indexOfFirst { it == playback.speed }.takeIf { it >= 0 } ?: 0
                val nextSpeed = speeds[(currentSpeedIndex + 1) % speeds.size]
                OutlinedButton(
                    onClick = { PlaybackService.setSpeed(nextSpeed) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text("Playback speed: ${playback.speed}×") }
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
