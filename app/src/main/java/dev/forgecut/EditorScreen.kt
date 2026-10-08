package dev.forgecut

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    state: EditorState,
    player: ExoPlayer,
    exporting: Boolean,
    snackbar: SnackbarHostState,
    onAdd: () -> Unit,
    onExport: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var playing by remember { mutableStateOf(false) }
    var globalPos by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme

    // Playhead + per-clip mute while previewing
    LaunchedEffect(player) {
        while (true) {
            playing = player.isPlaying
            val idx = player.currentMediaItemIndex
            val list = state.clips
            if (idx in list.indices) {
                if (!scrubbing) {
                    globalPos = list.take(idx).sumOf { it.lengthMs } +
                        player.currentPosition.coerceAtLeast(0L)
                }
                player.volume = if (list[idx].muted) 0f else 1f
            }
            delay(50)
        }
    }

    // Keep the preview in sync with edits, staying at the same timeline time
    LaunchedEffect(state.clips.toList()) {
        val list = state.clips.toList()
        if (list.isEmpty()) {
            player.clearMediaItems(); globalPos = 0L
            return@LaunchedEffect
        }
        val (i, p) = locate(list, globalPos)
        player.setMediaItems(list.map { it.toMediaItem() }, i, p)
        player.prepare()
    }

    fun seekGlobal(ms: Long) {
        val (i, p) = locate(state.clips, ms)
        player.seekTo(i, p)
    }

    Scaffold(
        containerColor = cs.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {

            // ── Top bar ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ForgeCut", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { state.undo() }, enabled = state.canUndo) {
                    Icon(Icons.AutoMirrored.Filled.Undo, "Undo")
                }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = onExport,
                    enabled = state.clips.isNotEmpty() && !exporting,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Icon(Icons.Default.FileDownload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export")
                }
            }

            // ── Preview ──
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                if (state.clips.isEmpty()) {
                    EmptyState(onAdd)
                } else {
                    AndroidView(
                        factory = { PlayerView(it).apply { useController = false; this.player = player } },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            if (state.clips.isNotEmpty()) {
                // ── Transport ──
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        if (player.isPlaying) player.pause() else {
                            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0)
                            player.play()
                        }
                    }) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (playing) "Pause" else "Play", Modifier.size(30.dp))
                    }
                    Slider(
                        value = globalPos.toFloat().coerceIn(0f, state.totalMs.toFloat().coerceAtLeast(1f)),
                        onValueChange = { scrubbing = true; globalPos = it.toLong(); seekGlobal(it.toLong()) },
                        onValueChangeFinished = { scrubbing = false },
                        valueRange = 0f..state.totalMs.toFloat().coerceAtLeast(1f),
                        modifier = Modifier.weight(1f)
                    )
                    Text("${fmtShort(globalPos)} / ${fmtShort(state.totalMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp))
                }

                // ── Timeline ──
                LazyRow(
                    Modifier.fillMaxWidth().height(76.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(state.clips, key = { it.id }) { c ->
                        ClipTile(c, selected = c.id == state.selectedId) {
                            state.selectedId = c.id
                            val idx = state.clips.indexOfFirst { it.id == c.id }
                            val start = state.clips.take(idx).sumOf { it.lengthMs }
                            globalPos = start
                            player.seekTo(idx, 0)
                        }
                    }
                    item {
                        Box(
                            Modifier.width(64.dp).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                                .background(cs.surfaceVariant).clickable(onClick = onAdd),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.Add, "Add video", tint = cs.primary) }
                    }
                }

                // ── Tools for selected clip ──
                Surface(
                    color = cs.surface,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val sel = state.selectedIndex
                    if (sel < 0) {
                        Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) {
                            Text("Tap a clip to edit it", color = cs.onSurfaceVariant)
                        }
                    } else {
                        val c = state.clips[sel]
                        var range by remember(c.id, c.startMs, c.endMs) {
                            mutableStateOf(c.startMs.toFloat()..c.endMs.toFloat())
                        }
                        Column(Modifier.padding(top = 12.dp, bottom = 8.dp)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("In ${fmt(range.start.toLong())}", style = MaterialTheme.typography.labelMedium)
                                Text("Length ${fmt((range.endInclusive - range.start).toLong())}",
                                    style = MaterialTheme.typography.labelMedium, color = cs.primary)
                                Text("Out ${fmt(range.endInclusive.toLong())}", style = MaterialTheme.typography.labelMedium)
                            }
                            RangeSlider(
                                value = range,
                                onValueChange = { range = it },
                                valueRange = 0f..c.durationMs.toFloat(),
                                onValueChangeFinished = {
                                    val s = range.start.toLong(); val e = range.endInclusive.toLong()
                                    if (e - s >= MIN_CLIP_MS) state.replace(sel, c.copy(startMs = s, endMs = e))
                                    else range = c.startMs.toFloat()..c.endMs.toFloat()
                                },
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp)) {
                                ToolButton(Icons.Default.ContentCut, "Split") {
                                    if (!state.splitAt(player.currentMediaItemIndex, player.currentPosition))
                                        scope.launch { snackbar.showSnackbar("Move the playhead inside a clip to split") }
                                }
                                ToolButton(Icons.Default.ContentCopy, "Duplicate") { state.duplicate(sel) }
                                ToolButton(
                                    if (c.muted) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                    if (c.muted) "Unmute" else "Mute"
                                ) { state.replace(sel, c.copy(muted = !c.muted)) }
                                ToolButton(Icons.Default.KeyboardArrowLeft, "Move left", sel > 0) { state.move(sel, -1) }
                                ToolButton(Icons.Default.KeyboardArrowRight, "Move right", sel < state.clips.lastIndex) { state.move(sel, 1) }
                                ToolButton(Icons.Default.Delete, "Delete") { state.remove(sel) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.VideoLibrary, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(20.dp))
        Text("Start your project", style = MaterialTheme.typography.titleMedium, color = Color.White)
        Spacer(Modifier.height(4.dp))
        Text("Add videos, then trim, split and export", color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd, shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add videos")
        }
    }
}

@Composable
private fun ClipTile(c: Clip, selected: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val thumb by produceState<ImageBitmap?>(null, c.uri, c.startMs) {
        value = withContext(Dispatchers.IO) { frameAt(ctx, c.uri, c.startMs) }
    }
    val w: Dp = (c.lengthMs / 1000f * 20f).dp.coerceIn(72.dp, 240.dp)
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.width(w).fillMaxHeight().clip(shape).background(cs.surfaceVariant)
            .then(if (selected) Modifier.border(3.dp, cs.primary, shape) else Modifier)
            .clickable(onClick = onClick)
    ) {
        thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Text(
            fmtShort(c.lengthMs), color = Color.White, fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                .background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp)
        )
        if (c.muted) Icon(Icons.Default.VolumeOff, "Muted", tint = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(14.dp))
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val base = MaterialTheme.colorScheme.onSurface
    val tint = if (enabled) base else base.copy(alpha = 0.35f)
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp).widthIn(min = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, label, tint = tint)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 11.sp, color = tint, maxLines = 1)
    }
}
