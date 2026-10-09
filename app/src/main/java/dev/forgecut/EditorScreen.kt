package dev.forgecut

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    state: EditorState,
    player: ExoPlayer,
    exporting: Boolean,
    snackbar: SnackbarHostState,
    onAdd: () -> Unit,
    onAddOverlay: () -> Unit,
    onExport: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current.density
    var playing by remember { mutableStateOf(false) }
    var globalPos by remember { mutableLongStateOf(0L) }
    var dpPerSec by remember { mutableFloatStateOf(60f) }
    val scroll = rememberScrollState()
    var showTrim by remember { mutableStateOf(false) }
    var showTextDialog by remember { mutableStateOf(false) }
    var editExisting by remember { mutableStateOf(false) }
    var joinIndex by remember { mutableStateOf<Int?>(null) }

    fun seekGlobal(ms: Long) {
        if (state.clips.isEmpty()) return
        val (i, p) = locate(state.clips, ms)
        player.seekTo(i, p)
    }

    fun seekToMs(ms: Long) {
        val t = ms.coerceIn(0L, state.totalMs)
        globalPos = t
        seekGlobal(t)
        scope.launch { scroll.scrollTo((t * dpPerSec / 1000f * density).toInt()) }
    }

    // Player → playhead (and keep the timeline centred on it)
    LaunchedEffect(player) {
        while (true) {
            playing = player.isPlaying
            val idx = player.currentMediaItemIndex
            val list = state.clips
            val userScrolling = scroll.isScrollInProgress
            if (idx in list.indices) {
                player.volume = if (list[idx].muted) 0f else 1f
                if (!userScrolling) {
                    globalPos = list.take(idx).sumOf { it.lengthMs } + player.currentPosition.coerceAtLeast(0L)
                }
            }
            if (!userScrolling) {
                val target = (globalPos * dpPerSec / 1000f * density).toInt().coerceIn(0, scroll.maxValue)
                if (abs(scroll.value - target) > 1) scroll.scrollTo(target)
                if (state.selectedTextId == null && list.isNotEmpty()) {
                    val cur = list.getOrNull(locate(list, globalPos).first)
                    if (cur != null && state.selectedId != cur.id) state.selectedId = cur.id
                }
            }
            delay(50)
        }
    }

    // Dragging the timeline → seek the preview
    LaunchedEffect(Unit) {
        snapshotFlow { scroll.value to scroll.isScrollInProgress }
            .filter { it.second }
            .collect { (v, _) ->
                val ms = (v / (dpPerSec / 1000f * density)).toLong().coerceIn(0L, state.totalMs)
                globalPos = ms
                seekGlobal(ms)
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

    val (splitIdx, splitRel) = locate(state.clips, globalPos)
    val splitClip = state.clips.getOrNull(splitIdx)
    val canSplit = splitClip != null && splitRel >= MIN_CLIP_MS && splitClip.lengthMs - splitRel >= MIN_CLIP_MS

    Scaffold(containerColor = cs.background, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
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
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                if (state.clips.isEmpty()) {
                    EmptyState(onAdd)
                } else {
                    AndroidView(
                        factory = { PlayerView(it).apply { useController = false; this.player = player } },
                        modifier = Modifier.fillMaxSize()
                    )
                    val ar = state.outputAspect
                    val rectW = if (ar > maxWidth / maxHeight) maxWidth else maxHeight * ar
                    val rectH = rectW / ar
                    Box(Modifier.size(rectW, rectH).align(Alignment.Center)) {
                        state.overlays.filter { globalPos >= it.startMs && globalPos < it.endMs }
                            .forEach { ov ->
                                PreviewOverlay(
                                    ov = ov,
                                    rectW = rectW,
                                    rectH = rectH,
                                    globalPos = globalPos,
                                    isSelected = ov.id == state.selectedOverlayId,
                                    onSelect = {
                                        state.selectedOverlayId = ov.id
                                        state.selectedTextId = null
                                    },
                                    onDragDelta = { dx, dy ->
                                        state.updateOverlayPosition(ov.id, ov.posX + dx, ov.posY + dy)
                                    }
                                )
                            }

                        state.texts.filter { globalPos >= it.startMs && globalPos < it.endMs }
                            .forEach { t ->
                                PreviewText(
                                    t = t,
                                    rectW = rectW,
                                    rectH = rectH,
                                    isSelected = t.id == state.selectedTextId,
                                    onSelect = {
                                        state.selectedTextId = t.id
                                        state.selectedOverlayId = null
                                    },
                                    onDragDelta = { dx, dy ->
                                        state.updateTextPosition(t.id, t.posX + dx, t.posY + dy)
                                    }
                                )
                            }
                    }
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
                            if (player.playbackState == Player.STATE_ENDED) seekToMs(0)
                            player.play()
                        }
                    }) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (playing) "Pause" else "Play", Modifier.size(30.dp))
                    }
                    Text("${fmt(globalPos)} / ${fmt(state.totalMs)}",
                        style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { dpPerSec = (dpPerSec / 1.4f).coerceAtLeast(12f) }) {
                        Icon(Icons.Default.ZoomOut, "Zoom out")
                    }
                    IconButton(onClick = { dpPerSec = (dpPerSec * 1.4f).coerceAtMost(240f) }) {
                        Icon(Icons.Default.ZoomIn, "Zoom in")
                    }
                }

                // ── Timeline ──
                Timeline(
                    state = state, scroll = scroll, dpPerSec = dpPerSec,
                    onSeekClip = { id, rel ->
                        state.selectedTextId = null
                        state.selectedOverlayId = null
                        val start = state.clips.takeWhile { it.id != id }.sumOf { it.lengthMs }
                        seekToMs(start + rel)
                    },
                    onJoinClick = { joinIndex = it },
                    onTextClick = {
                        state.selectedTextId = it
                        state.selectedOverlayId = null
                    },
                    onOverlayClick = {
                        state.selectedOverlayId = it
                        state.selectedTextId = null
                    },
                )

                // ── Tools ──
                Surface(
                    color = cs.surface,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val oIdx = state.overlays.indexOfFirst { it.id == state.selectedOverlayId }
                    val tIdx = state.texts.indexOfFirst { it.id == state.selectedTextId }
                    val sel = state.selectedIndex
                    Column(Modifier.padding(top = 8.dp, bottom = 8.dp)) {
                        if (oIdx >= 0) {
                            OverlayPanel(
                                ov = state.overlays[oIdx],
                                totalMs = state.totalMs,
                                onChange = { state.updateOverlay(it) },
                                onDelete = { state.removeOverlay(state.overlays[oIdx].id) },
                                onDone = { state.selectedOverlayId = null }
                            )
                        } else if (tIdx >= 0) {
                            TextPanel(
                                t = state.texts[tIdx], totalMs = state.totalMs,
                                onChange = { state.updateText(it) },
                                onEdit = { editExisting = true; showTextDialog = true },
                                onDelete = { state.removeText(state.texts[tIdx].id) },
                                onDone = { state.selectedTextId = null },
                            )
                        } else if (sel >= 0) {
                            val c = state.clips[sel]
                            if (showTrim) {
                                var range by remember(c.id, c.startMs, c.endMs) {
                                    mutableStateOf(c.startMs.toFloat()..c.endMs.toFloat())
                                }
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
                            }
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp)) {
                                ToolButton(Icons.Default.ContentCut, "Split", enabled = canSplit, highlight = true) {
                                    val at = globalPos
                                    if (state.splitAt(splitIdx, splitRel)) {
                                        scope.launch {
                                            val r = snackbar.showSnackbar("Split at ${fmt(at)}", actionLabel = "Undo",
                                                duration = SnackbarDuration.Short)
                                            if (r == SnackbarResult.ActionPerformed) state.undo()
                                        }
                                    }
                                }
                                ToolButton(Icons.Default.Tune, "Trim", highlight = showTrim) { showTrim = !showTrim }
                                ToolButton(Icons.Default.TextFields, "Text") { editExisting = false; showTextDialog = true }
                                ToolButton(Icons.Default.Layers, "Overlay") { onAddOverlay() }
                                ToolButton(Icons.Default.ContentCopy, "Duplicate") { state.duplicate(sel) }
                                ToolButton(
                                    if (c.muted) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                                    if (c.muted) "Unmute" else "Mute"
                                ) { state.replace(sel, c.copy(muted = !c.muted)) }
                                ToolButton(Icons.Default.KeyboardArrowLeft, "Move left", sel > 0) { state.move(sel, -1) }
                                ToolButton(Icons.Default.KeyboardArrowRight, "Move right", sel < state.clips.lastIndex) { state.move(sel, 1) }
                                ToolButton(Icons.Default.Delete, "Delete") { state.remove(sel) }
                            }
                            if (!canSplit) {
                                Text(
                                    "Scroll the timeline so the white line sits where you want to cut",
                                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTextDialog) {
        val existing = if (editExisting) state.texts.find { it.id == state.selectedTextId } else null
        TextDialog(
            initial = existing,
            onDismiss = { showTextDialog = false },
            onConfirm = { text, color, size, pos ->
                showTextDialog = false
                if (existing != null) {
                    state.updateText(existing.copy(text = text, color = color, size = size, pos = pos))
                } else {
                    val e = minOf(state.totalMs, globalPos + 3000L)
                    val s = maxOf(0L, e - 3000L)
                    state.addText(TextLayer(IdGen.next(), text, s, e, color, size, pos))
                }
            }
        )
    }

    joinIndex?.let { k ->
        val c = state.clips.getOrNull(k)
        if (c == null) joinIndex = null else TransitionDialog(
            current = c.transition, currentMs = c.transitionMs,
            onDismiss = { joinIndex = null },
            onConfirm = { type, ms -> state.setTransition(k, type, ms); joinIndex = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextPanel(
    t: TextLayer, totalMs: Long,
    onChange: (TextLayer) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onDone: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var range by remember(t.id, t.startMs, t.endMs) { mutableStateOf(t.startMs.toFloat()..t.endMs.toFloat()) }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Starts ${fmt(range.start.toLong())}", style = MaterialTheme.typography.labelMedium)
            Text("Shows for ${fmt((range.endInclusive - range.start).toLong())}",
                style = MaterialTheme.typography.labelMedium, color = cs.primary)
            Text("Ends ${fmt(range.endInclusive.toLong())}", style = MaterialTheme.typography.labelMedium)
        }
        RangeSlider(
            value = range,
            onValueChange = { range = it },
            valueRange = 0f..totalMs.toFloat().coerceAtLeast(1f),
            onValueChangeFinished = {
                val s = range.start.toLong(); val e = range.endInclusive.toLong()
                if (e - s >= MIN_CLIP_MS) onChange(t.copy(startMs = s, endMs = e))
                else range = t.startMs.toFloat()..t.endMs.toFloat()
            },
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            ToolButton(Icons.Default.Edit, "Edit text", onClick = onEdit)
            ToolButton(Icons.Default.Delete, "Delete", onClick = onDelete)
            ToolButton(Icons.Default.Done, "Done", highlight = true, onClick = onDone)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverlayPanel(
    ov: OverlayItem, totalMs: Long,
    onChange: (OverlayItem) -> Unit, onDelete: () -> Unit, onDone: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var range by remember(ov.id, ov.startMs, ov.endMs) { mutableStateOf(ov.startMs.toFloat()..ov.endMs.toFloat()) }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Starts ${fmt(range.start.toLong())}", style = MaterialTheme.typography.labelMedium)
            Text("Size ${(ov.scale * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium, color = cs.primary)
            Text("Ends ${fmt(range.endInclusive.toLong())}", style = MaterialTheme.typography.labelMedium)
        }
        RangeSlider(
            value = range,
            onValueChange = { range = it },
            valueRange = 0f..totalMs.toFloat().coerceAtLeast(1f),
            onValueChangeFinished = {
                val s = range.start.toLong(); val e = range.endInclusive.toLong()
                if (e - s >= MIN_CLIP_MS) onChange(ov.copy(startMs = s, endMs = e))
                else range = ov.startMs.toFloat()..ov.endMs.toFloat()
            },
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            ToolButton(Icons.Default.ZoomIn, "Scale +") {
                onChange(ov.copy(scale = (ov.scale + 0.05f).coerceAtMost(0.95f)))
            }
            ToolButton(Icons.Default.ZoomOut, "Scale -") {
                onChange(ov.copy(scale = (ov.scale - 0.05f).coerceAtLeast(0.15f)))
            }
            if (ov.type == OverlayType.VIDEO) {
                ToolButton(
                    if (ov.muted) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                    if (ov.muted) "Unmute" else "Mute"
                ) {
                    onChange(ov.copy(muted = !ov.muted))
                }
            }
            ToolButton(Icons.Default.Delete, "Delete", onClick = onDelete)
            ToolButton(Icons.Default.Done, "Done", highlight = true, onClick = onDone)
        }
    }
}

@Composable
private fun PreviewText(
    t: TextLayer,
    rectW: Dp,
    rectH: Dp,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDragDelta: (Float, Float) -> Unit,
) {
    val d = LocalDensity.current
    val fontSp = with(d) { (t.size.frac * rectH.toPx()).toSp() }
    val rectWPx = with(d) { rectW.toPx() }
    val rectHPx = with(d) { rectH.toPx() }

    Box(
        Modifier
            .offset {
                IntOffset(
                    (t.posX * rectWPx).toInt(),
                    (t.posY * rectHPx).toInt()
                )
            }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(placeable.width, placeable.height) {
                    placeable.placeRelative(-placeable.width / 2, -placeable.height / 2)
                }
            }
            .then(
                if (isSelected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                    .padding(4.dp)
                else Modifier.padding(4.dp)
            )
            .pointerInput(t.id, rectWPx, rectHPx) {
                detectTapGestures { onSelect() }
            }
            .pointerInput(t.id, rectWPx, rectHPx) {
                detectDragGestures(
                    onDragStart = { onSelect() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDragDelta(dragAmount.x / rectWPx, dragAmount.y / rectHPx)
                    }
                )
            }
    ) {
        Text(
            t.text,
            color = Color(t.color),
            fontSize = fontSp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            style = TextStyle(shadow = Shadow(Color.Black, Offset(2f, 2f), 6f))
        )
    }
}

@Composable
private fun PreviewOverlay(
    ov: OverlayItem,
    rectW: Dp,
    rectH: Dp,
    globalPos: Long,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDragDelta: (Float, Float) -> Unit,
) {
    val ctx = LocalContext.current
    val d = LocalDensity.current
    val rectWPx = with(d) { rectW.toPx() }
    val rectHPx = with(d) { rectH.toPx() }
    val ovW = rectW * ov.scale

    val relMs = (globalPos - ov.startMs).coerceAtLeast(0L)
    val imageBitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        null, ov.uri, if (ov.type == OverlayType.VIDEO) relMs / 500L else 0L
    ) {
        value = withContext(Dispatchers.IO) {
            if (ov.type == OverlayType.IMAGE) {
                loadBitmap(ctx, ov.uri, 800)
            } else {
                frameAt(ctx, ov.uri, relMs)
            }
        }
    }

    Box(
        Modifier
            .offset {
                IntOffset(
                    (ov.posX * rectWPx).toInt(),
                    (ov.posY * rectHPx).toInt()
                )
            }
            .width(ovW)
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    placeable.placeRelative(-placeable.width / 2, -placeable.height / 2)
                }
            }
            .then(
                if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                else Modifier
            )
            .clip(RoundedCornerShape(6.dp))
            .background(Color.DarkGray.copy(alpha = 0.5f))
            .pointerInput(ov.id, rectWPx, rectHPx) {
                detectTapGestures { onSelect() }
            }
            .pointerInput(ov.id, rectWPx, rectHPx) {
                detectDragGestures(
                    onDragStart = { onSelect() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDragDelta(dragAmount.x / rectWPx, dragAmount.y / rectHPx)
                    }
                )
            }
    ) {
        val bmp = imageBitmap
        if (bmp != null) {
            Image(
                bmp,
                contentDescription = "Overlay",
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Fit
            )
        } else {
            Box(Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                Text(if (ov.type == OverlayType.IMAGE) "Image" else "Video", fontSize = 12.sp, color = Color.White)
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
        Text("Add videos, then trim, split, add titles and export", color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd, shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add videos")
        }
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector, label: String, enabled: Boolean = true, highlight: Boolean = false, onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val base = cs.onSurface
    val tint = if (enabled) base else base.copy(alpha = 0.35f)
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp).widthIn(min = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(if (highlight && enabled) cs.primary else Color.Transparent),
            contentAlignment = Alignment.Center
        ) { Icon(icon, label, tint = if (highlight && enabled) Color.White else tint) }
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 11.sp, color = tint, maxLines = 1)
    }
}
