package com.hcimbot.guide

import com.google.gson.annotations.SerializedName
import java.util.TreeMap

/*
 * Mirrors the parts of the B0aty HCIM Guide v3 plugin's guide.json (schema 1)
 * that the bot acts on. Field names match the JSON exactly so Gson can read the
 * plugin's own file. Every property has a default so Kotlin generates the
 * no-arg constructor Gson needs, and a missing key reads as empty, not null.
 */

data class Tile(val x: Int, val y: Int, val plane: Int) {
    fun distanceTo(other: Tile): Int =
        if (plane != other.plane) Int.MAX_VALUE
        else maxOf(kotlin.math.abs(x - other.x), kotlin.math.abs(y - other.y))

    override fun toString() = "($x, $y, $plane)"
}

/** guide.json writes every point as [x, y, plane]. */
fun List<Int>?.toTile(): Tile? =
    if (this == null || size < 3) null else Tile(this[0], this[1], this[2])

class Guide(
    val schemaVersion: Int = 0,
    val sections: List<Section> = emptyList(),
    val questHelpers: Map<String, QuestHelper> = emptyMap(),
) {
    /** Every step in guide order. Step ids are unique across the whole guide. */
    val steps: List<Step> by lazy { sections.flatMap { it.steps } }

    fun questHelperFor(step: Step): QuestHelper? = step.questHelper?.let { questHelpers[it] }

    fun sectionOf(step: Step): Section? = sections.firstOrNull { section -> section.steps.any { it.id == step.id } }
}

class Section(
    val id: String = "",
    val slug: String = "",
    val title: String = "",
    val bankNumber: Int? = null,
    val steps: List<Step> = emptyList(),
)

class Step(
    val id: String = "",
    val ordinal: Int = 0,
    val depth: Int = 0,
    val kind: String = "step",
    val text: String = "",
    val target: Target? = null,
    val items: List<ItemRef> = emptyList(),
    val arrivesAt: List<Int>? = null,
    /** Numbered chat options from the guide text, "(3,1)" is [[3, 1]]. */
    val dialogue: List<List<Int>> = emptyList(),
    val completion: Completion? = null,
    val banking: Boolean = false,
    val depositsAll: Boolean = false,
    val withdraw: Boolean = false,
    val inventorySlots: Int? = null,
    val spell: Spell? = null,
    val teleport: Teleport? = null,
    val travel: Travel? = null,
    val inventoryAction: InventoryAction? = null,
    val approach: Approach? = null,
    val sellers: List<Seller> = emptyList(),
    val spawns: List<Spawn> = emptyList(),
    val questHelper: String? = null,
    val questStep: Boolean = false,
    val questFollow: Boolean = false,
    val questDoneAt: Int? = null,
    val questDoneAtPanel: Int? = null,
    val questCompletes: Boolean = false,
    val questStartOnly: Boolean = false,
    val questStopValue: Int? = null,
    val questStopItems: List<Need> = emptyList(),
    val questStopUnresolved: Boolean = false,
    val questStopCondition: Requirement? = null,
    val advice: Boolean = false,
    val collects: Boolean = false,
    val acquires: Boolean = false,
) {
    val isNote: Boolean get() = kind != "step"

    override fun toString() = "Step($id: $text)"
}

class Target(
    val name: String = "",
    val names: List<String> = emptyList(),
    /** npc, object, item, place, nav or bank. */
    val kind: String = "",
    val ids: List<Int> = emptyList(),
    val points: List<List<Int>> = emptyList(),
) {
    val tiles: List<Tile> get() = points.mapNotNull { it.toTile() }
    val allNames: List<String> get() = names.ifEmpty { listOf(name) }.filter { it.isNotBlank() }
}

class ItemRef(
    val name: String = "",
    val ids: List<Int> = emptyList(),
    val count: Int? = null,
)

