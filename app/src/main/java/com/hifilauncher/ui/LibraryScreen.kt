package com.hifilauncher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hifilauncher.audio.LibraryTree
import com.hifilauncher.audio.Track
import com.hifilauncher.audio.entryKey

/** Where the library browser is. [hint] is the entry key to focus first (where we came from). */
data class LibraryNav(val path: String, val hint: String?)

/**
 * Folder browser. Two ways to drive it:
 *  - touch: tap a folder to open it, tap a song to play it, tap the play button of a folder
 *    to play everything inside, scroll with a finger;
 *  - wheel (List mode): turning moves the highlighted row, the hub opens or plays it.
 *
 * A song starts its own folder as the queue (gapless album playback); the screen then
 * returns to the player.
 */
@Composable
fun LibraryScreen(
    tree: LibraryTree,
    nav: LibraryNav,
    nowPlayingId: String?,
    onOpen: (String) -> Unit,
    onUp: () -> Unit,
    onPlayTrack: (Track) -> Unit,
    onPlayFolder: (String) -> Unit,
    onClose: () -> Unit,
) {
    val entries = remember(tree, nav.path) { tree.entries(nav.path) }
    val listState = rememberLazyListState()
    var focus by remember(nav.path, nav.hint) {
        mutableIntStateOf(initialFocus(entries, nav.hint, nowPlayingId))
    }
    val atTop = tree.parent(nav.path) == null

    // Keep the highlighted row on screen, two rows from the top so the next ones are visible.
    LaunchedEffect(focus, entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem((focus - 2).coerceAtLeast(0))
    }

    fun activate(index: Int) {
        when (val e = entries.getOrNull(index)) {
            is LibraryTree.Entry.Folder -> onOpen(e.path)
            is LibraryTree.Entry.Song -> onPlayTrack(e.track)
            null -> Unit
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(HiFi.Bg)
            .systemBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    tree.nameOf(nav.path),
                    color = HiFi.TextHi,
                    fontFamily = HiFi.TitleFont,
                    fontSize = 26.sp,
                    maxLines = 1,
                )
                Text(countLabel(tree.trackCount(nav.path)), color = HiFi.TextLo, fontSize = 13.sp)
            }
            PillButton("Play all", Modifier.widthIn(min = 96.dp)) { onPlayFolder(nav.path) }
        }

        if (entries.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No music found", color = HiFi.TextLo, fontSize = 15.sp)
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(entries, key = { _, e -> e.entryKey() }) { i, e ->
                    EntryRow(
                        entry = e,
                        focused = i == focus,
                        playing = e is LibraryTree.Entry.Song && e.track.id.toString() == nowPlayingId,
                        onClick = {
                            focus = i
                            activate(i)
                        },
                        onPlayFolder = { if (e is LibraryTree.Entry.Folder) onPlayFolder(e.path) },
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (atTop) Spacer(Modifier.width(76.dp)) else PillButton("Up", Modifier.width(76.dp), onUp)
            VirtualWheel(
                mode = WheelMode.LIST,
                isPlaying = false, // the hub shows a play triangle: open or play the highlighted row
                onSteps = { steps ->
                    if (entries.isNotEmpty()) focus = (focus + steps).coerceIn(0, entries.lastIndex)
                },
                onRelease = {},
                onHubClick = { activate(focus) },
                diameter = 210.dp,
            )
            PillButton("Player", Modifier.width(76.dp), onClose)
        }
    }
}

@Composable
private fun EntryRow(
    entry: LibraryTree.Entry,
    focused: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    onPlayFolder: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .background(if (focused) HiFi.Chassis else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (entry) {
            is LibraryTree.Entry.Folder -> {
                Canvas(Modifier.size(28.dp)) {
                    val w = size.width
                    val h = size.height
                    drawRoundRect(
                        HiFi.TextLo, Offset(0f, h * 0.12f), Size(w * 0.42f, h * 0.22f),
                        CornerRadius(2.dp.toPx()),
                    )
                    drawRoundRect(
                        HiFi.TextLo, Offset(0f, h * 0.24f), Size(w, h * 0.60f),
                        CornerRadius(3.dp.toPx()),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.name, color = HiFi.TextHi, fontSize = 17.sp, maxLines = 1)
                    Text(countLabel(entry.trackCount), color = HiFi.TextLo, fontSize = 12.sp)
                }
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onPlayFolder),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(22.dp)) { drawTransportIcon(IconKind.Play, HiFi.Amber) }
                }
            }
            is LibraryTree.Entry.Song -> {
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    if (playing) Canvas(Modifier.size(8.dp)) { drawCircle(HiFi.Amber) }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.track.title,
                        color = if (playing) HiFi.Amber else HiFi.TextHi,
                        fontSize = 16.sp,
                        maxLines = 1,
                    )
                    Text(
                        entry.track.artist + "  " + formatDuration(entry.track.durationMs),
                        color = HiFi.TextLo,
                        fontSize = 12.sp,
                        style = HiFi.Tnum,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun initialFocus(entries: List<LibraryTree.Entry>, hint: String?, nowPlayingId: String?): Int {
    val wanted = hint ?: nowPlayingId?.let { "s:$it" } ?: return 0
    return entries.indexOfFirst { it.entryKey() == wanted }.coerceAtLeast(0)
}

private fun countLabel(n: Int) = if (n == 1) "1 track" else "$n tracks"

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
