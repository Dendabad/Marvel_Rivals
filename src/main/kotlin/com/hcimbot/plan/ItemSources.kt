package com.hcimbot.plan

/**
 * Where the guide expects some items to have come from, for when a later step
 * needs one the player does not have. If the quest that hands it over has not
 * started, the guide step that starts it was skipped or ticked without being
 * done, and the bot can go back and do it.
 */
object ItemSources {
    class Source(val stepId: String, val why: String)

    private class Given(
        val itemIds: Set<Int>,
        val varKind: String,
        val varId: Int,
        val stepId: String,
        val why: String,
        /** Not yet given while the quest's variable is below this. 1 means the quest has not started. */
        val belowValue: Int = 1,
    )

    /** Items a withdraw step lists that the guide gives later (the Research Package), so a bank without them is no reason to stop. */
    val LATER = setOf(290, 291)

    private val given = listOf(
        // 7. "Head East & Start X Marks the Spot on Veos": Veos hands over the scroll.
        Given(setOf(23067, 23068, 23069, 23070, 23071), "varbit", 8063, "e2deed373e", "Veos gives it when X Marks the Spot starts (step 7)"),
        // 9. "Go upstairs and Talk to Duke Horacio": the Duke hands over the talisman.
        Given(setOf(1438), "varplayer", 63, "d51fed8c25", "Duke Horacio gives it when Rune Mysteries starts (step 9)"),
        // 26. "Talk to Sedridor to continue Rune Mysteries (1,2,1)": Sedridor hands over the Research Package
        // (Rune Mysteries is at 3 once he has), and step 30 banks it for step 94.
        Given(setOf(290), "varplayer", 63, "666fe06edc", "Sedridor gives it in step 26, which was not done", belowValue = 3),
    )

    /**
     * Quest items that turn into the next one as the quest goes on. Digging with
     * X Marks the Spot's treasure scroll swaps it for a mysterious orb, then a
     * new scroll, then the casket, so "withdraw the Treasure Scroll" means
     * whichever of them the player has now.
     */
    private val families = listOf(
        setOf(23067, 23068, 23069, 23070, 23071),
    )

    /** [item] with every id that stands in for it at a later point of its quest. */
    fun widen(item: com.hcimbot.guide.ItemRef): com.hcimbot.guide.ItemRef {
        val family = families.firstOrNull { family -> item.ids.any(family::contains) } ?: return item
        return com.hcimbot.guide.ItemRef(item.name, (item.ids + family).distinct(), item.count)
    }

    /** The step to go back to, when [itemIds] come from a quest that has not started yet. */
    fun whenNotYetGiven(itemIds: Collection<Int>, ctx: TaskContext): Source? {
        val source = given.firstOrNull { it.itemIds.any(itemIds::contains) } ?: return null
        if (ctx.conditions.readVar(source.varKind, source.varId) >= source.belowValue) return null
        return Source(source.stepId, source.why)
    }
}
