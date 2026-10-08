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

        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(20)
        ) { uris ->
            scope.launch {
                val added = withContext(Dispatchers.IO) {
                    uris.mapNotNull { u ->
                        val d = durationOf(ctx, u)
                        if (d > 0) Clip(IdGen.next(), u, d, 0, d) else null
                    }
                }
                state.addAll(added)
            }
        }
        val pick = {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }

        LaunchedEffect(exporting) {
            val holder = ProgressHolder()
            while (exporting) {
                val t = transformer
                if (t != null && t.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    progress = holder.progress / 100f
                }
                delay(200)
            }
        }

        EditorScreen(
            state = state, player = player, exporting = exporting, snackbar = snackbar,
            onAdd = pick, onExport = { player.pause(); showExport = true }
        )

        if (showExport) {
            AlertDialog(
                onDismissRequest = { showExport = false },
                title = { Text("Export video") },
                text = {
                    Column {
                        FormatRow("H.264 · best compatibility", !useH265) { useH265 = false }
                        FormatRow("H.265 · smaller file", useH265) { useH265 = true }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        showExport = false; exporting = true; progress = 0f
                        startExport(ctx, state.clips.toList(), useH265) { msg ->
                            exporting = false
                            scope.launch { snackbar.showSnackbar(msg) }
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
                        transformer?.cancel(); exporting = false
                        scope.launch { snackbar.showSnackbar("Export cancelled") }
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

    private fun startExport(ctx: Context, clips: List<Clip>, h265: Boolean, done: (String) -> Unit) {
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
