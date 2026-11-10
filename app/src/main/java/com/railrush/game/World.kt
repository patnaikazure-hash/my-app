package com.railrush.game

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.random.Random

/** Distance between lane centres, in world units. */
const val LANE_W = 2.2f

fun laneX(lane: Int): Float = (lane - 1) * LANE_W

enum class Kind {
    LOW_BARRIER, HIGH_BARRIER, TRAIN, COIN, MAGNET, SHIELD, DOUBLER;

    val isObstacle: Boolean get() = this == LOW_BARRIER || this == HIGH_BARRIER || this == TRAIN
}

enum class GameState { MENU, PLAYING, PAUSED, CRASHED, GAME_OVER }

enum class GameEvent { COIN, JUMP, ROLL, LANE, STUMBLE, CRASH, POWER_UP, SHIELD_BREAK }

/**
 * Anything placed on the track. [s] is the distance along the track of its front edge,
 * [length] how far it extends away from the player.
 */
class Entity(
    val kind: Kind,
    val lane: Int,
    var s: Float,
    val length: Float,
    var y: Float = 0f,
    val variant: Int = 0,
    val front: Boolean = true,
    val group: Int = 0,
) {
    var x: Float = laneX(lane)
    var alive = true

    val halfW: Float
        get() = when (kind) {
            Kind.TRAIN -> 1.05f
            Kind.LOW_BARRIER, Kind.HIGH_BARRIER -> 1.0f
            else -> 0.45f
        }
    val bottom: Float
        get() = when (kind) {
            Kind.HIGH_BARRIER -> 1.15f
            Kind.LOW_BARRIER, Kind.TRAIN -> 0f
            else -> y - 0.45f
        }
    val top: Float
        get() = when (kind) {
            Kind.LOW_BARRIER -> 1.0f
            Kind.HIGH_BARRIER -> 2.6f
            Kind.TRAIN -> 2.9f
            else -> y + 0.45f
        }
}

class Player {
    var lane = 1
    var x = 0f
    var y = 0f
    var vy = 0f
    var rollTime = 0f
    var queuedRoll = false
    var queuedJump = false
    var runPhase = 0f

    val airborne: Boolean get() = y > 0f || vy > 0f
    val rolling: Boolean get() = rollTime > 0f
    val height: Float get() = if (rolling) 0.9f else 1.8f

    fun reset() {
        lane = 1; x = 0f; y = 0f; vy = 0f
        rollTime = 0f; queuedRoll = false; queuedJump = false; runPhase = 0f
    }
}

/** All game rules: movement, physics, collisions, scoring and procedural track generation. */
class World(seed: Long = System.nanoTime()) {

    companion object {
        const val BASE_SPEED = 15f
        const val MAX_SPEED = 34f
        const val ACCEL = 0.2f
        const val GRAVITY = 28f
        const val JUMP_V = 9.6f
        const val DROP_V = 22f
        const val ROLL_TIME = 0.6f
        const val LATERAL_SPEED = 24f
        const val SPAWN_AHEAD = 170f
        const val P_HALF_W = 0.35f
        const val P_HALF_LEN = 0.3f
        const val POWER_TIME = 10f
        const val SHIELD_TIME = 15f
        const val STUMBLE_WINDOW = 3.5f
        const val BOUNCE_TIME = 0.35f
        const val CAR_LEN = 11f
        const val CAR_GAP = 0.8f
        const val FIRST_ROW = 60f
    }

    private val rnd = Random(seed)
    val player = Player()
    val entities = ArrayList<Entity>()

    var state = GameState.MENU
    var dist = 0f
    var speed = BASE_SPEED
    var time = 0f
    var animTime = 0f
    var score = 0f
    var coins = 0
    var magnetT = 0f
    var shieldT = 0f
    var doublerT = 0f
    var stumbleT = 0f
    var crashT = 0f
    var autoSpawn = true
    var onEvent: ((GameEvent) -> Unit)? = null

