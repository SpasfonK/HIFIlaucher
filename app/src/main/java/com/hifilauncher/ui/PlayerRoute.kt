package com.hifilauncher.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * In LauncherActivity:  setContent { HiFiTheme { PlayerRoute() } }
 *
 * Asks for storage access, shows the player, and shows the diagnostics screen when the
 * clock is long-pressed. Back never leaves the launcher: it only closes diagnostics.
 */
@Composable
fun PlayerRoute(vm: PlayerViewModel = viewModel()) {
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()
    val tree by vm.tree.collectAsStateWithLifecycle()
    val nav by vm.nav.collectAsStateWithLifecycle()

    val permission = Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(granted) {
        if (granted) vm.loadLibrary() else ask.launch(permission)
    }

    // Back never leaves the launcher: it closes diagnostics, or climbs the library.
    BackHandler {
        when {
            diagnostics != null -> vm.closeDiagnostics()
            nav != null -> vm.libraryUp()
        }
    }

    val report = diagnostics
    val libraryNav = nav
    if (report != null) {
        DiagnosticsScreen(
            text = report,
            onCycleTicker = vm::cycleTickerMode,
            onOverlayPermission = vm::requestOverlayPermission,
            onHomeSettings = vm::openHomeSettings,
            onSystemSettings = vm::openSystemSettings,
            onClose = vm::closeDiagnostics,
        )
    } else if (libraryNav != null) {
        LibraryScreen(
            tree = tree,
            nav = libraryNav,
            nowPlayingId = ui.mediaId,
            onOpen = vm::libraryOpen,
            onUp = vm::libraryUp,
            onPlayTrack = vm::playFromLibrary,
            onPlayFolder = vm::playFolder,
            onClose = vm::closeLibrary,
        )
    } else {
        PlayerScreen(ui.copy(positionMs = position), vm.actions)
    }
}

@Composable
fun DiagnosticsScreen(
    text: String,
    onCycleTicker: () -> Unit,
    onOverlayPermission: () -> Unit,
    onHomeSettings: () -> Unit,
    onSystemSettings: () -> Unit,
    onClose: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier
            .fillMaxSize()
            .background(HiFi.Bg)
            .systemBarsPadding()
            .padding(20.dp),
    ) {
        Text("Diagnostics", color = HiFi.TextHi, fontFamily = HiFi.TitleFont, fontSize = 26.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            text,
            color = HiFi.TextLo,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Next ticker mode", Modifier.weight(1f), onCycleTicker)
            PillButton("Overlay access", Modifier.weight(1f), onOverlayPermission)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Home settings", Modifier.weight(1f), onHomeSettings)
            PillButton("System settings", Modifier.weight(1f), onSystemSettings)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Copy", Modifier.weight(1f)) { clipboard.setText(AnnotatedString(text)) }
            PillButton("Close", Modifier.weight(1f), onClose)
        }
    }
}

@Composable
internal fun PillButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .clip(shape)
            .border(1.dp, HiFi.Amber, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = HiFi.Amber, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}
