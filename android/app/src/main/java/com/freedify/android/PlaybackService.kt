package com.freedify.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NativePlaybackState(
    val bookId: String = "",
    val chapterId: String = "",
    val title: String = "",
    val author: String = "",
    val bookTitle: String = "",
    val coverUrl: String = "",
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val error: String? = null,
)

class PlaybackService : MediaBrowserServiceCompat() {
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var store: AudiobookStore
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var currentBook: Audiobook? = null
    private var currentChapter: AudiobookChapter? = null
    private var foreground = false
    private var legacyTitle = "BookDebrid"
    private var legacyAuthor = "Ready to play"
    private var legacyAlbum = ""
    private var usingLegacyPlayer = false
    private var lastPersistElapsedMs = 0L
    private val storeListener = {
        notifyChildrenChanged(ROOT_ID)
        notifyChildrenChanged(BOOKS_ID)
    }

    private val progressTicker = object : Runnable {
        override fun run() {
            val mediaPlayer = player ?: return
            val chapter = currentChapter ?: return
            if (mediaPlayer.isPlaying) {
                val relative = (mediaPlayer.currentPosition - chapter.startMs()).coerceAtLeast(0L)
                val duration = chapter.durationMs(mediaPlayer.duration)
                if (duration > 0 && relative >= duration - 250) {
                    playAdjacent(1)
                    return
                }
                publishNativeState(relative, duration, playing = true)
                val now = SystemClock.elapsedRealtime()
                if (now - lastPersistElapsedMs >= 5_000) {
                    persist(relative)
                    lastPersistElapsedMs = now
                }
            }
            handler.postDelayed(this, 750)
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        store = AudiobookStore.get(this)
        createNotificationChannel()
        mediaSession = MediaSessionCompat(this, "BookDebridPlayback").apply {
            setSessionActivity(
                PendingIntent.getActivity(
                    this@PlaybackService,
                    0,
                    Intent(this@PlaybackService, NativeMainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (player != null || commandHandler == null) resumeNative()
                    else commandHandler?.invoke("play", 0L)
                }
                override fun onPause() {
                    if (player != null) pauseNative() else commandHandler?.invoke("pause", 0L)
                }
                override fun onStop() {
                    if (player != null) stopNative() else commandHandler?.invoke("stop", 0L)
                }
                override fun onSeekTo(pos: Long) {
                    if (player != null) seekNative(pos) else commandHandler?.invoke("seekTo", pos)
                }
                override fun onSkipToNext() {
                    if (player != null) playAdjacent(1) else commandHandler?.invoke("next", 0L)
                }
                override fun onSkipToPrevious() {
                    if (player != null) previousOrRestart() else commandHandler?.invoke("previous", 0L)
                }
                override fun onPlayFromMediaId(mediaId: String, extras: Bundle?) = playMediaId(mediaId)
                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    val words = query.orEmpty().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
                    val book = store.books().firstOrNull { candidate ->
                        val haystack = "${candidate.title} ${candidate.author}".lowercase()
                        words.isNotEmpty() && words.all(haystack::contains)
                    } ?: store.book(store.snapshot().bookId) ?: store.books().firstOrNull()
                    val chapter = if (book?.id == store.snapshot().bookId) {
                        book.chapters.firstOrNull { it.id == store.snapshot().chapterId }
                    } else null
                    if (book != null) book.chapters.firstOrNull()?.let {
                        playChapter(book.id, chapter?.id ?: it.id)
                    }
                }
            })
            isActive = true
        }
        sessionToken = mediaSession.sessionToken
        store.addListener(storeListener)
        restoreMetadata()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        when (intent?.action) {
            ACTION_PLAY_CHAPTER -> playChapter(
                intent.getStringExtra(EXTRA_BOOK_ID),
                intent.getStringExtra(EXTRA_CHAPTER_ID),
                intent.getBooleanExtra(EXTRA_RESTART, false),
            )
        }
        return START_STICKY
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        BrowserRoot(ROOT_ID, null)

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
    ) {
        val items = when {
            parentId == ROOT_ID -> rootItems()
            parentId == CONTINUE_ID -> continueItems()
            parentId == BOOKS_ID -> store.books().filter { it.chapters.isNotEmpty() }.map(::bookItem)
            parentId.startsWith(BOOK_PREFIX) -> {
                val book = store.book(parentId.removePrefix(BOOK_PREFIX))
                book?.chapters?.map { chapterItem(book, it) }.orEmpty()
            }
            else -> emptyList()
        }
        result.sendResult(items.toMutableList())
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        persist(_playback.value.positionMs)
        releasePlayer()
        mediaSession.isActive = false
        mediaSession.release()
        store.removeListener(storeListener)
        activeInstance = null
        super.onDestroy()
    }

