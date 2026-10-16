package com.hocmemini.raid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hocmemini.raid.GameSession

/** Everything that was ever said, oldest first, kept on this phone. */
@Composable
fun JournalScreen(vm: GameSession, onBack: () -> Unit) {
    val lines by vm.journal.collectAsState()
    val scale by vm.textScale.collectAsState()
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.size - 1) }

    Column(Modifier.fillMaxSize().background(Dungeon.stone).safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("📖 JOURNAL", style = MaterialTheme.typography.headlineMedium, color = Dungeon.gold)
            Row { TextButton({ vm.cycleTextScale() }) { Text("Aa") }; OutlinedButton(onBack) { Text("Back") } }
        }
        if (lines.isEmpty()) Text("Nothing written yet. Host or join a raid.", color = Dungeon.parchment)
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().background(Dungeon.panel).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(lines) { _, raw ->
                val stamp = raw.take(5); val body = raw.drop(7)
                val mark = body.startsWith("━━")
                Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
                    Text(stamp, color = Dungeon.parchment.copy(alpha = 0.45f), fontSize = (11 * scale).sp, modifier = Modifier.padding(end = 8.dp, top = 2.dp))
                    Text(
                        if (mark) body.trim('━', ' ') else body,
                        color = when {
                            mark -> Dungeon.gold
                            body.startsWith("−") || body.contains("LEGENDARY") -> Dungeon.gold.copy(alpha = 0.9f)
                            body.startsWith("📜") || body.startsWith("🏷") -> Dungeon.parchment
                            body.startsWith("🧪") || body.startsWith("💚") || body.startsWith("🎁") || body.startsWith("💰") -> Dungeon.moss
                            body.startsWith("⚡") || body.contains("falls") -> Dungeon.ember
                            else -> Color.White.copy(alpha = 0.92f)
                        },
                        fontSize = ((if (mark) 17 else 15) * scale).sp,
                        fontWeight = if (mark) FontWeight.Black else FontWeight.Normal,
                    )
                }
            }
        }
        TextButton({ vm.clearJournal() }) { Text("Clear journal", color = Dungeon.parchment.copy(alpha = 0.6f)) }
    }
}
