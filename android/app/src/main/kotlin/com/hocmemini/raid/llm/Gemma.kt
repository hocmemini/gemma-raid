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
import com.google.ai.edge.litertlm.ResponseFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
data class SpawnedNpc(val name: String, val role: String, val emoji: String, val accessories: List<String>, val greeting: String, val offer: String)
data class Strike(val damageToPlayer: Int, val narration: String, val taunt: String)
data class Trade(val item: String, val effect: String, val priceXp: Int, val discount: Int = 0)

/**
 * The tools Gemma 4 calls. LiteRT-LM runs them automatically mid-generation; they just stash
 * their arguments so the game loop can read structured results after sendMessage returns.
 */
fun splitList(s: String): List<String> = s.split(",", ";", "\n").map { it.trim().trim('"', '\'') }.filter { it.isNotBlank() }.take(4)
fun splitEmoji(s: String): List<String> = s.split(" ", ",").map { it.trim() }.filter { it.isNotBlank() }.map { it.take(4) }.take(3)

class GameTools : ToolSet {
    @Volatile var spawned: SpawnedMonster? = null
    @Volatile var resolved: Resolved? = null
    @Volatile var loot: Loot? = null
    @Volatile var omens: List<OmenText>? = null
    @Volatile var potionRequested = false
    @Volatile var itemUsed: String? = null
    @Volatile var npc: SpawnedNpc? = null
    @Volatile var strike: Strike? = null
    @Volatile var trade: Trade? = null
    @Volatile var gift: Trade? = null
    @Volatile var heal: Int = 0
    @Volatile var power: Pair<String, String>? = null
    @Volatile var epithet: String? = null

    @Tool(description = "The player invokes one of their level powers by name: Taunt, Parley, Bind or Recast. Call in addition to resolve_action when they do.")
    fun use_power(
        @ToolParam(description = "Exactly one of: Taunt, Parley, Bind, Recast") power: String,
        @ToolParam(description = "For Recast: the new weakness they declare. Otherwise empty.") argument: String,
    ): Map<String, Any> { this.power = power.trim().replaceFirstChar { it.uppercase() } to argument.take(40); return mapOf("ok" to true) }

    @Tool(description = "Grant the player an epithet (title) earned by how they fight. Call exactly once.")
    fun grant_epithet(@ToolParam(description = "Three to five words, e.g. 'the Lemon-Juicer' or 'Scourge of Staplers'") title: String): Map<String, Any> {
        epithet = title.take(40); return mapOf("ok" to true)
    }

    @Tool(description = "Turn the real-world object in the photo into a FRIENDLY non-player character the players can talk to. Call exactly once.")
    fun spawn_npc(
        @ToolParam(description = "Character name derived from the object, e.g. 'Brenda the Stapler-Smith'") name: String,
        @ToolParam(description = "One of: merchant, sage, healer, oddball") role: String,
        @ToolParam(description = "One single emoji for the character") emoji: String,
        @ToolParam(description = "Two or three single emoji features to draw on the object, separated by spaces, e.g. 👀 🎩") accessories: String,
        @ToolParam(description = "What they say as the players walk up, under 15 words") greeting: String,
        @ToolParam(description = "One short line of what they offer: wares, wisdom, healing or nonsense") offer: String,
    ): Map<String, Any> {
        npc = SpawnedNpc(name.take(40), role.lowercase().take(12), emoji.take(4).ifBlank { "🙂" }, splitEmoji(accessories), greeting.take(90), offer.take(90))
        Log.i("Gemma", "spawn_npc $name role=$role | $greeting")
        return mapOf("ok" to true)
    }

    @Tool(description = "The monster attacks a player on its own because they hesitated. Call exactly once.")
    fun monster_strike(
        @ToolParam(description = "Damage dealt to the player, 1 to 8") damageToPlayer: Int,
        @ToolParam(description = "One punchy sentence describing the attack, in second person") narration: String,
        @ToolParam(description = "What the monster shouts, ALL CAPS, under 8 words") taunt: String,
    ): Map<String, Any> {
        strike = Strike(damageToPlayer.coerceIn(1, 12), narration, taunt.take(60)); return mapOf("ok" to true)
    }

