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

data class SpawnedMonster(val name: String, val hp: Int, val attacks: List<String>, val weakness: String, val flavor: String, val emoji: String, val accessories: List<String>, val taunt: String)
data class Resolved(val damageToMonster: Int, val damageToPlayer: Int, val narration: String, val style: Int, val taunt: String, val drankPotion: Boolean = false, val usedItem: String? = null)
data class Loot(val name: String, val effect: String)
data class OmenText(val name: String, val lore: String, val threat: Int)

/**
 * The tools Gemma 4 calls. LiteRT-LM runs them automatically mid-generation; they just stash
 * their arguments so the game loop can read structured results after sendMessage returns.
 */
class GameTools : ToolSet {
    @Volatile var spawned: SpawnedMonster? = null
    @Volatile var resolved: Resolved? = null
    @Volatile var loot: Loot? = null
    @Volatile var omens: List<OmenText>? = null
    @Volatile var potionRequested = false
    @Volatile var itemUsed: String? = null

    @Tool(description = "The player drinks a healing potion. Call this when the player says they drink, quaff, chug or use a potion, in addition to resolve_action.")
    fun drink_potion(): Map<String, Any> { potionRequested = true; return mapOf("ok" to true) }

    @Tool(description = "The player uses one of the items they carry, by name. Call this when the player invokes a carried item, in addition to resolve_action.")
    fun use_item(@ToolParam(description = "The item name as listed in what the player carries") name: String): Map<String, Any> {
        itemUsed = name.take(60); return mapOf("ok" to true)
    }

    @Tool(description = "Turn the real-world object or scene in the photo into the monster the players must fight. Call exactly once.")
    fun spawn_monster(
        @ToolParam(description = "Short dramatic monster name derived from the object, e.g. 'The Vending Golem'") name: String,
        @ToolParam(description = "Hit points from 20 to 60, higher for bigger or sturdier objects") hp: Int,
        @ToolParam(description = "Two or three attack names themed on the object") attacks: List<String>,
        @ToolParam(description = "One short phrase for what it is weak against, something players could plausibly do or find nearby") weakness: String,
        @ToolParam(description = "One or two vivid sentences introducing the monster to the players") flavor: String,
        @ToolParam(description = "One single emoji that best represents this monster") emoji: String,
        @ToolParam(description = "Two or three single emoji to draw onto the real object as its features: eyes, horns, a weapon, an aura. Example: 👀, 🗡️, 🔥") accessories: List<String>,
        @ToolParam(description = "One short line the monster shouts at the players as it appears, ALL CAPS, under 8 words") taunt: String,
    ): Map<String, Any> {
        spawned = SpawnedMonster(name, hp.coerceIn(10, 80), attacks, weakness, flavor, emoji.take(4).ifBlank { "👾" },
            accessories.map { it.trim().take(4) }.filter { it.isNotBlank() }.take(3), taunt.take(60))
        Log.i("Gemma", "spawn_monster $name hp=$hp weak=$weakness attacks=$attacks | $flavor")
        return mapOf("ok" to true)
    }

    @Tool(description = "Resolve one player's action against the monster. Call exactly once per action.")
    fun resolve_action(
        @ToolParam(description = "Damage this action deals to the monster, 0 to 15. Use the top of the range if it exploits the weakness or a carried item, 0 if it is nonsense or cowardly.") damageToMonster: Int,
        @ToolParam(description = "Damage the monster deals back to this player, 0 to 8") damageToPlayer: Int,
        @ToolParam(description = "One or two punchy sentences narrating the exchange in second person. Describe the blow and its effect; never say the monster or player is dead or defeated.") narration: String,
        @ToolParam(description = "How creative, funny or specific the action was: 1 boring or repeated, 3 decent, 5 legendary. Absurd ideas that fit the object score high.") style: Int,
        @ToolParam(description = "One short line the monster shouts back at the player, ALL CAPS, under 8 words, e.g. TAKE THAT, INTREPID FOOL") taunt: String,
    ): Map<String, Any> {
        resolved = Resolved(damageToMonster.coerceIn(0, 30), damageToPlayer.coerceIn(0, 12), narration, style.coerceIn(1, 5), taunt.take(60))
        Log.i("Gemma", "resolve_action dmg=$damageToMonster hurt=$damageToPlayer style=$style | $narration")
        return mapOf("ok" to true)
    }

