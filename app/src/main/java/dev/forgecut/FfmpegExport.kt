package dev.forgecut

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.Locale

object FfmpegExport {
    /** The FFmpeg CLI ships as libffmpeg.so inside the APK so Android lets us execute it. */
    fun binary(ctx: Context): File? =
        File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so").takeIf { it.exists() }

    private fun sec(ms: Long) = String.format(Locale.US, "%.3f", ms / 1000.0)

    fun run(
        ctx: Context,
        clips: List<Clip>,
        texts: List<TextLayer>,
        overlays: List<OverlayItem> = emptyList(),
        out: File,
        onProcess: (Process) -> Unit,
        onProgress: (Float) -> Unit,
    ): Result<Unit> {
        val bin = binary(ctx) ?: return Result.failure(IllegalStateException("FFmpeg engine not installed"))
        if (clips.isEmpty()) return Result.failure(IllegalStateException("Nothing to export"))

        val work = File(ctx.cacheDir, "ff").apply { deleteRecursively(); mkdirs() }
        val uris = clips.map { it.uri }.distinct()
        val files = uris.mapIndexed { i, u ->
            val f = File(work, "src$i")
            val input = ctx.contentResolver.openInputStream(u)
                ?: return Result.failure(IOException("Cannot read a source video"))
            input.use { ins -> f.outputStream().use { ins.copyTo(it) } }
            f
        }

        // Output size = first clip, capped at 1080p, even numbers
        val first = clips.first()
        var w = if (first.dispW > 0) first.dispW else 1280
        var h = if (first.dispH > 0) first.dispH else 720
        val sc = minOf(1f, 1920f / maxOf(w, h))
        w = (w * sc).toInt() / 2 * 2
        h = (h * sc).toInt() / 2 * 2

        val joins = planJoins(clips)
        val totalPreview = clips.sumOf { it.lengthMs }
        val exportMs = totalPreview - joins.sumOf { it.dMs }

        val titles = texts.filter { it.text.isNotBlank() && it.startMs < totalPreview }
            .mapIndexed { i, t ->
                val f = File(work, "t$i.png")
                renderTextPng(t, w, h, f)
                t to f
            }

        val activeOverlays = overlays.filter { it.startMs < totalPreview }
        val ovFiles = activeOverlays.mapIndexedNotNull { j, ov ->
            val f = File(work, "ov$j")
            val copied = ctx.contentResolver.openInputStream(ov.uri)?.use { ins ->
                f.outputStream().use { ins.copyTo(it) }
            }
            if (copied != null && copied > 0) ov to f else null
        }

        val args = mutableListOf(bin.absolutePath, "-y", "-loglevel", "error", "-nostats", "-progress", "pipe:1")
        files.forEach { args += listOf("-i", it.absolutePath) }
        titles.forEach { args += listOf("-i", it.second.absolutePath) }
        ovFiles.forEach { (ov, f) ->
            if (ov.type == OverlayType.IMAGE) {
                args += listOf("-loop", "1", "-i", f.absolutePath)
            } else {
                args += listOf("-i", f.absolutePath)
            }
        }

        // An input pad can only be used once, so split sources used by several clips
        val vUses = IntArray(files.size)
        val aUses = IntArray(files.size)
        clips.forEach { c ->
            val i = uris.indexOf(c.uri)
            vUses[i]++
            if (c.hasAudio && !c.muted) aUses[i]++
        }
        val sb = StringBuilder()
        val vPads = Array(files.size) { ArrayDeque<String>() }
        val aPads = Array(files.size) { ArrayDeque<String>() }
        for (i in files.indices) {
            if (vUses[i] == 1) vPads[i].addLast("[$i:v]")
            else if (vUses[i] > 1) {
                val names = (0 until vUses[i]).map { "[sv${i}_$it]" }
                sb.append("[$i:v]split=${vUses[i]}${names.joinToString("")};")
                names.forEach { vPads[i].addLast(it) }
            }
            if (aUses[i] == 1) aPads[i].addLast("[$i:a]")
            else if (aUses[i] > 1) {
                val names = (0 until aUses[i]).map { "[sa${i}_$it]" }
                sb.append("[$i:a]asplit=${aUses[i]}${names.joinToString("")};")
                names.forEach { aPads[i].addLast(it) }
            }
        }

        clips.forEachIndexed { k, c ->
            val i = uris.indexOf(c.uri)
            val vin = vPads[i].removeFirst()
            sb.append("${vin}trim=start=${sec(c.startMs)}:end=${sec(c.endMs)},setpts=PTS-STARTPTS,")
            sb.append("scale=$w:$h:force_original_aspect_ratio=decrease,pad=$w:$h:(ow-iw)/2:(oh-ih)/2,")
            sb.append("setsar=1,fps=30,format=yuv420p,settb=AVTB[v$k];")
            if (c.hasAudio && !c.muted) {
                val ain = aPads[i].removeFirst()
                sb.append("${ain}atrim=start=${sec(c.startMs)}:end=${sec(c.endMs)},asetpts=PTS-STARTPTS,")
                sb.append("aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo[a$k];")
            } else {
                sb.append("anullsrc=r=48000:cl=stereo,atrim=duration=${sec(c.lengthMs)},asetpts=PTS-STARTPTS,")
                sb.append("aformat=sample_fmts=fltp:channel_layouts=stereo[a$k];")
            }
        }

        var curV = "v0"
        var curA = "a0"
        var merged = clips[0].lengthMs
        for (k in 1 until clips.size) {
            val c = clips[k]
            val d = joins[k - 1].dMs
            val nv = "xv$k"
            val na = "xa$k"
            val ff = c.transition.ffmpeg
            if (ff == null || d == 0L) {
                sb.append("[$curV][v$k]concat=n=2:v=1:a=0,settb=AVTB[$nv];")
                sb.append("[$curA][a$k]concat=n=2:v=0:a=1[$na];")
                merged += c.lengthMs
            } else {
                sb.append("[$curV][v$k]xfade=transition=$ff:duration=${sec(d)}:offset=${sec(merged - d)},settb=AVTB[$nv];")
                sb.append("[$curA][a$k]acrossfade=d=${sec(d)}[$na];")
                merged += c.lengthMs - d
            }
            curV = nv
            curA = na
        }

        val ovInputBase = files.size + titles.size
        ovFiles.forEachIndexed { j, (ov, _) ->
            val inputIdx = ovInputBase + j
            val s = toExportMs(joins, ov.startMs)
            val e = toExportMs(joins, minOf(ov.endMs, totalPreview))
            val durSec = sec(ov.endMs - ov.startMs)
            val targetW = (w * ov.scale).toInt() / 2 * 2
            val padV = "ov_sc$j"
            val nextV = "nov$j"

            if (ov.type == OverlayType.IMAGE) {
                sb.append("[$inputIdx:v]scale=$targetW:-2,fps=30,format=yuv420p[$padV];")
            } else {
                sb.append("[$inputIdx:v]trim=start=0:end=$durSec,setpts=PTS-STARTPTS,scale=$targetW:-2,fps=30,format=yuv420p[$padV];")
            }
            sb.append("[$curV][$padV]overlay=x='min(max(0,${w}*${ov.posX}-w/2),${w}-w)':y='min(max(0,${h}*${ov.posY}-h/2),${h}-h)':enable='between(t,${sec(s)},${sec(e)})'[$nextV];")
            curV = nextV

            if (ov.type == OverlayType.VIDEO && !ov.muted) {
                val hasAud = probe(ctx, ov.uri)?.hasAudio == true
                if (hasAud) {
                    val nextA = "noa$j"
                    val sMs = s.coerceAtLeast(0L)
                    sb.append("[$inputIdx:a]atrim=start=0:end=$durSec,asetpts=PTS-STARTPTS,aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo,adelay=${sMs}|${sMs}[ov_aud$j];")
                    sb.append("[$curA][ov_aud$j]amix=inputs=2:duration=first:dropout_transition=0[$nextA];")
                    curA = nextA
                }
            }
        }

        titles.forEachIndexed { j, (t, _) ->
            val s = toExportMs(joins, t.startMs)
            val e = toExportMs(joins, minOf(t.endMs, totalPreview))
            val next = "txt$j"
            sb.append("[$curV][${files.size + j}:v]overlay=0:0:format=auto:enable='between(t,${sec(s)},${sec(e)})'[$next];")
            curV = next
        }

        args += listOf(
            "-filter_complex", sb.toString().trimEnd(';'),
            "-map", "[$curV]", "-map", "[$curA]",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "21", "-pix_fmt", "yuv420p", "-r", "30",
            "-c:a", "aac", "-b:a", "192k",
            "-movflags", "+faststart",
            out.absolutePath
        )

        val proc = try {
            ProcessBuilder(args).redirectErrorStream(true).start()
        } catch (e: IOException) {
            return Result.failure(e)
        }
        onProcess(proc)

        val tail = ArrayDeque<String>()
        val kv = Regex("^[A-Za-z_0-9]+=\\S*$")
        proc.inputStream.bufferedReader().forEachLine { line ->
            if (line.startsWith("out_time_us=")) {
                val us = line.removePrefix("out_time_us=").toLongOrNull()
                if (us != null && exportMs > 0) onProgress((us / 1000f / exportMs).coerceIn(0f, 1f))
            } else if (!kv.matches(line)) {
                tail.addLast(line)
                if (tail.size > 12) tail.removeFirst()
            }
        }
        val code = proc.waitFor()
        work.deleteRecursively()
        return if (code == 0) Result.success(Unit)
        else Result.failure(IOException(tail.joinToString("\n").takeLast(400).ifBlank { "FFmpeg exited with code $code" }))
    }
}
