package com.hcimbot

import com.hcimbot.engine.BotSettings
import com.hcimbot.engine.Conditions
import com.hcimbot.game.SceneEntity
import com.hcimbot.guide.Guide
import com.hcimbot.guide.Step
import com.hcimbot.guide.Tile
import com.hcimbot.plan.StepPlan
import com.hcimbot.plan.StepScripts
import com.hcimbot.plan.TaskContext
import com.hcimbot.plan.TaskResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Runs step scripts from the guide's first 100 steps against the fake game. */
class StepScriptsTest {
    @Test
    fun `the Stronghold drinks wine on any damage and does not pause when attacked`() {
        for (id in listOf("5405a57b1e", "b2738b149e")) {
            val auto = assertIs<StepPlan.Auto>(plan(id))
            assertEquals(listOf(1993), auto.drinkOnDamage, id)
            assertTrue(auto.combat, id)
            // Only the ladders' own "Yes - I know that it may be dangerous down there", never a bare yes.
            assertEquals(listOf("Yes - I know that it may be"), auto.acceptWarnings, id)
            // Its portals are listed without their "floor done" requirement, so the walker must not use them.
            assertEquals(mapOf<String, Any>("useTeleportationPortals" to false), auto.walkerOverrides, id)
        }
    }

    @Test
    fun `restarted on floor 3, the Stronghold step goes on to the Box of Health and not back up`() {
        val game = FakeGame()
        val ctx = TaskContext(game, game, Conditions(game))
        game.playerTile = Tile(2123, 5252, 0) // floor 3's start
        assertEquals(3, StepScripts.strongholdFloor(ctx))
        val tasks = assertIs<StepPlan.Auto>(plan("5405a57b1e")).tasks
        // The floor 1 and 2 parts are skipped from here.
        assertEquals(TaskResult.Done, tasks[0].tick(ctx))
        assertEquals(TaskResult.Done, tasks[2].tick(ctx))
        game.playerTile = Tile(3081, 3421, 0) // outside: floor 1 first
        assertEquals(0, StepScripts.strongholdFloor(ctx))
    }

    @Test
    fun `Vampyre Slayer's garlic comes from the bank, not the cupboard it was first taken from`() {
        val harlow = com.hcimbot.guide.Instruction(kind = "npc", point = listOf(3222, 3399, 0), text = "Talk to Dr. Harlow.", ids = listOf(3480), dialogue = listOf("Morgan needs your help!"))
        val cupboard = com.hcimbot.guide.Instruction(
            kind = "object", point = listOf(3100, 3267, 0), text = "Get garlic from the cupboard upstairs in Morgan's house.", ids = listOf(15645),
            whenIn = listOf(harlow.let { com.hcimbot.guide.Instruction(kind = "npc", point = it.point, text = it.text, ids = it.ids, dialogue = it.dialogue, condition = com.hcimbot.guide.Requirement(item = com.hcimbot.guide.Need("Garlic", 1, listOf(1550)))) }),
        )
        val helper = com.hcimbot.guide.QuestHelper(com.hcimbot.guide.QuestVar("varbit", 2561), lastValue = 2, steps = mapOf("1" to cupboard))
        val own = StepScripts(Guide(1, listOf(com.hcimbot.guide.Section()), mapOf("vampyreslayer" to helper)))
        val game = FakeGame().apply {
            bank[1550] = 4
            playerTile = Tile(3185, 3436, 0)
            varbits[2561] = 1
            npcs += SceneEntity(16277, "Dr Harlow", Tile(3188, 3438, 0)) // his id since the update, not 3480
        }
        val plan = own.planFor(Step(id = "8c44826480"), BotSettings())
        run(plan, game, ticks = 40)
        // Banked for garlic before anything else, and never sent to the cupboard.
        assertEquals(1, game.inv[1550])
        assertTrue(game.calls.indexOf("open bank") >= 0 && game.calls.none { it.startsWith("object") }, game.calls.toString())
    }

    @Test
    fun `both logs in Yarlo's house are lit with the tinderbox`() {
        val game = FakeGame().apply {
            inv[590] = 1
            playerTile = Tile(3239, 3396, 0)
            groundItems += SceneEntity(1511, "Logs", Tile(3238, 3394, 0))
            groundItems += SceneEntity(1511, "Logs", Tile(3241, 3398, 0))
        }
        assertEquals(TaskResult.Done, run(plan("08e4afe002"), game))
        assertEquals(2, game.calls.count { it == "use 590 on ground Logs" }, game.calls.toString())
    }

