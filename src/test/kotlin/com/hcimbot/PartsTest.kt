package com.hcimbot

import com.hcimbot.engine.Conditions
import com.hcimbot.guide.GuideLoader
import com.hcimbot.guide.Memory
import com.hcimbot.guide.Need
import com.hcimbot.guide.Requirement
import com.hcimbot.guide.Step
import com.hcimbot.guide.Tile
import com.hcimbot.guide.VarCheck
import com.hcimbot.plan.StepPlanner
import com.hcimbot.plan.TravelTask
import com.hcimbot.progress.ProgressFormat
import com.hcimbot.progress.SharedProgressStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartsTest {
    @Test
    fun `loader rejects a schema it does not understand`() {
        val json = """{"schemaVersion": 2, "sections": []}"""
        assertFailsWith<GuideLoader.GuideLoadException> { GuideLoader().load(json.byteInputStream()) }
    }

    @Test
    fun `conditions match the plugin's rules`() {
        val game = FakeGame()
        val conditions = Conditions(game)
        game.varbits[1] = 5
        game.playerTile = Tile(10, 10, 0)

        assertTrue(conditions.holds(VarCheck("varbit", 1, 4, ">=")))
        assertFalse(conditions.holds(VarCheck("varbit", 1, 4, "==")))
        assertTrue(conditions.holds(Requirement(zone = listOf(listOf(5, 5, 0, 15, 15, 0)))))
        assertFalse(conditions.holds(Requirement(zone = listOf(listOf(5, 5, 1, 15, 15, 1)))))

        game.inv[995] = 3
        game.bank[995] = 10
        assertFalse(conditions.holds(Requirement(item = Need("Coins", 5, listOf(995)))))
        assertTrue(conditions.holds(Requirement(item = Need("Coins", 5, listOf(995), bank = true))))
        assertTrue(conditions.holds(Requirement(not = Requirement(item = Need("Coins", 5, listOf(995))))))
    }

    @Test
    fun `once latches after its condition held`() {
        val game = FakeGame()
        val conditions = Conditions(game)
        val once = Requirement(once = Memory("k", condition = Requirement(varCheck = VarCheck("varp", 9, 1))))

        conditions.observe(once)
        assertFalse(conditions.holds(once))
        game.varps[9] = 1
        conditions.observe(once)
        game.varps[9] = 0
        assertTrue(conditions.holds(once))
    }

    @Test
    fun `quest fights pause unless combat is on`() {
        val game = FakeGame()
        game.npcs += com.hcimbot.game.SceneEntity(5, "Imp", Tile(3222, 3218, 0))
        val helper = com.hcimbot.guide.QuestHelper(
            com.hcimbot.guide.QuestVar("varp", 1),
            lastValue = 1,
            steps = mapOf("0" to com.hcimbot.guide.Instruction(kind = "npc", text = "Kill the Imp.", ids = listOf(5))),
        )
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))

        val refused = com.hcimbot.plan.QuestTask("q", helper, allowCombat = false).tick(ctx)
        assertTrue(refused is com.hcimbot.plan.TaskResult.Failed && "combat" in refused.reason, refused.toString())
        assertTrue(game.calls.isEmpty())

        com.hcimbot.plan.QuestTask("q", helper, allowCombat = true).tick(ctx)
        assertEquals(listOf("npc Imp Attack"), game.calls)
    }

    @Test
    fun `quest stairs with no id are climbed by walking to the floor above`() {
        // Rune Mysteries at the start: "use the south staircase", with no staircase id,
        // and a branch for once you are upstairs with the Duke.
        val game = FakeGame()
        game.playerTile = Tile(3222, 3218, 0)
        val upstairs = Requirement(zone = listOf(listOf(3203, 3206, 1, 3218, 3231, 1)))
        val duke = com.hcimbot.guide.Instruction(
            kind = "npc", point = listOf(3209, 3222, 1), text = "Talk to Duke Horacio on the first floor of Lumbridge castle.",
            ids = listOf(815), dialogue = listOf("Have you any quests for me?", "Yes."), condition = upstairs,
        )
        val helper = com.hcimbot.guide.QuestHelper(
            com.hcimbot.guide.QuestVar("varp", 63),
            lastValue = 1,
            steps = mapOf(
                "0" to com.hcimbot.guide.Instruction(
                    kind = "object", point = listOf(3205, 3208, 0), text = duke.text, whenIn = listOf(duke),
                ),
            ),
        )
        game.npcs += com.hcimbot.game.SceneEntity(815, "Duke Horacio", Tile(3209, 3222, 1))
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.QuestTask("runemysteries", helper, allowCombat = false, boundedTo = setOf(815, 5327))

        val first = task.tick(ctx)
        assertTrue(first !is com.hcimbot.plan.TaskResult.Failed, first.toString())
        assertEquals(listOf("walk (3209, 3222, 1)"), game.calls)

        repeat(5) { task.tick(ctx) }
        assertTrue(game.calls.any { it.startsWith("npc Duke Horacio") }, game.calls.toString())
    }

    @Test
    fun `X Marks the Spot digs come from Quest Helper's own source`() {
        val game = FakeGame()
        game.playerTile = Tile(3222, 3218, 0)
        game.inv[com.hcimbot.plan.DigTask.SPADE] = 1
        game.varbits[8063] = 2
        val talk = com.hcimbot.guide.Instruction(kind = "npc", text = "Talk to Veos.", ids = listOf(8484), dialogue = listOf("Yes."))
        val helper = com.hcimbot.guide.QuestHelper(
            com.hcimbot.guide.QuestVar("varbit", 8063),
            lastValue = 7,
            steps = mapOf("0" to talk, "1" to talk, "6" to talk, "7" to talk),
        )
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.QuestTask("xmarksthespot", helper, allowCombat = false)

        repeat(4) { task.tick(ctx) }
        assertEquals(listOf("walk (3230, 3209, 0)", "item 952 Dig"), game.calls)
    }

    private fun xMarksHelper(): com.hcimbot.guide.QuestHelper {
        val talk = com.hcimbot.guide.Instruction(kind = "npc", text = "Talk to Veos.", ids = listOf(8484), dialogue = listOf("Yes."))
        return com.hcimbot.guide.QuestHelper(
            com.hcimbot.guide.QuestVar("varbit", 8063),
            lastValue = 7,
            steps = mapOf("0" to talk, "1" to talk, "6" to talk, "7" to talk),
        )
    }

    @Test
    fun `starting X Marks the Spot does not run ahead to the first dig`() {
        // Step 7 run again once the quest has started: its part is done, and the dig is step 18's.
        val game = FakeGame()
        game.inv[com.hcimbot.plan.DigTask.SPADE] = 1
        game.varbits[8063] = 2
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.QuestTask("xmarksthespot", xMarksHelper(), allowCombat = false, boundedTo = setOf(8484))
        assertEquals(com.hcimbot.plan.TaskResult.Done, task.tick(ctx))
        assertTrue(game.calls.isEmpty(), game.calls.toString())
    }

    @Test
    fun `finishing X Marks the Spot does the last dig first if it is owed`() {
        val game = FakeGame()
        game.inv[com.hcimbot.plan.DigTask.SPADE] = 1
        game.varbits[8063] = 5
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.QuestTask(
            "xmarksthespot", xMarksHelper(), allowCombat = false, boundedTo = setOf(8484), finishesQuest = true,
        )
        task.tick(ctx)
        assertEquals(listOf("walk (3078, 3259, 0)"), game.calls)
    }

    @Test
    fun `withdrawing the treasure scroll takes whichever clue item the quest is on`() {
        val game = FakeGame()
        game.varbits[8063] = 3
        game.bank[23069] = 1 // the mysterious orb the first dig swapped the scroll for
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.BankTask(false, listOf(com.hcimbot.guide.ItemRef("Treasure Scroll", listOf(23067, 23068, 23070))))
        repeat(6) { task.tick(ctx) }
        assertEquals(1, game.inv[23069])
    }

    @Test
    fun `a treasure scroll missing because the quest never started sends the bot back to step 7`() {
        val game = FakeGame()
        game.bank[995] = 100
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.BankTask(
            false,
            listOf(com.hcimbot.guide.ItemRef("Treasure Scroll", listOf(23067)), com.hcimbot.guide.ItemRef("Coins", listOf(995))),
        )
        var result: com.hcimbot.plan.TaskResult = com.hcimbot.plan.TaskResult.Running
        repeat(8) { if (result == com.hcimbot.plan.TaskResult.Running) result = task.tick(ctx) }
        assertEquals(100, game.inv[995], "takes out what the bank has first")
        assertTrue(result is com.hcimbot.plan.TaskResult.NeedsStep && result.stepId == "e2deed373e", result.toString())
    }

    @Test
    fun `with no rat in sight the bot goes to where giant rats spawn`() {
        val game = FakeGame()
        game.playerTile = Tile(3222, 3218, 0)
        game.spawns["giant rat"] = listOf(
            Tile(3197, 3201, 0), // Lumbridge Swamp, close by
            Tile(3292, 3377, 0), // Varrock, too far to go looking
            Tile(3040, 3700, 0), // the Wilderness: never
        )
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.KillTask(emptyList(), listOf("Giant rat"), listOf(com.hcimbot.guide.ItemRef("Bones", listOf(526))), spell = "WIND_STRIKE")

        task.tick(ctx)
        task.tick(ctx)
        assertEquals(listOf("walk (3197, 3201, 0)"), game.calls)

        game.npcs += com.hcimbot.game.SceneEntity(2510, "Giant rat", Tile(3199, 3200, 0))
        repeat(6) { task.tick(ctx) }
        assertTrue("cast WIND_STRIKE on Giant rat" in game.calls, game.calls.toString())
    }

    @Test
    fun `wind strike is cast again and again until the rat dies`() {
        val ratAt = Tile(3223, 3218, 0)
        val rat = com.hcimbot.game.SceneEntity(2510, "Giant rat", ratAt, index = 7)
        val game = FakeGame()
        game.npcs += rat
        var casts = 0
        game.onNpcInteract = { _, _ ->
            // Three hits kill it, and it drops its bones.
            if (++casts == 3) {
                game.npcs.remove(rat)
                game.groundItems += com.hcimbot.game.SceneEntity(526, "Bones", ratAt)
            }
        }
        game.alive = { npc -> npc.index == 7 && rat in game.npcs }
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.KillTask(
            emptyList(), listOf("Giant rat"), listOf(com.hcimbot.guide.ItemRef("Bones", listOf(526))), spell = "WIND_STRIKE",
        )
        var result: com.hcimbot.plan.TaskResult = com.hcimbot.plan.TaskResult.Running
        repeat(60) { if (result == com.hcimbot.plan.TaskResult.Running) result = task.tick(ctx) }
        assertEquals(com.hcimbot.plan.TaskResult.Done, result)
        assertEquals(3, game.calls.count { it == "cast WIND_STRIKE on Giant rat" }, game.calls.toString())
        assertEquals(1, game.inv[526])
    }

    @Test
    fun `wind strike keeps going while the rat fights back, even when the game cannot say if it lives`() {
        val rat = com.hcimbot.game.SceneEntity(2510, "Giant rat", Tile(3223, 3218, 0))
        val game = FakeGame()
        game.npcs += rat
        game.isBusy = true // hit back every tick, so the block animation never stops
        game.onNpcInteract = { _, _ -> game.isInCombat = true }
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.KillTask(
            emptyList(), listOf("Giant rat"), listOf(com.hcimbot.guide.ItemRef("Bones", listOf(526))), spell = "WIND_STRIKE",
        )
        game.isBusy = false
        task.tick(ctx) // first cast
        game.isBusy = true
        repeat(11) { task.tick(ctx) }
        assertEquals(3, game.calls.count { it == "cast WIND_STRIKE on Giant rat" }, game.calls.toString())
    }

    @Test
    fun `step 19 looks for rats behind Lumbridge castle first`() {
        val game = FakeGame()
        game.playerTile = Tile(3230, 3209, 0)
        game.spawns["giant rat"] = listOf(Tile(3229, 3188, 0)) // nearer, but in the swamp
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val task = com.hcimbot.plan.KillTask(
            emptyList(), listOf("Giant rat"), listOf(com.hcimbot.guide.ItemRef("Bones", listOf(526))),
            lookFirst = Tile(3195, 3205, 0),
        )
        task.tick(ctx)
        task.tick(ctx)
        assertEquals(listOf("walk (3195, 3205, 0)"), game.calls)
    }

    @Test
    fun `hunting gives up rather than going far or into the Wilderness`() {
        val game = FakeGame()
        game.spawns["giant rat"] = listOf(Tile(3040, 3700, 0), Tile(3292, 3377, 0))
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        val result = com.hcimbot.plan.KillTask(emptyList(), listOf("Giant rat"), emptyList<com.hcimbot.guide.ItemRef>() + com.hcimbot.guide.ItemRef("Bones", listOf(526))).tick(ctx)
        assertTrue(result is com.hcimbot.plan.TaskResult.Failed, result.toString())
        assertTrue(game.calls.isEmpty())
    }

    @Test
    fun `quest objects with only a RuneLite name are looked up`() {
        val game = FakeGame()
        game.objectNames["PORCINE_NOTICEBOARD"] = 40307
        game.objects += com.hcimbot.game.SceneEntity(40307, "Notice board", Tile(3222, 3218, 0))
        val helper = com.hcimbot.guide.QuestHelper(
            com.hcimbot.guide.QuestVar("varp", 1),
            lastValue = 35,
            steps = mapOf(
                "0" to com.hcimbot.guide.Instruction(
                    kind = "object", point = listOf(3222, 3218, 0), text = "Read the notice board in Draynor Village.",
                    constant = "PORCINE_NOTICEBOARD",
                ),
            ),
        )
        val ctx = com.hcimbot.plan.TaskContext(game, game, Conditions(game))
        com.hcimbot.plan.QuestTask("aporcineofinterest", helper, allowCombat = false, boundedTo = emptySet()).tick(ctx)
        assertEquals(listOf("object Notice board Read"), game.calls)
    }

    @Test
    fun `menu verbs come from the step text`() {
        assertEquals("Pickpocket", StepPlanner.verb(Step(text = "Pickpocket a man/woman [Lumbridge Easy Diary]")))
        assertEquals("Talk-to", StepPlanner.verb(Step(text = "Go upstairs and Talk to Duke Horacio (1,1)")))
        assertEquals("Attack", StepPlanner.verb(Step(text = "Kill a giant rat with Wind Strike")))
        assertNull(StepPlanner.verb(Step(text = "Head North-West")))
        assertEquals("Talk-to", StepPlanner.verb(Step(text = "Walk to the Hosidius General Store, talk to Horace (3,2)")))
        assertNull(StepPlanner.verb(Step(text = "Teleport to Essence Mine via right click Wizard Cromperty")))
    }

    @Test
    fun `compound steps are spotted`() {
        assertTrue(StepPlanner.isCompound(Step(text = "Kill a giant rat and collect the Bones")))
        assertTrue(StepPlanner.isCompound(Step(text = "Sell the dagger & buy a hammer")))
        assertFalse(StepPlanner.isCompound(Step(text = "Talk to Father Aereck (3,1) [Restless Ghost & more]")))
        assertFalse(
            StepPlanner.isCompound(Step(text = "Bank at Draynor and Deposit all"), ignoringBanking = true)
        )
        assertFalse(StepPlanner.isCompound(Step(text = "Head South & pray at the Guthix Altar")))
        assertFalse(StepPlanner.isCompound(Step(text = "Go upstairs and Talk to Duke Horacio (1,1)")))
        assertTrue(StepPlanner.isCompound(Step(text = "Teleport to Kourend. Enter Catacombs. Head West")))
    }

    @Test
    fun `steps that only move become walks`() {
        assertTrue(StepPlanner.isMovement(Step(text = "Head to Canifis")))
        assertFalse(StepPlanner.isMovement(Step(text = "Complete the Agility Pyramid until 627,000 Agility Experience")))
        assertFalse(StepPlanner.isMovement(Step(text = "Teleport to Grand Exchange via spellbook")))
        assertFalse(StepPlanner.isMovement(Step(text = "Return to Ardougne & deposit the Pineapples & bones")))
        assertTrue(StepPlanner.isOpenEnded(Step(text = "Buy Balls of Wool at Ardougne if needed")))
        assertFalse(StepPlanner.isOpenEnded(Step(text = "Withdraw: Coins, Spade (2 Inventory slots)")))
    }

    @Test
    fun `teleport destinations`() {
        fun dest(text: String, via: String = "") =
            StepPlanner.teleportDestination(Step(text = text, teleport = com.hcimbot.guide.Teleport(via, listOf(1))))
        assertEquals("wintertodt", dest("Games necklace -> Wintertodt"))
        assertEquals("Ardougne", dest("Ardougne: Ardy Cloak 1 -> Run North", via = "Ardougne: Ardy Cloak 1"))
        assertNull(dest("Ectophial"))
    }

    @Test
    fun `travel menu option drops the target name`() {
        assertEquals("Travel", TravelTask.menuOption("Travel Minecart", "Minecart"))
        assertEquals("Brimhaven", TravelTask.menuOption("Brimhaven Captain Barnaby", "Captain Barnaby"))
        assertEquals("Pay-fare", TravelTask.menuOption("Pay-fare", "Customs officer"))
    }

    @Test
    fun `progress reads the plugin's ticks and keeps its own`() {
        var plugin = "a,b"
        var bot: String? = null
        val store = SharedProgressStore({ plugin }, { bot }, { bot = it })

        assertEquals(setOf("a", "b"), store.completedIds())
        store.markComplete("c")
        assertEquals("c", bot)
        plugin = "a,b,d"
        assertEquals(setOf("a", "b", "c", "d"), store.completedIds())
        assertEquals(setOf("x", "y"), ProgressFormat.decode(" x, ,y,"))
    }
}
