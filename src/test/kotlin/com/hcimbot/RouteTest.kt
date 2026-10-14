package com.hcimbot

import com.hcimbot.engine.Conditions
import com.hcimbot.guide.Tile
import com.hcimbot.plan.FollowRouteTask
import com.hcimbot.plan.TaskContext
import com.hcimbot.plan.TaskResult
import com.hcimbot.route.RouteFormat
import com.hcimbot.route.RouteRecorder
import com.hcimbot.route.RouteStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RouteTest {
    private class MemoryRoutes : RouteStore {
        val saved = mutableMapOf<String, List<Tile>>()
        override fun load(name: String) = saved[name]
        override fun save(name: String, tiles: List<Tile>) {
            saved[name] = tiles
        }
    }

    /** A straight walk west along y = 3250, then a turn north, one tile at a time. */
    private val walked = (3150 downTo 3110).map { Tile(it, 3250, 0) } + (3251..3260).map { Tile(3110, it, 0) }

    @Test
    fun `recorder keeps each new tile once`() {
        val recorder = RouteRecorder()
        assertTrue(recorder.record(Tile(1, 1, 0)))
        assertTrue(!recorder.record(Tile(1, 1, 0)), "standing still adds nothing")
        assertTrue(recorder.record(Tile(2, 1, 0)))
        assertTrue(!recorder.record(null))
        assertEquals(listOf(Tile(1, 1, 0), Tile(2, 1, 0)), recorder.tiles())
    }

    @Test
    fun `waypoints thin the route but keep its ends and corners close`() {
        val points = RouteRecorder.waypoints(walked)
        assertEquals(walked.first(), points.first())
        assertEquals(walked.last(), points.last())
        assertTrue(points.size < walked.size / 2)
        assertTrue(points.zipWithNext().all { (a, b) -> a.distanceTo(b) <= 4 }, "legs stay short: $points")
    }

    @Test
    fun `floor changes are kept`() {
        val route = listOf(Tile(0, 0, 0), Tile(1, 0, 0), Tile(1, 0, 1), Tile(2, 0, 1))
        assertTrue(Tile(1, 0, 1) in RouteRecorder.waypoints(route))
    }

    @Test
    fun `route files read back what was written`() {
        val text = "# a comment\n" + RouteFormat.encode(walked) + "\nnot a tile\n"
        assertEquals(walked, RouteFormat.decode(text))
    }

    @Test
    fun `following walks every waypoint in order`() {
        val routes = MemoryRoutes().apply { saved["fence"] = walked }
        val game = FakeGame().apply { playerTile = walked.first() }
        val task = FollowRouteTask("fence", routes)
        val ctx = TaskContext(game, game, Conditions(game))
        var result: TaskResult = TaskResult.Running
        repeat(200) { if (result == TaskResult.Running) result = task.tick(ctx) }
        assertEquals(TaskResult.Done, result)
        val walks = game.calls.filter { it.startsWith("walk") }
        assertEquals(RouteRecorder.waypoints(walked).drop(1).map { "walk $it" }, walks)
        assertEquals(walked.last(), game.playerTile)
    }

    @Test
    fun `a missing route pauses with how to record it`() {
        val game = FakeGame()
        val result = FollowRouteTask("draynor-fence", MemoryRoutes()).tick(TaskContext(game, game, Conditions(game)))
        val failed = assertIs<TaskResult.Failed>(result)
        assertTrue("Record route" in failed.reason && "draynor-fence" in failed.reason, failed.reason)
    }

    @Test
    fun `it will not walk to a route from far away`() {
        val routes = MemoryRoutes().apply { saved["fence"] = walked }
        val game = FakeGame().apply { playerTile = Tile(3200, 3300, 0) }
        val result = FollowRouteTask("fence", routes).tick(TaskContext(game, game, Conditions(game)))
        assertIs<TaskResult.Failed>(result)
        assertTrue(game.calls.isEmpty())
    }
}
