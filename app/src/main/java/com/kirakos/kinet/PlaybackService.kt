package com.kirakos.kinet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Foreground media playback service powering continuous background audio,
 * Android lockscreen/shade controls, and Google's Material You squiggly water-wave
 * scrubbing timeline on Android 13+ (API 33+).
 */
class PlaybackService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: NotificationManager

    private var observationJob: Job? = null
    private var lastReportedPositionMs = 0L
    private var lastReportedRealtimeMs = 0L

    companion object {
        const val CHANNEL_ID = "kinet_playback_channel"
        const val NOTIFICATION_ID = 4040

        const val ACTION_PLAY = "com.kirakos.kinet.action.PLAY"
        const val ACTION_PAUSE = "com.kirakos.kinet.action.PAUSE"
        const val ACTION_TOGGLE_PLAY = "com.kirakos.kinet.action.TOGGLE_PLAY"
        const val ACTION_REWIND_5 = "com.kirakos.kinet.action.REWIND_5"
        const val ACTION_FORWARD_5 = "com.kirakos.kinet.action.FORWARD_5"
        const val ACTION_STOP = "com.kirakos.kinet.action.STOP"

        var activePlayer: DualStemPlayer? = null

        fun start(context: Context, player: DualStemPlayer) {
            activePlayer = player
            val intent = Intent(context, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        mediaSession = MediaSessionCompat(this, "KinetPlaybackService").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    activePlayer?.play()
                }

                override fun onPause() {
                    activePlayer?.pause()
                }

                override fun onSeekTo(pos: Long) {
                    activePlayer?.seekTo(pos)
                    updatePlaybackState()
                }

                override fun onRewind() {
                    activePlayer?.seekRelative(-5000L)
                    updatePlaybackState()
                }

                override fun onFastForward() {
                    activePlayer?.seekRelative(5000L)
                    updatePlaybackState()
                }

                override fun onStop() {
                    activePlayer?.stop()
                    stopForegroundCompat()
                    stopSelf()
                }

                override fun onCustomAction(action: String?, extras: Bundle?) {
                    when (action) {
                        ACTION_REWIND_5 -> {
                            activePlayer?.seekRelative(-5000L)
                            updatePlaybackState()
                        }
                        ACTION_FORWARD_5 -> {
                            activePlayer?.seekRelative(5000L)
                            updatePlaybackState()
                        }
                    }
                }
            })
            isActive = true
        }

        startObservingPlayer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                activePlayer?.stop()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PLAY -> activePlayer?.play()
            ACTION_PAUSE -> activePlayer?.pause()
            ACTION_TOGGLE_PLAY -> activePlayer?.togglePlayPause()
            ACTION_REWIND_5 -> {
                activePlayer?.seekRelative(-5000L)
                updatePlaybackState()
            }
            ACTION_FORWARD_5 -> {
                activePlayer?.seekRelative(5000L)
                updatePlaybackState()
            }
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        updatePlaybackState()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observationJob?.cancel()
        serviceScope.cancel()
        mediaSession.isActive = false
        mediaSession.release()
        stopForegroundCompat()
        super.onDestroy()
    }

    private fun startObservingPlayer() {
        val player = activePlayer ?: return
        observationJob?.cancel()
        observationJob = serviceScope.launch {
            launch {
                player.currentProject.collectLatest { proj ->
                    if (proj == null) {
                        stopForegroundCompat()
                        stopSelf()
                    } else {
                        updateMetadata(proj, player.durationMs.value)
                        updatePlaybackState()
                        postNotification()
                    }
                }
            }
            launch {
                player.isPlaying.collectLatest {
                    updatePlaybackState()
                    postNotification()
                }
            }
            launch {
                player.durationMs.collectLatest { dur ->
                    player.currentProject.value?.let { proj ->
                        updateMetadata(proj, dur)
                        updatePlaybackState()
                    }
                }
            }
            launch {
                player.positionMs.collectLatest { pos ->
                    val now = SystemClock.elapsedRealtime()
                    val isPlaying = player.isPlaying.value
                    val expectedPos = if (isPlaying && lastReportedRealtimeMs > 0) {
                        lastReportedPositionMs + (now - lastReportedRealtimeMs)
                    } else {
                        lastReportedPositionMs
                    }
                    val drift = abs(pos - expectedPos)

                    // Re-publish state on loop jump, seek, or if drift exceeds 2 seconds
                    if (drift > 1500L || now - lastReportedRealtimeMs > 2500L) {
                        updatePlaybackState()
                    }
                }
            }
        }
    }

    private fun updateMetadata(project: SongProject, durationMs: Long) {
        val artist = if (project.artist.isNotBlank()) project.artist else "Kinet Stems"
        val meta = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, project.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Kinet")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, maxOf(1L, durationMs))
            .build()
        mediaSession.setMetadata(meta)
    }

    private fun updatePlaybackState() {
        val player = activePlayer ?: return
        val isPlaying = player.isPlaying.value
        val position = player.positionMs.value
        val now = SystemClock.elapsedRealtime()

        lastReportedPositionMs = position
        lastReportedRealtimeMs = now

        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val playbackSpeed = if (isPlaying) 1.0f else 0.0f

        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_FAST_FORWARD or
                PlaybackStateCompat.ACTION_REWIND or
                PlaybackStateCompat.ACTION_STOP

        val rewindCustomAction = PlaybackStateCompat.CustomAction.Builder(
            ACTION_REWIND_5,
            "-5s",
            R.drawable.ic_replay_5
        ).build()

        val forwardCustomAction = PlaybackStateCompat.CustomAction.Builder(
            ACTION_FORWARD_5,
            "+5s",
            R.drawable.ic_forward_5
        ).build()

        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, position, playbackSpeed, now)
            .setActions(actions)
            .addCustomAction(rewindCustomAction)
            .addCustomAction(forwardCustomAction)
            .build()

        mediaSession.setPlaybackState(playbackState)
    }

    private fun buildNotification(): Notification {
        val player = activePlayer
        val project = player?.currentProject?.value
        val isPlaying = player?.isPlaying?.value ?: false

        val title = project?.title ?: "Kinet"
        val artist = if (project != null && project.artist.isNotBlank()) project.artist else "Stem Player"

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val rewindIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PlaybackService::class.java).apply { action = ACTION_REWIND_5 },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, PlaybackService::class.java).apply { action = ACTION_TOGGLE_PLAY },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val forwardIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, PlaybackService::class.java).apply { action = ACTION_FORWARD_5 },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            4,
            Intent(this, PlaybackService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val playPauseIcon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val playPauseTitle = if (isPlaying) "Pause" else "Play"

        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(mediaSession.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)
            .setShowCancelButton(true)
            .setCancelButtonIntent(stopIntent)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setStyle(mediaStyle)
            .setSmallIcon(R.drawable.ic_stem_music)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(openAppIntent)
            .setDeleteIntent(stopIntent)
            .addAction(R.drawable.ic_replay_5, "-5s", rewindIntent)
            .addAction(playPauseIcon, playPauseTitle, playPauseIntent)
            .addAction(R.drawable.ic_forward_5, "+5s", forwardIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .build()
    }

    private fun postNotification() {
        try {
            val notification = buildNotification()
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playback controls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows now playing controls and timeline scrubbing"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
