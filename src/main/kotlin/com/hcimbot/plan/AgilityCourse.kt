package com.hcimbot.plan

import com.hcimbot.guide.Tile

/**
 * A rooftop agility course, as Quest Helper's skill guide lays it out: the
 * obstacles in order, each with the rooftop area the player stands in when it
 * is the next one. On the ground, the first obstacle is next.
 */
class AgilityCourse(val name: String, val obstacles: List<Obstacle>) {
    class Obstacle(val name: String, val objectId: Int, val tile: Tile, val from: Area?)

    /** A box of tiles, on any plane from [minPlane] to [maxPlane]. */
    class Area(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int, val minPlane: Int, val maxPlane: Int) {
        operator fun contains(t: Tile) = t.x in minX..maxX && t.y in minY..maxY && t.plane in minPlane..maxPlane

        companion object {
            fun of(a: Tile, b: Tile) = Area(
                minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y),
                minOf(a.plane, b.plane), maxOf(a.plane, b.plane),
            )
        }
    }

    val start: Obstacle get() = obstacles.first()

    /** Every rooftop area, with a few tiles to spare for the landing spots between them. */
    val rooftops: Area by lazy {
        val areas = obstacles.mapNotNull { it.from }
        Area(
            areas.minOf { it.minX } - MARGIN, areas.minOf { it.minY } - MARGIN,
            areas.maxOf { it.maxX } + MARGIN, areas.maxOf { it.maxY } + MARGIN,
            areas.minOf { it.minPlane }, areas.maxOf { it.maxPlane },
        )
    }

    /**
     * The obstacle to take from [at]. Null only when on the course's rooftops
     * between obstacles (mid-jump). Anywhere else, upstairs in a house nearby
     * included, the start is next.
     */
    fun next(at: Tile): Obstacle? {
        obstacles.firstOrNull { it.from != null && at in it.from }?.let { return it }
        return if (at in rooftops) null else start
    }

    companion object {
        private const val MARGIN = 3

        /** The course whose rooftops the player is on, if any. Off the ground only. */
        fun rooftopsAt(at: Tile?): AgilityCourse? = at?.takeIf { it.plane > 0 }?.let { tile -> ALL.firstOrNull { tile in it.rooftops } }

        val ALL: List<AgilityCourse> by lazy { listOf(DRAYNOR) }

        /** Quest Helper's DraynorVillage: object ids from RuneLite's gameval ObjectID. */
        val DRAYNOR = AgilityCourse(
            "Draynor Village Rooftop Course",
            listOf(
                Obstacle("Rough wall", 11404, Tile(3103, 3279, 0), null),
                Obstacle("Tightrope", 11405, Tile(3098, 3277, 3), Area.of(Tile(3094, 3277, 3), Tile(3102, 3281, 3))),
                Obstacle("Tightrope", 11406, Tile(3092, 3276, 3), Area.of(Tile(3087, 3272, 3), Tile(3093, 3278, 2))),
                Obstacle("Narrow wall", 11430, Tile(3089, 3264, 3), Area.of(Tile(3089, 3262, 3), Tile(3094, 3271, 3))),
                Obstacle("Wall", 11630, Tile(3088, 3256, 3), Area.of(Tile(3087, 3256, 3), Tile(3088, 3262, 3))),
                Obstacle("Gap", 11631, Tile(3095, 3255, 3), Area.of(Tile(3087, 3252, 3), Tile(3095, 3255, 3))),
                Obstacle("Crate", 11632, Tile(3102, 3261, 3), Area.of(Tile(3095, 3256, 3), Tile(3101, 3261, 3))),
            ),
        )
    }
}

/**
 * Runs laps of [course] until the step's goal (a level, a diary task) is met,
 * which the runner checks every tick. Picks up marks of grace on the way, and
 * after a fall walks back to the start. Gives up after [maxLaps].
 */
