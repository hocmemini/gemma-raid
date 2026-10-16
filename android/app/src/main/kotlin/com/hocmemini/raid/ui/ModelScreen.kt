package com.hocmemini.raid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hocmemini.raid.GameSession

/** Choose the dungeon master's brain: what's on the phone, what can be fetched, and what this phone should run. */
@Composable
fun ModelScreen(vm: GameSession, onBack: () -> Unit) {
    val gemmaStatus by vm.gemmaStatus.collectAsState()
    val gemmaLoading by vm.gemmaLoading.collectAsState()
    val gemmaReady by vm.gemmaReady.collectAsState()
    val downloading by vm.downloading.collectAsState()
    val choice by vm.modelChoice.collectAsState()
    val selected by vm.selectedModel.collectAsState()
    val files = vm.models.candidates()
    val rec = vm.models.recommendedVariant()

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("🧠 BRAIN", style = MaterialTheme.typography.headlineMedium, color = Dungeon.gold)
            OutlinedButton(onBack) { Text("Back") }
        }
        Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("This phone", fontWeight = FontWeight.Bold)
            Text("${vm.models.soc} · ${"%.0f".format(vm.models.totalGb)} GB RAM · backend: ${choice?.backendName ?: "—"}")
            Text(if (vm.models.soc.contains("Tensor", true) && choice?.backendName != "NPU") "No NPU build for this chip (Google ships them for Tensor G5/G6 only), so the GPU does the work. CPU is the automatic fallback." else "GPU is the fastest backend here; CPU is the automatic fallback.", style = MaterialTheme.typography.bodySmall)
            Text("Now: $gemmaStatus", style = MaterialTheme.typography.bodySmall, color = Dungeon.gold)
            if (gemmaLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (downloading) OutlinedButton({ vm.cancelDownload() }) { Text("Cancel download") }
        } }

        Text("Models", fontWeight = FontWeight.Bold)
        listOf(
            Triple("E2B", "Gemma 4 E2B · 2.6 GB", "Fast. ~12 s per photo, ~6 s per action on a Pixel 8 Pro. The right pick for a party game."),
            Triple("E4B", "Gemma 4 E4B · 3.7 GB", "Smarter prose and tool use, ~1.7× slower. Needs 8 GB+ RAM. Better for story, worse for pace."),
        ).forEach { (variant, title, blurb) ->
            val file = files.firstOrNull { it.name.contains(variant, true) }
            val isSel = (selected ?: choice?.label)?.contains(variant) == true
            Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(title, fontWeight = FontWeight.Bold)
                    if (variant == rec) Text("recommended", color = Dungeon.moss, style = MaterialTheme.typography.bodySmall)
                }
                Text(blurb, style = MaterialTheme.typography.bodySmall)
                Text(if (file != null) "on this phone (${file.length() shr 20} MB)${if (isSel) " · selected" else ""}" else "not downloaded", style = MaterialTheme.typography.bodySmall, color = if (file != null) Dungeon.gold else Dungeon.parchment.copy(alpha = 0.6f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (file != null) Button({ vm.selectModel(variant) }, enabled = !gemmaLoading && !isSel) { Text(if (isSel) "In use" else "Use this") }
                    else OutlinedButton({ vm.downloadModel(variant) }, enabled = !gemmaLoading) { Text("Download") }
                    if (file != null && !isSel) TextButton({ vm.deleteModel(variant) }, enabled = !gemmaLoading) { Text("Delete") }
                }
            } }
        }
        if (files.any { !it.name.contains("E2B", true) && !it.name.contains("E4B", true) }) {
            Text("Other files: " + files.filter { !it.name.contains("E2B", true) && !it.name.contains("E4B", true) }.joinToString { it.name }, style = MaterialTheme.typography.bodySmall)
        }
        Text("Models are open weights (Apache 2.0) from Hugging Face's litert-community. Downloads need internet once; play never does.", style = MaterialTheme.typography.bodySmall, color = Dungeon.parchment.copy(alpha = 0.7f))
        if (gemmaReady) Text("Switching models reloads the dungeon master (a few seconds warm, ~30 s cold).", style = MaterialTheme.typography.bodySmall)
    }
}
