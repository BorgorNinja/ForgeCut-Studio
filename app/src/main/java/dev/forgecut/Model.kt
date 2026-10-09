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

enum class TransitionType(val label: String, val ffmpeg: String?) {
    NONE("None (hard cut)", null),
    FADE("Crossfade", "fade"),
    BLACK("Dip to black", "fadeblack"),
    WHITE("Dip to white", "fadewhite"),
    DISSOLVE("Dissolve", "dissolve"),
    WIPE_LEFT("Wipe left", "wipeleft"),
    WIPE_RIGHT("Wipe right", "wiperight"),
    SLIDE_LEFT("Slide left", "slideleft"),
    SLIDE_RIGHT("Slide right", "slideright"),
    CIRCLE("Circle open", "circleopen"),
}

enum class TextSize(val label: String, val frac: Float) {
    SMALL("Small", 0.05f), MEDIUM("Medium", 0.08f), LARGE("Large", 0.12f)
}

enum class TextPos(val label: String, val frac: Float) {
    TOP("Top", 0.12f), CENTER("Center", 0.5f), BOTTOM("Bottom", 0.85f)
}

data class TextLayer(
    val id: Long,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val color: Int,
    val size: TextSize,
    val pos: TextPos = TextPos.BOTTOM,
    val posX: Float = 0.5f,
    val posY: Float = pos.frac,
)

enum class OverlayType { IMAGE, VIDEO }

data class OverlayItem(
    val id: Long,
    val uri: Uri,
    val type: OverlayType,
    val startMs: Long,
    val endMs: Long,
    val posX: Float = 0.5f,
    val posY: Float = 0.5f,
    val scale: Float = 0.35f,
    val durationMs: Long = 5000L,
    val muted: Boolean = false,
)