    private fun playMediaId(mediaId: String) {
        val match = store.books().firstNotNullOfOrNull { book ->
            book.chapters.firstOrNull { it.id == mediaId }?.let { book to it }
        }
        if (match == null) setError("This chapter is no longer in My Books")
        else playChapter(match.first.id, match.second.id)
    }

    private fun playChapter(bookId: String?, chapterId: String?, restart: Boolean = false) {
        val book = bookId?.let(store::book)
        val chapter = book?.chapters?.firstOrNull { it.id == chapterId }
        if (book == null || chapter == null) {
            setError("The selected chapter could not be found")
            return
        }
        val apiKey = SecureSettings(this).getApiKey()
        if (apiKey.isNullOrBlank()) {
            setError("Open BookDebrid on your phone and add the AllDebrid API key")
            return
        }
        currentBook = book
        currentChapter = chapter
        notifyChildrenChanged(ROOT_ID)
        notifyChildrenChanged(CONTINUE_ID)
        usingLegacyPlayer = false
        val resume = if (restart) 0L else store.progress(chapter.id)
        if (restart) store.updateProgress(chapter.id, 0L)
        publishNativeState(resume, chapter.effectiveDurationSeconds?.times(1000)?.toLong() ?: 0L, buffering = true)
        ensureForeground()
        BackendManager.startOrUpdate(
            applicationContext,
            apiKey,
            onReady = { preparePlayer(book, chapter, resume) },
            onError = ::setError,
        )
    }