    private var bounceT = 0f
    private val busyUntil = FloatArray(3)
    private var nextRowS = 0f
    private var groupCounter = 0

    val multiplier: Int get() = if (doublerT > 0f) 2 else 1
    val difficulty: Float get() = ((speed - BASE_SPEED) / (MAX_SPEED - BASE_SPEED)).coerceIn(0f, 1f)

    init {
        reset()
    }

    fun reset() {
        entities.clear()
        player.reset()
        dist = 0f; speed = BASE_SPEED; time = 0f
        score = 0f; coins = 0
        magnetT = 0f; shieldT = 0f; doublerT = 0f
        stumbleT = 0f; bounceT = 0f; crashT = 0f
        busyUntil.fill(-1000f)
        nextRowS = FIRST_ROW
        groupCounter = 0
        if (autoSpawn) spawnAhead()
    }

    fun start() {
        reset()
        state = GameState.PLAYING
    }

    // ---------------------------------------------------------------- input

    fun swipeLeft() = changeLane(-1)
    fun swipeRight() = changeLane(1)

    private fun changeLane(d: Int) {
        if (state != GameState.PLAYING) return
        val target = (player.lane + d).coerceIn(0, 2)
        if (target != player.lane) {
            player.lane = target
            emit(GameEvent.LANE)
        }
    }

    fun swipeUp() {
        if (state != GameState.PLAYING) return
        val p = player
        if (!p.airborne) {
            jump()
        } else if (p.vy < 0f && p.y < 0.6f) {
            p.queuedJump = true // jump buffering: jump again as soon as we land
            p.queuedRoll = false
        }
    }

    fun swipeDown() {
        if (state != GameState.PLAYING) return
        val p = player
        p.queuedJump = false
        if (p.airborne) {
            p.vy = min(p.vy, -DROP_V)
            p.queuedRoll = true
        } else {
            p.rollTime = ROLL_TIME
            emit(GameEvent.ROLL)
        }
    }

    private fun jump() {
        val p = player
        p.vy = JUMP_V
        p.rollTime = 0f
        p.queuedRoll = false
        p.queuedJump = false
        emit(GameEvent.JUMP)
    }

    // ---------------------------------------------------------------- simulation

    fun update(dt: Float) {
        if (state != GameState.PAUSED) animTime += dt
        when (state) {
            GameState.MENU -> player.runPhase += dt * 4f
            GameState.PLAYING -> step(dt)
            GameState.CRASHED -> {
                crashT += dt
                if (crashT >= 1.0f) state = GameState.GAME_OVER
            }
            GameState.GAME_OVER -> crashT += dt
            GameState.PAUSED -> Unit
        }
    }

    private fun step(dt: Float) {
        time += dt
        speed = min(MAX_SPEED, BASE_SPEED + time * ACCEL)
        val prev = dist
        dist += speed * dt
        score += speed * dt * 0.5f * multiplier

        val p = player
        val tx = laneX(p.lane)
        val dx = tx - p.x
        val mv = LATERAL_SPEED * dt
        p.x = if (abs(dx) <= mv) tx else p.x + sign(dx) * mv

        if (p.airborne) {
            p.vy -= GRAVITY * dt
            p.y += p.vy * dt
            if (p.y <= 0f) {
                p.y = 0f
                p.vy = 0f
                land()
            }
        }
        if (p.rollTime > 0f) p.rollTime -= dt
        p.runPhase += dt * (6f + speed * 0.25f)

        magnetT = max(0f, magnetT - dt)
        doublerT = max(0f, doublerT - dt)
        shieldT = max(0f, shieldT - dt)
        stumbleT = max(0f, stumbleT - dt)
        bounceT = max(0f, bounceT - dt)

        collide(prev, dt)
        if (state != GameState.PLAYING) return

        if (autoSpawn) spawnAhead()
        entities.removeAll { !it.alive || it.s + it.length < dist - 10f }
    }