data class Clip(
    val id: Long,
    val uri: Uri,
    val durationMs: Long,
    val startMs: Long,
    val endMs: Long,
    val muted: Boolean = false,
    val hasAudio: Boolean = true,
    val dispW: Int = 0,
    val dispH: Int = 0,
    /** Transition between the previous clip and this one. */
    val transition: TransitionType = TransitionType.NONE,
    val transitionMs: Long = 600L,
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

/** A cut between clip k-1 and k. [cutMs] is on the preview timeline; [dMs] > 0 means an overlapping transition. */
data class Join(val cutMs: Long, val dMs: Long, val type: TransitionType)

fun planJoins(clips: List<Clip>): List<Join> {
    val joins = mutableListOf<Join>()
    if (clips.isEmpty()) return joins
    var merged = clips[0].lengthMs   // length of the exported stream so far
    var edge = clips[0].lengthMs     // preview timeline position of the next cut
    for (k in 1 until clips.size) {
        val c = clips[k]
        var d = 0L
        if (c.transition != TransitionType.NONE) {
            d = minOf(c.transitionMs, c.lengthMs, merged) - 50
            if (d < 100) d = 0
        }
        joins += Join(edge, d, c.transition)
        merged += c.lengthMs - d
        edge += c.lengthMs
    }
    return joins
}

/** Transitions overlap clips, so exported time runs slightly behind preview time. */
fun toExportMs(joins: List<Join>, previewMs: Long): Long =
    previewMs - joins.filter { it.cutMs <= previewMs }.sumOf { it.dMs }

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

private data class Snap(
    val clips: List<Clip>,
    val texts: List<TextLayer>,
    val overlays: List<OverlayItem>
)

class EditorState {
    val clips = mutableStateListOf<Clip>()
    val texts = mutableStateListOf<TextLayer>()
    val overlays = mutableStateListOf<OverlayItem>()
    var selectedId by mutableStateOf<Long?>(null)
    var selectedTextId by mutableStateOf<Long?>(null)
    var selectedOverlayId by mutableStateOf<Long?>(null)
    var canUndo by mutableStateOf(false)
        private set

    private val history = ArrayDeque<Snap>()

    val selectedIndex: Int get() = clips.indexOfFirst { it.id == selectedId }
    val totalMs: Long get() = clips.sumOf { it.lengthMs }
    val outputAspect: Float
        get() {
            val c = clips.firstOrNull()
            return if (c != null && c.dispW > 0 && c.dispH > 0) c.dispW.toFloat() / c.dispH else 16f / 9f
        }

    private fun snapshot() {
        history.addLast(Snap(clips.toList(), texts.toList(), overlays.toList()))
        if (history.size > 40) history.removeFirst()
        canUndo = true
    }

    fun undo() {
        if (history.isEmpty()) return
        val p = history.removeLast()
        clips.clear(); clips.addAll(p.clips)
        texts.clear(); texts.addAll(p.texts)
        overlays.clear(); overlays.addAll(p.overlays)
        if (clips.none { it.id == selectedId }) selectedId = null
        if (texts.none { it.id == selectedTextId }) selectedTextId = null
        if (overlays.none { it.id == selectedOverlayId }) selectedOverlayId = null
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
        val d = clips[i].copy(id = IdGen.next(), transition = TransitionType.NONE)
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
        val second = c.copy(id = IdGen.next(), startMs = cut, transition = TransitionType.NONE)
        clips[i] = c.copy(endMs = cut)
        clips.add(i + 1, second)
        selectedId = second.id
        return true
    }

    fun setTransition(i: Int, type: TransitionType, ms: Long) {
        snapshot()
        clips[i] = clips[i].copy(transition = type, transitionMs = ms)
    }

    fun addText(t: TextLayer) {
        snapshot()
        texts.add(t)
        selectedTextId = t.id
        selectedOverlayId = null
    }

    fun updateText(t: TextLayer) {
        val i = texts.indexOfFirst { it.id == t.id }
        if (i >= 0) { snapshot(); texts[i] = t }
    }

    fun updateTextPosition(id: Long, x: Float, y: Float) {
        val i = texts.indexOfFirst { it.id == id }
        if (i >= 0) {
            val cur = texts[i]
            texts[i] = cur.copy(posX = x.coerceIn(0.05f, 0.95f), posY = y.coerceIn(0.05f, 0.95f))
        }
    }

    fun removeText(id: Long) {
        val i = texts.indexOfFirst { it.id == id }
        if (i >= 0) { snapshot(); texts.removeAt(i) }
        selectedTextId = null
    }

    fun addOverlay(o: OverlayItem) {
        snapshot()
        overlays.add(o)
        selectedOverlayId = o.id
        selectedTextId = null
        selectedId = null
    }

    fun updateOverlay(o: OverlayItem) {
        val i = overlays.indexOfFirst { it.id == o.id }
        if (i >= 0) { snapshot(); overlays[i] = o }
    }

    fun updateOverlayPosition(id: Long, x: Float, y: Float) {
        val i = overlays.indexOfFirst { it.id == id }
        if (i >= 0) {
            val cur = overlays[i]
            overlays[i] = cur.copy(posX = x.coerceIn(0.05f, 0.95f), posY = y.coerceIn(0.05f, 0.95f))
        }
    }

    fun removeOverlay(id: Long) {
        val i = overlays.indexOfFirst { it.id == id }
        if (i >= 0) { snapshot(); overlays.removeAt(i) }
        selectedOverlayId = null
    }
}

fun fmt(ms: Long): String =
    String.format(Locale.US, "%d:%02d.%d", ms / 60000, (ms / 1000) % 60, (ms % 1000) / 100)

fun fmtShort(ms: Long): String =
    String.format(Locale.US, "%d:%02d", ms / 60000, (ms / 1000) % 60)

data class Probe(val durationMs: Long, val w: Int, val h: Int, val hasAudio: Boolean)

fun probe(ctx: Context, uri: Uri): Probe? = runCatching {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(ctx, uri)
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
        val a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        Probe(d, w, h, a)
    } finally { r.release() }
}.getOrNull()?.takeIf { it.durationMs > 0 }

fun frameAt(ctx: Context, uri: Uri, ms: Long): ImageBitmap? = runCatching {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(ctx, uri)
        r.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 360)
            ?.asImageBitmap()
    } finally { r.release() }
}.getOrNull()

fun loadBitmap(ctx: Context, uri: Uri, maxDim: Int = 800): ImageBitmap? = runCatching {
    val opt = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use {
        android.graphics.BitmapFactory.decodeStream(it, null, opt)
    }
    val sample = maxOf(1, maxOf(opt.outWidth, opt.outHeight) / maxDim)
    val decodeOpt = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    ctx.contentResolver.openInputStream(uri)?.use {
        android.graphics.BitmapFactory.decodeStream(it, null, decodeOpt)?.asImageBitmap()
    }
}.getOrNull()
