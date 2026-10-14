package com.hcimbot

import com.hcimbot.engine.BotSettings
import com.hcimbot.engine.Conditions
import com.hcimbot.game.SceneEntity
import com.hcimbot.guide.Guide
import com.hcimbot.guide.Instruction
import com.hcimbot.guide.ItemRef
import com.hcimbot.guide.QuestHelper
import com.hcimbot.guide.QuestVar
import com.hcimbot.guide.Section
import com.hcimbot.guide.Step
import com.hcimbot.guide.Target
import com.hcimbot.guide.Tile
import com.hcimbot.plan.EquipTask
import com.hcimbot.plan.InteractTask
import com.hcimbot.plan.QuestTask
import com.hcimbot.plan.ShopTask
import com.hcimbot.plan.StepPlan
import com.hcimbot.plan.StepPlanner
import com.hcimbot.plan.TakeItemTask
import com.hcimbot.plan.TaskContext
import com.hcimbot.plan.TaskResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Cases taken from the guide's first 100 steps, each one a mistake the planner used to make. */
class PlannerTest {
    private val quest = QuestHelper(
        QuestVar("varp", 1),
        lastValue = 3,
        steps = mapOf(
            "0" to Instruction(kind = "npc", text = "Talk to Alina.", ids = listOf(12644)),
            "1" to Instruction(kind = "npc", text = "Talk to Harlow.", ids = listOf(3480)),
            "2" to Instruction(kind = "npc", text = "Kill the Count.", ids = listOf(9999)),
        ),
    )
    private val planner = StepPlanner(Guide(1, listOf(Section()), mapOf("q" to quest)), BotSettings())

    private fun plan(step: Step) = planner.plan(step)
    private fun npc(name: String, vararg ids: Int, confidence: String = "linked") =
        Target(name, listOf(name), "npc", ids.toList(), listOf(listOf(3222, 3218, 0)), confidence)

    @Test
    fun `a walk with a warning pauses`() {
        val step = Step(text = "Head West towards Draynor hugging the fence to avoid the Jail Guards", arrivesAt = listOf(3103, 3265, 0))
        assertIs<StepPlan.Manual>(plan(step))
    }

    @Test
    fun `depositing some items is not a deposit all`() {
        assertIs<StepPlan.Manual>(plan(Step(text = "Bank at Draynor and deposit the Garlic & Cheese", banking = true)))
    }

    @Test
    fun `a then always means another action`() {
        val step = Step(text = "Talk to Old Man Yarlo, then destroy all the furniture", target = npc("Old Man Yarlo", 1))
        assertIs<StepPlan.Manual>(plan(step))
    }

    @Test
    fun `worded asides count, chat numbers do not`() {
        val buy = Step(text = "Talk to Friendly Forester and obtain Forestry kit (right click shop and buy 1)", target = npc("Friendly Forester", 1))
        assertIs<StepPlan.Manual>(plan(buy))
        assertIs<StepPlan.Auto>(plan(Step(text = "Talk to Jennifer (3,2)", target = npc("Jennifer", 1), dialogue = listOf(listOf(3, 2)))))
    }

    @Test
    fun `guessed targets are ignored`() {
        val step = Step(text = "Open Minimap & follow the path South-West to Shayzien", target = Target("Minimap", kind = "object", confidence = "inferred"))
        assertIs<StepPlan.Manual>(plan(step))
    }

    @Test
    fun `take and wield picks up then equips`() {
        val gloves = ItemRef("Leather Gloves", listOf(1059))
        val step = Step(text = "Take Leather Gloves on the table inside his house & wield", items = listOf(gloves), collects = true)
        val auto = assertIs<StepPlan.Auto>(plan(step))
        assertEquals(listOf(TakeItemTask::class, EquipTask::class), auto.tasks.map { it::class })
    }

    @Test
    fun `picks up nearby items, equips them, and leaves far ones alone`() {
        val gloves = ItemRef("Leather Gloves", listOf(1059))
        val game = FakeGame().apply {
            groundItems += SceneEntity(1059, "Leather gloves", Tile(3224, 3218, 0))
            itemMenus[1059] = listOf("Wear", "Drop")
        }
        val ctx = TaskContext(game, game, Conditions(game))
        val take = TakeItemTask(listOf(gloves))
        val equip = EquipTask(listOf(gloves))
        assertEquals(TaskResult.Running, take.tick(ctx))
        assertEquals(TaskResult.Done, take.tick(ctx))
        assertEquals(TaskResult.Running, equip.tick(ctx))
        assertEquals(TaskResult.Done, equip.tick(ctx))
        assertEquals(listOf("take Leather gloves", "item 1059 Wear"), game.calls)

        val far = FakeGame().apply { groundItems += SceneEntity(1061, "Leather boots", Tile(3260, 3218, 0)) }
        val boots = TakeItemTask(listOf(ItemRef("Leather Boots", listOf(1061))))
        val farCtx = TaskContext(far, far, Conditions(far))
        val results = (1..20).map { boots.tick(farCtx) }
        assertIs<TaskResult.Failed>(results.last())
        assertTrue(far.calls.isEmpty(), "must not walk off after an item ${far.calls}")
    }