    @Tool(description = "An NPC merchant offers to sell the player an item for XP. Call when the player wants to buy or asks what is for sale. The price in this call is the real price; quote exactly it in your words.")
    fun sell_item(
        @ToolParam(description = "Item name") name: String,
        @ToolParam(description = "One short sentence: what it does in a fight") effect: String,
        @ToolParam(description = "Base price in XP, 20 to 150") priceXp: Int,
        @ToolParam(description = "Haggling: 0 to 50 percent off, earned by charm, wit or credible intimidation; 0 if the player was rude or boring") discountPercent: Int,
    ): Map<String, Any> {
        val d = discountPercent.coerceIn(0, 50)
        val final = (priceXp.coerceIn(10, 300) * (100 - d) / 100).coerceAtLeast(5)
        trade = Trade(name.take(40), effect.take(120), final, d)
        Log.i("Gemma", "sell_item $name base=$priceXp discount=$d% -> $final")
        return mapOf("ok" to true, "finalPriceXp" to final)
    }

    @Tool(description = "An NPC gives the player an item for free. Call at most once per character, when the player earns or charms it out of them.")
    fun give_item(
        @ToolParam(description = "Item name") name: String,
        @ToolParam(description = "One short sentence: what it does in a fight") effect: String,
    ): Map<String, Any> { gift = Trade(name.take(40), effect.take(120), 0); return mapOf("ok" to true) }

