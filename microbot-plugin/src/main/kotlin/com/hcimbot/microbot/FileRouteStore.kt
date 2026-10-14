package com.hcimbot.microbot

import com.hcimbot.guide.Tile
import com.hcimbot.route.RouteFormat
import com.hcimbot.route.RouteStore
import java.io.File

/**
 * Recorded routes as text files in ~/.runelite/hcim-guide-bot/routes, one per
 * route name, e.g. draynor-fence.txt. They are plain "x,y,plane" lines, so a
 * route can be sent along, checked or edited by hand.
 */
class FileRouteStore(
    private val dir: File = File(System.getProperty("user.home"), ".runelite/hcim-guide-bot/routes"),
) : RouteStore {

    override fun load(name: String): List<Tile>? {
        val file = fileFor(name)
        if (!file.isFile) return null
        return RouteFormat.decode(file.readText()).takeIf { it.isNotEmpty() }
    }

    override fun save(name: String, tiles: List<Tile>) {
        dir.mkdirs()
        val file = fileFor(name)
        // Write then rename, so a crash mid-save never leaves half a route.
        val temp = File(dir, "${file.name}.tmp")
        temp.writeText("# Route \"$name\" recorded by the HCIM Guide Bot, ${tiles.size} tiles\n" + RouteFormat.encode(tiles))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    fun fileFor(name: String): File = File(dir, safeName(name) + ".txt")

    companion object {
        /** Route names become file names, so keep them to letters, digits, dashes and underscores. */
        fun safeName(name: String): String =
            name.trim().lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').ifEmpty { "route" }
    }
}