    @Tool(description = "Turn the list of nearby wireless signal names into dungeon lore. Call exactly once, with one entry per signal, same order.")
    fun write_omens(
        @ToolParam(description = "A spooky or funny entity name for each signal, same order as given") names: List<String>,
        @ToolParam(description = "One short sentence of lore for each signal, same order") lores: List<String>,
        @ToolParam(description = "Threat level 1 to 5 for each signal, same order; 5 only for something that sounds powerful or ominous") threats: List<Int>,
    ): Map<String, Any> {
        val n = minOf(names.size, lores.size, threats.size)
        omens = (0 until n).map { OmenText(names[it].take(40), lores[it].take(140), threats[it].coerceIn(1, 5)) }
        return mapOf("ok" to true)
    }

    @Tool(description = "Give the players an item salvaged from the defeated monster's real-world object. Call exactly once.")
    fun drop_loot(
        @ToolParam(description = "Short item name, e.g. 'Cable of Binding'") name: String,
        @ToolParam(description = "One short sentence: what it does in future fights") effect: String,
    ): Map<String, Any> {
        loot = Loot(name.take(40), effect.take(120))
        return mapOf("ok" to true)
    }
}

/** Thin wrapper over LiteRT-LM running Gemma 4 E2B fully on-device. */
class Gemma(private val modelPath: String, private val useGpu: Boolean) {
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var turns = 0
    val tools = GameTools()
    /** Incremented whenever a tool call had to be re-prompted. */
    @Volatile var retries = 0

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
        val secs = (System.currentTimeMillis() - t0) / 1000
        Log.i(TAG, "engine ready in ${secs}s (gpu=$useGpu)")
        onStatus("Gemma ready in ${secs}s")
    }

    private fun newConversation(extraSystem: String = ""): Conversation {
        conversation?.close()
        val c = engine!!.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(SYSTEM_PROMPT + extraSystem),
                tools = listOf(tool(tools)),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.9),
                thinkingConfig = ThinkingConfig(false, 0),
                maxOutputToken = 200,
            )
        )
        conversation = c
        turns = 0
        return c
    }

    /** Send on the current conversation; if the runtime chokes (e.g. context full), start a fresh one and retry once. */
    private fun send(contents: Contents): Message {
        val c = conversation ?: newConversation()
        turns++
        return try {
            c.sendMessage(contents)
        } catch (e: Exception) {
            Log.w(TAG, "sendMessage failed (${e.message}); resetting conversation")
            newConversation().sendMessage(contents)
        }
    }

    /** Photo of a real object -> monster via the spawn_monster tool. Starts a fresh conversation. */
    suspend fun spawn(imagePath: String, stepsWalked: Int, environment: String, bossLair: String?): Pair<SpawnedMonster?, String> = withContext(Dispatchers.IO) {
        tools.spawned = null
        newConversation()
        val prompt = buildString {
            append(SPAWN_PROMPT)
            if (stepsWalked > 30) append(" The players walked $stepsWalked steps to find it, so make it bigger and nastier than usual.")
            if (environment.isNotBlank()) append(" Conditions: $environment. Let that shape the monster.")
            if (bossLair != null) append(" IMPORTANT: this place is the lair of $bossLair. The monster is its champion or the entity itself: make it a BOSS, hp 60 to 80, more menacing than usual.")
        }
        val t0 = System.currentTimeMillis()
        var reply = send(Contents.of(Content.ImageFile(imagePath), Content.Text(prompt)))
        if (tools.spawned == null) {
            Log.w(TAG, "no spawn_monster call; retrying once"); retries++
            reply = send(Contents.of("You must call the spawn_monster tool for the object in the photo now, then say one short line."))
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
        items: List<String>,
        potions: Int,
    ): Pair<Resolved?, String> = withContext(Dispatchers.IO) {
        tools.resolved = null; tools.potionRequested = false; tools.itemUsed = null
        if (turns >= MAX_TURNS_PER_CONVERSATION) {
            newConversation(" A battle is in progress: $monsterName (HP $monsterHp, weak to $weakness) fights the players.")
        }
        val situation = buildString {
            append("$playerName acts against $monsterName (HP $monsterHp, weak to: $weakness). ")
            if (items.isNotEmpty()) append("$playerName carries: ${items.joinToString(", ")}. If they use one by name, also call use_item. ")
            if (potions > 0) append("$playerName has $potions healing potion${if (potions > 1) "s" else ""}; if they say they drink one, also call drink_potion. ")
            if (!facing) append("They are NOT facing the monster, so the action mostly misses. ")
            if (flanking) append("Another player is attacking from a different side, so this is a flanking strike. ")
            if (audioWav != null) append("Their action is spoken in the attached audio clip. ")
            else append("Their action: \"$action\". ")
            append("Call resolve_action.")
        }
        val t0 = System.currentTimeMillis()
        var reply = if (audioWav != null) send(Contents.of(Content.AudioBytes(audioWav), Content.Text(situation)))
                    else send(Contents.of(situation))
        if (tools.resolved == null) {
            Log.w(TAG, "no resolve_action call; retrying once"); retries++
            reply = send(Contents.of("You must call the resolve_action tool for that action now, then reply in one sentence."))
        }
        Log.i(TAG, "resolve took ${System.currentTimeMillis() - t0} ms; tool=${tools.resolved != null}; audio=${audioWav != null}; potion=${tools.potionRequested}; item=${tools.itemUsed}")
        tools.resolved?.copy(drankPotion = tools.potionRequested, usedItem = tools.itemUsed) to reply.plainText()
    }

    /** Nearby signal names -> lore via write_omens. Uses a throwaway conversation so it doesn't pollute a battle. */
    suspend fun omens(signals: List<String>): Pair<List<OmenText>?, String> = withContext(Dispatchers.IO) {
        tools.omens = null
        val keep = conversation; conversation = null
        val c = newConversation()
        val t0 = System.currentTimeMillis()
        val list = signals.mapIndexed { i, n -> "${i + 1}. \"$n\"" }.joinToString("\n")
        var reply = c.sendMessage(Contents.of("The players sense ${signals.size} wireless signals nearby — Wi-Fi networks and Bluetooth devices — in this order:\n$list\nTreat each name as the name of a spirit, faction or creature haunting this place. Call write_omens once with lists for all ${signals.size}, then say one short ominous line."))
        if (tools.omens == null) reply = c.sendMessage(Contents.of("Call the write_omens tool now with all ${signals.size} entries."))
        Log.i(TAG, "omens took ${System.currentTimeMillis() - t0} ms; tool=${tools.omens != null}")
        c.close(); conversation = keep
        tools.omens to reply.plainText()
    }

    /** After a kill: ask for an item salvaged from the object. */
    suspend fun loot(monsterName: String, playerName: String): Pair<Loot?, String> = withContext(Dispatchers.IO) {
        tools.loot = null
        val t0 = System.currentTimeMillis()
        var reply = send(Contents.of("$monsterName is defeated, $playerName landed the final blow! Call drop_loot with an item salvaged from the real object it came from, then say one triumphant line."))
        if (tools.loot == null) reply = send(Contents.of("Call the drop_loot tool now."))
        Log.i(TAG, "loot took ${System.currentTimeMillis() - t0} ms; tool=${tools.loot != null}")
        tools.loot to reply.plainText()
    }

    fun close() {
        conversation?.close(); conversation = null
        engine?.close(); engine = null
    }

    private fun Message.plainText(): String =
        contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }.trim()

    companion object {
        private const val TAG = "Gemma"
        private const val MAX_TURNS_PER_CONVERSATION = 8
        const val SYSTEM_PROMPT = """You are the dungeon master of a co-op augmented-reality RPG played in a real room.
Real objects seen through the phone camera become monsters. Be vivid, funny and fast. Keep every reply under 40 words.
Always use the provided tools to report game effects; never invent numbers in prose.
Reward wit: an absurd, specific, funny action that fits the object (a TSA liquid check on a bag, a firmware update on a printer) is a legendary blow — top damage and style 5. Vague or repeated actions do little.
Never declare the monster or a player dead, defeated or victorious; the game engine decides and announces that. Describe the blow and its effect only."""
        const val SPAWN_PROMPT = """Look at this photo. Pick the most prominent object or feature and turn it into a monster.
Call spawn_monster with a name, hp, attacks, a weakness, flavor text, an emoji, 2-3 emoji features to draw on the object, and a taunt; then say one short line to the players."""
    }
}