    @Tool(description = "A healer NPC restores the player's health. Call when the player asks to be healed.")
    fun heal_player(@ToolParam(description = "HP restored, 5 to 30") amount: Int): Map<String, Any> { heal = amount.coerceIn(1, 40); return mapOf("ok" to true) }

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
        @ToolParam(description = "Two or three attack names themed on the object, comma separated") attacks: String,
        @ToolParam(description = "One short phrase for what it is weak against, something players could plausibly do or find nearby") weakness: String,
        @ToolParam(description = "One or two vivid sentences introducing the monster to the players") flavor: String,
        @ToolParam(description = "One single emoji that best represents this monster") emoji: String,
        @ToolParam(description = "Two or three single emoji to draw onto the real object as its features, separated by spaces, e.g. 👀 🗡️ 🔥") accessories: String,
        @ToolParam(description = "One short line the monster shouts at the players as it appears, ALL CAPS, under 8 words") taunt: String,
    ): Map<String, Any> {
        spawned = SpawnedMonster(name, hp.coerceIn(10, 80), splitList(attacks), weakness, flavor, emoji.take(4).ifBlank { "👾" }, splitEmoji(accessories), taunt.take(60))
        Log.i("Gemma", "spawn_monster $name hp=$hp weak=$weakness attacks=$attacks | $flavor")
        return mapOf("ok" to true)
    }

    @Tool(description = "Resolve one player's action against the monster. Call exactly once per action.")
    fun resolve_action(
        @ToolParam(description = "Damage this action deals to the monster, 0 to 15. Use the top of the range if it exploits the weakness or a carried item, 0 if it is nonsense or cowardly.") damageToMonster: Int,
        @ToolParam(description = "Damage the monster deals back to this player, 0 to 8") damageToPlayer: Int,
        @ToolParam(description = "One or two punchy sentences narrating the exchange in second person. Describe the blow and its effect; never say the monster or player is dead or defeated.") narration: String,
        @ToolParam(description = "Style rating. 1 = boring, vague or repeated. 2 = plain attack. 3 = decent, fits the monster. 4 = clever or funny, uses the weakness or an item well. 5 = rare: a genuinely inspired, hilarious, object-specific idea nobody expected. Most actions are 2 or 3; give 5 at most once in a few turns.") style: Int,
        @ToolParam(description = "One short line the monster shouts back at the player, ALL CAPS, under 8 words, e.g. TAKE THAT, INTREPID FOOL") taunt: String,
    ): Map<String, Any> {
        resolved = Resolved(damageToMonster.coerceIn(0, 30), damageToPlayer.coerceIn(0, 12), narration, style.coerceIn(1, 5), taunt.take(60))
        Log.i("Gemma", "resolve_action dmg=$damageToMonster hurt=$damageToPlayer style=$style | $narration")
        return mapOf("ok" to true)
    }

    @Tool(description = "Turn the list of nearby wireless signal names into dungeon lore. Call exactly once.")
    fun write_omens(
        @ToolParam(description = "One line per signal, in the given order, formatted exactly as: entity name | one sentence of lore | threat 1-5") lines: String,
    ): Map<String, Any> {
        omens = lines.lines().mapNotNull { ln ->
            val parts = ln.split("|").map { it.trim() }
            if (parts.size < 2) null else OmenText(parts[0].removePrefix("-").trim().take(40), parts[1].take(140), parts.getOrNull(2)?.filter { it.isDigit() }?.take(1)?.toIntOrNull()?.coerceIn(1, 5) ?: 2)
        }.take(8)
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
class Gemma(private val modelPath: String, private val backend: Backend, private val backendName: String) {
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var turns = 0
    val tools = GameTools()
    /** Incremented whenever a tool call had to be re-prompted. */
    @Volatile var retries = 0

    suspend fun load(onStatus: (String) -> Unit) = withContext(Dispatchers.IO) {
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        onStatus("Loading on $backendName…")
        val t0 = System.currentTimeMillis()
        val e = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = backend,
                visionBackend = if (backendName == "NPU") Backend.GPU() else backend,
                audioBackend = Backend.CPU(),
                maxNumTokens = 4096,
                maxNumImages = 1,
            )
        )
        e.initialize()
        engine = e
        val secs = (System.currentTimeMillis() - t0) / 1000
        Log.i(TAG, "engine ready in ${secs}s ($backendName)")
        onStatus("ready in ${secs}s on $backendName")
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

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Serializable private data class MonsterJson(val name: String = "", val hp: Int = 30, val attacks: String = "", val weakness: String = "", val flavor: String = "", val emoji: String = "👾", val accessories: String = "", val taunt: String = "")
    @Serializable private data class NpcJson(val name: String = "", val role: String = "oddball", val emoji: String = "🙂", val accessories: String = "", val greeting: String = "", val offer: String = "")

    /**
     * Structured output instead of a tool call: a throwaway conversation with constrained JSON decoding,
     * so the model cannot answer "please provide an image" or slip a quote token. Returns the JSON text.
     */
    private fun askJson(contents: Contents, schema: String, maxTokens: Int = 320): String {
        val c = engine!!.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(SYSTEM_PROMPT),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.9),
                thinkingConfig = ThinkingConfig(false, 0),
                maxOutputToken = maxTokens,
                enableResponseFormat = true,
            )
        )
        return try {
            c.sendMessage(contents, responseFormat = ResponseFormat.json(schema)).plainText()
        } finally { c.close() }
    }

    private fun extractJson(text: String): String {
        val a = text.indexOf('{'); val b = text.lastIndexOf('}')
        return if (a >= 0 && b > a) text.substring(a, b + 1) else text
    }

    /**
     * Send on the current conversation. If LiteRT-LM's strict tool-call parser rejects the model's
     * output (a slipped quote token is enough), recover the arguments leniently from the raw response
     * and run the tool ourselves — no second 15 s generation. Other failures reset the conversation and retry once.
     */
    private fun send(contents: Contents): Message {
        val c = conversation ?: newConversation()
        turns++
        return try {
            c.sendMessage(contents)
        } catch (e: Exception) {
            val msg = e.message ?: ""
            val raw = msg.substringAfter("full response:", "").substringBefore("\nerror:").trim()
            if (raw.contains("call:") && LenientTools.recover(raw, tools)) {
                Log.w(TAG, "strict tool parse failed; recovered leniently from raw response")
                Message.model("")
            } else {
                Log.w(TAG, "sendMessage failed (${msg.take(160)}); resetting conversation")
                newConversation().sendMessage(contents)
            }
        }
    }

    /** Photo of a real object -> monster. Constrained JSON first (reliable); the tool path only as a fallback. Starts the battle conversation. */
    suspend fun spawn(imagePath: String, stepsWalked: Int, environment: String, bossLair: String?, reputation: String): Pair<SpawnedMonster?, String> = withContext(Dispatchers.IO) {
        tools.spawned = null
        val prompt = buildString {
            append("Look at this photo. Pick the most prominent object or feature and turn it into a monster. ")
            if (stepsWalked > 30) append("The players walked $stepsWalked steps to find it, so make it bigger and nastier than usual. ")
            if (environment.isNotBlank()) append("Conditions: $environment. Let that shape the monster. ")
            if (reputation.isNotBlank()) append("The players are known as: $reputation; the monster has heard of them. ")
            if (bossLair != null) append("IMPORTANT: this place is the lair of $bossLair. The monster is its champion or the entity itself: make it a BOSS, hp 60 to 80, more menacing than usual. ")
            append("Answer as JSON: name (short dramatic, from the object), hp (20-60, bigger objects higher), attacks (2-3 names, comma separated), weakness (one short phrase players could plausibly act on), flavor (1-2 vivid sentences), emoji (one), accessories (2-3 emoji separated by spaces: eyes, horns, a weapon), taunt (what it shouts, ALL CAPS, under 8 words).")
        }
        val t0 = System.currentTimeMillis()
        var line = ""
        val viaJson = runCatching {
            val raw = askJson(Contents.of(Content.ImageFile(imagePath), Content.Text(prompt)), MONSTER_SCHEMA)
            Log.i(TAG, "spawn json: ${raw.take(300)}")
            val j = lenientJson.decodeFromString(MonsterJson.serializer(), extractJson(raw))
            if (j.name.isBlank()) null else SpawnedMonster(j.name.take(40), j.hp.coerceIn(10, 80), splitList(j.attacks).ifEmpty { listOf("Lunge") }, j.weakness.ifBlank { "a clever idea" }, j.flavor, j.emoji.take(4).ifBlank { "👾" }, splitEmoji(j.accessories), j.taunt.take(60))
        }.onFailure { Log.w(TAG, "spawn json failed: ${it.message?.take(160)}") }.getOrNull()
        var result = viaJson
        if (result == null) {
            newConversation()
            var reply = send(Contents.of(Content.ImageFile(imagePath), Content.Text("$prompt Call spawn_monster with those fields.")))
            if (tools.spawned == null) { retries++; Log.w(TAG, "no spawn_monster call (model said: ${reply.plainText().take(120)}); retrying once"); reply = send(Contents.of("You must call the spawn_monster tool for the object in the photo now, then say one short line.")) }
            result = tools.spawned; line = reply.plainText()
        }
        // battle conversation: the monster's sheet rides in the system instruction so resolve_action knows it
        if (result != null) {
            newConversation(" The current monster is ${result.name} (HP ${result.hp}; attacks: ${result.attacks.joinToString()}; weak to ${result.weakness}). ${result.flavor}")
        }
        Log.i(TAG, "spawn took ${System.currentTimeMillis() - t0} ms; ok=${result != null}; json=${viaJson != null}")
        result to line
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
        powers: List<String>,
    ): Pair<Resolved?, String> = withContext(Dispatchers.IO) {
        tools.resolved = null; tools.potionRequested = false; tools.itemUsed = null; tools.power = null
        if (turns >= MAX_TURNS_PER_CONVERSATION) {
            newConversation(" A battle is in progress: $monsterName (HP $monsterHp, weak to $weakness) fights the players.")
        }
        val situation = buildString {
            append("$playerName acts against $monsterName (HP $monsterHp, weak to: $weakness). ")
            if (items.isNotEmpty()) append("$playerName carries: ${items.joinToString("; ")}. If they use one by name, also call use_item. ")
            if (potions > 0) append("$playerName has $potions healing potion${if (potions > 1) "s" else ""}; if they say they drink one, also call drink_potion. ")
            if (powers.isNotEmpty()) append("$playerName's powers, invoked by name: ${powers.joinToString(", ")}. If they invoke one, also call use_power. ")
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
            Log.w(TAG, "no resolve_action call (model said: ${reply.plainText().take(120)}); retrying once"); retries++
            reply = send(Contents.of("You must call the resolve_action tool for that action now, then reply in one sentence."))
        }
        Log.i(TAG, "resolve took ${System.currentTimeMillis() - t0} ms; tool=${tools.resolved != null}; audio=${audioWav != null}; potion=${tools.potionRequested}; item=${tools.itemUsed}")
        tools.resolved?.copy(drankPotion = tools.potionRequested, usedItem = tools.itemUsed) to reply.plainText()
    }

    /** Photo -> friendly NPC. Constrained JSON first; the NPC's persona then rides in a fresh tools conversation. */
    suspend fun meet(imagePath: String, environment: String): Pair<SpawnedNpc?, String> = withContext(Dispatchers.IO) {
        tools.npc = null
        val prompt = buildString {
            append("Look at this photo. Turn the most prominent object into a friendly character the players can talk to: a merchant selling odd wares for XP, a sage with lore and tips, a healer, or an oddball. ")
            if (environment.isNotBlank()) append("Conditions: $environment. ")
            append("Answer as JSON: name, role (merchant|sage|healer|oddball), emoji (one), accessories (2-3 emoji separated by spaces), greeting (under 15 words), offer (one short line of what they offer).")
        }
        val t0 = System.currentTimeMillis()
        val viaJson = runCatching {
            val raw = askJson(Contents.of(Content.ImageFile(imagePath), Content.Text(prompt)), NPC_SCHEMA)
            Log.i(TAG, "meet json: ${raw.take(300)}")
            val j = lenientJson.decodeFromString(NpcJson.serializer(), extractJson(raw))
            if (j.name.isBlank()) null else SpawnedNpc(j.name.take(40), j.role.lowercase().take(12), j.emoji.take(4).ifBlank { "🙂" }, splitEmoji(j.accessories), j.greeting.take(90), j.offer.take(90))
        }.onFailure { Log.w(TAG, "meet json failed: ${it.message?.take(160)}") }.getOrNull()
        var result = viaJson; var line = ""
        if (result == null) {
            newConversation()
            var reply = send(Contents.of(Content.ImageFile(imagePath), Content.Text("$prompt Call spawn_npc with those fields.")))
            if (tools.npc == null) { retries++; reply = send(Contents.of("You must call the spawn_npc tool for the object in the photo now.")) }
            result = tools.npc; line = reply.plainText()
        }
        if (result != null) newConversation(" You are now ${result.name}, the ${result.role}: ${result.offer}. Stay in character.")
        Log.i(TAG, "meet took ${System.currentTimeMillis() - t0} ms; ok=${result != null}; json=${viaJson != null}")
        result to line
    }

    /** Chat with the current NPC (continues the conversation started by meet). */
    suspend fun npcChat(playerName: String, text: String?, audioWav: ByteArray?, npcName: String, role: String, xp: Int, items: List<String>): Pair<String, Triple<Trade?, Trade?, Int>> = withContext(Dispatchers.IO) {
        tools.trade = null; tools.gift = null; tools.heal = 0
        val situation = buildString {
            append("You are now $npcName, the $role. Stay in character, under 40 words. $playerName has $xp XP and carries: ${items.ifEmpty { listOf("nothing") }.joinToString(", ")}. ")
            append("Use sell_item when they ask to buy or what's for sale — the tool's price is the real price, so say that exact number; a good haggle earns discountPercent. give_item for a free gift at most once, heal_player to heal. ")
            if (audioWav != null) append("$playerName speaks in the attached audio.") else append("$playerName says: \"$text\"")
        }
        val reply = if (audioWav != null) send(Contents.of(Content.AudioBytes(audioWav), Content.Text(situation))) else send(Contents.of(situation))
        Log.i(TAG, "npcChat trade=${tools.trade} gift=${tools.gift} heal=${tools.heal} | ${reply.plainText().take(80)}")
        reply.plainText() to Triple(tools.trade, tools.gift, tools.heal)
    }

    /** Free-form talk with the dungeon master while exploring. */
    suspend fun dmChat(playerName: String, text: String?, audioWav: ByteArray?, summary: String): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        tools.potionRequested = false
        if (conversation == null || turns >= MAX_TURNS_PER_CONVERSATION) newConversation(" The party is exploring between fights. Answer as the dungeon master: lore, tips, banter. Under 40 words.")
        val situation = buildString {
            append("Party status: $summary. ")
            if (audioWav != null) append("$playerName speaks to you in the attached audio.") else append("$playerName says: \"$text\"")
            append(" If they drink a potion, call drink_potion.")
        }
        val reply = if (audioWav != null) send(Contents.of(Content.AudioBytes(audioWav), Content.Text(situation))) else send(Contents.of(situation))
        reply.plainText() to tools.potionRequested
    }

    /** The monster takes a free swing at a hesitating player. */
    suspend fun monsterTurn(monsterName: String, targetName: String, monsterHp: Int): Strike? = withContext(Dispatchers.IO) {
        tools.strike = null
        var reply = send(Contents.of("$targetName hesitates. $monsterName (HP $monsterHp) seizes the moment and attacks them. Call monster_strike."))
        if (tools.strike == null) { retries++; reply = send(Contents.of("Call the monster_strike tool now.")) }
        tools.strike
    }

    /** Nearby signal names -> lore via write_omens. Uses a throwaway conversation so it doesn't pollute a battle. */
    suspend fun omens(signals: List<String>): Pair<List<OmenText>?, String> = withContext(Dispatchers.IO) {
        tools.omens = null
        val keep = conversation; conversation = null
        val c = newConversation()
        val t0 = System.currentTimeMillis()
        val list = signals.mapIndexed { i, n -> "${i + 1}. \"$n\"" }.joinToString("\n")
        var reply = c.sendMessage(Contents.of("The players sense ${signals.size} wireless signals nearby — Wi-Fi networks and Bluetooth devices — in this order:\n$list\nTreat each name as the name of a spirit, faction or creature haunting this place. Call write_omens once with one line per signal (entity name | lore | threat), then say one short ominous line."))
        if (tools.omens == null) reply = c.sendMessage(Contents.of("Call the write_omens tool now, one line per signal."))
        Log.i(TAG, "omens took ${System.currentTimeMillis() - t0} ms; tool=${tools.omens != null}")
        c.close(); conversation = keep
        tools.omens to reply.plainText()
    }

    val lastPower: Pair<String, String>? get() = tools.power

    /** Level-up: a title from how they fought. Throwaway conversation. */
    suspend fun epithet(playerName: String, level: Int, deeds: String): String? = withContext(Dispatchers.IO) {
        tools.epithet = null
        val keep = conversation; conversation = null
        val c = newConversation()
        runCatching {
            var reply = c.sendMessage(Contents.of("$playerName just reached level $level. Their deeds: $deeds. Call grant_epithet with a short, funny, specific title."))
            if (tools.epithet == null) c.sendMessage(Contents.of("Call the grant_epithet tool now."))
        }
        c.close(); conversation = keep
        tools.epithet
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
        const val MONSTER_SCHEMA = """{"type":"object","properties":{"name":{"type":"string"},"hp":{"type":"integer"},"attacks":{"type":"string"},"weakness":{"type":"string"},"flavor":{"type":"string"},"emoji":{"type":"string"},"accessories":{"type":"string"},"taunt":{"type":"string"}},"required":["name","hp","attacks","weakness","flavor","emoji","accessories","taunt"]}"""
        const val NPC_SCHEMA = """{"type":"object","properties":{"name":{"type":"string"},"role":{"type":"string"},"emoji":{"type":"string"},"accessories":{"type":"string"},"greeting":{"type":"string"},"offer":{"type":"string"}},"required":["name","role","emoji","accessories","greeting","offer"]}"""
        const val SYSTEM_PROMPT = """You are the dungeon master of a co-op augmented-reality RPG played in a real room.
Real objects seen through the phone camera become monsters. Be vivid, funny and fast. Keep every reply under 40 words.
Always use the provided tools to report game effects; never invent numbers in prose.
Reward wit: an absurd, specific, funny action that fits the object (a TSA liquid check on a bag, a firmware update on a printer) is a legendary blow — top damage and style 5. Vague or repeated actions do little.
Never declare the monster or a player dead, defeated or victorious; the game engine decides and announces that. Describe the blow and its effect only."""
        const val SPAWN_PROMPT = """Look at this photo. Pick the most prominent object or feature and turn it into a monster.
Call spawn_monster with a name, hp, attacks, a weakness, flavor text, an emoji, 2-3 emoji features to draw on the object, and a taunt; then say one short line to the players."""
    }
}


