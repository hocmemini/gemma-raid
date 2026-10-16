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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hocmemini.raid.GameSession
import com.hocmemini.raid.game.Stats
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment

@Composable
fun StatsScreen(vm: GameSession, onBack: () -> Unit) {
    val st by vm.statsStore.stats.collectAsState()
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("STATS", fontSize = 32.sp, fontWeight = FontWeight.Black)
            OutlinedButton(onBack) { Text("Back") }
        }

        StatCard("Campaign") {
            StatRow("Raids", st.raids); StatRow("Encounters", st.encounters); StatRow("Kills", st.kills)
            StatRow("Bosses slain", st.bosses); StatRow("Party wipes", st.wipes)
            StatRow("Time in raids", fmtTime(st.playSeconds)); StatRow("Steps walked exploring", st.stepsWalked)
            StatRow("Omens read", st.omensRead); StatRow("Longest fight", "${st.longestFightTurns} turns")
            StatRow("Things that followed you", st.lurkersSeen); StatRow("Ambushes survived", st.ambushes)
        }
        StatCard("Combat") {
            StatRow("Damage dealt", st.damageDealt); StatRow("Damage taken", st.damageTaken); StatRow("Best single hit", st.bestHit)
            StatRow("★ Legendary hits", st.legendaryHits); StatRow("★ Stylish hits", st.stylishHits)
            StatRow("Parries", st.parries); StatRow("Potions drunk", st.potions); StatRow("Second winds", st.secondWinds)
        }
        StatCard("How you fight") {
            StatRow("Typed actions", st.typedActions); StatRow("Shouted actions", st.shouts)
            StatRow("Loudest shout", "${st.loudestShout}%"); StatRow("Phone swings", st.swings)
        }
        StatCard("Gemma 4 E2B on this phone") {
            StatRow("Monsters spawned", st.spawns); StatRow("Avg photo → monster", fmtMs(st.avgSpawnMs))
            StatRow("Actions resolved", st.resolves); StatRow("Avg action → verdict", fmtMs(st.avgResolveMs))
            StatRow("…of which spoken", st.audioResolves); StatRow("Tool-call retries", st.toolRetries)
            Text("Every number above came from a tool call Gemma made, not from parsing its prose.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
        StatCard("Hall of monsters") {
            if (st.history.isEmpty()) Text("Nothing slain yet.")
            st.history.asReversed().forEach { e ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val thumb = remember(e.sprite) { e.sprite.takeIf { it.isNotBlank() }?.let { runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() } }
                    if (thumb != null) Image(thumb, e.name, Modifier.size(56.dp).padding(end = 8.dp), contentScale = ContentScale.Fit) else Text(e.emoji, modifier = Modifier.padding(end = 8.dp), fontSize = 28.sp)
                    Column {
                        Text("${e.name}${if (e.boss) " 👑" else ""} · ${e.maxHp} HP", fontWeight = FontWeight.SemiBold)
                        Text("${e.outcome} · ${e.turns} turns · ${fmtTime(e.seconds)}${if (e.loot.isNotBlank()) " · 🎁 ${e.loot}" else ""}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ vm.statsStore.reset() }) { Text("Reset stats") }
            TextButton({ vm.resetCharacter() }) { Text("Reset character") }
        }
    }
}

@Composable
private fun StatCard(title: String, content: @Composable () -> Unit) {
    Card { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(bottom = 6.dp)); content() } }
}

@Composable
private fun StatRow(label: String, value: Any) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label); Text(value.toString(), fontWeight = FontWeight.SemiBold)
    }
}

private fun fmtMs(ms: Long) = if (ms <= 0) "—" else String.format("%.1f s", ms / 1000.0)
private fun fmtTime(s: Int) = if (s < 60) "${s}s" else if (s < 3600) "${s / 60}m ${s % 60}s" else "${s / 3600}h ${(s % 3600) / 60}m"
