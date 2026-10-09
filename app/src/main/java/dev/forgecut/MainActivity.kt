package dev.forgecut

import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.*
import androidx.media3.transformer.Composition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private var transformer: Transformer? = null
    private var ffProcess: Process? = null
    private var cancelled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ForgeTheme { Root() } }
    }

    @Composable
    private fun Root() {
        val ctx = this
        val scope = rememberCoroutineScope()
        val state = remember { EditorState() }
        val player = remember { ExoPlayer.Builder(ctx).build() }
        DisposableEffect(Unit) { onDispose { player.release() } }
        val snackbar = remember { SnackbarHostState() }
        var showExport by remember { mutableStateOf(false) }
        var exporting by remember { mutableStateOf(false) }
        var progress by remember { mutableFloatStateOf(0f) }
        var useH265 by remember { mutableStateOf(false) }
        val ffmpegReady = remember { FfmpegExport.binary(ctx) != null }

        fun needsFfmpeg() = state.texts.isNotEmpty() || state.overlays.isNotEmpty() || state.clips.any { it.transition != TransitionType.NONE }
        fun say(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(20)
        ) { uris ->
            scope.launch {
                val added = withContext(Dispatchers.IO) {
                    uris.mapNotNull { u ->
                        probe(ctx, u)?.let { p ->
                            Clip(IdGen.next(), u, p.durationMs, 0, p.durationMs,
                                hasAudio = p.hasAudio, dispW = p.w, dispH = p.h)
                        }
                    }
                }
                state.addAll(added)
            }
        }
        val pick = {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }

        val overlayPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    val p = withContext(Dispatchers.IO) { probe(ctx, uri) }
                    val isVid = p != null && p.durationMs > 0
                    val dur = if (isVid) p!!.durationMs else 5000L
                    val curPos = player.currentPosition.coerceAtLeast(0L)
                    val s = if (state.totalMs > 0) minOf(curPos, (state.totalMs - 500L).coerceAtLeast(0L)) else 0L
                    val e = if (state.totalMs > 0) minOf(s + dur, state.totalMs).coerceAtLeast(s + 500L) else s + dur
                    val item = OverlayItem(
                        id = IdGen.next(),
                        uri = uri,
                        type = if (isVid) OverlayType.VIDEO else OverlayType.IMAGE,
                        startMs = s,
                        endMs = e,
                        durationMs = dur,
                        muted = false
                    )
                    state.addOverlay(item)
                }
            }
        }
        val pickOverlay = {
            overlayPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }

        LaunchedEffect(exporting) {
            val holder = ProgressHolder()
            while (exporting) {
                val t = transformer
                if (t != null && ffProcess == null &&
                    t.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    progress = holder.progress / 100f
                }
                delay(200)
            }
        }

        EditorScreen(
            state = state, player = player, exporting = exporting, snackbar = snackbar,
            onAdd = pick,
            onAddOverlay = pickOverlay,
            onExport = {
                player.pause()
                if (needsFfmpeg() && !ffmpegReady) {
                    scope.launch {
                        snackbar.showSnackbar(
                            "Titles, overlays and transitions need the FFmpeg engine. Run “Build FFmpeg” on GitHub once, then rebuild (see README).",
                            duration = SnackbarDuration.Long
                        )
                    }
                } else showExport = true
            }
        )

        if (showExport) {
            val ff = needsFfmpeg()
            AlertDialog(
                onDismissRequest = { showExport = false },
                title = { Text("Export video") },
                text = {
                    Column {
                        if (ff) {
                            Text("MP4 · H.264 · up to 1080p")
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Titles/transitions use the FFmpeg engine. It is software-encoded, so it takes longer.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            FormatRow("H.264 · best compatibility", !useH265) { useH265 = false }
                            FormatRow("H.265 · smaller file", useH265) { useH265 = true }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        showExport = false; exporting = true; progress = 0f; cancelled = false
                        val clips = state.clips.toList()
                        if (ff) {
                            scope.launch {
                                val out = File(ctx.cacheDir, "forgecut_${System.currentTimeMillis()}.mp4")
                                val res = withContext(Dispatchers.IO) {
                                    FfmpegExport.run(ctx, clips, state.texts.toList(), state.overlays.toList(), out,
                                        onProcess = { ffProcess = it }, onProgress = { progress = it })
                                }
                                ffProcess = null
                                val msg = when {
                                    cancelled -> "Export cancelled"
                                    res.isSuccess -> {
                                        val ok = withContext(Dispatchers.IO) { saveToMovies(ctx, out) }
                                        if (ok) "Saved to Movies/ForgeCut" else "Export finished, but saving failed"
                                    }
                                    else -> "Export failed: ${res.exceptionOrNull()?.message}"
                                }
                                exporting = false
                                say(msg)
                            }
                        } else {
                            startMedia3Export(ctx, clips, useH265) { msg -> exporting = false; say(msg) }
                        }
                    }) { Text("Export") }
                },
                dismissButton = { TextButton(onClick = { showExport = false }) { Text("Cancel") } }
            )
        }

        if (exporting) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Exporting…") },
                text = {
                    Column {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text("${(progress * 100).toInt()}%")
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        cancelled = true
                        ffProcess?.destroy()
                        if (ffProcess == null) {
                            transformer?.cancel(); exporting = false; say("Export cancelled")
                        }
                    }) { Text("Cancel") }
                }
            )
        }
    }

    @Composable
    private fun FormatRow(label: String, selected: Boolean, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Text(label)
        }
    }

    private fun startMedia3Export(ctx: Context, clips: List<Clip>, h265: Boolean, done: (String) -> Unit) {
        val items = clips.map {
            EditedMediaItem.Builder(it.toMediaItem()).setRemoveAudio(it.muted).build()
        }
        val composition = Composition.Builder(EditedMediaItemSequence(items)).build()
        val out = File(ctx.cacheDir, "forgecut_${System.currentTimeMillis()}.mp4")
        transformer = Transformer.Builder(ctx)
            .setVideoMimeType(if (h265) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    val ok = saveToMovies(ctx, out)
                    done(if (ok) "Saved to Movies/ForgeCut" else "Export finished, but saving failed")
                }
                override fun onError(
                    composition: Composition, exportResult: ExportResult,
                    exportException: ExportException
                ) { done("Export failed: ${exportException.errorCodeName}") }
            })
            .build()
        transformer!!.start(composition, out.absolutePath)
    }

    private fun saveToMovies(ctx: Context, file: File): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ForgeCut")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        ctx.contentResolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
        file.delete()
        return true
    }
}
