package com.freedify.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver

class PlaybackService : Service() {
    private lateinit var mediaSession: MediaSessionCompat
    private var title = "Freedify"
    private var artist = "Ready to play"
    private var album = ""
    private var playing = false
    private var positionMs = 0L
    private var durationMs = 0L
    private var playbackRate = 1f

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        createNotificationChannel()
        mediaSession = MediaSessionCompat(this, "FreedifyPlayback").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = sendCommand(COMMAND_PLAY)
                override fun onPause() = sendCommand(COMMAND_PAUSE)
                override fun onSkipToPrevious() = sendCommand(COMMAND_PREVIOUS)
                override fun onSkipToNext() = sendCommand(COMMAND_NEXT)
                override fun onSeekTo(pos: Long) = sendCommand(COMMAND_SEEK_TO, pos)
                override fun onStop() = sendCommand(COMMAND_STOP)
            })
            isActive = true
        }
        updateMediaState()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        activeInstance = null
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    private fun sendCommand(command: String, value: Long = 0L) {
        commandHandler?.invoke(command, value)
    }

    private fun applyMetadata(newTitle: String, newArtist: String, newAlbum: String) {
        title = newTitle.ifBlank { "Freedify" }
        artist = newArtist.ifBlank { "Unknown artist" }
        album = newAlbum
        updateMediaState()
        refreshNotification()
    }

    private fun applyPlaybackState(
        newPlaying: Boolean,
        newPositionMs: Long,
        newDurationMs: Long,
        newPlaybackRate: Float,
    ) {
        val stateChanged = playing != newPlaying
        playing = newPlaying
        positionMs = newPositionMs.coerceAtLeast(0)
        durationMs = newDurationMs.coerceAtLeast(0)
        playbackRate = newPlaybackRate.coerceAtLeast(0.1f)
        updateMediaState()
        if (stateChanged) refreshNotification()
    }

    private fun updateMediaState() {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
        if (durationMs > 0) metadata.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
        mediaSession.setMetadata(metadata.build())

        val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_STOP,
                )
                .setState(state, positionMs, if (playing) playbackRate else 0f, SystemClock.elapsedRealtime())
                .build(),
        )
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val playPauseAction = if (playing) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                "Pause",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PAUSE),
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                "Play",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY),
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(if (album.isBlank()) artist else "$artist • $album")
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_media_previous,
                "Previous",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
                ),
            )
            .addAction(playPauseAction)
            .addAction(
                android.R.drawable.ic_media_next,
                "Next",
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT,
                ),
            )
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Playback controls for Freedify audiobooks"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val COMMAND_PLAY = "play"
        private const val COMMAND_PAUSE = "pause"
        private const val COMMAND_PREVIOUS = "previous"
        private const val COMMAND_NEXT = "next"
        private const val COMMAND_SEEK_TO = "seekTo"
        private const val COMMAND_STOP = "stop"
        private const val CHANNEL_ID = "freedify_playback"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var commandHandler: ((String, Long) -> Unit)? = null

        @Volatile
        private var activeInstance: PlaybackService? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        fun publishMetadata(title: String, artist: String, album: String) {
            mainHandler.post { activeInstance?.applyMetadata(title, artist, album) }
        }

        fun publishPlaybackState(
            playing: Boolean,
            positionMs: Long,
            durationMs: Long,
            playbackRate: Float,
        ) {
            mainHandler.post {
                activeInstance?.applyPlaybackState(
                    playing,
                    positionMs,
                    durationMs,
                    playbackRate,
                )
            }
        }
    }
}
