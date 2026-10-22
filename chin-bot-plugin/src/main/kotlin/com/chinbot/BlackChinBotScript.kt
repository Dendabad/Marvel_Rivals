package com.chinbot

import com.hcimbot.game.SceneEntity
import com.hcimbot.guide.Tile
import com.hcimbot.microbot.MicrobotActions
import com.hcimbot.microbot.MicrobotGameState
import java.awt.Color
import java.util.concurrent.TimeUnit
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.Script
import org.slf4j.LoggerFactory

/**
 * Traps black chinchompas with box traps around the spot where the bot is started. Stand where the chinchompas spawn (south east of the
 * Lava Maze, level 32 to 36 Wilderness), have box traps in the bag, and turn the plugin on. Each tick: look after the traps (check one
 * that has caught something, reset one that failed), lay more until [BlackChinBotConfig.traps] are out, and watch for danger: another
 * player near means a world hop, low health means the bot stops.
 *
 * The Wilderness is dangerous: anyone can attack here, and what is carried can be lost. Carry only the traps and what the hunt needs.
 */
class BlackChinBotScript : Script() {
    private val log = LoggerFactory.getLogger(BlackChinBotScript::class.java)
    private val game = MicrobotGameState()
    private val actions = MicrobotActions()
    private var config: BlackChinBotConfig? = null

    @Volatile private var paused = false
    @Volatile private var state = "Starting"
    @Volatile private var stateColor = IDLE
    @Volatile private var detail: String? = null
    private var anchor: Tile? = null
    private var waitUntil = 0L
    private var lastHop = 0L
    private var caught = 0
    private var hops = 0
    private val recent = ArrayDeque<String>()
    private val clock = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")

    fun run(config: BlackChinBotConfig): Boolean {
        this.config = config
        paused = config.paused()
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay({ tick() }, 0, TICK_MS, TimeUnit.MILLISECONDS)
        return true
    }

    fun setPaused(value: Boolean) {
        paused = value
        note(if (value) "Paused by you" else "Resumed by you")
    }

    fun status(): List<Pair<String, Color>> = buildList {
        add(state to stateColor)
        add("Caught: $caught, hops: $hops" to Color.LIGHT_GRAY)
        detail?.let { add(it to stateColor) }
        synchronized(recent) { recent.take(RECENT_SHOWN) }.forEach { add(it to Color.GRAY) }
    }

    private fun note(line: String) {
        log.info("Black chin bot: {}", line)
        synchronized(recent) {
            recent.addFirst("${java.time.LocalTime.now().format(clock)}  $line")
            while (recent.size > RECENT_KEPT) recent.removeLast()
        }
    }

    private fun gate(): Boolean = super.run()

    private fun show(newState: String, color: Color, why: String? = null) {
        state = newState
        stateColor = color
        detail = why
    }

    private fun tick() {
        try {
            if (!gate() || !Microbot.isLoggedIn()) return
            val cfg = config ?: return
            if (paused) { show("PAUSED", PAUSED_COLOR, detail ?: "Untick Pause to carry on."); return }
            game.refresh()
            val here = game.playerTile ?: return
            val home = anchor ?: here.also { anchor = it; note("Hunting around $it") }

            if (lowHealth(cfg)) {
                paused = true
                show("STOPPED", STOPPED_COLOR, "Health is under ${cfg.minHealthPercent()}%. Get out, then untick Pause.")
                note("Stopped on low health")
                return
            }
            if (cfg.hopOnPlayers() && playerNear(cfg)) {
                hop()
                return
            }
            if (System.currentTimeMillis() < waitUntil || game.isBusy) { show("Running", RUNNING_COLOR); return }

            val traps = trapsNear(home, cfg.radius() + 3)
            // A trap that caught something is checked first, then one that failed is reset. Both clicks do nothing for a trap that is just set.
            traps.firstOrNull { it.name.equals("Shaking box", ignoreCase = true) }?.let { trap ->
                if (actions.interactObject(trap, "Check")) {
                    caught++
                    note("Checked a trap at ${trap.tile}")
                    pause(2400)
                    return
                }
            }
            traps.firstOrNull { it.name.equals("Box trap", ignoreCase = true) }?.let { trap ->
                if (!tried(trap) && actions.interactObject(trap, "Reset")) {
                    note("Reset a trap at ${trap.tile}")
                    pause(2400)
                    return
                }
            }
            val out = traps.size
            if (out < cfg.traps()) {
                if (layOne(home, cfg, traps)) return
            }
            show("Running", RUNNING_COLOR)
        } catch (e: Exception) {
            if (e is InterruptedException || e.cause is InterruptedException) return
            log.error("Black chin bot tick failed", e)
            note("Error: ${e.message}")
            paused = true
            show("PAUSED", PAUSED_COLOR, "Unexpected error: ${e.message}. Check the client log.")
        }
    }

