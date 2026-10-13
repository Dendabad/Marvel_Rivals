package com.hcimbot.engine

import com.hcimbot.game.GameActions
import com.hcimbot.game.GameState

data class BotSettings(
    /** Combat steps pause for the player unless this is on. */
    val allowCombat: Boolean = false,
    /** Show the guide's tips and warnings and wait, instead of skipping them. */
    val pauseOnAdvice: Boolean = true,
    /** Eat at or below this share of max hitpoints. */
    val eatAtPercent: Int = 60,
    /** Stop the bot outright at or below this share of max hitpoints. */
    val stopAtPercent: Int = 40,
    /** Food item ids to eat, best first. Empty means never eat, only stop. */
    val foodIds: List<Int> = emptyList(),
    /** Pause when something attacks the player during a step that is not a combat step. */
    val pauseWhenAttacked: Boolean = true,
    /** Pause if one step takes longer than this many ticks (0.6s each). */
    val stepTimeoutTicks: Int = 1000,
)

/**
 * Hardcore checks run before anything else on every tick. On a hardcore
 * account a wrong guess costs the account status, so every rule here prefers
 * stopping over carrying on.
 */
class SafetyMonitor(private val settings: BotSettings) {

    sealed interface Verdict {
        object Safe : Verdict

        /** An action was taken to stay safe this tick, such as eating. Skip the step's own action. */
        object Handled : Verdict
        data class Pause(val reason: String) : Verdict
        data class Stop(val reason: String) : Verdict
    }

    fun check(game: GameState, actions: GameActions, combatStep: Boolean): Verdict {
        val max = game.maxHitpoints
        if (max <= 0) return Verdict.Safe
        val percent = game.hitpoints * 100 / max

        if (percent <= settings.stopAtPercent) {
            return Verdict.Stop("hitpoints are at ${game.hitpoints}/$max. Stopped to keep the account safe.")
        }
        if (percent <= settings.eatAtPercent && settings.foodIds.isNotEmpty()) {
            val inventory = game.inventory()
            val food = settings.foodIds.firstOrNull { (inventory[it] ?: 0) > 0 }
                ?: return Verdict.Stop("hitpoints are at ${game.hitpoints}/$max and there is no food left")
            if (!game.isBusy) actions.itemAction(food, "Eat")
            return Verdict.Handled
        }
        if (settings.pauseWhenAttacked && game.isInCombat && !combatStep) {
            return Verdict.Pause("something is attacking the player during a non-combat step")
        }
        return Verdict.Safe
    }
}
