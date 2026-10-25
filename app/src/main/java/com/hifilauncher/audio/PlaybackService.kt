package com.hifilauncher.audio

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.hifilauncher.ticker.TickerHub
import com.hifilauncher.ticker.TickerPrefs
import com.hifilauncher.ticker.TickerPublisher
import com.hifilauncher.ticker.TickerSinkFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the player, the media session and the ticker publisher. Declared in the manifest
 * in Gauntlet 1 (foregroundServiceType="mediaPlayback"); Media3 starts it in the
 * foreground when playback begins, so it survives the Activity and home switching.
 *
 * Gapless: the UI hands over a whole queue (an album or a folder). ExoPlayer pre-buffers
 * the next item and joins them without a gap. FLAC and WAV are gapless by nature; MP3
 * needs the LAME/Xing delay info in the file. Never replace the queue between tracks.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var ticker: TickerPublisher
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var probeJob: Job? = null
    private lateinit var resume: ResumeStore
    private val main = Handler(Looper.getMainLooper())

    /** Runs only while playing (the CPU is awake then anyway): position saved every 30 s. */
    private val saveTick = object : Runnable {
        override fun run() {
            saveResume()
            main.postDelayed(this, SAVE_EVERY_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        resume = ResumeStore(this)
        player = buildPlayer()
        player.addListener(playerListener)
        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setSessionActivity(homePendingIntent())
            .build()

        ticker = TickerPublisher(this) { TickerSinkFactory.candidates(this, TickerPrefs.get(this)) }
        ticker.start()
        TickerHub.reload = { ticker.reload() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** This app is the launcher: swiping it away must never stop the music. */
    override fun onTaskRemoved(rootIntent: Intent?) = Unit

    override fun onDestroy() {
        TickerHub.reload = null
        main.removeCallbacks(saveTick)
        saveResume() // before the player is released
        ticker.stop()
        scope.cancel()
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }

    private fun buildPlayer(): ExoPlayer {
        // Float output keeps 24-bit PCM from being truncated to 16 bits inside ExoPlayer.
        // Whether the platform decoder delivers more than 16 bits on Android 8 is
        // something to check on the device. Set to false if you hear glitches.
        val renderers = DefaultRenderersFactory(this).setEnableAudioFloatOutput(HI_RES_FLOAT)
        return ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true, // pauses on calls and other apps, resumes properly
            )
            .setHandleAudioBecomingNoisy(true) // headphones unplugged or Bluetooth lost: pause
            .setWakeMode(C.WAKE_MODE_LOCAL)    // local files: CPU wake lock only while playing
            .build()
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            showTrack(mediaItem)
            saveResume()
        }

        // Also fires on audio focus loss and when headphones are unplugged: the ticker
        // and the saved position follow without any extra code.
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            ticker.update { it.copy(playing = isPlaying) }
            main.removeCallbacks(saveTick)
            if (isPlaying) main.postDelayed(saveTick, SAVE_EVERY_MS) else saveResume()
        }
    }

    /** Title and artist right away (from the item), then the real format once probed. */
    private fun showTrack(item: MediaItem?) {
        probeJob?.cancel()
        val md = item?.mediaMetadata
        ticker.update {
            it.copy(
                title = md?.title?.toString().orEmpty(),
                artist = md?.artist?.toString().orEmpty(),
                codec = "",
                detail = "",
                bitrateKbps = 0,
            )
        }
        val uri = item?.localConfiguration?.uri ?: return
        val durationMs = md?.extras?.getLong(Track.EXTRA_DURATION) ?: 0L
        probeJob = scope.launch {
            val info = withContext(Dispatchers.IO) {
                AudioFormatProbe.probe(applicationContext, uri, durationMs)
            }
            ticker.update {
                it.copy(codec = info.codec, detail = info.detail(), bitrateKbps = info.bitrateKbps ?: 0)
            }
        }
    }

    private fun saveResume() {
        val item = player.currentMediaItem ?: return
        val folder = item.mediaMetadata.extras?.getString(Track.EXTRA_FOLDER) ?: return
        // After the last track has ended, come back to its start rather than to its end.
        val position = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition
        resume.save(folder, item.mediaId, position)
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri
                if (uri != null) item.buildUpon().setUri(uri).build() else item
            }
            return Futures.immediateFuture(resolved.toMutableList())
        }
    }

    /** Tapping the playback notification returns to the launcher (it has no LAUNCHER entry). */
    private fun homePendingIntent(): PendingIntent {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val HI_RES_FLOAT = true
        const val SAVE_EVERY_MS = 30_000L
    }
}
