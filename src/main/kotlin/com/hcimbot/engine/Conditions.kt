package com.hcimbot.engine

import com.hcimbot.game.GameState
import com.hcimbot.guide.Need
import com.hcimbot.guide.Requirement
import com.hcimbot.guide.Tile
import com.hcimbot.guide.VarCheck
import com.hcimbot.guide.toTile

/**
 * Evaluates guide.json requirements against the game, the same way the
 * B0aty plugin's QuestHelperSteps.Requirement.holds does.
 *
 * `memory` and `once` latch: once their inner condition has held, they stay
 * true for the rest of the quest. [observe] records those latches, so call it
 * every tick before reading a branch.
 */
class Conditions(private val game: GameState) {
    private val remembered = mutableMapOf<String, String>()

    fun observe(requirement: Requirement?) {
        requirement ?: return
        requirement.not?.let { observe(it) }
        requirement.all?.forEach { observe(it) }
        requirement.any?.forEach { observe(it) }
        requirement.memory?.let { memory ->
            observe(memory.condition)
            if (memory.condition != null && holds(memory.condition)) {
                remembered[memory.key] = memory.value ?: "true"
            }
        }
        requirement.once?.let { once ->
            observe(once.condition)
            if (once.condition != null && holds(once.condition)) remembered[once.key] = "true"
        }
    }

    fun holds(requirement: Requirement): Boolean {
        requirement.memory?.let { return remembered[it.key] == (it.value ?: "true") }
        requirement.once?.let { return remembered[it.key] == "true" }
        requirement.dialog?.let { dialog ->
            val name = game.playerName
            val speaker = if (dialog.speaker == "@player") name else dialog.speaker
            val lines = if (dialog.active) listOfNotNull(game.currentDialog()) else game.dialogHistory()
            return lines.any { line ->
                (dialog.speaker == null || (speaker != null && line.contains("$speaker|"))) &&
                    dialog.text.any { line.contains(it) }
            }
        }
        requirement.not?.let { return !holds(it) }
        requirement.all?.let { parts -> return parts.all { holds(it) } }
        requirement.any?.let { parts -> return parts.any { holds(it) } }
        requirement.varCheck?.let { return holds(it) }
        requirement.here?.let { present ->
            val found = when (present.kind) {
                "npc" -> game.nearestNpc(present.ids)
                "object" -> game.nearestObject(present.ids)
                else -> null
            } ?: return false
            return present.zone == null || inZone(present.zone, found.tile)
        }
        requirement.open?.let { return game.isInterfaceOpen(it) }
        requirement.widget?.let { shown ->
            val text = game.widgetText(shown.group, shown.child) ?: return false
            return shown.text.isEmpty() || shown.text.any { text.contains(it) }
        }
        requirement.item?.let { return has(it) }
        requirement.zone?.let { zone -> return game.playerTile?.let { inZone(zone, it) } ?: false }
        return false
    }

    fun holds(check: VarCheck): Boolean {
        val now = readVar(check.kind, check.id)
        return when (check.op) {
            "==" -> now == check.value
            "!=" -> now != check.value
            ">=" -> now >= check.value
            ">" -> now > check.value
            "<=" -> now <= check.value
            "<" -> now < check.value
            else -> false
        }
    }

    fun readVar(kind: String, id: Int): Int = if (kind == "varbit") game.varbit(id) else game.varp(id)

    /** Carried, worn and (when the need allows it) banked items all count. */
    fun has(need: Need): Boolean = count(need.ids, includeBank = need.bank) >= need.count

    fun count(ids: Collection<Int>, includeBank: Boolean = false): Long {
        val inventory = game.inventory()
        val worn = game.equipment()
        return ids.sumOf { id ->
            val held = (inventory[id] ?: 0).coerceAtLeast(0) + (worn[id] ?: 0).coerceAtLeast(0)
            val banked = if (includeBank) game.bankCount(id).coerceAtLeast(0) else 0
            (held + banked).toLong()
        }
    }

    companion object {
        /** Each box is [x1, y1, plane1, x2, y2, plane2], inclusive. */
        fun inZone(zone: List<List<Int>>, at: Tile): Boolean = zone.any { box ->
            val low = box.take(3).toTile()
            val high = box.drop(3).toTile()
            low != null && high != null &&
                at.x in low.x..high.x && at.y in low.y..high.y && at.plane in low.plane..high.plane
        }
    }
}