    @Test
    fun `talk to and buy is one shop visit`() {
        val step = Step(text = "Talk to Bob and buy a Steel Axe", target = npc("Bob", 10619), items = listOf(ItemRef("Steel Axe", listOf(1353))))
        val auto = assertIs<StepPlan.Auto>(plan(step))
        assertIs<ShopTask>(auto.tasks.single())
    }

    @Test
    fun `shop steps that ask for more pause`() {
        val wine = Step(text = "Talk to Fortunato & buy 5 Jugs of wine. Hop worlds and buy 10 in total.", target = npc("Fortunato", 1), items = listOf(ItemRef("Jug of wine", listOf(1993), 5)))
        assertIs<StepPlan.Manual>(plan(wine))
        val wear = Step(text = "Buy Pink Skirt & Brown Apron. Wear Brown Apron in honour of Faux", target = npc("Thessalia", 1), items = listOf(ItemRef("Pink Skirt", listOf(1013))))
        assertIs<StepPlan.Manual>(plan(wear))
    }

    @Test
    fun `buying through chat is a talk, not a shop`() {
        val step = Step(text = "Buy a Newspaper from Benny(1,1)", target = npc("Benny", 5216), dialogue = listOf(listOf(1, 1)), items = listOf(ItemRef("Newspaper", listOf(11171))))
        val task = assertIs<StepPlan.Auto>(plan(step)).tasks.single()
        assertIs<InteractTask>(task)
        assertEquals("Talk-to Benny", task.description)
    }

    @Test
    fun `the last listed part of a quest is still only that part`() {
        val step = Step(text = "Head East and talk to Dr Harlow (1,2 talk again)", target = npc("Dr Harlow", 3480), questHelper = "q", questStep = true, questCompletes = true)
        val game = FakeGame().apply {
            varps[1] = 1
            npcs += SceneEntity(3480, "Dr Harlow", Tile(3222, 3218, 0))
            npcs += SceneEntity(9999, "Count", Tile(3222, 3218, 0))
            onNpcInteract = { npc, _ -> if (npc.id == 3480) varps[1] = 2 }
        }
        val auto = assertIs<StepPlan.Auto>(plan(step))
        assertTrue(auto.trustTasks)
        val ctx = TaskContext(game, game, Conditions(game))
        val task = auto.tasks.single()
        repeat(10) { if (task.tick(ctx) == TaskResult.Done) return@repeat }
        // Talked to Harlow and stopped before the fight.
        assertEquals(listOf("npc Dr Harlow Talk-to"), game.calls)
    }

    @Test
    fun `a quest step Quest Helper does not mention follows the guide text`() {
        val step = Step(text = "Talk to Noah (1,3)", target = npc("Noah", 12646), questHelper = "q", questStep = true, dialogue = listOf(listOf(1, 3)))
        val auto = assertIs<StepPlan.Auto>(plan(step))
        assertTrue(auto.trustTasks)
        assertEquals("Talk-to Noah", auto.tasks.single().description)
    }

    @Test
    fun `quest helper gaps are spotted`() {
        val xmts = QuestHelper(steps = listOf(0, 1, 6, 7).associate { "$it" to Instruction(text = "Talk to Veos") }, lastValue = 7)
        assertFalse(xmts.hasGapAt(1))
        assertTrue(xmts.hasGapAt(2))
        assertTrue(xmts.hasGapAt(5))
        assertFalse(xmts.hasGapAt(6))
        val romeo = QuestHelper(steps = listOf(0, 10, 20).associate { "$it" to Instruction() }, lastValue = 20)
        assertFalse(romeo.hasGapAt(15), "values between Quest Helper's keys are normal")

        val game = FakeGame().apply { varps[1] = 3 }
        val result = QuestTask("xmts", QuestHelper(QuestVar("varp", 1), 7, xmts.steps), allowCombat = false)
            .tick(TaskContext(game, game, Conditions(game)))
        assertIs<TaskResult.Failed>(result)
        assertTrue(game.calls.isEmpty())
    }

    @Test
    fun `a step that only starts a quest stops once the quest has started`() {
        // "Check the Notice board ... to Start A Porcine of Interest", whose data says done at the quest's end.
        val step = Step(text = "Check the Notice board next to Fortunato to Start A Porcine of Interest", questHelper = "q", questStep = true, questDoneAt = 3)
        val auto = assertIs<StepPlan.Auto>(plan(step))
        val game = FakeGame().apply { varps[1] = 1 } // already read: the quest is on to Harlow
        assertEquals(TaskResult.Done, auto.tasks.single().tick(TaskContext(game, game, Conditions(game))))
        assertTrue(game.calls.isEmpty(), "it must not carry on with the quest")
    }
}
