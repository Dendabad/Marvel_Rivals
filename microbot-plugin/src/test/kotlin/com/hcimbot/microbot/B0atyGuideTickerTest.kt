package com.hcimbot.microbot

import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

/** Shaped like the B0aty plugin: a private guide field, sections of steps, and a private click handler. */
@Suppress("unused")
class FakeB0atyPlugin {
    class Step(private val id: String) {
        fun getId() = id
    }

    class Section(private val steps: List<Step>) {
        fun getSteps() = steps
    }

    class Guide(private val sections: List<Section>) {
        fun getSections() = sections
    }

    private val guide = Guide(listOf(Section(listOf(Step("a"))), Section(listOf(Step("b"), Step("c")))))
    val toggled = mutableListOf<String>()

    private fun onStepToggled(step: Step, complete: Boolean) {
        toggled += "${step.getId()} $complete"
    }
}

class B0atyGuideTickerTest {
    @Test
    fun `ticks and unticks through the plugin's own click handler`() {
        val plugin = FakeB0atyPlugin()
        val ticker = B0atyGuideTicker({ true }, { plugin })
        ticker.set("c", true)
        ticker.set("a", false)
        SwingUtilities.invokeAndWait {}
        assertEquals(listOf("c true", "a false"), plugin.toggled)
    }

    @Test
    fun `does nothing when switched off`() {
        val plugin = FakeB0atyPlugin()
        B0atyGuideTicker({ false }, { plugin }).set("c", true)
        SwingUtilities.invokeAndWait {}
        assertEquals(emptyList(), plugin.toggled)
    }
}
