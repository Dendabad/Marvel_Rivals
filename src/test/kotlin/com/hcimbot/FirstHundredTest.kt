package com.hcimbot

import com.hcimbot.engine.BotSettings
import com.hcimbot.guide.GuideLoader
import com.hcimbot.plan.StepPlan
import com.hcimbot.plan.StepPlanner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The first 100 steps of the real guide, each reviewed by hand. The expected
 * plans live in first-100-plans.txt as step id and plan shape only, so the
 * guide's own text stays out of this repo. Skipped unless run with
 * -PguideJson=/path/to/guide.json.
 *
 * Regenerate with -PwriteFirstHundred=true after reviewing a deliberate change.
 */
class FirstHundredTest {
    @Test
    fun `first 100 steps plan as reviewed`() {
        val path = System.getProperty("guideJson").orEmpty()
        if (path.isBlank()) return
        val guide = GuideLoader().loadFromFile(File(path))
        val planner = StepPlanner(guide, BotSettings())
        val actual = guide.steps.take(100).mapIndexed { i, step ->
            "%03d %s %s".format(i + 1, step.id, shape(planner.plan(step)))
        }

        val expectedFile = File("src/test/resources/first-100-plans.txt")
        if (System.getProperty("writeFirstHundred") == "true") {
            expectedFile.writeText(actual.joinToString("\n", postfix = "\n"))
            return
        }
        val expected = expectedFile.readLines().filter { it.isNotBlank() }
        val changed = expected.zip(actual).filter { (e, a) -> e != a }
        assertEquals(emptyList(), changed.map { (e, a) -> "expected $e\n     got $a" }, "plans changed")
        assertEquals(expected.size, actual.size)
    }

    private fun shape(plan: StepPlan): String = when (plan) {
        is StepPlan.Manual -> "PAUSE"
        is StepPlan.Auto -> buildString {
            append("AUTO")
            if (plan.trustTasks) append("+trust")
            if (plan.confirm) append("+confirm")
            append(' ')
            append(plan.tasks.joinToString(">") { it::class.simpleName ?: "?" })
        }
    }
}
