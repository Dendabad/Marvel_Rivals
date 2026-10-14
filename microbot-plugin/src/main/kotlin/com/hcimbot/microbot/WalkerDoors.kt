package com.hcimbot.microbot

import org.slf4j.LoggerFactory

/**
 * Microbot's walker remembers door attempts in a ledger: a short cooldown per
 * door, and strikes for crossings that failed, which take 5 minutes to wear off
 * and after 3 make it give up on the door. Eating or drinking mid-walk cuts a
 * crossing short, which it counts as a failure, so it then waits a long time
 * before trying that door again. [forget] clears that memory, so the next walk
 * clicks the door again at once.
 *
 * The ledger is internal to the walker, so it is reached by reflection. If
 * Microbot changes it, this does nothing and the walker behaves as before.
 */
object WalkerDoors {
    private val log = LoggerFactory.getLogger(WalkerDoors::class.java)
    private const val WALKER = "net.runelite.client.plugins.microbot.util.walker.Rs2Walker"
    @Volatile private var broken = false

    fun forget() {
        if (broken) return
        try {
            val ledger = Class.forName(WALKER).getDeclaredField("doorAttemptLedger").apply { isAccessible = true }.get(null) ?: return
            for (field in ledger.javaClass.declaredFields) {
                field.isAccessible = true
                when (val value = field.get(ledger)) {
                    is MutableMap<*, *> -> value.clear()
                    is MutableCollection<*> -> value.clear()
                }
            }
            runCatching { ledger.javaClass.getMethod("clearLatestAttempt").invoke(ledger) }
        } catch (e: Exception) {
            broken = true
            log.warn("Could not reset the walker's door memory", e)
        }
    }
}