class AgilityCourseTask(private val course: AgilityCourse, private val maxLaps: Int = 40) : BaseTask() {
    private var laps = 0
    private var lastObstacle: AgilityCourse.Obstacle? = null
    private var waited = 0
    private var sinceClick = 0
    private var startTries = 0
    private var clickedWhileBusy = false
    private var sinceMark = MARK_TICKS
    private var walk: WalkTask? = null
    override val description = "Run laps of the ${course.name}"

    /** Only on the ground: a lap is finished first, never left on a rooftop. */
    override fun canStopHere(ctx: TaskContext): Boolean = ctx.game.playerTile?.plane?.let { it == 0 } ?: true

    override fun tick(ctx: TaskContext): TaskResult {
        val game = ctx.game
        sinceClick++
        sinceMark++
        val at = game.playerTile ?: return TaskResult.Running
        val next = course.next(at)
        // Just landed by the next obstacle: click it now, as a player would. The game
        // runs the click as soon as the landing animation ends, so there is no pause.
        val landed = next != null && next !== lastObstacle
        // A click made mid-animation can be dropped. Once idle on the same obstacle, click again.
        val dropped = next != null && next === lastObstacle && clickedWhileBusy && !game.isBusy
        if (game.isBusy && !landed) return TaskResult.Running

        // A mark of grace on the same roof is worth the few steps.
        if (at.plane > 0 && sinceMark >= MARK_TICKS) {
            game.nearestGroundItem(listOf(MARK_OF_GRACE))?.takeIf { it.tile.plane == at.plane && it.tile.distanceTo(at) <= 6 }?.let {
                ctx.log("Picking up a Mark of grace")
                sinceMark = 0
                lastObstacle = null
                return attempt(ctx.actions.takeGroundItem(it), "pick up the Mark of grace")
            }
        }

        if (at.plane > 0) startTries = 0
        if (next == null) {
            // Landing from a jump: give it a moment before deciding where we are.
            if (++waited < SETTLE_TICKS) return TaskResult.Running
            return TaskResult.Failed("on the ${course.name} but not near any obstacle, at $at")
        }
        waited = 0

        if (next === course.start) {
            if (lastObstacle === course.obstacles.last()) {
                laps++
                ctx.log("Finished lap $laps of the ${course.name}")
                if (laps >= maxLaps) return TaskResult.Failed("ran $laps laps and the step is still not done")
            } else if (lastObstacle != null && lastObstacle !== course.start) {
                ctx.log("Fell off at the ${lastObstacle!!.name}, going back to the start")
            }
            val wall = game.nearestObject(listOf(next.objectId), emptyList(), next.tile)
            if (wall == null || wall.tile.distanceTo(at) > NEAR) {
                val going = walk ?: WalkTask(next.tile, radius = 2).also { walk = it }
                return when (val walked = going.tick(ctx)) {
                    TaskResult.Done -> { walk = null; TaskResult.Running }
                    else -> walked
                }
            }
        }
        walk = null
        // Still on the obstacle just clicked: the run up to it may not have started yet.
        if (next === lastObstacle && sinceClick < RECLICK_TICKS && !dropped) return TaskResult.Running
        val obstacle = game.nearestObject(listOf(next.objectId), emptyList(), next.tile)
            ?: return TaskResult.Failed("cannot see the ${next.name} on the ${course.name}")
        if (next === course.start && ++startTries > START_TRIES) {
            return TaskResult.Failed("the ${next.name} does nothing when clicked. The course may need a higher Agility level")
        }
        lastObstacle = next
        sinceClick = 0
        clickedWhileBusy = game.isBusy
        return attempt(ctx.actions.interactObject(obstacle, null), "use the ${next.name}")
    }

    private companion object {
        const val MARK_OF_GRACE = 11849
        const val SETTLE_TICKS = 6
        const val RECLICK_TICKS = 6
        const val START_TRIES = 4
        const val NEAR = 12
        const val MARK_TICKS = 3
    }
}
