package com.hcimbot

import com.hcimbot.engine.BotSettings
import com.hcimbot.guide.GuideLoader
import com.hcimbot.plan.StepPlan
import com.hcimbot.plan.StepPlanner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Loads the B0aty plugin's real guide.json and plans every step, then prints
 * how much of the guide the bot can do on its own. Skipped unless run with
 * -PguideJson=/path/to/guide.json, since the guide is not part of this repo.
 */
class RealGuideTest {
    @Test
    fun `every step of the real guide can be planned`() {
        val path = System.getProperty("guideJson").orEmpty()
        if (path.isBlank()) return
        val guide = GuideLoader().loadFromFile(File(path))
        val planner = StepPlanner(guide, BotSettings())

        val plans = guide.steps.map { it to planner.plan(it) }
        val auto = plans.filter { it.second is StepPlan.Auto }
        val confirm = auto.count { (it.second as StepPlan.Auto).confirm }
        val manual = plans.size - auto.size

        println("Steps: ${plans.size}")
        println("Automated: ${auto.size - confirm}")
        println("Automated, then pause to confirm: $confirm")
        println("Manual (bot pauses): $manual")
        auto.groupingBy { (it.second as StepPlan.Auto).tasks.first()::class.simpleName }.eachCount()
            .entries.sortedByDescending { it.value }
            .forEach { println("  ${it.key}: ${it.value}") }
        assertTrue(auto.isNotEmpty())
    }
}