    private fun preparePlayer(book: Audiobook, chapter: AudiobookChapter, resumeMs: Long) {
        releasePlayer()
        val mediaPlayer = ExoPlayer.Builder(this).build()
        player = mediaPlayer
        mediaPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            true,
        )
        mediaPlayer.setHandleAudioBecomingNoisy(true)
        mediaPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        val duration = chapter.durationMs(mediaPlayer.duration)
                        publishNativeState(
                            (mediaPlayer.currentPosition - chapter.startMs()).coerceAtLeast(0L),
                            duration,
                            playing = mediaPlayer.isPlaying,
                        )
                    }
                    Player.STATE_ENDED -> playAdjacent(1)
                    Player.STATE_BUFFERING -> publishNativeState(
                        (mediaPlayer.currentPosition - chapter.startMs()).coerceAtLeast(0L),
                        chapter.durationMs(mediaPlayer.duration),
                        buffering = true,
                    )
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publishNativeState(
                    (mediaPlayer.currentPosition - chapter.startMs()).coerceAtLeast(0L),
                    chapter.durationMs(mediaPlayer.duration),
                    playing = isPlaying,
                )
                if (isPlaying) {
                    handler.removeCallbacks(progressTicker)
                    handler.post(progressTicker)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                setError(playbackErrorMessage(error))
            }
        })
        runCatching {
            mediaPlayer.setMediaItem(MediaItem.fromUri(BookDebridApi.streamUrl(chapter)))
            mediaPlayer.seekTo(chapter.startMs() + resumeMs)
            mediaPlayer.playbackParameters = PlaybackParameters(_playback.value.speed)
            mediaPlayer.prepare()
            mediaPlayer.playWhenReady = true
        }.onFailure { setError(it.message ?: "The audiobook stream could not be opened") }
        updateMediaSession(book, chapter, resumeMs, chapter.effectiveDurationSeconds?.times(1000)?.toLong() ?: 0L, false, true)
    }

    private fun resumeNative() {
        val mediaPlayer = player
        if (mediaPlayer == null) {
            val snapshot = store.snapshot()
            if (snapshot.bookId.isNotBlank() && snapshot.chapterId.isNotBlank()) {
                playChapter(snapshot.bookId, snapshot.chapterId)
            }
            return
        }
        mediaPlayer.play()
    }

    private fun pauseNative() {
        player?.pause()
        persist(_playback.value.positionMs)
        publishNativeState(_playback.value.positionMs, _playback.value.durationMs, playing = false)
    }

    private fun stopNative() {
        pauseNative()
        releasePlayer()
        stopForeground(STOP_FOREGROUND_DETACH)
        foreground = false
    }

    private fun seekNative(relativeMs: Long) {
        val mediaPlayer = player ?: return
        val chapter = currentChapter ?: return
        val duration = chapter.durationMs(mediaPlayer.duration)
        val safeRelative = relativeMs.coerceIn(0L, duration.coerceAtLeast(0L))
        mediaPlayer.seekTo(chapter.startMs() + safeRelative)
        publishNativeState(safeRelative, duration, playing = mediaPlayer.isPlaying)
        persist(safeRelative)
    }

    private fun changeSpeed(speed: Float) {
        val safeSpeed = speed.coerceIn(0.5f, 3f)
        player?.playbackParameters = PlaybackParameters(safeSpeed)
        _playback.value = _playback.value.copy(speed = safeSpeed)
        persist(_playback.value.positionMs)
        currentBook?.let { book -> currentChapter?.let { chapter ->
            updateMediaSession(book, chapter, _playback.value.positionMs, _playback.value.durationMs, _playback.value.playing, false)
        } }
        refreshNotification()
    }

    private fun previousOrRestart() {
        if (_playback.value.positionMs > 10_000) seekNative(0) else playAdjacent(-1)
    }

    private fun playAdjacent(offset: Int) {
        val book = currentBook ?: return
        val chapter = currentChapter ?: return
        val index = book.chapters.indexOfFirst { it.id == chapter.id }
        val adjacent = book.chapters.getOrNull(index + offset)
        if (adjacent == null) {
            pauseNative()
            if (offset > 0) seekNative(_playback.value.durationMs)
        } else playChapter(book.id, adjacent.id)
    }

    private fun publishNativeState(
        position: Long,
        duration: Long,
        playing: Boolean = false,
        buffering: Boolean = false,
    ) {
        val book = currentBook ?: return
        val chapter = currentChapter ?: return
        _playback.value = NativePlaybackState(
            bookId = book.id,
            chapterId = chapter.id,
            title = chapter.title,
            author = book.author,
            bookTitle = book.title,
            coverUrl = book.coverUrl,
            playing = playing,
            buffering = buffering,
            positionMs = position.coerceAtLeast(0),
            durationMs = duration.coerceAtLeast(0),
            speed = _playback.value.speed,
        )
        updateMediaSession(book, chapter, position, duration, playing, buffering)
        refreshNotification()
    }

    private fun updateMediaSession(
        book: Audiobook,
        chapter: AudiobookChapter,
        position: Long,
        duration: Long,
        playing: Boolean,
        buffering: Boolean,
    ) {
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, chapter.id)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, chapter.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, book.author)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, book.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, book.coverUrl)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                .build(),
        )
        val state = when {
            buffering -> PlaybackStateCompat.STATE_BUFFERING
            playing -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PLAYBACK_ACTIONS)
                .setState(state, position, if (playing) _playback.value.speed else 0f, SystemClock.elapsedRealtime())
                .build(),
        )
    }

    private fun applyLegacyMetadata(title: String, artist: String, album: String) {
        if (player != null) releasePlayer()
        usingLegacyPlayer = true
        legacyTitle = title.ifBlank { "BookDebrid" }
        legacyAuthor = artist.ifBlank { "Unknown artist" }
        legacyAlbum = album
    }

    private fun applyLegacyPlayback(playing: Boolean, position: Long, duration: Long, speed: Float) {
        if (!usingLegacyPlayer) return
        _playback.value = NativePlaybackState(
            title = legacyTitle,
            author = legacyAuthor,
            bookTitle = legacyAlbum,
            playing = playing,
            positionMs = position,
            durationMs = duration,
            speed = speed,
        )
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, legacyTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, legacyAuthor)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, legacyAlbum)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                .build(),
        )
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder().setActions(PLAYBACK_ACTIONS)
                .setState(if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED, position, speed)
                .build(),
        )
        refreshNotification()
    }

    private fun restoreMetadata() {
        val snapshot = store.snapshot()
        val book = store.book(snapshot.bookId)
        val chapter = book?.chapters?.firstOrNull { it.id == snapshot.chapterId }
        if (book != null && chapter != null) {
            currentBook = book
            currentChapter = chapter
            _playback.value = NativePlaybackState(
                bookId = book.id,
                chapterId = chapter.id,
                title = chapter.title,
                author = book.author,
                bookTitle = book.title,
                coverUrl = book.coverUrl,
                positionMs = snapshot.positionMs,
                durationMs = chapter.effectiveDurationSeconds?.times(1000)?.toLong() ?: 0L,
                speed = snapshot.speed,
            )
            updateMediaSession(book, chapter, snapshot.positionMs, _playback.value.durationMs, false, false)
        } else {
            mediaSession.setPlaybackState(
                PlaybackStateCompat.Builder().setActions(PLAYBACK_ACTIONS)
                    .setState(PlaybackStateCompat.STATE_NONE, 0, 0f).build(),
            )
        }
    }

    private fun persist(position: Long) {
        val book = currentBook ?: return
        val chapter = currentChapter ?: return
        store.updateSnapshot(book.id, chapter.id, position, _playback.value.speed)
    }

    private fun setError(message: String) {
        _playback.value = _playback.value.copy(playing = false, buffering = false, error = message)
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder().setActions(PLAYBACK_ACTIONS)
                .setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, message)
                .setState(PlaybackStateCompat.STATE_ERROR, _playback.value.positionMs, 0f)
                .build(),
        )
        refreshNotification()
    }

    private fun releasePlayer() {
        handler.removeCallbacks(progressTicker)
        player?.release()
        player = null
    }

    private fun playbackErrorMessage(error: PlaybackException): String {
        val causeMessages = generateSequence<Throwable>(error) { it.cause }
            .mapNotNull { it.message?.takeIf(String::isNotBlank) }
            .distinct()
            .take(3)
            .joinToString(" — ")
        return if (causeMessages.isBlank()) {
            "Audiobook playback failed (${error.errorCodeName})"
        } else {
            "Audiobook playback failed: $causeMessages"
        }
    }

    private fun rootItems(): List<MediaBrowserCompat.MediaItem> {
        val items = mutableListOf<MediaBrowserCompat.MediaItem>()
        if (store.snapshot().chapterId.isNotBlank()) {
            items += browsable(CONTINUE_ID, "Continue listening", "Resume your current audiobook")
        }
        items += browsable(BOOKS_ID, "My Books", "Downloaded audiobooks")
        return items
    }

    private fun continueItems(): List<MediaBrowserCompat.MediaItem> {
        val snapshot = store.snapshot()
        val book = store.book(snapshot.bookId) ?: return emptyList()
        val chapter = book.chapters.firstOrNull { it.id == snapshot.chapterId } ?: return emptyList()
        return listOf(chapterItem(book, chapter))
    }

    private fun bookItem(book: Audiobook) = MediaBrowserCompat.MediaItem(
        MediaDescriptionCompat.Builder()
            .setMediaId("$BOOK_PREFIX${book.id}")
            .setTitle(book.title)
            .setSubtitle(book.author)
            .setIconUri(book.coverUrl.takeIf(String::isNotBlank)?.let(Uri::parse))
            .build(),
        MediaBrowserCompat.MediaItem.FLAG_BROWSABLE,
    )

    private fun chapterItem(book: Audiobook, chapter: AudiobookChapter) = MediaBrowserCompat.MediaItem(
        MediaDescriptionCompat.Builder()
            .setMediaId(chapter.id)
            .setTitle(chapter.title)
            .setSubtitle(book.title)
            .setIconUri(book.coverUrl.takeIf(String::isNotBlank)?.let(Uri::parse))
            .build(),
        MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
    )

    private fun browsable(id: String, title: String, subtitle: String) = MediaBrowserCompat.MediaItem(
        MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setSubtitle(subtitle).build(),
        MediaBrowserCompat.MediaItem.FLAG_BROWSABLE,
    )

    private fun buildNotification(): Notification {
        val state = _playback.value
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, NativeMainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val playPause = NotificationCompat.Action(
            if (state.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (state.playing) "Pause" else "Play",
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                this,
                if (state.playing) PlaybackStateCompat.ACTION_PAUSE else PlaybackStateCompat.ACTION_PLAY,
            ),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(state.title.ifBlank { "BookDebrid" })
            .setContentText(state.bookTitle.ifBlank { state.author.ifBlank { "Ready to listen" } })
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(state.playing)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_media_previous,
                "Previous chapter",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS),
            )
            .addAction(playPause)
            .addAction(
                android.R.drawable.ic_media_next,
                "Next chapter",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT),
            )
            .setStyle(MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun ensureForeground() {
        if (!foreground) {
            startForeground(NOTIFICATION_ID, buildNotification())
            foreground = true
        }
    }

    private fun refreshNotification() {
        if (foreground) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Audiobook playback controls"
                    setShowBadge(false)
                },
            )
        }
    }

    companion object {
        private const val ROOT_ID = "bookdebrid_root"
        private const val CONTINUE_ID = "bookdebrid_continue"
        private const val BOOKS_ID = "bookdebrid_books"
        private const val BOOK_PREFIX = "book:"
        private const val CHANNEL_ID = "freedify_playback"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_INIT = "com.freedify.android.INIT_PLAYBACK"
        private const val ACTION_PLAY_CHAPTER = "com.freedify.android.PLAY_CHAPTER"
        private const val EXTRA_BOOK_ID = "book_id"
        private const val EXTRA_CHAPTER_ID = "chapter_id"
        private const val EXTRA_RESTART = "restart"
        private const val PLAYBACK_ACTIONS = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_STOP or PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID

        @Volatile var commandHandler: ((String, Long) -> Unit)? = null
        @Volatile private var activeInstance: PlaybackService? = null
        private val mainHandler = Handler(Looper.getMainLooper())
        private val _playback = MutableStateFlow(NativePlaybackState())
        val playback: StateFlow<NativePlaybackState> = _playback.asStateFlow()

        fun ensureStarted(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PlaybackService::class.java).setAction(ACTION_INIT),
            )
        }

        fun play(context: Context, bookId: String, chapterId: String, fromBeginning: Boolean = false) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PlaybackService::class.java).setAction(ACTION_PLAY_CHAPTER)
                    .putExtra(EXTRA_BOOK_ID, bookId)
                    .putExtra(EXTRA_CHAPTER_ID, chapterId)
                    .putExtra(EXTRA_RESTART, fromBeginning),
            )
        }

        fun toggle() = mainHandler.post {
            activeInstance?.let { if (it.player?.isPlaying == true) it.pauseNative() else it.resumeNative() }
        }
        fun seekRelative(offsetMs: Long) = mainHandler.post {
            activeInstance?.seekNative(_playback.value.positionMs + offsetMs)
        }
        fun seekTo(positionMs: Long) = mainHandler.post { activeInstance?.seekNative(positionMs) }
        fun setSpeed(speed: Float) = mainHandler.post { activeInstance?.changeSpeed(speed) }
        fun next() = mainHandler.post { activeInstance?.playAdjacent(1) }

        fun publishMetadata(title: String, artist: String, album: String) {
            mainHandler.post { activeInstance?.applyLegacyMetadata(title, artist, album) }
        }

        fun publishPlaybackState(playing: Boolean, positionMs: Long, durationMs: Long, playbackRate: Float) {
            mainHandler.post { activeInstance?.applyLegacyPlayback(playing, positionMs, durationMs, playbackRate) }
        }
    }
}

private fun AudiobookChapter.startMs(): Long = (startSeconds * 1000).toLong()

private fun AudiobookChapter.durationMs(fullFileDurationMs: Long): Long =
    effectiveDurationSeconds?.times(1000)?.toLong()
        ?: (fullFileDurationMs - startMs()).coerceAtLeast(0L)
