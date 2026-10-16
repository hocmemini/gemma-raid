package com.hocmemini.raid.game

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Encounter(
    val emoji: String, val name: String, val maxHp: Int, val outcome: String, val turns: Int, val seconds: Int,
    val loot: String = "", val boss: Boolean = false, val at: Long = System.currentTimeMillis(),
    /** Absolute path of the saved cut-out (bestiary thumbnail), if any. */
    val sprite: String = "",
)

/** Lifetime numbers for this phone. Everything here is derived from game events, no model guesses. */
@Serializable
data class Stats(
    val raids: Int = 0,
    val encounters: Int = 0,
    val kills: Int = 0,
    val bosses: Int = 0,
    val wipes: Int = 0,
    val damageDealt: Int = 0,
    val damageTaken: Int = 0,
    val bestHit: Int = 0,
    val legendaryHits: Int = 0,
    val stylishHits: Int = 0,
    val parries: Int = 0,
    val potions: Int = 0,
    val secondWinds: Int = 0,
    val swings: Int = 0,
    val shouts: Int = 0,
    val loudestShout: Int = 0,          // percent
    val typedActions: Int = 0,
    val stepsWalked: Int = 0,
    val omensRead: Int = 0,
    val lurkersSeen: Int = 0,
    val ambushes: Int = 0,
    val playSeconds: Int = 0,
    val longestFightTurns: Int = 0,
    // Gemma, host only
    val spawns: Int = 0,
    val spawnMs: Long = 0,
    val resolves: Int = 0,
    val resolveMs: Long = 0,
    val audioResolves: Int = 0,
    val toolRetries: Int = 0,
    val history: List<Encounter> = emptyList(),
) {
    val avgSpawnMs: Long get() = if (spawns == 0) 0 else spawnMs / spawns
    val avgResolveMs: Long get() = if (resolves == 0) 0 else resolveMs / resolves
}

class StatsStore(context: Context) {
    private val prefs = context.getSharedPreferences("raid-stats", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _stats = MutableStateFlow(load())
    val stats: StateFlow<Stats> = _stats

    private fun load(): Stats = runCatching { json.decodeFromString(Stats.serializer(), prefs.getString("v1", null) ?: "{}") }.getOrDefault(Stats())

    fun update(f: (Stats) -> Stats) {
        val next = f(_stats.value)
        _stats.value = next
        prefs.edit().putString("v1", json.encodeToString(Stats.serializer(), next)).apply()
    }

    fun reset() = update { Stats() }
}