    @Test
    fun `the sewer key is walked to and taken`() {
        val game = FakeGame().apply {
            playerTile = Tile(3237, 3458, 0)
            objects += SceneEntity(17431, "Rusty key", Tile(3225, 9897, 0))
        }
        assertEquals(TaskResult.Done, run(plan("578b1fb44c"), game))
        assertEquals("walk (3225, 9897, 0)", game.calls.first())
        assertTrue(game.calls.any { it.startsWith("object Rusty key") }, game.calls.toString())
    }

    @Test
    fun `Dr Harlow's reworded first question is answered`() {
        val harlow = com.hcimbot.guide.Instruction(kind = "npc", point = listOf(3222, 3399, 0), text = "Talk to Dr. Harlow.", ids = listOf(3480), dialogue = listOf("Morgan needs your help!"))
        val helper = com.hcimbot.guide.QuestHelper(com.hcimbot.guide.QuestVar("varbit", 2561), lastValue = 2, steps = mapOf("1" to harlow))
        val game = FakeGame().apply {
            playerTile = Tile(3222, 3399, 0)
            varbits[2561] = 1
            inv[1550] = 1
            npcs += SceneEntity(16277, "Dr Harlow", Tile(3223, 3399, 0))
            onNpcInteract = { _, _ -> options = listOf("I need your help dealing with a vampyre.", "I think you've had enough.") }
        }
        val ctx = TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.QuestTask("vampyreslayer", helper, allowCombat = false, boundedTo = setOf(3480, 16276, 16277))
        repeat(5) { task.tick(ctx) }
        assertTrue("option I need your help dealing with a vampyre." in game.calls, game.calls.toString())
    }

    @Test
    fun `Dr Harlow's beer question is answered by agreeing, not by refusing`() {
        val plan = com.hcimbot.plan.DialoguePlan.Texts(com.hcimbot.plan.QuestFills.extraDialogue("vampyreslayer"))
        val choice = plan.choose(listOf("Yes, here you go.", "No. I think you've had enough."))
        assertEquals(com.hcimbot.plan.DialoguePlan.Choice.ByText("Yes, here you go."), choice)
    }

    @Test
    fun `step 74 is done once Dr Harlow has given the stake, without going on to Count Draynor`() {
        val harlow = com.hcimbot.guide.Instruction(kind = "npc", point = listOf(3222, 3399, 0), text = "Talk to Dr. Harlow.", ids = listOf(3480))
        val helper = com.hcimbot.guide.QuestHelper(com.hcimbot.guide.QuestVar("varbit", 2561), lastValue = 3, steps = mapOf("2" to harlow, "3" to harlow))
        val game = FakeGame().apply { varbits[2561] = 3; inv[1549] = 1 }
        val ctx = TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.QuestTask("vampyreslayer", helper, allowCombat = true, boundedTo = setOf(3480), finishesQuest = true, doneWithItem = 1549)
        assertEquals(com.hcimbot.plan.TaskResult.Done, task.tick(ctx))
    }

    @Test
    fun `no step has two scripts, where the later would silently replace the earlier`() {
        val source = java.io.File("src/main/kotlin/com/hcimbot/plan/StepScripts.kt").readText()
        val ids = Regex("\"([0-9a-f]{10})\" to \\{").findAll(source).map { it.groupValues[1] }.toList()
        assertEquals(emptyList(), ids.groupBy { it }.filterValues { it.size > 1 }.keys.toList())
    }

    private val dropped = mutableMapOf<Int, Int>()

    private val scripts = StepScripts(Guide(1))

    private fun plan(id: String, settings: BotSettings = BotSettings()) = scripts.planFor(Step(id = id), settings)

    /** Runs a plan's tasks in order, one tick at a time, like the runner does. */
    private fun run(plan: StepPlan?, game: FakeGame, ticks: Int = 80): TaskResult {
        val auto = assertIs<StepPlan.Auto>(plan)
        val ctx = TaskContext(game, game, Conditions(game))
        var index = 0
        repeat(ticks) {
            val task = auto.tasks.getOrNull(index) ?: return TaskResult.Done
            when (val result = task.tick(ctx)) {
                TaskResult.Done -> index++
                is TaskResult.Failed, is TaskResult.NeedsStep -> return result
                TaskResult.Running -> Unit
            }
        }
        return if (index >= auto.tasks.size) TaskResult.Done else TaskResult.Running
    }

