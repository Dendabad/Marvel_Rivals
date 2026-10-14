package com.hcimbot.plan

import com.hcimbot.engine.BotSettings
import com.hcimbot.guide.Guide
import com.hcimbot.guide.ItemRef
import com.hcimbot.guide.Step
import com.hcimbot.guide.Target
import com.hcimbot.guide.Travel
import com.hcimbot.guide.toTile
import com.hcimbot.route.RouteStore

sealed interface StepPlan {
    /**
     * Run [tasks] in order.
     *
     * [trustTasks]: once the tasks finish the step is done, even when the
     * plugin's own check would wait for something later (used for a quest step
     * that is one part of a quest).
     *
     * [confirm]: the tasks cover the step's main action but the text may ask
     * for a little more ("withdraw and equip"), so the bot pauses afterwards for
     * the player to confirm.
     *
     * [combat]: the step fights on purpose, so being attacked is expected.
     */
    data class Auto(
        val tasks: List<Task>,
        val trustTasks: Boolean = false,
        val confirm: Boolean = false,
        val combat: Boolean = false,
    ) : StepPlan

    /** The bot cannot do this step. It pauses and shows [reason] until the step is ticked. */
    data class Manual(val reason: String) : StepPlan

    /** Nothing to do in the game, e.g. a heading or a note to keep an item. The bot ticks it and moves on. */
    data class Skip(val reason: String) : StepPlan
}

/**
 * Turns a guide step into tasks, using only the structured data guide.json
 * carries for it. Anything the data does not pin down becomes a manual step,
 * so the bot pauses rather than guess on a hardcore account.
 */
