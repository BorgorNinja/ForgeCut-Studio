package dev.forgecut

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.MediaItem
import java.util.Locale

const val MIN_CLIP_MS = 300L

object IdGen {
    private var n = 0L
    fun next(): Long = ++n
}

data class Clip(
    val id: Long,
    val uri: Uri,
    val durationMs: Long,
    val startMs: Long,
    val endMs: Long,
    val muted: Boolean = false,
) {
    val lengthMs: Long get() = endMs - startMs

    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setUri(uri)
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs)
                .setEndPositionMs(endMs)
                .build()
        ).build()
}

/** Maps a time on the whole timeline to (clip index, position inside that clip). */
fun locate(clips: List<Clip>, globalMs: Long): Pair<Int, Long> {
    var rem = globalMs.coerceAtLeast(0L)
    clips.forEachIndexed { i, c ->
        if (rem < c.lengthMs) return i to rem
        rem -= c.lengthMs
    }
    val last = clips.lastOrNull() ?: return 0 to 0L
    return clips.lastIndex to (last.lengthMs - 1).coerceAtLeast(0L)
}

class EditorState {
    val clips = mutableStateListOf<Clip>()
    var selectedId by mutableStateOf<Long?>(null)
    var canUndo by mutableStateOf(false)
        private set

    private val history = ArrayDeque<List<Clip>>()

    val selectedIndex: Int get() = clips.indexOfFirst { it.id == selectedId }
    val totalMs: Long get() = clips.sumOf { it.lengthMs }

    private fun snapshot() {
        history.addLast(clips.toList())
        if (history.size > 30) history.removeFirst()
        canUndo = true
    }

    fun undo() {
        if (history.isEmpty()) return
        val prev = history.removeLast()
        clips.clear(); clips.addAll(prev)
        if (clips.none { it.id == selectedId }) selectedId = null
        canUndo = history.isNotEmpty()
    }

    fun addAll(newClips: List<Clip>) {
        if (newClips.isEmpty()) return
        snapshot()
        clips.addAll(newClips)
        selectedId = newClips.first().id
    }

    fun replace(i: Int, c: Clip) { snapshot(); clips[i] = c }

    fun remove(i: Int) {
        snapshot()
        clips.removeAt(i)
        selectedId = clips.getOrNull(i.coerceAtMost(clips.lastIndex))?.id
    }

    fun duplicate(i: Int) {
        snapshot()
        val d = clips[i].copy(id = IdGen.next())
        clips.add(i + 1, d)
        selectedId = d.id
    }

    fun move(i: Int, delta: Int) {
        val j = i + delta
        if (j !in clips.indices) return
        snapshot()
        val c = clips.removeAt(i)
        clips.add(j, c)
    }

    /** Splits clip [i] at [relMs] (relative to the clip's trimmed start). */
    fun splitAt(i: Int, relMs: Long): Boolean {
        val c = clips.getOrNull(i) ?: return false
        if (relMs < MIN_CLIP_MS || c.lengthMs - relMs < MIN_CLIP_MS) return false
        snapshot()
        val cut = c.startMs + relMs
        val second = c.copy(id = IdGen.next(), startMs = cut)
        clips[i] = c.copy(endMs = cut)
        clips.add(i + 1, second)
        selectedId = second.id
        return true
    }
}

fun fmt(ms: Long): String =
    String.format(Locale.US, "%d:%02d.%d", ms / 60000, (ms / 1000) % 60, (ms % 1000) / 100)

fun fmtShort(ms: Long): String =
    String.format(Locale.US, "%d:%02d", ms / 60000, (ms / 1000) % 60)

fun durationOf(ctx: Context, uri: Uri): Long = runCatching {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(ctx, uri)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
    } finally { r.release() }
}.getOrDefault(0L)

fun frameAt(ctx: Context, uri: Uri, ms: Long): ImageBitmap? = runCatching {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(ctx, uri)
        r.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 240)
            ?.asImageBitmap()
    } finally { r.release() }
}.getOrNull()