    private fun land() {
        val p = player
        if (p.queuedRoll) {
            p.queuedRoll = false
            p.rollTime = ROLL_TIME
            emit(GameEvent.ROLL)
        } else if (p.queuedJump) {
            jump()
        }
    }

    private fun collide(prev: Float, dt: Float) {
        val p = player
        for (e in entities) {
            if (!e.alive) continue
            if (e.kind.isObstacle) {
                // Swept test along the track so low frame rates can't tunnel through thin barriers.
                if (e.s > dist + P_HALF_LEN || e.s + e.length < prev - P_HALF_LEN) continue
                if (abs(p.x - e.x) >= e.halfW + P_HALF_W) continue
                if (p.y >= e.top || p.y + p.height <= e.bottom) continue
                val alreadyAlongside = prev + P_HALF_LEN > e.s + 0.15f
                if (alreadyAlongside) stumble(e) else hit(e)
                if (state != GameState.PLAYING) return
            } else {
                if (e.kind == Kind.COIN && magnetT > 0f) {
                    val ahead = e.s - dist
                    if (ahead > -1f && ahead < 22f) {
                        val a = min(1f, dt * 9f)
                        e.x += (p.x - e.x) * a
                        e.y += (p.y + 0.9f - e.y) * a
                        e.s += (dist - e.s) * min(1f, dt * 5f)
                    }
                }
                if (abs(e.s - dist) < 0.9f && abs(e.x - p.x) < 0.9f &&
                    e.y > p.y - 0.4f && e.y < p.y + p.height + 0.5f
                ) {
                    e.alive = false
                    collect(e)
                }
            }
        }
    }

    private fun collect(e: Entity) {
        when (e.kind) {
            Kind.COIN -> {
                coins++
                score += 10f * multiplier
                emit(GameEvent.COIN)
                return
            }
            Kind.MAGNET -> magnetT = POWER_TIME
            Kind.SHIELD -> shieldT = SHIELD_TIME
            Kind.DOUBLER -> doublerT = POWER_TIME
            else -> return
        }
        emit(GameEvent.POWER_UP)
    }

    /** Side bump: bounce back to the lane we came from. Two bumps in a short time = crash. */
    private fun stumble(e: Entity) {
        if (bounceT > 0f) return
        val dir = if (e.x > player.x) 1 else -1
        player.lane = (e.lane - dir).coerceIn(0, 2)
        bounceT = BOUNCE_TIME
        if (stumbleT > 0f) {
            if (shieldT > 0f) {
                shieldT = 0f
                stumbleT = STUMBLE_WINDOW
                emit(GameEvent.SHIELD_BREAK)
            } else {
                crash()
            }
        } else {
            stumbleT = STUMBLE_WINDOW
            emit(GameEvent.STUMBLE)
        }
    }

    /** Head-on collision. */
    private fun hit(e: Entity) {
        if (shieldT > 0f) {
            shieldT = 0f
            if (e.kind == Kind.TRAIN) {
                for (o in entities) if (o.kind == Kind.TRAIN && o.group == e.group) o.alive = false
            }
            e.alive = false
            emit(GameEvent.SHIELD_BREAK)
        } else {
            crash()
        }
    }

    private fun crash() {
        state = GameState.CRASHED
        crashT = 0f
        emit(GameEvent.CRASH)
    }

    private fun emit(ev: GameEvent) {
        onEvent?.invoke(ev)
    }

    // ---------------------------------------------------------------- track generation

    internal fun spawnAhead() {
        while (nextRowS < dist + SPAWN_AHEAD) {
            val gap = rowGap()
            spawnRow(nextRowS)
            nextRowS += gap
        }
    }

    private fun rowGap(): Float {
        val t = lerp(1.25f, 0.72f, difficulty) * (0.85f + rnd.nextFloat() * 0.4f)
        return max(14f, speed * t)
    }

