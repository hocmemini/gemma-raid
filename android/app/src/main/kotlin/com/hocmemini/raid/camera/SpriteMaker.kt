package com.hocmemini.raid.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.media.ExifInterface
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * Cuts the photographed object out of the spawn photo so it can be animated as the monster.
 * Primary: ML Kit subject segmentation (on-device). Fallback: feathered centre crop.
 */
object SpriteMaker {
    private const val TAG = "Sprite"

    suspend fun make(context: Context, photoPath: String): File? = withContext(Dispatchers.IO) {
        val src = decode(photoPath, 1024) ?: return@withContext null
        val t0 = System.currentTimeMillis()
        val cut = runCatching { segment(src) }.getOrNull()?.let { trimAlpha(it) }
        val usable = cut != null && cut.width * cut.height > src.width * src.height * 0.02
        val sprite = if (usable) cut!! else centerCrop(src)
        Log.i(TAG, "segmentation ${if (usable) "ok" else "fallback"} in ${System.currentTimeMillis() - t0} ms; sprite ${sprite.width}x${sprite.height}")
        val scaled = fit(sprite, 512)
        val f = File(context.cacheDir, "sprite-${System.currentTimeMillis()}.png")
        f.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        f
    }

    private suspend fun segment(bmp: Bitmap): Bitmap? = withTimeoutOrNull(6000) {
        suspendCancellableCoroutine { cont ->
            val seg = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundBitmap().build())
            seg.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { r -> cont.resume(r.foregroundBitmap) }
                .addOnFailureListener { e -> Log.w(TAG, "segmentation failed: ${e.message}"); cont.resume(null) }
        }
    }

    private fun decode(path: String, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
        val rot = when (runCatching { ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
        }
        return if (rot == 0f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
    }

    /** Crop to the opaque bounding box (alpha > 24) with a little padding. */
    private fun trimAlpha(bmp: Bitmap): Bitmap? {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) if ((px[y * w + x] ushr 24) > 24) { if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y }
        if (maxX < 0) return null
        val pad = 8
        minX = (minX - pad).coerceAtLeast(0); minY = (minY - pad).coerceAtLeast(0)
        maxX = (maxX + pad).coerceAtMost(w - 1); maxY = (maxY + pad).coerceAtMost(h - 1)
        return Bitmap.createBitmap(bmp, minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    /** Centre square (60% of the short side) with a soft radial alpha edge. */
    private fun centerCrop(src: Bitmap): Bitmap {
        val side = (minOf(src.width, src.height) * 0.6f).toInt()
        val x = (src.width - side) / 2; val y = (src.height - side) / 2
        val crop = Bitmap.createBitmap(src, x, y, side, side)
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(crop, 0f, 0f, null)
        val mask = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            shader = RadialGradient(side / 2f, side / 2f, side / 2f, intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT), floatArrayOf(0f, 0.72f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, side.toFloat(), side.toFloat(), mask)
        return out
    }

    private fun fit(bmp: Bitmap, maxSide: Int): Bitmap {
        val s = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        return if (s >= 1f) bmp else Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
    }
}
