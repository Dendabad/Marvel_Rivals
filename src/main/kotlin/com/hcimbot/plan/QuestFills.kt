package com.hcimbot.plan

import com.hcimbot.guide.Instruction

/**
 * Quest Helper steps that guide.json leaves out, copied from Quest Helper's own
 * source. Its pipeline drops dig steps, so X Marks the Spot has no data for the
 * four digs between talking to Veos in Lumbridge and in Port Sarim.
 */
object QuestFills {
    private val fills: Map<String, Map<Int, Instruction>> = mapOf(
        "xmarksthespot" to mapOf(
            2 to dig(3230, 3209, "Dig outside Bob's Brilliant Axes in Lumbridge."),
            3 to dig(3203, 3212, "Dig behind Lumbridge castle."),
            4 to dig(3109, 3264, "Dig north of Draynor Village."),
            5 to dig(3078, 3259, "Dig in the pig pen just west of Martin the Master Gardener."),
        ),
    )

    fun at(quest: String, value: Int): Instruction? = fills[quest]?.get(value)

    /**
     * Chat options a quest now has that Quest Helper's data does not list, added to what each of its
     * instructions says to pick. Dr Harlow's first question was reworded, from "Morgan needs your help!".
     */
    private val extraDialogue: Map<String, List<String>> = mapOf(
        "vampyreslayer" to listOf(
            "I need your help dealing with a vampyre",
            // Dr Harlow asking for a beer: the agreeing answer, never "No, you've had enough".
            "Yes, here you go", "get you a beer", "buy you a beer", "got you a beer", "have a beer", "here's a beer", "here is a beer",
        ),
    )

    fun extraDialogue(quest: String): List<String> = extraDialogue[quest].orEmpty()

    private fun dig(x: Int, y: Int, text: String) = Instruction(kind = "dig", point = listOf(x, y, 0), text = text)
}