    @Test
    fun `headings and reminders are skipped`() {
        assertIs<StepPlan.Skip>(plan("332728ed58"))
        assertIs<StepPlan.Skip>(plan("caa42731e5"))
    }

    @Test
    fun `magic tutor drops the runes, claims new ones, then picks the old ones back up`() {
        val tutorAt = Tile(3218, 3238, 0)
        val game = FakeGame().apply {
            inv[556] = 25
            inv[558] = 15
            npcs += SceneEntity(3218, "Magic combat tutor", tutorAt)
        }
        // Dropping leaves the stack on the ground; the tutor only gives runes to players without any.
        // The tutor's right click Claim gives 30 of each, but only to a player who has none.
        game.onNpcInteract = { _, action ->
            if (action == "Claim" && 556 !in game.inv && 558 !in game.inv) { game.inv[556] = 30; game.inv[558] = 30 }
        }
        val drop = { id: Int, count: Int -> game.groundItems += SceneEntity(id, "Rune $id", tutorAt); dropped[id] = count }
        game.onItemAction = { id, action -> if (action == "Drop") drop(id, game.inv[id] ?: 0) }
        game.onTake = { item -> game.inv[item.id] = (game.inv[item.id] ?: 0) - 1 + (dropped.remove(item.id) ?: 1) }

        assertEquals(TaskResult.Done, run(plan("cce7a1b4dc"), game))
        assertEquals(55, game.inv[556], "25 picked back up on top of the 30 claimed")
        assertEquals(45, game.inv[558])
        assertTrue(game.groundItems.isEmpty())
        val drops = game.calls.filter { it.startsWith("item") }
        assertEquals(listOf("item 556 Drop", "item 558 Drop"), drops)
        assertTrue("npc Magic combat tutor Claim" in game.calls, "${game.calls}")
        // Dropped before talking, as the tutor requires.
        assertTrue(game.calls.indexOf("item 558 Drop") < game.calls.indexOfFirst { it.startsWith("npc") })
    }

    @Test
    fun `general store sells the starter gear then buys the tools`() {
        val game = FakeGame().apply {
            playerTile = Tile(3212, 3246, 0)
            listOf(1205, 1277, 1171, 841).forEach { inv[it] = 1 }
            npcs += SceneEntity(2813, "Shop keeper", Tile(3212, 3247, 0))
            onNpcInteract = { _, action -> if (action == "Trade") isShopOpen = true }
        }
        assertEquals(TaskResult.Done, run(plan("42b86f0029"), game))
        assertEquals(listOf("sell 1205 x5", "sell 1277 x5", "sell 1171 x5", "sell 841 x5"), game.calls.filter { it.startsWith("sell") })
        assertEquals(listOf("buy 952 x1", "buy 1755 x1", "buy 2347 x1"), game.calls.filter { it.startsWith("buy") })
        assertTrue(listOf(952, 1755, 2347).all { it in game.inv })
    }

    @Test
    fun `combat steps pause unless combat is allowed`() {
        assertIs<StepPlan.Manual>(plan("0cd68d667b"))
        assertIs<StepPlan.Auto>(plan("0cd68d667b", BotSettings(allowCombat = true)))
    }

    @Test
    fun `chicken kills repeat until a feather drops`() {
        var kills = 0
        val chickenAt = Tile(3223, 3218, 0)
        val game = FakeGame().apply {
            npcs += SceneEntity(1173, "Chicken", chickenAt)
            onNpcInteract = { _, action ->
                if (action == "Attack") {
                    kills++
                    groundItems += SceneEntity(526, "Bones", chickenAt)
                    groundItems += SceneEntity(2138, "Raw chicken", chickenAt)
                    // The first chicken drops no feather.
                    if (kills == 2) groundItems += SceneEntity(314, "Feather", chickenAt)
                }
            }
        }
        assertEquals(TaskResult.Done, run(plan("0cd68d667b", BotSettings(allowCombat = true)), game, ticks = 200))
        assertEquals(2, kills)
        assertTrue(listOf(314, 526, 2138).all { it in game.inv })
    }