    private fun spawnRow(s: Float) {
        val d = difficulty
        val safe = speed * 0.8f + 8f
        val prevBusy = busyUntil.copyOf()
        val kinds = arrayOfNulls<Kind>(3)
        val order = mutableListOf(0, 1, 2).also { it.shuffle(rnd) }

        for (l in order) {
            if (busyUntil[l] > s - 3f) continue // a train is still running in this lane
            val r = rnd.nextFloat()
            val trainP = 0.22f + 0.18f * d
            val lowP = trainP + 0.22f
            val highP = lowP + 0.16f + 0.1f * d
            when {
                r < trainP && canPlaceTrain(l, s, safe) -> {
                    placeTrain(l, s); kinds[l] = Kind.TRAIN
                }
                r < lowP -> {
                    entities.add(Entity(Kind.LOW_BARRIER, l, s, 0.4f)); kinds[l] = Kind.LOW_BARRIER
                }
                r < highP -> {
                    entities.add(Entity(Kind.HIGH_BARRIER, l, s, 0.4f)); kinds[l] = Kind.HIGH_BARRIER
                }
            }
        }

        // Coins
        if (rnd.nextFloat() < 0.65f) {
            val candidates = (0..2).filter { kinds[it] != Kind.TRAIN && prevBusy[it] < s - 14f }
            if (candidates.isNotEmpty()) {
                val c = candidates[rnd.nextInt(candidates.size)]
                when (kinds[c]) {
                    Kind.LOW_BARRIER -> for (i in -4..4) {
                        val ds = i * 1.5f
                        val arc = (1f - (ds / 6.5f) * (ds / 6.5f)).coerceAtLeast(0f)
                        entities.add(Entity(Kind.COIN, c, s + 0.2f + ds, 0.1f, y = 0.6f + 1.6f * arc))
                    }
                    Kind.HIGH_BARRIER -> for (i in -4..3) {
                        entities.add(Entity(Kind.COIN, c, s + i * 1.6f, 0.1f, y = 0.45f))
                    }
                    else -> {
                        val n = 5 + rnd.nextInt(5)
                        for (i in 0 until n) entities.add(Entity(Kind.COIN, c, s - 10f + i * 2f, 0.1f, y = 0.6f))
                    }
                }
            }
        }

        // Power-ups
        if (rnd.nextFloat() < 0.06f) {
            val candidates = (0..2).filter { kinds[it] == null && busyUntil[it] < s + 4f }
            if (candidates.isNotEmpty()) {
                val l = candidates[rnd.nextInt(candidates.size)]
                val kind = when (rnd.nextInt(3)) {
                    0 -> Kind.MAGNET
                    1 -> Kind.SHIELD
                    else -> Kind.DOUBLER
                }
                entities.add(Entity(kind, l, s + 7f, 0.1f, y = 1.0f))
            }
        }
    }

    /**
     * A train may only be placed if the player always keeps a reachable free lane:
     * never three blocked lanes, and never outer+middle blocked with the free lane
     * on the far side of the middle train (which would trap the player).
     */
    private fun canPlaceTrain(l: Int, s: Float, safe: Float): Boolean {
        var others = 0
        var middleBusy = false
        for (o in 0..2) {
            if (o != l && busyUntil[o] > s - safe) {
                others++
                if (o == 1) middleBusy = true
            }
        }
        if (others >= 2) return false
        if (others == 1 && middleBusy) return false
        return true
    }

    private fun placeTrain(l: Int, s: Float) {
        val cars = 1 + rnd.nextInt(if (difficulty > 0.4f) 3 else 2)
        val variant = rnd.nextInt(4)
        val group = ++groupCounter
        for (i in 0 until cars) {
            entities.add(
                Entity(Kind.TRAIN, l, s + i * (CAR_LEN + CAR_GAP), CAR_LEN, variant = variant, front = i == 0, group = group)
            )
        }
        busyUntil[l] = s + cars * CAR_LEN + (cars - 1) * CAR_GAP
    }
}

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
