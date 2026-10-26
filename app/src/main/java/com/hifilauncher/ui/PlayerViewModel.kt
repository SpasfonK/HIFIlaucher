package com.hifilauncher.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.hifilauncher.audio.CoverLoader
import com.hifilauncher.audio.LibraryRepository
import com.hifilauncher.audio.LibraryTree
import com.hifilauncher.audio.PlaybackService
import com.hifilauncher.audio.ResumeStore
import com.hifilauncher.audio.Track
import com.hifilauncher.audio.toMediaItem
import com.hifilauncher.ticker.TickerHub
import com.hifilauncher.ticker.TickerPrefs
import com.hifilauncher.ticker.buildDiagnostics
import com.hifilauncher.ticker.overlayPermissionIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bridge between the Gauntlet 2 UI and the playback service. Holds no audio logic: it
 * sends commands to the MediaController and turns its state into PlayerUiState.
 */
class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context = app.applicationContext
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var controller: MediaController? = null
    private var tracks: List<Track> = emptyList()
    private var volumeAccum = 0
    private var coverJob: Job? = null
    private var coverFor: String? = null
    private var restored = false
    private val resume = ResumeStore(ctx)
    private var rescanJob: Job? = null

    // Files copied over USB show up without restarting the launcher.
    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            // The media scanner fires once per file: wait for the burst to end.
            rescanJob?.cancel()
            rescanJob = viewModelScope.launch {
                delay(3_000)
                tracks = LibraryRepository(ctx.contentResolver).scan()
                _tree.value = LibraryTree(tracks)
            }
        }
    }

    private val _ui = MutableStateFlow(
        PlayerUiState(
            title = "Nothing playing", artist = "", album = "",
            codec = "", format = "", bitrateKbps = 0,
            positionMs = 0, durationMs = 0, isPlaying = false,
            volumePct = volumePct(), clock = "", batteryPct = 0,
        ),
    )
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    private val _diagnostics = MutableStateFlow<String?>(null)
    val diagnostics: StateFlow<String?> = _diagnostics.asStateFlow()

    // Library browser. nav == null means the player is showing.
    private val _tree = MutableStateFlow(LibraryTree(emptyList()))
    val tree: StateFlow<LibraryTree> = _tree.asStateFlow()
    private val _nav = MutableStateFlow<LibraryNav?>(null)
    val nav: StateFlow<LibraryNav?> = _nav.asStateFlow()

    /**
     * Position ticks once a second, and only while a screen is collecting it (stateIn with
     * WhileSubscribed). Screen off means no polling at all.
     */
    val position: StateFlow<Long> = flow {
        while (true) {
            emit(controller?.currentPosition ?: 0L)
            delay(1_000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val actions = PlayerActions(
        onPlayPause = ::togglePlay,
        onPrev = { controller?.seekToPrevious() }, // restarts the track if past 3 s, else previous
        onNext = { controller?.seekToNextMediaItem() },
        onWheelSteps = ::onWheelSteps,
        onWheelRelease = ::onWheelRelease,
        onToggleMode = {
            _ui.update {
                it.copy(
                    wheelMode = if (it.wheelMode == WheelMode.VOLUME) WheelMode.SKIP else WheelMode.VOLUME,
                    pendingSkip = 0,
                )
            }
        },
        onOpenDiagnostics = { _diagnostics.value = buildDiagnostics(ctx) },
        onOpenLibrary = ::openLibrary,
    )

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = syncFrom(player)
    }

    init {
        ctx.contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mediaObserver,
        )
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()?.also {
                it.addListener(playerListener)
                syncFrom(it)
            }
            tryRestore() // after the assignment, so the controller is visible to it
        }, ContextCompat.getMainExecutor(ctx))

        // Codec, bitrate, clock and battery come from the same state the secondary screen shows.
        viewModelScope.launch {
            TickerHub.state.collect { t ->
                _ui.update {
                    it.copy(
                        codec = t.codec, format = t.detail, bitrateKbps = t.bitrateKbps,
                        clock = t.clock, batteryPct = t.batteryPct,
                    )
                }
            }
        }
    }

    fun loadLibrary() {
        if (tracks.isNotEmpty()) return
        viewModelScope.launch {
            tracks = LibraryRepository(ctx.contentResolver).scan()
            _tree.value = LibraryTree(tracks)
            tryRestore()
        }
    }

    /**
     * After a reboot or a process death the service has an empty queue: put back the last
     * folder, track and position, paused. It needs both the controller and the library, so
     * it runs when either arrives and acts only once.
     */
    private fun tryRestore() {
        val c = controller ?: return
        if (restored || tracks.isEmpty()) return
        restored = true
        if (c.mediaItemCount > 0) return // the service is already playing something
        val snapshot = resume.load() ?: return
        val track = tracks.firstOrNull { it.id.toString() == snapshot.mediaId } ?: return
        val queue = tracks.filter { it.folder == track.folder }.take(MAX_QUEUE)
        val index = queue.indexOf(track)
        if (index < 0) return
        c.setMediaItems(queue.map { it.toMediaItem() }, index, snapshot.positionMs)
        c.prepare() // paused: the music starts when the user asks for it
    }

    /**
     * The queue is the track's own folder (capped), not the whole library: a few dozen
     * items cross the binder cheaply, and an album is the gapless use case anyway.
     */
    fun playTrack(track: Track) {
        val c = controller ?: return
        val queue = tracks.filter { it.folder == track.folder }.take(MAX_QUEUE)
        c.setMediaItems(queue.map { it.toMediaItem() }, queue.indexOf(track).coerceAtLeast(0), 0L)
        c.prepare()
        c.play()
    }

    private fun togglePlay() {
        val c = controller ?: return
        when {
            c.mediaItemCount == 0 -> tracks.firstOrNull()?.let { playTrack(it) }
            c.isPlaying -> c.pause()
            else -> c.play()
        }
    }

    private fun syncFrom(p: Player) {
        val md = p.mediaMetadata
        _ui.update {
            it.copy(
                title = md.title?.toString() ?: "Nothing playing",
                artist = md.artist?.toString().orEmpty(),
                album = md.albumTitle?.toString().orEmpty(),
                isPlaying = p.isPlaying,
                mediaId = p.currentMediaItem?.mediaId,
                durationMs = p.duration.coerceAtLeast(0L), // TIME_UNSET is a huge negative
                volumePct = volumePct(),                   // also catches the hardware keys
            )
        }
        val mediaId = p.currentMediaItem?.mediaId
        if (mediaId != coverFor) {
            coverFor = mediaId
            coverJob?.cancel()
            val track = tracks.firstOrNull { it.id.toString() == mediaId }
            coverJob = viewModelScope.launch {
                val cover = track?.let { withContext(Dispatchers.IO) { CoverLoader.load(ctx, it) } }
                _ui.update { it.copy(cover = cover) }
            }
        }
    }

    private fun onWheelSteps(steps: Int, mode: WheelMode) {
        when (mode) {
            WheelMode.VOLUME -> {
                // The system music stream has about 15 steps: two wheel ticks per step keeps
                // the full range around 300 degrees. Software gain would give finer control
                // but throws bits away, so it is deliberately not used.
                volumeAccum += steps
                val delta = volumeAccum / 2
                if (delta != 0) {
                    volumeAccum -= delta * 2
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val next = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + delta).coerceIn(0, max)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                }
                _ui.update { it.copy(volumePct = volumePct()) }
            }
            // The skip is only previewed while the wheel turns, and committed on release.
            WheelMode.SKIP -> _ui.update { it.copy(pendingSkip = it.pendingSkip + steps) }
            WheelMode.LIST -> Unit
        }
    }

    private fun onWheelRelease(mode: WheelMode) {
        if (mode != WheelMode.SKIP) return
        val pending = _ui.value.pendingSkip
        _ui.update { it.copy(pendingSkip = 0) }
        val c = controller ?: return
        if (pending != 0 && c.mediaItemCount > 0) {
            c.seekTo((c.currentMediaItemIndex + pending).coerceIn(0, c.mediaItemCount - 1), 0L)
        }
    }

    private fun volumePct(): Int {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    // Diagnostics screen actions (no adb needed to explore the secondary screen).

    fun closeDiagnostics() {
        _diagnostics.value = null
    }

    fun cycleTickerMode() {
        TickerPrefs.cycle(ctx)
        TickerHub.reload?.invoke()
        viewModelScope.launch {
            delay(400) // let the publisher switch sink, then refresh the report
            if (_diagnostics.value != null) _diagnostics.value = buildDiagnostics(ctx)
        }
    }

    fun requestOverlayPermission() {
        ctx.startActivity(overlayPermissionIntent(ctx))
    }

    // Library navigation

    /** Opens on the folder of the track that is playing, with that track highlighted. */
    fun openLibrary() {
        val root = _tree.value.root
        val playing = tracks.firstOrNull { it.id.toString() == _ui.value.mediaId }
        val path = playing?.folder?.takeIf { it.startsWith(root) } ?: root
        _nav.value = LibraryNav(path, hint = _ui.value.mediaId?.let { "s:$it" })
    }

    fun libraryOpen(path: String) {
        _nav.value = LibraryNav(path, hint = null)
    }

    /** One level up, highlighting the folder we just left. At the top it returns to the player. */
    fun libraryUp() {
        val current = _nav.value ?: return
        val parent = _tree.value.parent(current.path)
        _nav.value = if (parent == null) null else LibraryNav(parent, hint = "f:${current.path}")
    }

    fun closeLibrary() {
        _nav.value = null
    }

    /** Home key: back to the player from anywhere. */
    fun goHome() {
        _diagnostics.value = null
        _nav.value = null
    }

    fun playFromLibrary(track: Track) {
        playTrack(track)
        _nav.value = null
    }

    /** Plays everything under a folder (capped), subfolders included. */
    fun playFolder(path: String) {
        val c = controller ?: return
        val queue = _tree.value.tracksUnder(path).take(MAX_QUEUE)
        if (queue.isEmpty()) return
        c.setMediaItems(queue.map { it.toMediaItem() }, 0, 0L)
        c.prepare()
        c.play()
        _nav.value = null
    }

    /** Escape hatches: switch the default home app back, or open Settings, from inside the app. */
    fun openHomeSettings() = startSettings(Settings.ACTION_HOME_SETTINGS)

    fun openSystemSettings() = startSettings(Settings.ACTION_SETTINGS)

    private fun startSettings(action: String) {
        // Some vendor ROMs do not implement every settings screen: ignore, don't crash.
        runCatching { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    override fun onCleared() {
        ctx.contentResolver.unregisterContentObserver(mediaObserver)
        controller?.release()
        super.onCleared()
    }

    private companion object {
        const val MAX_QUEUE = 300
    }
}
