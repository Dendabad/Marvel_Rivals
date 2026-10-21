package com.hcimbot

import com.hcimbot.engine.Conditions
import com.hcimbot.game.QuestItem
import com.hcimbot.guide.Tile
import com.hcimbot.plan.GrandExchangeTask
import com.hcimbot.plan.TaskContext
import com.hcimbot.plan.TaskResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GrandExchangeTaskTest {
    private fun run(game: FakeGame, task: GrandExchangeTask): TaskResult {
        var result: TaskResult = TaskResult.Running
        var ticks = 0
        while (result == TaskResult.Running && ticks++ < 600) {
            result = task.tick(TaskContext(game, game, Conditions(game)))
        }
        return result
    }

    @Test
    fun `what the bank holds is taken and the rest is bought, needed and recommended alike`() {
        val game = FakeGame()
        game.playerTile = Tile(3164, 3487, 0)
        game.bank[995] = 50_000
        game.bank[1985] = 2
        game.questItemList = listOf(
            QuestItem("Cheese", listOf(1985), 3, false),
            QuestItem("Law rune", listOf(563), 5, true),
            QuestItem("Spade", listOf(952), 1, false),
        )
        game.inv[952] = 1
        assertEquals(TaskResult.Done, run(game, GrandExchangeTask("witchshouse")))
        assertEquals(3, game.inv[1985])
        assertEquals(5, game.inv[563])
        assertTrue(game.calls.contains("buy offer Cheese x1"), game.calls.toString())
        assertTrue(game.calls.contains("buy offer Law rune x5"), game.calls.toString())
        assertTrue(game.calls.none { it.startsWith("buy offer Spade") }, game.calls.toString())
    }

    @Test
    fun `nothing is short means nothing is bought`() {
        val game = FakeGame()
        game.inv[1985] = 1
        game.questItemList = listOf(QuestItem("Cheese", listOf(1985), 1, false))
        assertEquals(TaskResult.Done, run(game, GrandExchangeTask("witchshouse")))
        assertTrue(game.calls.none { it.startsWith("buy offer") })
    }

    @Test
    fun `a list that cannot be read pauses with a reason`() {
        val game = FakeGame()
        game.questItemList = null
        val result = run(game, GrandExchangeTask("witchshouse"))
        assertTrue(result is TaskResult.Failed && result.reason.contains("could not read Quest Helper"), result.toString())
    }
}
