package dev.forgecut

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File

/** Renders a title to a full-frame transparent PNG so FFmpeg can overlay it (no font/freetype needed). */
fun renderTextPng(layer: TextLayer, w: Int, h: Int, out: File) {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = layer.size.frac * h
        typeface = Typeface.DEFAULT_BOLD
        color = layer.color
    }
    val width = (w * 0.9f).toInt()
    val layout = StaticLayout.Builder.obtain(layer.text, 0, layer.text.length, paint, width)
        .setAlignment(Layout.Alignment.ALIGN_CENTER)
        .build()
    val cx = layer.posX * w
    val cy = layer.posY * h
    canvas.translate(cx - layout.width / 2f, cy - layout.height / 2f)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = paint.textSize * 0.12f
    paint.strokeJoin = Paint.Join.ROUND
    paint.color = Color.BLACK
    layout.draw(canvas)
    paint.style = Paint.Style.FILL
    paint.color = layer.color
    layout.draw(canvas)
    out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bmp.recycle()
}
