package com.hocmemini.raid.llm

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.google.ai.edge.litertlm.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Picks the strongest Gemma 4 file this phone can run and the fastest backend it has.
 * Rule of thumb: E4B on 11 GB+ phones when present, else E2B; NPU only when Google ships a build
 * for this exact SoC (Tensor G5/G6 today), otherwise GPU, with CPU as a silent fallback.
 */
class ModelManager(private val context: Context) {
    data class Choice(val file: File, val label: String, val backend: Backend, val backendName: String, val why: String)

    val soc: String = (if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE).ifBlank { Build.HARDWARE }
    val totalGb: Float = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem / 1_073_741_824f

    private fun dirs() = listOfNotNull(context.filesDir, context.getExternalFilesDir(null))
    fun candidates(): List<File> = dirs().flatMap { d -> d.listFiles { f -> f.isFile && f.name.endsWith(".litertlm") && f.length() > 100_000_000 }?.toList() ?: emptyList() }.distinctBy { it.name }

    /** NPU builds are named like gemma-4-E2B-it_Google_Tensor_G5.litertlm; match the SoC token loosely. */
    private fun npuFileFor(files: List<File>): File? {
        val tag = soc.replace(" ", "_").lowercase()
        return files.firstOrNull { f -> val n = f.name.lowercase(); n.contains("_google_tensor_") && tag.isNotBlank() && n.contains(tag.substringAfter("tensor_", tag)) }
    }

    fun best(forceCpu: Boolean = false): Choice? {
        val files = candidates()
        if (files.isEmpty()) return null
        val npu = npuFileFor(files)
        val wantBig = totalGb >= 11f
        val generic = files.filterNot { it.name.contains("_") && it.name.contains("Tensor", true) || it.name.contains("qualcomm", true) || it.name.contains("intel", true) }
        val e4b = generic.firstOrNull { it.name.contains("E4B", true) }
        val e2b = generic.firstOrNull { it.name.contains("E2B", true) } ?: generic.maxByOrNull { it.length() }
        val file = when {
            npu != null -> npu
            wantBig && e4b != null -> e4b
            else -> e2b ?: files.first()
        }
        val label = when {
            file.name.contains("E4B", true) -> "Gemma 4 E4B"
            file.name.contains("E2B", true) -> "Gemma 4 E2B"
            else -> file.name.removeSuffix(".litertlm")
        }
        return when {
            forceCpu -> Choice(file, label, Backend.CPU(), "CPU", "forced")
            npu != null -> Choice(file, label, Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir), "NPU", "Google ships an NPU build for $soc")
            else -> Choice(file, label, Backend.GPU(), "GPU", if (soc.contains("Tensor", true)) "$soc has no NPU build (G5/G6 only); GPU via OpenCL is fastest" else "GPU is fastest on $soc")
        }
    }

    fun why(c: Choice?): String {
        if (c == null) return "no model file yet"
        val e4bNote = if (totalGb >= 11f && !c.label.contains("E4B")) " · E4B would fit in ${"%.0f".format(totalGb)} GB but is ~1.7× slower; download it under Advanced if you want smarter over faster" else ""
        return "${c.label} · $soc · ${c.backendName} — ${c.why}$e4bNote"
    }

    // ---------- in-app download (needs internet once) ----------

    fun downloadUrl(variant: String) = when (variant) {
        "E4B" -> "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm"
        else -> "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    }

    fun startDownload(variant: String): Long {
        val name = "gemma-4-$variant-it.litertlm"
        val dm = context.getSystemService(DownloadManager::class.java)
        File(context.getExternalFilesDir(null), name).delete()
        val req = DownloadManager.Request(Uri.parse(downloadUrl(variant)))
            .setTitle("Raid: Gemma 4 $variant").setDescription("Open-weight model for the dungeon master")
            .setDestinationInExternalFilesDir(context, null, name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        return dm.enqueue(req)
    }

    /** Polls a download until it finishes; reports "x / y MB" lines. Returns true on success. */
    suspend fun awaitDownload(id: Long, onProgress: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val dm = context.getSystemService(DownloadManager::class.java)
        while (true) {
            val q = DownloadManager.Query().setFilterById(id)
            dm.query(q)?.use { c ->
                if (!c.moveToFirst()) return@withContext false
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> return@withContext true
                    DownloadManager.STATUS_FAILED -> { onProgress("download failed (${c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))})"); return@withContext false }
                    else -> onProgress("downloading… ${done shr 20} / ${if (total > 0) (total shr 20).toString() else "?"} MB")
                }
            } ?: return@withContext false
            delay(1000)
        }
        @Suppress("UNREACHABLE_CODE") false
    }
}
