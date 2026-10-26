package com.hifilauncher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * To try it on the device before Gauntlet 3, in LauncherActivity:
 *     setContent { HiFiTheme { PlayerDemo() } }
 * PlayerDemo fakes the player so you can judge the wheel's feel (tick angle,
 * acceleration, haptics) without any audio code.
 */

data class PlayerUiState(
    val title: String,
    val artist: String,
    val album: String,
    val cover: ImageBitmap? = null,
    val codec: String,
    val format: String,
    val bitrateKbps: Int,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val volumePct: Int,
    val wheelMode: WheelMode = WheelMode.VOLUME,
    /** Tracks the wheel has scrolled past but not yet committed (SKIP mode). */
    val pendingSkip: Int = 0,
    val clock: String,
    val batteryPct: Int,
    /** Id of the current item, so the library can mark and focus the playing track. */
    val mediaId: String? = null,
)

data class PlayerActions(
    val onPlayPause: () -> Unit,
    val onPrev: () -> Unit,
    val onNext: () -> Unit,
    val onWheelSteps: (steps: Int, mode: WheelMode) -> Unit,
    val onWheelRelease: (mode: WheelMode) -> Unit,
    val onToggleMode: () -> Unit,
    val onOpenDiagnostics: () -> Unit = {},
    val onOpenLibrary: () -> Unit = {},
)

@Composable
fun PlayerScreen(ui: PlayerUiState, actions: PlayerActions, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .background(HiFi.Bg)
            .systemBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        StatusStrip(ui, actions.onOpenDiagnostics, actions.onOpenLibrary)

        // The cover takes whatever height is left and stays square.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            CoverArt(
                ui.cover,
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f, matchHeightConstraintsFirst = true)
                    .clickable(onClick = actions.onOpenLibrary),
            )
        }

        TrackInfo(ui)
        Spacer(Modifier.height(10.dp))
        FormatRow(ui, actions.onToggleMode)
        Spacer(Modifier.height(10.dp))
        ProgressLine(ui.positionMs, ui.durationMs)

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton(IconKind.Prev, actions.onPrev)
            VirtualWheel(
                mode = ui.wheelMode,
                isPlaying = ui.isPlaying,
                onSteps = { actions.onWheelSteps(it, ui.wheelMode) },
                onRelease = { actions.onWheelRelease(ui.wheelMode) },
                onHubClick = actions.onPlayPause,
            )
            TransportButton(IconKind.Next, actions.onNext)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatusStrip(ui: PlayerUiState, onLongPress: () -> Unit, onOpenLibrary: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            ui.clock,
            color = HiFi.TextHi,
            fontSize = 14.sp,
            style = HiFi.Tnum,
            // Long-press anywhere on the left half of the strip opens diagnostics.
            modifier = Modifier
                .weight(1f)
                .combinedClickable(onClick = {}, onLongClick = onLongPress),
        )
        LibraryPill(onOpenLibrary)
        Spacer(Modifier.width(14.dp))
        Text("${ui.batteryPct}%", color = HiFi.TextLo, fontSize = 14.sp, style = HiFi.Tnum)
    }
}

@Composable
private fun LibraryPill(onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .clip(shape)
            .border(1.dp, HiFi.Amber, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Library", color = HiFi.Amber, fontSize = 13.sp)
    }
}

