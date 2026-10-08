package dev.forgecut

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun msToDp(ms: Long, dpPerMs: Float): Dp = (ms * dpPerMs).dp

/**
 * Scrolling timeline. The white line in the middle is the playhead: whatever sits under it is what
 * you see in the preview and what "Split" cuts.
 */
@Composable
fun Timeline(
    state: EditorState,
    scroll: ScrollState,
    dpPerSec: Float,
    onSeekClip: (clipId: Long, relMs: Long) -> Unit,
    onJoinClick: (Int) -> Unit,
    onTextClick: (Long) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val dpPerMs = dpPerSec / 1000f

    BoxWithConstraints(Modifier.fillMaxWidth().height(150.dp)) {
        val half = maxWidth / 2
        val total = msToDp(state.totalMs, dpPerMs)

        Row(Modifier.fillMaxSize().horizontalScroll(scroll)) {
            Spacer(Modifier.width(half))
            Box(Modifier.width(total).fillMaxHeight()) {
                Ruler(state.totalMs, dpPerSec)

                Row(Modifier.padding(top = 26.dp).height(64.dp)) {
                    state.clips.forEach { c ->
                        key(c.id) {
                            TimelineClip(
                                c, msToDp(c.lengthMs, dpPerMs), dpPerMs,
                                selected = c.id == state.selectedId && state.selectedTextId == null
                            ) { rel -> onSeekClip(c.id, rel) }
                        }
                    }
                }

                var edge = 0L
                state.clips.forEachIndexed { k, c ->
                    if (k > 0) {
                        val active = c.transition != TransitionType.NONE
                        Box(
                            Modifier.offset(x = msToDp(edge, dpPerMs) - 13.dp, y = 45.dp)
                                .size(26.dp).clip(CircleShape)
                                .background(if (active) cs.primary else Color(0xCC000000))
                                .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                                .clickable { onJoinClick(k) },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.SwapHoriz, "Transition", Modifier.size(16.dp), tint = Color.White) }
                    }
                    edge += c.lengthMs
                }

                if (state.texts.isEmpty()) {
                    Text("Text track — tap “Text” to add a title", fontSize = 11.sp,
                        color = cs.onSurfaceVariant, modifier = Modifier.offset(x = 6.dp, y = 106.dp))
                }
                state.texts.forEach { t ->
                    val sel = t.id == state.selectedTextId
                    Box(
                        Modifier.offset(x = msToDp(t.startMs, dpPerMs), y = 100.dp)
                            .width(msToDp(t.endMs - t.startMs, dpPerMs).coerceAtLeast(28.dp)).height(30.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (sel) cs.primary else cs.secondaryContainer)
                            .clickable { onTextClick(t.id) }
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(t.text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (sel) Color.White else cs.onSecondaryContainer)
                    }
                }
            }
            Spacer(Modifier.width(half))
        }

        // Playhead
        Box(
            Modifier.align(Alignment.TopCenter).width(2.dp).fillMaxHeight().background(Color.White)
        )
        Box(
            Modifier.align(Alignment.TopCenter).size(10.dp).clip(CircleShape).background(Color.White)
        )
    }
}

@Composable
private fun Ruler(totalMs: Long, dpPerSec: Float) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val step = listOf(1, 2, 5, 10, 30, 60, 120, 300).firstOrNull { it * dpPerSec >= 56f } ?: 600
    Box(Modifier.fillMaxWidth().height(24.dp)) {
        var s = 0
        while (s * 1000L <= totalMs) {
            Box(Modifier.offset(x = (s * dpPerSec).dp, y = 14.dp).width(1.dp).height(8.dp).background(color))
            Text(fmtShort(s * 1000L), fontSize = 10.sp, color = color,
                modifier = Modifier.offset(x = (s * dpPerSec).dp + 3.dp, y = 2.dp))
            s += step
        }
    }
}

@Composable
private fun TimelineClip(
    c: Clip, width: Dp, dpPerMs: Float, selected: Boolean, onTapMs: (Long) -> Unit
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val cs = MaterialTheme.colorScheme
    val thumb by produceState<ImageBitmap?>(null, c.uri, c.startMs) {
        value = withContext(Dispatchers.IO) { frameAt(ctx, c.uri, c.startMs) }
    }
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.width(width).fillMaxHeight().padding(end = 1.dp).clip(shape).background(cs.surfaceVariant)
            .then(if (selected) Modifier.border(2.dp, cs.primary, shape) else Modifier)
            .pointerInput(c.id, dpPerMs, c.lengthMs) {
                detectTapGestures { off ->
                    onTapMs(((off.x / density) / dpPerMs).toLong().coerceIn(0L, c.lengthMs - 1))
                }
            }
    ) {
        thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Text(
            fmtShort(c.lengthMs), color = Color.White, fontSize = 10.sp, maxLines = 1,
            modifier = Modifier.align(Alignment.BottomStart).padding(3.dp)
                .background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp)
        )
        if (c.muted) Icon(Icons.Default.VolumeOff, "Muted", tint = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(14.dp))
    }
}
