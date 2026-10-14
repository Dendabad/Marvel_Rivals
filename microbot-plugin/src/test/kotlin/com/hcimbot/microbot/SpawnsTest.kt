package com.hcimbot.microbot

import com.hcimbot.guide.Tile
import kotlin.test.Test
import kotlin.test.assertEquals

class SpawnsTest {
    @Test
    fun `reads Microbot's NPC location file`() {
        val json = """{"Giant rat": [
            {"location_name": "Lumbridge Swamp", "coords": [[3192, 3207, 0], [3194, 3203, 0]], "mapID": null},
            {"location_name": "Varrock", "coords": [[3292, 3377, 0]], "mapID": null}
        ], "Cow": []}"""
        val spawns = MicrobotGameState.parseSpawns(json.reader())
        assertEquals(listOf(Tile(3192, 3207, 0), Tile(3194, 3203, 0), Tile(3292, 3377, 0)), spawns["giant rat"])
        assertEquals(emptyList(), spawns["cow"])
    }
}
