package com.hocmemini.raid.llm

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SpawnedMonster(val name: String, val hp: Int, val attacks: List<String>, val weakness: String, val flavor: String)
data class Resolved(val damageToMonster: Int, val damageToPlayer: Int, val narration: String)

/**
 * The tools Gemma 4 calls. LiteRT-LM runs them automatically mid-generation; they just stash
 * their arguments so the game loop can read structured results after sendMessage returns.
 */
class GameTools : ToolSet {
    @Volatile var spawned: SpawnedMonster? = null
    @Volatile var resolved: Resolved? = null

    @Tool(description = "Turn the real-world object or scene in the photo into the monster the players must fight. Call exactly once.")
    fun spawn_monster(
        @ToolParam(description = "Short dramatic monster name derived from the object, e.g. 'The Vending Golem'") name: String,
        @ToolParam(description = "Hit points from 20 to 60, higher for bigger or sturdier objects") hp: Int,
        @ToolParam(description = "Two or three attack names themed on the object") attacks: List<String>,
        @ToolParam(description = "One short phrase for what it is weak against, something players could plausibly do or find nearby") weakness: String,
        @ToolParam(description = "One or two vivid sentences introducing the monster to the players") flavor: String,
    ): Map<String, Any> {
        spawned = SpawnedMonster(name, hp.coerceIn(10, 80), attacks, weakness, flavor)
        return mapOf("ok" to true)
    }

    @Tool(description = "Resolve one player's action against the monster. Call exactly once per action.")
    fun resolve_action(
        @ToolParam(description = "Damage this action deals to the monster, 0 to 15. Use the top of the range if it exploits the weakness, 0 if it is nonsense or cowardly.") damageToMonster: Int,
        @ToolParam(description = "Damage the monster deals back to this player, 0 to 8") damageToPlayer: Int,
        @ToolParam(description = "One or two punchy sentences narrating the exchange in second person") narration: String,
    ): Map<String, Any> {
        resolved = Resolved(damageToMonster.coerceIn(0, 30), damageToPlayer.coerceIn(0, 12), narration)
        return mapOf("ok" to true)
    }
}

/** Thin wrapper over LiteRT-LM running Gemma 4 E2B fully on-device. */
class Gemma(private val modelPath: String, private val useGpu: Boolean) {
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    val tools = GameTools()

    suspend fun load(onStatus: (String) -> Unit) = withContext(Dispatchers.IO) {
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        val backend = if (useGpu) Backend.GPU() else Backend.CPU()
        onStatus("Loading Gemma 4 E2B on ${if (useGpu) "GPU" else "CPU"}…")
        val t0 = System.currentTimeMillis()
        val e = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = backend,
                visionBackend = backend,
                audioBackend = Backend.CPU(),
                maxNumTokens = 4096,
                maxNumImages = 1,
            )
        )
        e.initialize()
        engine = e
        onStatus("Gemma ready in ${(System.currentTimeMillis() - t0) / 1000}s")
    }

    private fun newConversation(): Conversation {
        conversation?.close()
        val c = engine!!.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(SYSTEM_PROMPT),
                tools = listOf(tool(tools)),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.9),
                thinkingConfig = ThinkingConfig(false, 0),
                maxOutputToken = 200,
            )
        )
        conversation = c
        return c
    }

    /** Photo of a real object -> monster via the spawn_monster tool. Starts a fresh conversation. */
    suspend fun spawn(imagePath: String): Pair<SpawnedMonster?, String> = withContext(Dispatchers.IO) {
        tools.spawned = null
        val c = newConversation()
        val t0 = System.currentTimeMillis()
        var reply = c.sendMessage(Contents.of(Content.ImageFile(imagePath), Content.Text(SPAWN_PROMPT)))
        if (tools.spawned == null) {
            Log.w(TAG, "no spawn_monster call; retrying once")
            reply = c.sendMessage("You must call the spawn_monster tool for the object in the photo now, then say one short line.")
        }
        Log.i(TAG, "spawn took ${System.currentTimeMillis() - t0} ms; tool=${tools.spawned != null}")
        tools.spawned to reply.plainText()
    }

    /**
     * One player action -> resolve_action tool. The action is either typed text or a WAV clip the
     * player spoke (Gemma 4 E2B takes audio natively, no speech-to-text layer). Continues the battle conversation.
     */
    suspend fun resolve(
        playerName: String,
        action: String?,
        audioWav: ByteArray?,
        monsterName: String,
        monsterHp: Int,
        weakness: String,
        facing: Boolean,
        flanking: Boolean,
    ): Pair<Resolved?, String> = withContext(Dispatchers.IO) {
        tools.resolved = null
        val c = conversation ?: newConversation()
        val situation = buildString {
            append("$playerName acts against $monsterName (HP $monsterHp, weak to: $weakness). ")
            if (!facing) append("They are NOT facing the monster, so the action mostly misses. ")
            if (flanking) append("Another player is attacking from a different side, so this is a flanking strike. ")
            if (audioWav != null) append("Their action is spoken in the attached audio clip. ")
            else append("Their action: \"$action\". ")
            append("Call resolve_action.")
        }
        val t0 = System.currentTimeMillis()
        var reply = if (audioWav != null) c.sendMessage(Contents.of(Content.AudioBytes(audioWav), Content.Text(situation)))
                    else c.sendMessage(situation)
        if (tools.resolved == null) {
            Log.w(TAG, "no resolve_action call; retrying once")
            reply = c.sendMessage("You must call the resolve_action tool for that action now, then reply in one sentence.")
        }
        Log.i(TAG, "resolve took ${System.currentTimeMillis() - t0} ms; tool=${tools.resolved != null}; audio=${audioWav != null}")
        tools.resolved to reply.plainText()
    }

    fun close() {
        conversation?.close(); conversation = null
        engine?.close(); engine = null
    }

    private fun Message.plainText(): String =
        contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }.trim()

    companion object {
        private const val TAG = "Gemma"
        const val SYSTEM_PROMPT = """You are the dungeon master of a co-op augmented-reality RPG played in a real room.
Real objects seen through the phone camera become monsters. Be vivid, funny and fast. Keep every reply under 40 words.
Always use the provided tools to report game effects; never invent numbers in prose."""
        const val SPAWN_PROMPT = """Look at this photo. Pick the most prominent object or feature and turn it into a monster.
Call spawn_monster with a name, hp, attacks, a weakness and flavor text, then say one short line to the players."""
    }
}