class StepPlanner(
    private val guide: Guide,
    private val settings: BotSettings,
    routes: RouteStore? = null,
) {
    private val scripts = StepScripts(guide, routes)

    fun plan(step: Step): StepPlan {
        // A hand written script knows more about a step than its guide data does.
        scripts.planFor(step, settings)?.let { return it }
        if (step.isNote) return StepPlan.Manual("Note: ${step.text}")
        if (step.advice && settings.pauseOnAdvice) return StepPlan.Manual("Tip: ${step.text}")
        if (hasWarning(step)) return manual(step, "the guide warns about something here")

        quest(step)?.let { return it }
        val plan = action(step)
        // A quest step whose Quest Helper data does not match the guide text is
        // done from the text. Its part of the quest is over when the action is.
        return if (isQuestStep(step) && plan is StepPlan.Auto) plan.copy(trustTasks = true) else plan
    }

    private fun action(step: Step): StepPlan {
        if (isOpenEnded(step)) return manual(step, "depends on a condition or repeats")
        if (step.banking || step.withdraw || step.depositsAll) return bank(step)

        // "Take Leather Gloves & wield": the equip is done as a second task.
        val core = withoutEquip(step.text)
        val equip = core != null
        val text = core ?: step.text
        val target = step.target?.takeIf { !it.isGuess }

        if (isShop(step, text, target)) return shop(step, text, target, equip)
        if (isCompound(text)) return manual(step, "asks for several things")
        if (isCollect(step, text) && (target == null || target.kind == "item")) return collect(step, equip)
        if (equip) return manual(step, "asks for several things")

        step.spell?.let { spell -> return StepPlan.Auto(listOf(CastSpellTask(spell.name, spell.widget))) }
        step.teleport?.takeIf { it.ids.isNotEmpty() }?.let { teleport ->
            val destination = teleportDestination(step) ?: return manual(step, "unclear where to teleport")
            if (FAIRY_RING.matches(destination)) return manual(step, "fairy ring codes are not automated")
            return StepPlan.Auto(listOf(ItemTeleportTask(teleport.via, teleport.ids, destination)))
        }
        step.travel?.let { travel -> return travel(step, travel) }
        step.inventoryAction?.let { action -> inventoryAction(step, action.highlightIds)?.let { return it } }

        if (target != null && target.kind == "npc") return npc(step, target)
        if (target != null && target.kind == "object") return obj(step, target)

        // Only a step that is just movement ("Head to Canifis") becomes a walk.
        // "Teleport to the GE" or "Complete the Agility Pyramid" also carry a
        // location, but walking there is not what they ask.
        val arrival = step.arrivesAt.toTile() ?: target?.takeIf { it.kind == "place" }?.tiles?.firstOrNull()
        if (arrival != null && isMovement(step)) return StepPlan.Auto(listOf(WalkTask(arrival)))

        return StepPlan.Manual(step.text)
    }

    private fun isQuestStep(step: Step) = step.questHelper != null && (step.questStep || step.questFollow)

    /** Null when the step is not a quest step, or its Quest Helper data does not match the guide's text. */
    private fun quest(step: Step): StepPlan? {
        if (!isQuestStep(step)) return null
        val key = step.questHelper!!
        val helper = guide.questHelperFor(step) ?: return manual(step, "no Quest Helper data for $key")
        if (step.questStopUnresolved) return manual(step, "the guide's stopping point for this quest is not machine readable")

        // questCompletes alone ("Talk to Dr Harlow", the last step the guide
        // lists for a quest) is still just that talk. Only an explicit follow or
        // stopping point carries the quest on.
        val carriesOn = step.questFollow || step.questStopValue != null ||
            step.questStopCondition != null || step.questStopItems.isNotEmpty()
        if (carriesOn) {
            return StepPlan.Auto(listOf(QuestTask(key, helper, settings.allowCombat)), combat = settings.allowCombat)
        }
        val ids = step.target?.ids.orEmpty().toSet() + step.approach?.ids.orEmpty()
        val mentioned = helper.steps.values.any { instruction ->
            (listOf(instruction) + instruction.whenIn).any { branch -> branch.ids.any { it in ids } }
        }
        // "Talk to Noah" where Quest Helper says to start with Alina: trust the guide.
        if (ids.isNotEmpty() && !mentioned) return null
        return StepPlan.Auto(
            listOf(QuestTask(key, helper, settings.allowCombat, boundedTo = ids)),
            trustTasks = true,
            combat = settings.allowCombat,
        )
    }

    private fun bank(step: Step): StepPlan {
        val text = step.text.lowercase()
        // "Deposit all except the Ice Gloves" must not deposit the gloves.
        if (Regex("\\b(except|keep|but)\\b").containsMatchIn(text)) return manual(step, "deposits only some items")
        val withdraw = if (step.withdraw) step.items else emptyList()
        if ("withdraw" in text && withdraw.isEmpty()) return manual(step, "does not say exactly what to withdraw")
        // "Deposit the Garlic & Cheese" is not a deposit all.
        if ("deposit" in text && !step.depositsAll) return manual(step, "deposits only some items")
        val unresolved = withdraw.filter { it.ids.isEmpty() }
        if (unresolved.isNotEmpty()) return manual(step, "cannot identify ${unresolved.joinToString { it.name }}")
        if (isCompound(step, ignoringBanking = true)) return manual(step, "asks for more than banking")

        val tasks = mutableListOf<Task>()
        step.target?.takeIf { it.kind == "place" || it.kind == "bank" }?.tiles?.firstOrNull()
            ?.let { tasks += WalkTask(it, radius = BANK_AREA, anyFloor = true) }
        tasks += BankTask(depositAll = step.depositsAll, withdraw = withdraw)
        return StepPlan.Auto(tasks, confirm = Regex("\\b(equip|wear|wield)\\b").containsMatchIn(text))
    }

    private fun travel(step: Step, travel: Travel): StepPlan {
        val departures = travel.departures.mapNotNull { departure ->
            val origin = departure.origin.toTile() ?: return@mapNotNull null
            val entry = departure.action ?: return@mapNotNull null
            TravelTask.Departure(origin, departure.id, entry)
        }
        if (departures.isEmpty()) return StepPlan.Manual(step.text)
        return StepPlan.Auto(listOf(TravelTask(travel.place, departures, travel.destination.toTile())))
    }

    private fun isShop(step: Step, text: String, target: Target?): Boolean {
        if (!Regex("(^|[,&.]|\\band\\b)\\s*buy\\b").containsMatchIn(stripped(text))) return false
        // "Buy a Newspaper from Benny (1,1)" is bought through chat, not a shop window.
        if (step.dialogue.isNotEmpty()) return false
        return step.sellers.isNotEmpty() || target?.kind == "npc"
    }

    private fun shop(step: Step, text: String, target: Target?, equip: Boolean): StepPlan {
        if (Regex("\\bsell\\b").containsMatchIn(text.lowercase())) return manual(step, "selling is not automated")
        // "Buy 2,100 Iron Arrowtips": guide.json's item counts do not read thousands.
        if (Regex("\\d,\\d").containsMatchIn(text)) return manual(step, "unsure of the amount to buy")
        if (hasExtraAction(text)) return manual(step, "asks for more than buying")
        val items = itemsOf(step)
        if (items.isEmpty()) return manual(step, "cannot identify what to buy")
        val sellers = if (step.sellers.isNotEmpty()) {
            step.sellers.map { ShopTask.Seller(it.name, it.ids, it.points.mapNotNull { p -> p.toTile() }) }
        } else {
            listOfNotNull(target?.let { ShopTask.Seller(it.name, it.ids, it.tiles) })
        }.filter { it.ids.isNotEmpty() && it.tiles.isNotEmpty() }
        if (sellers.isEmpty()) return manual(step, "no shop location known")
        val tasks = listOf(ShopTask(sellers, items)) + if (equip) listOf(EquipTask(items)) else emptyList()
        return StepPlan.Auto(tasks)
    }

    private fun isCollect(step: Step, text: String): Boolean =
        step.collects || clauses(text).firstOrNull { !MOVEMENT.containsMatchIn(it) }
            ?.let { Regex("^(take|collect|pick up|loot|grab)\\b").containsMatchIn(it) } == true

    private fun collect(step: Step, equip: Boolean): StepPlan {
        val items = itemsOf(step)
        if (items.isEmpty()) return manual(step, "cannot identify what to pick up")
        val tasks = listOf(TakeItemTask(items)) + if (equip) listOf(EquipTask(items)) else emptyList()
        return StepPlan.Auto(tasks)
    }

    /** The items a buy or pick up step names. Empty unless every one of them is identified. */
    private fun itemsOf(step: Step): List<ItemRef> {
        if (step.items.isNotEmpty()) {
            // Every item the step names must be known, or the bot would leave some behind.
            return if (step.items.all { it.ids.isNotEmpty() }) step.items else emptyList()
        }
        val target = step.target ?: return emptyList()
        return if (target.kind == "item" && target.ids.isNotEmpty()) listOf(ItemRef(target.name, target.ids)) else emptyList()
    }

    private fun inventoryAction(step: Step, ids: List<Int>): StepPlan? {
        val objectIds = step.target?.takeIf { it.kind == "object" }?.ids.orEmpty()
        val task = when {
            ids.size >= 2 -> UseItemTask(ids[0], ids[1])
            ids.size == 1 && objectIds.isNotEmpty() -> UseItemTask(ids[0], null, objectIds)
            else -> return null
        }
        return StepPlan.Auto(listOf(task))
    }

    private fun npc(step: Step, target: Target): StepPlan {
        if (target.ids.isEmpty() && target.allNames.isEmpty()) return StepPlan.Manual(step.text)
        val verb = verb(step) ?: if (step.dialogue.isNotEmpty()) "Talk-to" else null
        if (verb == "Attack" && !settings.allowCombat) return manual(step, "combat is turned off for safety")
        // A left click on a monster attacks it, so never click an NPC without knowing the option.
        if (verb == null) return manual(step, "unclear what to click")
        val tasks = approach(step) + InteractTask(
            kind = InteractTask.Kind.NPC,
            ids = target.ids,
            names = target.allNames,
            tiles = target.tiles,
            action = verb,
            dialogue = dialogue(step),
            repeat = step.completion != null,
        )
        return StepPlan.Auto(tasks, combat = verb == "Attack")
    }

    private fun obj(step: Step, target: Target): StepPlan {
        if (target.ids.isEmpty() && target.allNames.isEmpty()) return StepPlan.Manual(step.text)
        // Left clicks on objects include things like crossing the Wilderness ditch.
        val verb = verb(step) ?: return manual(step, "unclear what to click")
        val tasks = approach(step) + InteractTask(
            kind = InteractTask.Kind.OBJECT,
            ids = target.ids,
            names = target.allNames,
            tiles = target.tiles,
            action = verb,
            dialogue = dialogue(step),
            repeat = step.completion != null,
        )
        return StepPlan.Auto(tasks)
    }

    /** Stairs or a ladder the guide says to take first, e.g. "Go upstairs and talk to...". */
    private fun approach(step: Step): List<Task> {
        val approach = step.approach ?: return emptyList()
        val point = approach.point.toTile() ?: return emptyList()
        if (approach.ids.isEmpty()) return emptyList()
        val destination = step.target?.tiles?.firstOrNull() ?: return emptyList()
        val action = if (destination.plane > point.plane) "Climb-up" else "Climb-down"
        val climb = InteractTask(InteractTask.Kind.OBJECT, approach.ids, emptyList(), listOf(point), action)
        return listOf(OnlyIf({ ctx -> ctx.game.playerTile?.plane != destination.plane }, climb))
    }

    private fun dialogue(step: Step): DialoguePlan =
        if (step.dialogue.isEmpty()) DialoguePlan.None else DialoguePlan.Numbered(step.dialogue.first())

    private fun manual(step: Step, why: String) = StepPlan.Manual("${step.text} ($why)")

    companion object {
        /** How close to a bank's place the bot walks before opening the nearest bank. */
        private const val BANK_AREA = 30

        private val VERBS = listOf(
            "talk to" to "Talk-to",
            "speak to" to "Talk-to",
            "pickpocket" to "Pickpocket",
            "steal from" to "Steal-from",
            "search" to "Search",
            "open" to "Open",
            "climb up" to "Climb-up",
            "climb down" to "Climb-down",
            "enter" to "Enter",
            "pick" to "Pick",
            "kill" to "Attack",
            "attack" to "Attack",
            "fight" to "Attack",
            "mine" to "Mine",
            "chop" to "Chop down",
            "pray at" to "Pray-at",
            "trade" to "Trade",
            "take" to "Take",
        )

        /** Words that start an action. Two of them in one step means it asks for several things. */
        private val ACTION_WORDS = setOf(
            "talk", "speak", "kill", "attack", "fight", "bop", "collect", "pick", "take", "grab", "loot", "get",
            "buy", "sell", "wear", "equip", "wield", "deposit", "withdraw", "bank", "use", "teleport", "tele",
            "enter", "exit", "leave", "climb", "search", "open", "close", "cut", "chop", "mine", "fish", "cook",
            "craft", "smith", "make", "fletch", "light", "burn", "bury", "drop", "pray", "cast", "catch", "fill",
            "empty", "trade", "steal", "pickpocket", "give", "read", "dig", "cross", "jump", "complete", "continue",
            "start", "train", "claim", "check", "drink", "eat", "repair", "build", "plant", "harvest", "charter",
            "receive", "spin", "smelt", "tan", "turn", "wait", "hop", "disable", "enable", "set", "toggle",
            "travel", "home", "minigame", "destroy", "obtain",
        )

        /** In a shop step, "Trade Robyn and buy a hat" is one purchase. */
        private val SHOP_WORDS = setOf("buy", "trade", "talk", "speak")

        /** Movement clauses, which every task already does on its way. */
        private val MOVEMENT = Regex("^(head|go|walk|run|return|make your way|follow)\\b")

        private val FAIRY_RING = Regex("(?i)[a-d][i-l][p-s]")

        /** Words that make a step conditional or open ended, which the bot cannot judge. */
        private val OPEN_ENDED = Regex("\\b(if|until|optional|optionally|roughly|as many|per inv|unless|or)\\b")

        /** Words the guide uses to warn about a danger the bot's pathing cannot honour. */
        private val WARNING = Regex("\\b(avoid|hug|hugging|careful|aggressive|aggro|don't|do not|never|warning|beware)\\b")

        private val EQUIP_SUFFIX = Regex("(,|&|\\.|\\band\\b)\\s*(wield|wear|equip)\\s*(it|them|this|these)?\\s*[.!]?\\s*$")

        /** "Head West hugging the fence to avoid the Jail Guards": the walker cannot follow that. */
        fun hasWarning(step: Step): Boolean = WARNING.containsMatchIn(step.text.lowercase())

        /** The text without a trailing "& wield" or ". Equip it.", or null when it has none. */
        fun withoutEquip(text: String): String? {
            val match = EQUIP_SUFFIX.find(text.lowercase()) ?: return null
            return text.substring(0, match.range.first)
        }

        /**
         * The menu option the step's text asks for. A verb only counts at the
         * start of an instruction, so "Talk to Horace" gives Talk-to but the
         * place name in "Teleport to Essence Mine" does not give Mine.
         */
        fun verb(step: Step): String? = verb(step.text)

        fun verb(text: String): String? {
            for (clause in clauses(text)) {
                VERBS.firstOrNull { (phrase, _) -> clause == phrase || clause.startsWith("$phrase ") }
                    ?.let { return it.second }
            }
            return null
        }

        /**
         * True when the text asks for more than one action ("Kill a rat and
         * collect the bones"). Leading movement ("Head South & pray at the
         * altar") and item lists ("Buy 5x Cloth & 100 Nails") do not count.
         */
        fun isCompound(step: Step, ignoringBanking: Boolean = false): Boolean {
            var text = step.text
            if (ignoringBanking) {
                text = text.lowercase().replace(Regex("deposit all,? (then )?|and deposit all|bank at [^,.&]*|withdraw:?"), "")
            }
            return isCompound(text)
        }

        private fun isCompound(text: String): Boolean {
            // "Talk to Yarlo, then destroy the furniture": a "then" always starts another action.
            if (Regex("\\bthen\\b").containsMatchIn(stripped(text))) return true
            return actionWords(text).size > 1
        }

        /**
         * For a shop step: true when it asks for anything besides one purchase.
         * "Trade Robyn and buy a Chef's Hat" is one purchase. "Buy 5 Jugs of wine.
         * Hop worlds and buy 10 in total" and "Buy a skirt. Wear it" are not.
         */
        fun hasExtraAction(text: String): Boolean {
            if (Regex("\\bthen\\b").containsMatchIn(stripped(text))) return true
            val words = actionWords(text)
            return words.count { it == "buy" } > 1 || words.any { it !in SHOP_WORDS }
        }

        private fun actionWords(text: String): List<String> =
            clauses(text)
                .filterNot { MOVEMENT.containsMatchIn(it) }
                .map { it.substringBefore(' ').trim(':') }
                .filter { it in ACTION_WORDS }

        fun isOpenEnded(step: Step): Boolean = OPEN_ENDED.containsMatchIn(stripped(step.text))

        /** True when the step only moves the player: it starts with movement and asks for no action after it. */
        fun isMovement(step: Step): Boolean {
            val clauses = clauses(step.text)
            return clauses.firstOrNull()?.let { MOVEMENT.containsMatchIn(it) } == true &&
                clauses.none { !MOVEMENT.containsMatchIn(it) && it.substringBefore(' ') in ACTION_WORDS }
        }

        /**
         * Where an item teleport goes: "Ardougne: Ardy Cloak 1", "Games necklace
         * -> Wintertodt", or the step's place. "-> Run North" is what to do after
         * landing, not a destination.
         */
        fun teleportDestination(step: Step): String? {
            step.teleport?.via?.takeIf { ':' in it }?.substringBefore(':')?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            val arrow = step.text.split("->").getOrNull(1)?.let { clauses(it).firstOrNull() }
            if (arrow != null && !MOVEMENT.containsMatchIn(arrow)) return arrow
            return step.target?.takeIf { it.kind == "place" }?.name?.takeIf { it.isNotBlank() }
        }

        /** The step's instructions, split at commas, "&", "and", "then", brackets and full stops. */
        private fun clauses(text: String): List<String> =
            stripped(text)
                .split(Regex(",|&|;|->|[.!]\\s|\\band\\b|\\bthen\\b|[()]"))
                .map { it.trim(' ', '.', '!') }
                .filter { it.isNotEmpty() }

        /** Lowercased, without [tags] or chat numbers "(3,1)". Worded asides "(right click and buy 1)" stay. */
        private fun stripped(text: String) =
            text.lowercase().replace(Regex("\\[[^]]*]"), "").replace(Regex("\\([\\d,\\s]*\\)"), "")
    }
}
