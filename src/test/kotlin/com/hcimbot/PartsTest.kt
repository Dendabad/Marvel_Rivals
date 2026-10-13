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