@Composable
private fun CoverArt(cover: ImageBitmap?, modifier: Modifier) {
    val shape = RoundedCornerShape(4.dp)
    if (cover != null) {
        Image(
            bitmap = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape),
        )
    } else {
        // Placeholder: concentric grooves around an amber centre.
        Canvas(modifier.clip(shape).background(HiFi.Chassis)) {
            for (i in 1..6) {
                drawCircle(
                    color = HiFi.Hairline,
                    radius = size.minDimension * (0.08f + i * 0.07f),
                    center = center,
                    style = Stroke(1.dp.toPx()),
                )
            }
            drawCircle(HiFi.Amber, radius = size.minDimension * 0.035f, center = center)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackInfo(ui: PlayerUiState) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            ui.title,
            color = HiFi.TextHi,
            fontFamily = HiFi.TitleFont,
            fontSize = 28.sp,
            lineHeight = 32.sp,
            maxLines = 1,
            modifier = Modifier.basicMarquee(),
        )
        Text(ui.artist, color = HiFi.TextHi, fontSize = 16.sp, maxLines = 1)
        Text(ui.album, color = HiFi.TextLo, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
private fun FormatRow(ui: PlayerUiState, onToggleMode: () -> Unit) {
    val pill = when (ui.wheelMode) {
        WheelMode.VOLUME -> "Volume ${ui.volumePct}"
        WheelMode.SKIP ->
            if (ui.pendingSkip != 0) "Skip " + "%+d".format(ui.pendingSkip) else "Skip"
        WheelMode.LIST -> "List"
    }
    val pillShape = RoundedCornerShape(14.dp)
    // Nothing playing: no "0 kbps" placeholder.
    val info = listOf(ui.codec, ui.format, if (ui.bitrateKbps > 0) "${ui.bitrateKbps} kbps" else "")
        .filter { it.isNotEmpty() }
        .joinToString("  ")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            info,
            color = HiFi.TextLo,
            fontSize = 13.sp,
            style = HiFi.Tnum,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .clip(pillShape)
                .border(1.dp, HiFi.Amber, pillShape)
                .clickable(onClick = onToggleMode)
                .widthIn(min = 92.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                pill,
                color = HiFi.Amber,
                fontSize = 13.sp,
                style = HiFi.Tnum,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ProgressLine(positionMs: Long, durationMs: Long) {
    val frac = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(3.dp),
        ) {
            drawRect(HiFi.Hairline)
            drawRect(HiFi.Amber, size = Size(size.width * frac, size.height))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(positionMs.mmss(), color = HiFi.TextLo, fontSize = 12.sp, style = HiFi.Tnum)
            Text(durationMs.mmss(), color = HiFi.TextLo, fontSize = 12.sp, style = HiFi.Tnum)
        }
    }
}

private fun Long.mmss(): String {
    val s = this / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun TransportButton(kind: IconKind, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(HiFi.Chassis)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(26.dp)) { drawTransportIcon(kind, HiFi.TextHi) }
    }
}

/** Fake player wired to the real UI. SKIP mode shows a pending count and commits on release. */
@Composable
fun PlayerDemo() {
    val tracks = remember {
        listOf("So What", "Freddie Freeloader", "Blue in Green", "All Blues", "Flamenco Sketches")
    }
    var index by remember { mutableIntStateOf(0) }
    var volume by remember { mutableIntStateOf(40) }
    var pending by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(WheelMode.VOLUME) }

    fun move(by: Int) {
        index = ((index + by) % tracks.size + tracks.size) % tracks.size
    }

    PlayerScreen(
        ui = PlayerUiState(
            title = tracks[index],
            artist = "Miles Davis",
            album = "Kind of Blue",
            codec = "FLAC",
            format = "24 bit / 96 kHz",
            bitrateKbps = 2304,
            positionMs = 95_000,
            durationMs = 545_000,
            isPlaying = playing,
            volumePct = volume,
            wheelMode = mode,
            pendingSkip = pending,
            clock = "21:07",
            batteryPct = 87,
        ),
        actions = PlayerActions(
            onPlayPause = { playing = !playing },
            onPrev = { move(-1) },
            onNext = { move(1) },
            onWheelSteps = { steps, m ->
                if (m == WheelMode.VOLUME) volume = (volume + steps).coerceIn(0, 100)
                else pending += steps
            },
            onWheelRelease = { m ->
                if (m == WheelMode.SKIP && pending != 0) {
                    move(pending)
                    pending = 0
                }
            },
            onToggleMode = {
                mode = if (mode == WheelMode.VOLUME) WheelMode.SKIP else WheelMode.VOLUME
            },
        ),
    )
}

@Preview(widthDp = 411, heightDp = 731, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PlayerScreenPreview() {
    HiFiTheme { PlayerDemo() }
}