class Completion(
    /** diary, skill or diary+skill. */
    val kind: String = "",
    val varplayer: Int? = null,
    val bit: Int? = null,
    val varbits: List<VarbitValue> = emptyList(),
    val skill: String? = null,
    val level: Int? = null,
)

class VarbitValue(val varbit: Int = 0, val value: Int = 0)

class Spell(val name: String = "", val widget: Int = 0)

class Teleport(val via: String = "", val ids: List<Int> = emptyList())

class Travel(
    val place: String = "",
    val kind: String = "",
    val id: Int = 0,
    val action: String? = null,
    val origins: List<List<Int>> = emptyList(),
    val departures: List<Departure> = emptyList(),
    val destination: List<Int>? = null,
)

class Departure(val origin: List<Int>? = null, val id: Int = 0, val action: String? = null)

class InventoryAction(val kind: String = "", val highlightIds: List<Int> = emptyList())

class Approach(val ids: List<Int> = emptyList(), val point: List<Int>? = null)

class Seller(val name: String = "", val ids: List<Int> = emptyList(), val points: List<List<Int>> = emptyList())

class Spawn(val name: String = "", val ids: List<Int> = emptyList(), val points: List<List<Int>> = emptyList())

class Need(
    val name: String = "",
    val count: Int = 1,
    val ids: List<Int> = emptyList(),
    /** True when items in the bank count towards the need. */
    val bank: Boolean = false,
)

/** A Quest Helper condition, in the shapes guide.json ships. Exactly one field is set. */
class Requirement(
    val zone: List<List<Int>>? = null,
    val item: Need? = null,
    @SerializedName("var") val varCheck: VarCheck? = null,
    val here: Present? = null,
    val open: Int? = null,
    val widget: Shown? = null,
    val all: List<Requirement>? = null,
    val any: List<Requirement>? = null,
    val not: Requirement? = null,
    val memory: Memory? = null,
    val once: Memory? = null,
    val dialog: Dialog? = null,
)

class VarCheck(
    /** varbit or varplayer. */
    val kind: String = "",
    val id: Int = 0,
    val value: Int = 0,
    val op: String = "==",
)

class Present(val kind: String = "", val ids: List<Int> = emptyList(), val zone: List<List<Int>>? = null)

class Shown(val group: Int = 0, val child: Int = 0, val text: List<String> = emptyList())

class Memory(val key: String = "", val value: String? = null, @SerializedName("when") val condition: Requirement? = null)

class Dialog(val speaker: String? = null, val text: List<String> = emptyList(), val active: Boolean = false)

class QuestVar(val kind: String = "", val id: Int = 0)

class QuestHelper(
    @SerializedName("var") val progressVar: QuestVar? = null,
    val lastValue: Int = 0,
    val steps: Map<String, Instruction> = emptyMap(),
) {
    private val byValue: TreeMap<Int, Instruction> by lazy {
        TreeMap<Int, Instruction>().also { map ->
            steps.forEach { (key, step) -> key.trim().toIntOrNull()?.let { map[it] = step } }
        }
    }

    /**
     * What Quest Helper says to do at this progress value. Steps are keyed where
     * the instruction changes, so the answer is the highest key at or below the
     * value. Past [lastValue] the quest is finished and there is nothing to do.
     */
    fun at(value: Int): Instruction? {
        if (lastValue in 1 until value) return null
        return byValue.floorEntry(value)?.value
    }
}

/** One Quest Helper instruction, or one conditional branch of it. */
class Instruction(
    /** npc, object or walk. */
    val kind: String = "",
    val point: List<Int>? = null,
    val text: String = "",
    val ids: List<Int> = emptyList(),
    /** Chat options to pick, by their text. */
    val dialogue: List<String> = emptyList(),
    val items: List<Need> = emptyList(),
    val panel: Int? = null,
    val conditional: Boolean = false,
    val whenIn: List<Instruction> = emptyList(),
    @SerializedName("when") val condition: Requirement? = null,
) {
    val tile: Tile? get() = point.toTile()
}
