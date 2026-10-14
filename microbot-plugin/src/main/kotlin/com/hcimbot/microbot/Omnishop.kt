package com.hcimbot.microbot

import com.hcimbot.microbot.MicrobotGameState.Companion.onClient
import net.runelite.api.MenuAction
import net.runelite.api.widgets.Widget
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry
import net.runelite.client.util.Text
import org.slf4j.LoggerFactory

/**
 * The newer shop window some NPCs use, like the Friendly Forester's Forestry
 * Shop: a list of items on the left and an info panel with buttons on the
 * right. Microbot's Rs2Shop only knows the classic shop window, so this finds
 * the item in the list and uses its Buy option, or selects it and presses the
 * info panel's Buy button. Widget ids are RuneLite's gameval InterfaceID.OmnishopMain.
 */
object Omnishop {
    private val log = LoggerFactory.getLogger(Omnishop::class.java)

    private const val ROOT = 0x0333_0000
    private const val LIST = 0x0333_0026
    private val INFO_BUTTONS = intArrayOf(0x0333_0017, 0x0333_001a, 0x0333_001b, 0x0333_001c, 0x0333_001d)
    private const val SELECT_WAIT_MS = 900L

    fun isOpen(): Boolean = onClient { Microbot.getClient().getWidget(ROOT)?.let { !it.isHidden } } == true

    /** A widget read on the client thread, so it can be clicked from the bot's own thread. */
    private class Seen(val index: Int, val id: Int, val itemId: Int, val name: String, val actions: List<String>, val bounds: java.awt.Rectangle) {
        constructor(w: Widget) : this(
            w.index, w.id, w.itemId, w.name.orEmpty(), w.actions?.map { it.orEmpty() }.orEmpty(), w.bounds,
        )
    }

    /** Buys one [itemId]. True when a buy was clicked. */
    fun buy(itemId: Int): Boolean {
        val item = onClient { findItem(itemId)?.let(::Seen) } ?: return false.also { log.info("Omnishop: item {} not in the list", itemId) }
        buyIndex(item.actions)?.let { index ->
            invoke(item, index)
            return true
        }
        // No Buy option on the item itself: select it, then press the info panel's Buy button.
        val select = item.actions.indexOfFirst { it.isNotBlank() }
        if (select >= 0) invoke(item, select) else Microbot.getMouse().click(item.bounds)
        Thread.sleep(SELECT_WAIT_MS)
        val button = onClient {
            INFO_BUTTONS.asSequence().mapNotNull { Microbot.getClient().getWidget(it) }.flatMap { descendants(it) }
                .firstOrNull { w ->
                    !w.isHidden && (buyIndex(w.actions?.toList().orEmpty()) != null || Text.removeTags(w.text.orEmpty()).contains("buy", true))
                }
                ?.let(::Seen)
        } ?: return false.also { log.info("Omnishop: no Buy option on {} ({}) and no Buy button", itemId, item.actions) }
        val index = buyIndex(button.actions)
        if (index != null) invoke(button, index) else Microbot.getMouse().click(button.bounds)
        return true
    }

    fun close(): Boolean {
        val closer = onClient {
            Microbot.getClient().getWidget(ROOT)?.let(::descendants)
                ?.firstOrNull { w -> !w.isHidden && w.actions?.any { it.equals("Close", true) } == true }
                ?.let(::Seen)
        } ?: return false
        invoke(closer, closer.actions.indexOfFirst { it.equals("Close", true) })
        return true
    }

    /** What the item offers, for the pause message when buying does not work. */
    fun describe(itemId: Int): String? = onClient {
        findItem(itemId)?.let { "${Text.removeTags(it.name.orEmpty())} offers ${it.actions?.filterNotNull()?.filter(String::isNotBlank)}" }
    }

    private fun findItem(itemId: Int): Widget? {
        val client = Microbot.getClient()
        val name = client.getItemDefinition(itemId)?.name
        return client.getWidget(LIST)?.let(::descendants)?.firstOrNull { w ->
            !w.isHidden && (w.itemId == itemId || (name != null && Text.removeTags(w.name.orEmpty()).equals(name, true)))
        }
    }

    /** "Buy 1" first, then "Buy", then any other buy option. */
    private fun buyIndex(actions: List<String?>): Int? {
        val clean = actions.map { it?.let(Text::removeTags)?.trim().orEmpty() }
        return clean.indexOfFirst { Regex("^buy[- ]?1$", RegexOption.IGNORE_CASE).matches(it) }.takeIf { it >= 0 }
            ?: clean.indexOfFirst { it.equals("buy", true) }.takeIf { it >= 0 }
            ?: clean.indexOfFirst { it.startsWith("buy", true) }.takeIf { it >= 0 }
    }

    private fun invoke(widget: Seen, index: Int) {
        val identifier = index + 1
        val opcode = if (identifier > 5) MenuAction.CC_OP_LOW_PRIORITY.id else MenuAction.CC_OP.id
        Microbot.doInvoke(
            NewMenuEntry(widget.actions[index], widget.index, widget.id, opcode, identifier, widget.itemId, widget.name),
            widget.bounds,
        )
    }

    private fun descendants(widget: Widget): Sequence<Widget> = sequence {
        yield(widget)
        for (group in listOf(widget.dynamicChildren, widget.staticChildren, widget.nestedChildren)) {
            group?.filterNotNull()?.forEach { yieldAll(descendants(it)) }
        }
    }
}
