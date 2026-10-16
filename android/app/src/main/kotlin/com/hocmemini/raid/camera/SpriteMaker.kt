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
import com.google.mlkit.vision.segmentation.subject.Subject
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

    suspend fun make(context: Context, photoPath: String, requireSubject: Boolean = false): File? = withContext(Dispatchers.IO) {
        val src = decode(photoPath, 1024) ?: return@withContext null
        val t0 = System.currentTimeMillis()
        val picked = runCatching { segmentAimed(src) }.getOrNull()
        val cut = picked?.second?.let { trimAlpha(it) }
        val usable = cut != null && cut.width * cut.height > src.width * src.height * 0.01
        if (requireSubject && (!usable || picked?.first?.contains("under crosshair") != true)) { Log.i(TAG, "scout: nothing under the crosshair (${picked?.first})"); return@withContext null }
        val sprite = if (usable) cut!! else centerCrop(src)
        Log.i(TAG, "segmentation ${if (usable) "ok (${picked?.first})" else "fallback"} in ${System.currentTimeMillis() - t0} ms; sprite ${sprite.width}x${sprite.height}")
        val scaled = fit(sprite, 512)
        val f = File(context.cacheDir, "sprite-${System.currentTimeMillis()}.png")
        f.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        f
    }

    /**
     * All subjects, then the one the player aimed at: boxes that contain the frame centre, smallest first
     * (the mug on the table beats the table), skipping anything that is basically the whole frame.
     * Falls back to the subject whose centre is nearest the crosshair. Returns (reason, full-frame cut-out).
     */
    private suspend fun segmentAimed(bmp: Bitmap): Pair<String, Bitmap?>? = withTimeoutOrNull(7000) {
        suspendCancellableCoroutine { cont ->
            val opts = SubjectSegmenterOptions.Builder()
                .enableMultipleSubjects(SubjectSegmenterOptions.SubjectResultOptions.Builder().enableSubjectBitmap().build())
                .build()
            SubjectSegmentation.getClient(opts).process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { r ->
                    val subjects = r.subjects
                    if (subjects.isEmpty()) { cont.resume("no subjects" to null); return@addOnSuccessListener }
                    val cx = bmp.width / 2f; val cy = bmp.height / 2f
                    val frame = bmp.width.toFloat() * bmp.height
                    fun area(s: Subject) = s.width.toFloat() * s.height
                    fun contains(s: Subject) = cx >= s.startX && cx <= s.startX + s.width && cy >= s.startY && cy <= s.startY + s.height
                    fun dist(s: Subject): Float { val dx = (s.startX + s.width / 2f) - cx; val dy = (s.startY + s.height / 2f) - cy; return dx * dx + dy * dy }
                    val sane = subjects.filter { area(it) in (frame * 0.005f)..(frame * 0.6f) }
                    val aimed = sane.filter { contains(it) }.minByOrNull { area(it) }
                        ?: sane.minByOrNull { dist(it) }
                        ?: subjects.minByOrNull { dist(it) }!!
                    val why = "${subjects.size} subjects, picked ${aimed.width}x${aimed.height}${if (contains(aimed)) " under crosshair" else " nearest"}"
                    // paste the subject's bitmap back at its frame position so trimAlpha/crop logic stays the same
                    val full = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                    aimed.bitmap?.let { Canvas(full).drawBitmap(it, aimed.startX.toFloat(), aimed.startY.toFloat(), null) }
                    cont.resume(why to full)
                }
                .addOnFailureListener { e -> Log.w(TAG, "segmentation failed: ${e.message}"); cont.resume("failed" to null) }
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

    /** 8x8 grayscale thumbprint of a sprite file, for "have we seen this already". */
    fun signature(file: File): IntArray {
        val b = BitmapFactory.decodeFile(file.absolutePath) ?: return IntArray(64)
        val t = Bitmap.createScaledBitmap(b, 8, 8, true)
        val out = IntArray(64)
        for (y in 0 until 8) for (x in 0 until 8) { val p = t.getPixel(x, y); out[y * 8 + x] = if ((p ushr 24) < 40) -1 else ((p shr 16 and 0xff) + (p shr 8 and 0xff) + (p and 0xff)) / 3 }
        return out
    }
    fun similar(a: IntArray, b: IntArray): Boolean {
        var d = 0; var n = 0
        for (i in 0 until 64) { if (a[i] < 0 && b[i] < 0) continue; if (a[i] < 0 || b[i] < 0) { d += 255; n++; continue }; d += kotlin.math.abs(a[i] - b[i]); n++ }
        return n == 0 || d / n < 28
    }

    private fun fit(bmp: Bitmap, maxSide: Int): Bitmap {
        val s = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        return if (s >= 1f) bmp else Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
    }
}
