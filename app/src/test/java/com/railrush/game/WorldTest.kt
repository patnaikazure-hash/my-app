package com.railrush.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

class WorldTest {

    private fun scenario(vararg entities: Entity): World {
        val w = World(1)
        w.autoSpawn = false
        w.start()
        w.entities.addAll(entities)
        return w
    }

    private fun run(w: World, seconds: Float, action: (World) -> Unit = {}) {
        repeat((seconds * 60).toInt()) {
            action(w)
            w.update(1f / 60f)
        }
    }

    private fun alive(w: World) = w.state == GameState.PLAYING

    @Test
    fun lowBarrierWithoutJumpCrashes() {
        val w = scenario(Entity(Kind.LOW_BARRIER, 1, 30f, 0.4f))
        run(w, 3f)
        assertNotEquals(GameState.PLAYING, w.state)
    }

    @Test
    fun jumpClearsLowBarrier() {
        val e = Entity(Kind.LOW_BARRIER, 1, 30f, 0.4f)
        val w = scenario(e)
        var done = false
        run(w, 3f) { if (!done && e.s - it.dist < it.speed * 0.3f) { it.swipeUp(); done = true } }
        assertTrue(alive(w))
    }

    @Test
    fun rollUnderHighBarrier() {
        val e = Entity(Kind.HIGH_BARRIER, 1, 30f, 0.4f)
        val w = scenario(e)
        var done = false
        run(w, 3f) { if (!done && e.s - it.dist < it.speed * 0.3f) { it.swipeDown(); done = true } }
        assertTrue(alive(w))
    }

    @Test
    fun jumpIntoHighBarrierCrashes() {
        val e = Entity(Kind.HIGH_BARRIER, 1, 30f, 0.4f)
        val w = scenario(e)
        var done = false
        run(w, 3f) { if (!done && e.s - it.dist < it.speed * 0.3f) { it.swipeUp(); done = true } }
        assertNotEquals(GameState.PLAYING, w.state)
    }

    @Test
    fun trainHeadOnCrashesButDodgeSurvives() {
        val crash = scenario(Entity(Kind.TRAIN, 1, 30f, World.CAR_LEN))
        run(crash, 4f)
        assertNotEquals(GameState.PLAYING, crash.state)

        val dodge = scenario(Entity(Kind.TRAIN, 1, 30f, World.CAR_LEN))
        var done = false
        run(dodge, 4f) { if (!done && 30f - it.dist < 10f) { it.swipeLeft(); done = true } }
        assertTrue(alive(dodge))
    }

    @Test
    fun sideBumpStumblesThenSecondBumpCrashes() {
        val w = scenario(Entity(Kind.TRAIN, 0, -5f, 45f))
        w.swipeLeft()
        run(w, 0.5f)
        assertTrue(alive(w))
        assertEquals(1, w.player.lane)
        assertTrue(w.stumbleT > 0f)
        w.swipeLeft()
        run(w, 0.5f)
        assertNotEquals(GameState.PLAYING, w.state)
    }

    @Test
    fun shieldAbsorbsOneHit() {
        val w = scenario(Entity(Kind.TRAIN, 1, 30f, World.CAR_LEN, group = 1), Entity(Kind.TRAIN, 1, 41.8f, World.CAR_LEN, front = false, group = 1))
        w.shieldT = 10f
        run(w, 4f)
        assertTrue(alive(w))
        assertEquals(0f, w.shieldT, 0f)
    }

    @Test
    fun coinsAreCollected() {
        val w = scenario(
            Entity(Kind.COIN, 1, 20f, 0.1f, y = 0.6f),
            Entity(Kind.COIN, 1, 22f, 0.1f, y = 0.6f),
            Entity(Kind.COIN, 2, 24f, 0.1f, y = 0.6f),
        )
        run(w, 3f)
        assertEquals(2, w.coins)
    }

    @Test
    fun magnetPullsCoinsFromOtherLanes() {
        val w = scenario(Entity(Kind.MAGNET, 1, 10f, 0.1f, y = 1f), Entity(Kind.COIN, 2, 30f, 0.1f, y = 0.6f))
        run(w, 3f)
        assertEquals(1, w.coins)
    }

    /**
     * Generates long tracks for many seeds and checks with a conservative reachability search
     * that there is always a lane the player can be in (trains are the only unpassable obstacles).
     */
    @Test
    fun generatedTrackIsAlwaysSurvivable() {
        val length = 15000f
        val step = 0.5f
        val n = (length / step).toInt()
        val laneCells = ceil(World.MAX_SPEED * (LANE_W / World.LATERAL_SPEED) / step).toInt() + 2

        for (seed in 1L..80L) {
            val w = World(seed)
            w.start()
            while (w.dist < length) {
                w.dist += 20f
                w.speed = min(World.MAX_SPEED, World.BASE_SPEED + w.dist / 250f)
                w.spawnAhead()
            }
            val blocked = Array(3) { BooleanArray(n + laneCells + 1) }
            for (e in w.entities) {
                if (e.kind != Kind.TRAIN) continue
                val a = floor((e.s - 1f) / step).toInt().coerceAtLeast(0)
                val b = ceil((e.s + e.length + 1f) / step).toInt().coerceAtMost(blocked[0].size - 1)
                for (i in a..b) blocked[e.lane][i] = true
            }
            val reach = Array(3) { BooleanArray(n + laneCells + 1) }
            reach[1][0] = true
            for (i in 0 until n) {
                var any = false
                for (l in 0..2) {
                    if (!reach[l][i]) continue
                    any = true
                    if (!blocked[l][i + 1]) reach[l][i + 1] = true
                    for (m in intArrayOf(l - 1, l + 1)) {
                        if (m !in 0..2) continue
                        if ((i..i + laneCells).all { !blocked[l][it] && !blocked[m][it] }) reach[m][i + laneCells] = true
                    }
                }
                assertTrue("seed $seed trapped at s=${i * step}", any)
            }
        }
    }
}