/** Pulls `call:tool{key:<|"|>value<|"|>,n:12,...}` apart without caring about a missing quote token here or there. */
object LenientTools {
    private val Q = "<|\"|>"

    fun recover(raw: String, t: GameTools): Boolean {
        val m = Regex("call:(\\w+)\\{(.*)", RegexOption.DOT_MATCHES_ALL).find(raw) ?: return false
        val tool = m.groupValues[1]
        val body = m.groupValues[2].substringBeforeLast("}")
        fun str(key: String): String {
            val r = Regex("(?:^|[,{\\s])$key:\\s*(?:$Q)?(.*?)(?:$Q)?\\s*(?=,\\s*\\w+:|$)", RegexOption.DOT_MATCHES_ALL).find(body) ?: return ""
            return r.groupValues[1].replace(Q, "\"").trim().trim('"', '[', ']').trim()
        }
        fun int(key: String, def: Int): Int = Regex("$key:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: def
        return when (tool) {
            "spawn_monster" -> { t.spawn_monster(str("name").ifBlank { "The Thing" }, int("hp", 30), str("attacks").replace("\"", ","), str("weakness").ifBlank { "a clever idea" }, str("flavor"), str("emoji").ifBlank { "👾" }, str("accessories").replace("\"", " "), str("taunt")); true }
            "resolve_action" -> { t.resolve_action(int("damageToMonster", 4), int("damageToPlayer", 2), str("narration").ifBlank { "The blow lands, more or less." }, int("style", 3), str("taunt")); true }
            "spawn_npc" -> { t.spawn_npc(str("name").ifBlank { "A Stranger" }, str("role").ifBlank { "oddball" }, str("emoji").ifBlank { "🙂" }, str("accessories").replace("\"", " "), str("greeting"), str("offer")); true }
            "drop_loot" -> { t.drop_loot(str("name").ifBlank { "a curious trinket" }, str("effect")); true }
            "monster_strike" -> { t.monster_strike(int("damageToPlayer", 3), str("narration"), str("taunt")); true }
            "sell_item" -> { t.sell_item(str("name"), str("effect"), int("priceXp", 50), int("discountPercent", 0)); true }
            "give_item" -> { t.give_item(str("name"), str("effect")); true }
            "heal_player" -> { t.heal_player(int("amount", 10)); true }
            "use_power" -> { t.use_power(str("power"), str("argument")); true }
            "grant_epithet" -> { t.grant_epithet(str("title")); true }
            "drink_potion" -> { t.drink_potion(); true }
            "write_omens" -> { t.write_omens(str("lines")); true }
            else -> false
        }
    }
}