    @Test
    fun `garlic cupboard is searched until there are four`() {
        val game = FakeGame().apply {
            playerTile = Tile(3100, 3267, 0)
            objects += SceneEntity(15645, "Staircase", Tile(3100, 3267, 0))
            onObjectInteract = { obj, action ->
                when {
                    obj.id == 15645 -> {
                        playerTile = Tile(3097, 3269, 1)
                        objects.clear()
                        objects += SceneEntity(2612, "Cupboard", Tile(3096, 3270, 1))
                    }
                    action == "Open" -> { objects.clear(); objects += SceneEntity(2613, "Cupboard", Tile(3096, 3270, 1)) }
                    action == "Search" -> inv.merge(1550, 1, Int::plus)
                }
            }
        }
        assertEquals(TaskResult.Done, run(plan("1d0e75f508"), game, ticks = 120))
        assertEquals(4, game.inv[1550])
        assertEquals(4, game.calls.count { it == "object Cupboard Search" })
    }

    @Test
    fun `wine is bought in two lots with a world hop between`() {
        var stock = 5
        val game = FakeGame().apply {
            playerTile = Tile(3085, 3252, 0)
            npcs += SceneEntity(1260, "Fortunato", Tile(3085, 3253, 0))
            onNpcInteract = { _, action -> if (action == "Trade") isShopOpen = true }
            onHop = { stock = 5 }
        }
        // The fake shop sells what is asked; cap it at the stock so a single visit cannot finish.
        val shop = object : FakeGameShopCap(game) {}
        shop.limit = { stock }
        shop.bought = { stock -= it }
        assertEquals(TaskResult.Done, run(plan("e3eead8e86"), shop.game, ticks = 120))
        assertEquals(10, game.inv[1993])
        assertTrue("hop" in game.calls)
    }

    @Test
    fun `stray dog is looked for and given a bone from the inventory`() {
        val game = FakeGame().apply {
            inv[526] = 1
            playerTile = Tile(3212, 3428, 0)
            npcs += SceneEntity(2902, "Stray dog", Tile(3215, 3430, 0))
        }
        assertEquals(TaskResult.Done, run(plan("3ad09c7fc7"), game))
        assertEquals(listOf("use 526 on Stray dog"), game.calls)
    }

    @Test
    fun `with no bone on hand the bank gives one first`() {
        val game = FakeGame().apply {
            bank[526] = 5
            playerTile = Tile(3185, 3436, 0)
            npcs += SceneEntity(2902, "Stray dog", Tile(3188, 3438, 0))
        }
        assertEquals(TaskResult.Done, run(plan("3ad09c7fc7"), game))
        assertEquals(1, game.bank[526]?.let { 5 - it })
        assertTrue("use 526 on Stray dog" in game.calls, game.calls.toString())
    }
}

/** Wraps the fake game's buying so a shop only has [limit] in stock. */
open class FakeGameShopCap(val game: FakeGame) {
    var limit: () -> Int = { Int.MAX_VALUE }
    var bought: (Int) -> Unit = {}

    init {
        game.buyCap = { wanted -> minOf(wanted, limit()).also { bought(it) } }
    }
}

class HansScriptTest {
    private val scripts = StepScripts(Guide(1))

    private fun run(game: FakeGame): TaskResult {
        val auto = assertIs<StepPlan.Auto>(scripts.planFor(Step(id = "d3c4ea0a99"), BotSettings()))
        val ctx = TaskContext(game, game, Conditions(game))
        var index = 0
        repeat(60) {
            val task = auto.tasks.getOrNull(index) ?: return TaskResult.Done
            when (val result = task.tick(ctx)) {
                TaskResult.Done -> index++
                is TaskResult.Failed, is TaskResult.NeedsStep -> return result
                TaskResult.Running -> Unit
            }
        }
        return TaskResult.Running
    }

    private fun hans() = FakeGame().apply { npcs += SceneEntity(3105, "Hans", Tile(3212, 3219, 0)) }

    @Test
    fun `the Age option is enough when it ticks the diary`() {
        val game = hans()
        game.onNpcInteract = { _, action -> if (action == "Age") game.varps[1194] = 1 shl 5 }
        assertEquals(TaskResult.Done, run(game))
        assertEquals(listOf("npc Hans Age"), game.calls)
    }

    @Test
    fun `otherwise it asks Hans in chat`() {
        val game = hans()
        game.onNpcInteract = { _, action ->
            if (action == "Talk-to") {
                game.options = listOf("I'm looking for whoever is in charge of this place.", "Can you tell me how long I've been here?", "Nothing.")
            }
        }
        game.onOption = { if ("how long" in it) game.varps[1194] = 1 shl 5 }
        assertEquals(TaskResult.Done, run(game))
        assertTrue("option Can you tell me how long I've been here?" in game.calls, "${game.calls}")
    }
}