    private val resetTried = HashMap<Tile, Long>()

    /** A set trap has no Reset option, so clicking it fails each time: not tried again for a while. */
    private fun tried(trap: SceneEntity): Boolean {
        val now = System.currentTimeMillis()
        val last = resetTried[trap.tile]
        if (last != null && now - last < RESET_RETRY_MS) return true
        resetTried[trap.tile] = now
        return false
    }

    private fun pause(ms: Long) { waitUntil = System.currentTimeMillis() + ms }

    private fun trapsNear(home: Tile, within: Int): List<SceneEntity> =
        game.objectsNear(listOf("Box trap", "Shaking box"), home, within)

    /** Walks to a free tile in the radius and lays a trap there. False if there is nothing to lay (no box traps in the bag). */
    private fun layOne(home: Tile, cfg: BlackChinBotConfig, traps: List<SceneEntity>): Boolean {
        if (game.inventory().none { (id, _) -> id == BOX_TRAP }) {
            show("Out of traps", STOPPED_COLOR, "No box traps in the bag.")
            return false
        }
        val at = game.playerTile ?: return false
        val used = traps.map { it.tile }.toSet()
        // The trap goes on the tile stood on, so a tile with none of its own is walked to first.
        if (at in used || at.distanceTo(home) > cfg.radius()) {
            val free = (-cfg.radius()..cfg.radius()).flatMap { dx -> (-cfg.radius()..cfg.radius()).map { dy -> Tile(home.x + dx, home.y + dy, home.plane) } }
                .filter { it !in used && game.canReach(it) != false }
                .shuffled()
                .firstOrNull() ?: return false
            actions.walkTo(free)
            pause(1200)
            return true
        }
        if (actions.itemActionByName("Box trap", "Lay")) {
            note("Laid a trap at $at")
            pause(3000)
            return true
        }
        return false
    }

    private fun lowHealth(cfg: BlackChinBotConfig): Boolean {
        val max = game.skillLevel("HITPOINTS")
        return max > 0 && game.hitpoints * 100 < max * cfg.minHealthPercent()
    }

    /** Another player within [BlackChinBotConfig.playerRange] tiles, whoever they are. */
    private fun playerNear(cfg: BlackChinBotConfig): Boolean = runCatching {
        val client = Microbot.getClient()
        val me = client.localPlayer
        client.players.any { p -> p != null && p !== me && p.worldLocation.distanceTo(me.worldLocation) <= cfg.playerRange() }
    }.getOrDefault(false)

    private fun hop() {
        val now = System.currentTimeMillis()
        if (now - lastHop < HOP_GAP_MS) return
        lastHop = now
        hops++
        note("A player is near, hopping worlds")
        show("Hopping", PAUSED_COLOR, "A player came near.")
        actions.hopWorld()
        // The traps stay behind with the world: the next one starts from where the player is.
        waitUntil = now + HOP_WAIT_MS
    }

    override fun shutdown() {
        super.shutdown()
    }

    private companion object {
        const val TICK_MS = 600L
        const val BOX_TRAP = 10008
        const val RESET_RETRY_MS = 20_000L
        const val HOP_GAP_MS = 15_000L
        const val HOP_WAIT_MS = 8_000L
        const val RECENT_KEPT = 12
        const val RECENT_SHOWN = 4
        val RUNNING_COLOR = Color(0x4C, 0xAF, 0x50)
        val PAUSED_COLOR = Color(0xFF, 0xB3, 0x00)
        val STOPPED_COLOR = Color(0xE5, 0x39, 0x35)
        val IDLE = Color(0xB0, 0xB0, 0xB0)
    }
}
