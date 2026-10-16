package com.hocmemini.raid.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.provider.MediaStore
import com.hocmemini.raid.game.Victory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A shareable 1080x1350 trophy card: the cut-out, the name, who felled it, XP, loot. Saved to Pictures/Raid. */
object TrophyCard {
    fun render(context: Context, v: Victory, spriteName: String, epithet: String): Bitmap {
        val w = 1080; val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0xFF17130F.toInt(), 0xFF2A2117.toInt(), 0xFF0A0603.toInt()), null, Shader.TileMode.CLAMP) })
        val gold = 0xFFD4AF37.toInt(); val parchment = 0xFFE8D8B0.toInt(); val moss = 0xFF69F0AE.toInt()
        val serifBold = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        fun text(t: String, y: Float, size: Float, color: Int, bold: Boolean = true, alpha: Int = 255) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = size; typeface = if (bold) serifBold else Typeface.SERIF; textAlign = Paint.Align.CENTER; this.alpha = alpha }
            c.drawText(t, w / 2f, y, p)
        }
        // frame
        c.drawRoundRect(RectF(40f, 40f, w - 40f, h - 40f), 28f, 28f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gold; style = Paint.Style.STROKE; strokeWidth = 6f })
        text("⚔ RAID", 140f, 54f, gold)
        text(if (v.name.startsWith("👑")) "BOSS SLAIN" else "SLAIN", 215f, 40f, 0xFFFF6E40.toInt())
        // sprite
        val sp = File(context.cacheDir, spriteName).takeIf { spriteName.isNotBlank() && it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
        if (sp != null) {
            val maxW = 700f; val maxH = 560f
            val sc = minOf(maxW / sp.width, maxH / sp.height)
            val dw = sp.width * sc; val dh = sp.height * sc
            val left = (w - dw) / 2; val top = 270f + (maxH - dh) / 2
            c.drawOval(RectF(left + dw * 0.1f, top + dh - 30f, left + dw * 0.9f, top + dh + 30f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; alpha = 90 })
            c.drawBitmap(sp, null, RectF(left, top, left + dw, top + dh), Paint(Paint.FILTER_BITMAP_FLAG))
        } else text(v.emoji, 600f, 300f, parchment)
        var y = 920f
        val name = v.name.removePrefix("👑 ")
        text(name, y, if (name.length > 18) 52f else 64f, gold); y += 70f
        text("felled by ${v.by}${if (epithet.isNotBlank()) " $epithet" else ""} in ${v.turns} turn${if (v.turns == 1) "" else "s"}", y, 32f, parchment, false); y += 70f
        text("+${v.xp} XP", y, 56f, moss); y += 70f
        if (v.loot.isNotBlank()) { text("🎁 ${v.loot}", y, 36f, gold); y += 44f; text(v.lootEffect.take(60), y, 26f, parchment, false, 200); y += 40f }
        text(SimpleDateFormat("EEE d MMM yyyy · HH:mm", Locale.US).format(Date(v.turns.toLong().let { System.currentTimeMillis() })), h - 120f, 26f, parchment, false, 160)
        text("a real object, judged by Gemma 4 running on the phone", h - 80f, 24f, parchment, false, 140)
        return bmp
    }

    /** Saves to Pictures/Raid via MediaStore (no permission needed on Android 10+). Returns the display name. */
    /** Last saved card, for sharing. */
    @Volatile var lastUri: android.net.Uri? = null

    fun save(context: Context, bmp: Bitmap, baseName: String): String? {
        val name = "raid-" + baseName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40) + "-" + System.currentTimeMillis() / 1000 + ".png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Raid")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: return null
        lastUri = uri
        return name
    }
}
