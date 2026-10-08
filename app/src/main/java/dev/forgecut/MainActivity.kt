package dev.forgecut

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.*
import androidx.media3.transformer.Composition
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Clip(val uri: Uri, val durationMs: Long, val startMs: Long, val endMs: Long) {
    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setUri(uri)
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs)
                .setEndPositionMs(endMs)
                .build()
        ).build()
}

class MainActivity : ComponentActivity() {
    private var transformer: Transformer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface { Editor() } } }
    }

    @Composable
    private fun Editor() {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val clips = remember { mutableStateListOf<Clip>() }
        var status by remember { mutableStateOf("Add videos to start") }
        var exporting by remember { mutableStateOf(false) }
        val player = remember { ExoPlayer.Builder(ctx).build() }
        DisposableEffect(Unit) { onDispose { player.release() } }

        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(20)
        ) { uris ->
            scope.launch {
                uris.forEach { u ->
                    val d = withContext(Dispatchers.IO) { durationOf(ctx, u) }
                    if (d > 0) clips.add(Clip(u, d, 0, d))
                }
            }
        }

        LaunchedEffect(clips.toList()) {
            player.setMediaItems(clips.map { it.toMediaItem() })
            player.prepare()
        }

        Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp)) {
            AndroidView(
                factory = { PlayerView(it).apply { this.player = player } },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            )
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                }) { Text("Add videos") }
                Button(
                    enabled = clips.isNotEmpty() && !exporting,
                    onClick = {
                        exporting = true; status = "Exporting…"
                        export(ctx, clips.toList()) { msg -> exporting = false; status = msg }
                    }
                ) { Text("Export MP4") }
            }
            if (exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(status, style = MaterialTheme.typography.bodySmall)

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(clips) { i, clip ->
                    ClipCard(
                        index = i, clip = clip,
                        onChange = { clips[i] = it },
                        onMove = { d ->
                            val j = i + d
                            if (j in clips.indices) { val c = clips.removeAt(i); clips.add(j, c) }
                        },
                        onRemove = { clips.removeAt(i) }
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ClipCard(
        index: Int, clip: Clip,
        onChange: (Clip) -> Unit, onMove: (Int) -> Unit, onRemove: () -> Unit
    ) {
        var range by remember(clip.startMs, clip.endMs) {
            mutableStateOf(clip.startMs.toFloat()..clip.endMs.toFloat())
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Clip ${index + 1}  ·  ${fmt(range.endInclusive.toLong() - range.start.toLong())}")
                RangeSlider(
                    value = range,
                    onValueChange = { range = it },
                    valueRange = 0f..clip.durationMs.toFloat(),
                    onValueChangeFinished = {
                        val s = range.start.toLong(); val e = range.endInclusive.toLong()
                        if (e - s >= 300) onChange(clip.copy(startMs = s, endMs = e))
                        else range = clip.startMs.toFloat()..clip.endMs.toFloat()
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onMove(-1) }) { Text("←") }
                    OutlinedButton(onClick = { onMove(1) }) { Text("→") }
                    OutlinedButton(onClick = onRemove) { Text("Delete") }
                }
            }
        }
    }

    private fun export(ctx: Context, clips: List<Clip>, done: (String) -> Unit) {
        val items = clips.map { EditedMediaItem.Builder(it.toMediaItem()).build() }
        val composition = Composition.Builder(EditedMediaItemSequence(items)).build()
        val out = File(ctx.cacheDir, "forgecut_${System.currentTimeMillis()}.mp4")
        transformer = Transformer.Builder(ctx)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    val ok = saveToMovies(ctx, out)
                    done(if (ok) "Saved to Movies/ForgeCut" else "Export done, but saving failed")
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

    private fun durationOf(ctx: Context, uri: Uri): Long = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        } finally { r.release() }
    }.getOrDefault(0L)

    private fun fmt(ms: Long): String = "%d:%02d.%d".format(ms / 60000, (ms / 1000) % 60, (ms % 1000) / 100)
}
