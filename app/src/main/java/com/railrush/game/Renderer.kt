package com.railrush.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class UiState {
    var best = 0
    var bank = 0
    var soundOn = true
    var newBest = false
}

/**
 * Draws the world with a simple perspective camera that sits behind and above the runner.
 * World coordinates: x = sideways (lanes), y = up, s = distance along the track.
 */
class Renderer(private val world: World, private val ui: UiState) {

    companion object {
        const val CAM_BACK = 7f
        const val CAM_Y = 4.6f
        const val NEAR = 0.5f
        const val FAR = 150f
        const val SLEEPER_STEP = 1.5f
        const val BUILDING_STEP = 16f

        val SKY_TOP = Color.rgb(29, 27, 77)
        val SKY_HORIZON = Color.rgb(255, 158, 107)
        val FOG = Color.rgb(214, 128, 118)
        val GROUND = Color.rgb(75, 63, 87)
        val BED = Color.rgb(52, 43, 60)
        val SLEEPER = Color.rgb(120, 98, 86)
        val RAIL = Color.rgb(205, 210, 220)
        val GLASS = Color.rgb(28, 38, 66)
        val WINDOW_LIT = Color.rgb(255, 210, 122)
        val COIN_DARK = Color.rgb(233, 168, 0)
        val COIN_LIGHT = Color.rgb(255, 210, 63)
        val TRAIN_COLORS = intArrayOf(
            Color.rgb(42, 157, 143), Color.rgb(230, 57, 70), Color.rgb(244, 162, 97), Color.rgb(69, 123, 157),
        )
        val BUILDING_COLORS = intArrayOf(
            Color.rgb(61, 59, 110), Color.rgb(90, 75, 124), Color.rgb(47, 74, 109), Color.rgb(107, 78, 113),
        )
        val HOODIE = Color.rgb(255, 123, 0)
        val HOODIE_DARK = Color.rgb(214, 96, 0)
        val PANTS = Color.rgb(43, 45, 66)
        val SHOE = Color.rgb(241, 250, 238)
        val BACKPACK = Color.rgb(38, 70, 83)
        val HAIR = Color.rgb(61, 44, 46)
        val CAP = Color.rgb(230, 57, 70)
        val MAGNET_C = Color.rgb(230, 57, 70)
        val SHIELD_C = Color.rgb(0, 180, 216)
        val DOUBLER_C = Color.rgb(131, 56, 236)
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val oval = RectF()
    private val rect = RectF()
    private val ahead = ArrayList<Entity>()
    private val behind = ArrayList<Entity>()

    private var w = 1f
    private var h = 1f
    private var u = 1f
    private var f = 1f
    private var cx = 0f
    private var horizon = 0f
    private var camS = 0f
    private var camX = 0f
    private var camY = CAM_Y

    val pauseRect = RectF()
    val soundRect = RectF()

    private var skyShader: Shader? = null
    private var groundShader: Shader? = null
    private var sunShader: Shader? = null
    private val skyline = ArrayList<FloatArray>() // [x offset, width, height, layer]

    fun resize(width: Int, height: Int) {
        w = width.toFloat()
        h = height.toFloat()
        u = min(w / 1080f, h / 1920f)
        cx = w / 2f
        f = min(w * 1.05f, h * 0.75f)
        horizon = max(h * 0.22f, h * 0.80f - CAM_Y / CAM_BACK * f)

        skyShader = LinearGradient(0f, 0f, 0f, horizon, SKY_TOP, SKY_HORIZON, Shader.TileMode.CLAMP)
        groundShader = LinearGradient(0f, horizon, 0f, horizon + h * 0.2f, FOG, GROUND, Shader.TileMode.CLAMP)
        sunShader = RadialGradient(
            cx, horizon - h * 0.03f, w * 0.16f,
            Color.rgb(255, 236, 160), Color.rgb(255, 120, 90), Shader.TileMode.CLAMP,
        )

        skyline.clear()
        val r = Random(7)
        for (layer in 0..1) {
            var x = -w
            while (x < w) {
                val bw = w * (0.04f + r.nextFloat() * 0.07f)
                val bh = h * if (layer == 0) 0.05f + r.nextFloat() * 0.11f else 0.025f + r.nextFloat() * 0.06f
                skyline.add(floatArrayOf(x, bw, bh, layer.toFloat()))
                x += bw + w * 0.004f
            }
        }

        pauseRect.set(20f * u, 40f * u, 170f * u, 190f * u)
        soundRect.set(w - 300f * u, 50f * u, w - 30f * u, 140f * u)
    }

    // ------------------------------------------------------------ projection helpers

    private fun px(x: Float, z: Float) = cx + (x - camX) * f / z
    private fun py(y: Float, z: Float) = horizon + (camY - y) * f / z

    private fun shade(color: Int, k: Float, z: Float): Int {
        val t = ((z - 30f) / (FAR - 30f)).coerceIn(0f, 1f)
        var r = min(255f, Color.red(color) * k)
        var g = min(255f, Color.green(color) * k)
        var b = min(255f, Color.blue(color) * k)
        r += (Color.red(FOG) - r) * t
        g += (Color.green(FOG) - g) * t
        b += (Color.blue(FOG) - b) * t
        return Color.rgb(r.toInt(), g.toInt(), b.toInt())
    }

    private fun q3(
        c: Canvas,
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        color: Int,
    ) {
        path.reset()
        path.moveTo(px(x0, z0), py(y0, z0))
        path.lineTo(px(x1, z1), py(y1, z1))
        path.lineTo(px(x2, z2), py(y2, z2))
        path.lineTo(px(x3, z3), py(y3, z3))
        path.close()
        fill.color = color
        c.drawPath(path, fill)
    }

    /** Axis aligned box between s0 and s1. Returns false if not visible. */
    private fun box(
        c: Canvas, x0: Float, x1: Float, y0: Float, y1: Float, s0: Float, s1: Float, color: Int,
    ): Boolean {
        val zf = s0 - camS
        val zb = s1 - camS
        if (zb <= NEAR || zf > FAR) return false
        val z0 = max(zf, NEAR)
        if (x0 - camX > 0f) q3(c, x0, y0, z0, x0, y1, z0, x0, y1, zb, x0, y0, zb, shade(color, 0.72f, z0))
        if (x1 - camX < 0f) q3(c, x1, y0, z0, x1, y1, z0, x1, y1, zb, x1, y0, zb, shade(color, 0.72f, z0))
        if (camY > y1) q3(c, x0, y1, z0, x1, y1, z0, x1, y1, zb, x0, y1, zb, shade(color, 1.15f, z0))
        if (zf >= NEAR) {
            fill.color = shade(color, 1f, zf)
            c.drawRect(px(x0, zf), py(y1, zf), px(x1, zf), py(y0, zf), fill)
        }
        return true
    }

    /** A plank made of alternating coloured segments, drawn in an order that keeps side faces hidden. */
    private fun stripes(
        c: Canvas, xa: Float, xb: Float, y0: Float, y1: Float, s0: Float, s1: Float, n: Int, ca: Int, cb: Int,
    ) {
        val sw = (xb - xa) / n
        for (i in 0 until n) {
            val mid = xa + (i + 0.5f) * sw
            if (mid < camX) box(c, xa + i * sw, xa + (i + 1) * sw, y0, y1, s0, s1, if (i % 2 == 0) ca else cb)
        }
        for (i in n - 1 downTo 0) {
            val mid = xa + (i + 0.5f) * sw
            if (mid >= camX) box(c, xa + i * sw, xa + (i + 1) * sw, y0, y1, s0, s1, if (i % 2 == 0) ca else cb)
        }
    }

    // ------------------------------------------------------------ frame

    fun draw(c: Canvas) {
        val p = world.player
        camS = world.dist - CAM_BACK
        camX = p.x * 0.65f
        camY = CAM_Y + p.y * 0.3f
        if (world.state == GameState.CRASHED && world.crashT < 0.35f) {
            camX += sin(world.crashT * 90f) * 0.18f * (1f - world.crashT / 0.35f)
        }

        drawSky(c)
        drawGround(c)
        drawBuildings(c)
        drawEntities(c)
        drawHud(c)
        when (world.state) {
            GameState.MENU -> drawMenu(c)
            GameState.PAUSED -> drawPaused(c)
            GameState.GAME_OVER -> drawGameOver(c)
            else -> Unit
        }
    }

    private fun drawSky(c: Canvas) {
        fill.shader = skyShader
        c.drawRect(0f, 0f, w, horizon + 1f, fill)
        fill.shader = sunShader
        c.drawCircle(cx, horizon - h * 0.03f, w * 0.16f, fill)
        fill.shader = null

        val shift = -camX * 4f * u
        for (b in skyline) {
            val layer = b[3].toInt()
            fill.color = if (layer == 0) Color.rgb(58, 46, 102) else Color.rgb(84, 60, 112)
            val x = cx + b[0] + shift * (1 + layer)
            c.drawRect(x, horizon - b[2], x + b[1], horizon + 2f, fill)
        }
    }

    private fun drawGround(c: Canvas) {
        fill.shader = groundShader
        c.drawRect(0f, horizon, w, h, fill)
        fill.shader = null

        for (lane in 0..2) {
            val x = laneX(lane)
            q3(c, x - 1.05f, 0f, NEAR, x + 1.05f, 0f, NEAR, x + 1.05f, 0f, FAR, x - 1.05f, 0f, FAR, BED)
        }
        var s = ceil((camS + NEAR) / SLEEPER_STEP) * SLEEPER_STEP
        val sleeperEnd = camS + 90f
        // far to near is not needed: sleepers are flat and never overlap each other
        while (s < sleeperEnd) {
            val z = s - camS
            val col = shade(SLEEPER, 1f, z)
            for (lane in 0..2) {
                val x = laneX(lane)
                q3(c, x - 1.0f, 0f, z, x + 1.0f, 0f, z, x + 1.0f, 0f, z + 0.4f, x - 1.0f, 0f, z + 0.4f, col)
            }
            s += SLEEPER_STEP
        }
        for (lane in 0..2) {
            val x = laneX(lane)
            for (rx in floatArrayOf(x - 0.55f, x + 0.55f)) {
                q3(c, rx - 0.06f, 0.12f, NEAR, rx + 0.06f, 0.12f, NEAR, rx + 0.06f, 0.12f, FAR, rx - 0.06f, 0.12f, FAR, RAIL)
            }
        }
    }

    private fun hash(k: Int, side: Int): Int {
        var x = k * 73856093 xor (side * 19349663)
        x = x xor (x ushr 13)
        x *= 0x5bd1e995
        x = x xor (x ushr 15)
        return x and 0x7fffffff
    }

    private fun drawBuildings(c: Canvas) {
        val k0 = floor((camS - BUILDING_STEP) / BUILDING_STEP).toInt()
        val k1 = floor((camS + FAR) / BUILDING_STEP).toInt()
        for (k in k1 downTo k0) {
            for (side in intArrayOf(-1, 1)) {
                val hs = hash(k, side)
                val height = 5f + (hs % 1000) / 1000f * 14f
                val setback = ((hs / 1000) % 100) / 100f * 1.5f
                val color = BUILDING_COLORS[(hs / 100000) % BUILDING_COLORS.size]
                val s0 = k * BUILDING_STEP
                val s1 = s0 + 13f
                val inner = 5.4f + setback
                val x0 = if (side < 0) -18f else inner
                val x1 = if (side < 0) -inner else 18f
                if (!box(c, x0, x1, 0f, height, s0, s1, color)) continue
                val zStart = s0 - camS
                if (zStart > 85f) continue
                val fx = if (side < 0) x1 else x0
                var wy = 1.6f
                var row = 0
                while (wy + 1.3f < height - 0.5f) {
                    var ws = s0 + 1.5f
                    var col = 0
                    while (ws + 2f < s1 - 0.5f) {
                        if ((hs shr ((row * 3 + col) % 24)) and 1 == 1) {
                            val a = max(ws - camS, NEAR)
                            val b = ws + 2f - camS
                            if (b > NEAR) q3(c, fx, wy, a, fx, wy + 1.3f, a, fx, wy + 1.3f, b, fx, wy, b, shade(WINDOW_LIT, 1f, a))
                        }
                        ws += 3.5f
                        col++
                    }
                    wy += 2.6f
                    row++
                }
            }
        }
    }

    private fun drawEntities(c: Canvas) {
        ahead.clear()
        behind.clear()
        val maxS = camS + FAR
        val minS = camS + NEAR
        for (e in world.entities) {
            if (!e.alive || e.s > maxS || e.s + e.length < minS) continue
            if (e.s >= world.dist - 0.3f) ahead.add(e) else behind.add(e)
        }
        ahead.sortByDescending { it.s }
        behind.sortByDescending { it.s }
        for (e in ahead) drawEntity(c, e)
        drawPlayer(c)
        for (e in behind) drawEntity(c, e)
    }

    private fun drawEntity(c: Canvas, e: Entity) {
        when (e.kind) {
            Kind.TRAIN -> drawTrain(c, e)
            Kind.LOW_BARRIER -> drawLowBarrier(c, e)
            Kind.HIGH_BARRIER -> drawHighBarrier(c, e)
            Kind.COIN -> drawCoin(c, e)
            Kind.MAGNET, Kind.SHIELD, Kind.DOUBLER -> drawPowerUp(c, e)
        }
    }

    private fun drawTrain(c: Canvas, e: Entity) {
        val x = e.x
        val s0 = e.s
        val s1 = e.s + e.length
        val body = TRAIN_COLORS[e.variant % TRAIN_COLORS.size]
        box(c, x - 0.85f, x + 0.85f, 0f, 0.45f, s0 + 0.3f, s1 - 0.3f, Color.rgb(30, 27, 38))
        if (!box(c, x - 1.05f, x + 1.05f, 0.45f, 2.9f, s0, s1, body)) return

        val zf = s0 - camS
        val z0 = max(zf, NEAR)
        val zb = s1 - camS
        val sideX = when {
            x - 1.05f - camX > 0f -> x - 1.05f
            x + 1.05f - camX < 0f -> x + 1.05f
            else -> Float.NaN
        }
        if (!sideX.isNaN()) {
            q3(c, sideX, 1.2f, z0, sideX, 1.38f, z0, sideX, 1.38f, zb, sideX, 1.2f, zb, shade(Color.WHITE, 0.8f, z0))
            if (z0 < 100f) {
                var ws = s0 + 1.2f
                while (ws + 1.6f < s1 - 0.8f) {
                    val a = max(ws - camS, NEAR)
                    val b = ws + 1.6f - camS
                    if (b > NEAR) q3(c, sideX, 1.65f, a, sideX, 2.45f, a, sideX, 2.45f, b, sideX, 1.65f, b, shade(GLASS, 1f, a))
                    ws += 2.4f
                }
            }
        }
        if (zf >= NEAR) {
            val k = f / zf
            fill.color = shade(Color.WHITE, 0.85f, zf)
            c.drawRect(px(x - 1.05f, zf), py(1.38f, zf), px(x + 1.05f, zf), py(1.2f, zf), fill)
            if (e.front) {
                fill.color = shade(GLASS, 1f, zf)
                c.drawRect(px(x - 0.8f, zf), py(2.6f, zf), px(x + 0.8f, zf), py(1.65f, zf), fill)
                fill.color = shade(Color.rgb(90, 110, 160), 1f, zf)
                c.drawRect(px(x - 0.8f, zf), py(2.6f, zf), px(x - 0.55f, zf), py(1.65f, zf), fill)
                fill.color = shade(Color.rgb(255, 243, 176), 1.1f, zf)
                c.drawCircle(px(x - 0.62f, zf), py(0.82f, zf), 0.16f * k, fill)
                c.drawCircle(px(x + 0.62f, zf), py(0.82f, zf), 0.16f * k, fill)
            } else {
                fill.color = shade(body, 0.8f, zf)
                c.drawRect(px(x - 0.35f, zf), py(2.4f, zf), px(x + 0.35f, zf), py(0.6f, zf), fill)
            }
        }
    }

    private fun drawLowBarrier(c: Canvas, e: Entity) {
        val x = e.x
        val s0 = e.s
        val s1 = e.s + e.length
        val post = Color.rgb(70, 70, 80)
        box(c, x - 0.95f, x - 0.8f, 0f, 1.0f, s0 + 0.05f, s1 - 0.05f, post)
        box(c, x + 0.8f, x + 0.95f, 0f, 1.0f, s0 + 0.05f, s1 - 0.05f, post)
        box(c, x - 0.9f, x + 0.9f, 0.15f, 0.32f, s0 + 0.1f, s1 - 0.1f, Color.rgb(60, 60, 70))
        stripes(c, x - 1.0f, x + 1.0f, 0.55f, 1.0f, s0, s1, 6, Color.rgb(230, 57, 70), Color.rgb(245, 245, 245))
    }

    private fun drawHighBarrier(c: Canvas, e: Entity) {
        val x = e.x
        val s0 = e.s
        val s1 = e.s + e.length
        val post = Color.rgb(70, 70, 80)
        box(c, x - 1.0f, x - 0.86f, 0f, 2.6f, s0 + 0.05f, s1 - 0.05f, post)
        box(c, x + 0.86f, x + 1.0f, 0f, 2.6f, s0 + 0.05f, s1 - 0.05f, post)
        box(c, x - 0.86f, x + 0.86f, 1.95f, 2.55f, s0 + 0.1f, s1 - 0.1f, Color.rgb(29, 53, 87))
        stripes(c, x - 0.86f, x + 0.86f, 1.15f, 1.7f, s0, s1, 7, Color.rgb(255, 196, 0), Color.rgb(30, 30, 30))
        val zf = s0 - camS
        if (zf >= NEAR && zf < 90f) {
            path.reset()
            path.moveTo(px(x - 0.2f, zf), py(2.45f, zf))
            path.lineTo(px(x + 0.2f, zf), py(2.45f, zf))
            path.lineTo(px(x, zf), py(2.08f, zf))
            path.close()
            fill.color = shade(Color.WHITE, 1f, zf)
            c.drawPath(path, fill)
        }
    }

    private fun drawCoin(c: Canvas, e: Entity) {
        val z = e.s - camS
        if (z < NEAR) return
        val k = f / z
        val sx = px(e.x, z)
        val sy = py(e.y, z)
        val r = 0.32f * k
        val spin = abs(cos(world.animTime * 4f + e.s * 0.15f)).coerceAtLeast(0.15f)
        fill.color = shade(COIN_DARK, 1f, z)
        oval.set(sx - r * spin, sy - r, sx + r * spin, sy + r)
        c.drawOval(oval, fill)
        fill.color = shade(COIN_LIGHT, 1f, z)
        oval.set(sx - r * spin * 0.7f, sy - r * 0.7f, sx + r * spin * 0.7f, sy + r * 0.7f)
        c.drawOval(oval, fill)
    }

    private fun powerColor(kind: Kind) = when (kind) {
        Kind.MAGNET -> MAGNET_C
        Kind.SHIELD -> SHIELD_C
        else -> DOUBLER_C
    }

    private fun powerLabel(kind: Kind) = when (kind) {
        Kind.MAGNET -> "M"
        Kind.SHIELD -> "S"
        else -> "x2"
    }

    private fun drawPowerUp(c: Canvas, e: Entity) {
        val z = e.s - camS
        if (z < NEAR) return
        val k = f / z
        val bob = sin(world.animTime * 3f + e.s) * 0.12f
        val sx = px(e.x, z)
        val sy = py(e.y + bob, z)
        val r = 0.5f * k
        fill.color = shade(powerColor(e.kind), 1f, z)
        c.drawCircle(sx, sy, r, fill)
        stroke.color = Color.WHITE
        stroke.strokeWidth = 0.07f * k
        c.drawCircle(sx, sy, r, stroke)
        text.textAlign = Paint.Align.CENTER
        text.color = Color.WHITE
        text.textSize = r * 1.1f
        c.drawText(powerLabel(e.kind), sx, sy + text.textSize * 0.36f, text)
    }

    private fun drawPlayer(c: Canvas) {
        val p = world.player
        val z = world.dist - camS
        val k = f / z
        val fx = px(p.x, z)
        val groundY = py(0f, z)
        val feetY = py(p.y, z)

        fill.color = Color.argb((90 * (1f - min(1f, p.y / 3f))).toInt(), 0, 0, 0)
        oval.set(fx - 0.45f * k, groundY - 0.12f * k, fx + 0.45f * k, groundY + 0.12f * k)
        c.drawOval(oval, fill)

        c.save()
        if (world.state == GameState.CRASHED || world.state == GameState.GAME_OVER) {
            c.rotate(min(1f, world.crashT * 3f) * 80f, fx, feetY)
        }

        if (p.rolling) {
            val r = 0.48f * k
            val cy = feetY - r
            fill.color = HOODIE
            c.drawCircle(fx, cy, r, fill)
            stroke.color = HOODIE_DARK
            stroke.strokeWidth = 0.1f * k
            val a = (world.animTime * 900f) % 360f
            oval.set(fx - r * 0.7f, cy - r * 0.7f, fx + r * 0.7f, cy + r * 0.7f)
            c.drawArc(oval, a, 120f, false, stroke)
            c.drawArc(oval, a + 180f, 120f, false, stroke)
            fill.color = CAP
            c.drawCircle(fx + cos(a * PI.toFloat() / 180f) * r * 0.55f, cy + sin(a * PI.toFloat() / 180f) * r * 0.55f, r * 0.25f, fill)
        } else {
            val airborne = p.y > 0.05f
            val ph = p.runPhase
            val idle = world.state == GameState.MENU

            // legs
            stroke.color = PANTS
            stroke.strokeWidth = 0.17f * k
            for (i in 0..1) {
                val sideX = if (i == 0) -0.15f else 0.15f
                val lift = when {
                    airborne -> 0.3f
                    idle -> 0f
                    else -> max(0f, sin(ph + i * PI.toFloat())) * 0.35f
                }
                val hipY = feetY - 0.9f * k
                val footY = feetY - lift * k
                c.drawLine(fx + sideX * k, hipY, fx + sideX * k, footY, stroke)
                fill.color = SHOE
                oval.set(fx + (sideX - 0.11f) * k, footY - 0.08f * k, fx + (sideX + 0.11f) * k, footY + 0.08f * k)
                c.drawOval(oval, fill)
            }

            // arms
            stroke.color = HOODIE_DARK
            stroke.strokeWidth = 0.13f * k
            for (i in 0..1) {
                val side = if (i == 0) -1f else 1f
                val swing = if (idle) 0f else sin(ph + if (i == 0) PI.toFloat() else 0f) * 0.25f
                val handY = if (airborne) feetY - 1.85f * k else feetY - (1.0f + swing) * k
                c.drawLine(fx + side * 0.3f * k, feetY - 1.36f * k, fx + side * 0.45f * k, handY, stroke)
            }

            // torso + backpack (seen from behind)
            fill.color = HOODIE
            rect.set(fx - 0.32f * k, feetY - 1.5f * k, fx + 0.32f * k, feetY - 0.82f * k)
            c.drawRoundRect(rect, 0.12f * k, 0.12f * k, fill)
            fill.color = BACKPACK
            rect.set(fx - 0.22f * k, feetY - 1.42f * k, fx + 0.22f * k, feetY - 0.98f * k)
            c.drawRoundRect(rect, 0.08f * k, 0.08f * k, fill)

            // head with cap
            val headY = feetY - 1.68f * k
            fill.color = HAIR
            c.drawCircle(fx, headY, 0.2f * k, fill)
            fill.color = CAP
            oval.set(fx - 0.21f * k, headY - 0.22f * k, fx + 0.21f * k, headY + 0.2f * k)
            c.drawArc(oval, 180f, 180f, true, fill)
            fill.color = Color.WHITE
            c.drawCircle(fx, headY - 0.2f * k, 0.035f * k, fill)
        }
        c.restore()

        if (world.shieldT > 0f) {
            val pulse = 0.5f + 0.5f * sin(world.animTime * 6f)
            val blink = world.shieldT > 2f || sin(world.animTime * 25f) > 0f
            if (blink) {
                fill.color = Color.argb(40, 0, 180, 216)
                c.drawCircle(fx, feetY - 0.9f * k, 1.1f * k, fill)
                stroke.color = Color.argb((140 + 100 * pulse).toInt(), 120, 230, 255)
                stroke.strokeWidth = 0.06f * k
                c.drawCircle(fx, feetY - 0.9f * k, 1.1f * k, stroke)
            }
        }
    }

    // ------------------------------------------------------------ HUD & overlays

    private fun shadowText(c: Canvas, s: String, x: Float, y: Float, color: Int) {
        text.color = Color.argb(140, 0, 0, 0)
        c.drawText(s, x + 4f * u, y + 5f * u, text)
        text.color = color
        c.drawText(s, x, y, text)
    }

    private fun coinIcon(c: Canvas, x: Float, y: Float, r: Float) {
        fill.color = COIN_DARK
        c.drawCircle(x, y, r, fill)
        fill.color = COIN_LIGHT
        c.drawCircle(x, y, r * 0.7f, fill)
    }

    private fun drawHud(c: Canvas) {
        val st = world.state
        if (st == GameState.MENU) return

        // stumble warning
        if (world.stumbleT > World.STUMBLE_WINDOW - 0.6f && st == GameState.PLAYING) {
            val a = ((world.stumbleT - (World.STUMBLE_WINDOW - 0.6f)) / 0.6f * 160).toInt()
            stroke.color = Color.argb(a, 255, 40, 40)
            stroke.strokeWidth = 40f * u
            c.drawRect(0f, 0f, w, h, stroke)
        }

        text.textAlign = Paint.Align.RIGHT
        text.textSize = 84f * u
        shadowText(c, world.score.toInt().toString(), w - 40f * u, 130f * u, Color.WHITE)

        text.textSize = 58f * u
        val coinStr = world.coins.toString()
        shadowText(c, coinStr, w - 40f * u, 215f * u, COIN_LIGHT)
        coinIcon(c, w - 40f * u - text.measureText(coinStr) - 38f * u, 195f * u, 24f * u)

        if (world.multiplier > 1) {
            text.textSize = 50f * u
            shadowText(c, "x${world.multiplier}", w - 40f * u, 290f * u, Color.rgb(200, 160, 255))
        }

        if (st == GameState.PLAYING) {
            val pcx = pauseRect.centerX()
            val pcy = pauseRect.centerY()
            fill.color = Color.argb(110, 0, 0, 0)
            c.drawCircle(pcx, pcy, 52f * u, fill)
            fill.color = Color.WHITE
            c.drawRoundRect(pcx - 22f * u, pcy - 24f * u, pcx - 8f * u, pcy + 24f * u, 4f * u, 4f * u, fill)
            c.drawRoundRect(pcx + 8f * u, pcy - 24f * u, pcx + 22f * u, pcy + 24f * u, 4f * u, 4f * u, fill)
        }

        // active power-up timers
        var y = 250f * u
        val timers = arrayOf(
            Triple(Kind.MAGNET, world.magnetT, World.POWER_TIME),
            Triple(Kind.SHIELD, world.shieldT, World.SHIELD_TIME),
            Triple(Kind.DOUBLER, world.doublerT, World.POWER_TIME),
        )
        for ((kind, t, max) in timers) {
            if (t <= 0f) continue
            val ix = 75f * u
            fill.color = powerColor(kind)
            c.drawCircle(ix, y, 30f * u, fill)
            text.textAlign = Paint.Align.CENTER
            text.textSize = 30f * u
            text.color = Color.WHITE
            c.drawText(powerLabel(kind), ix, y + 11f * u, text)
            fill.color = Color.argb(120, 0, 0, 0)
            c.drawRoundRect(ix + 45f * u, y - 10f * u, ix + 245f * u, y + 10f * u, 10f * u, 10f * u, fill)
            fill.color = powerColor(kind)
            c.drawRoundRect(ix + 45f * u, y - 10f * u, ix + 45f * u + 200f * u * (t / max), y + 10f * u, 10f * u, 10f * u, fill)
            y += 80f * u
        }
    }

    private fun drawSoundToggle(c: Canvas) {
        fill.color = Color.argb(130, 0, 0, 0)
        c.drawRoundRect(soundRect, 45f * u, 45f * u, fill)
        text.textAlign = Paint.Align.CENTER
        text.textSize = 40f * u
        text.color = if (ui.soundOn) Color.WHITE else Color.rgb(170, 170, 170)
        c.drawText(if (ui.soundOn) "SOUND ON" else "SOUND OFF", soundRect.centerX(), soundRect.centerY() + 14f * u, text)
    }

    private fun pulseAlpha(): Int = (170 + 85 * sin(world.animTime * 5f)).toInt().coerceIn(0, 255)

    private fun drawMenu(c: Canvas) {
        fill.color = Color.argb(90, 10, 5, 30)
        c.drawRect(0f, 0f, w, h, fill)

        text.textAlign = Paint.Align.CENTER
        val ty = h * 0.17f
        text.textSize = 150f * u
        text.style = Paint.Style.STROKE
        text.strokeWidth = 16f * u
        text.color = Color.rgb(40, 20, 70)
        c.drawText("RAIL RUSH", cx, ty, text)
        text.style = Paint.Style.FILL
        text.color = Color.rgb(255, 179, 71)
        c.drawText("RAIL RUSH", cx, ty, text)

        text.textSize = 44f * u
        shadowText(c, "Dash the rails. Dodge the trains.", cx, ty + 80f * u, Color.WHITE)

        fill.color = Color.argb(120, 0, 0, 0)
        rect.set(w * 0.08f, h * 0.27f, w * 0.92f, h * 0.27f + 330f * u)
        c.drawRoundRect(rect, 40f * u, 40f * u, fill)
        text.textSize = 42f * u
        val lines = arrayOf(
            "←  →   swipe to switch lanes",
            "↑   swipe up to jump",
            "↓   swipe down to roll",
            "Grab coins, magnets, shields & x2",
        )
        var ly = h * 0.27f + 75f * u
        for (l in lines) {
            text.color = Color.WHITE
            c.drawText(l, cx, ly, text)
            ly += 70f * u
        }

        text.textSize = 46f * u
        shadowText(c, "BEST ${ui.best}    COINS ${ui.bank}", cx, h * 0.27f + 410f * u, COIN_LIGHT)

        text.textSize = 76f * u
        text.color = Color.argb(pulseAlpha(), 255, 255, 255)
        c.drawText("TAP TO RUN", cx, h * 0.27f + 530f * u, text)

        drawSoundToggle(c)
    }

    private fun drawPaused(c: Canvas) {
        fill.color = Color.argb(150, 10, 5, 30)
        c.drawRect(0f, 0f, w, h, fill)
        text.textAlign = Paint.Align.CENTER
        text.textSize = 120f * u
        shadowText(c, "PAUSED", cx, h * 0.38f, Color.WHITE)
        text.textSize = 56f * u
        text.color = Color.argb(pulseAlpha(), 255, 255, 255)
        c.drawText("Tap to resume", cx, h * 0.38f + 110f * u, text)
        drawSoundToggle(c)
    }

    private fun drawGameOver(c: Canvas) {
        val a = min(1f, world.crashT)
        fill.color = Color.argb((150 * a).toInt(), 10, 5, 30)
        c.drawRect(0f, 0f, w, h, fill)

        fill.color = Color.argb(235, 29, 27, 77)
        rect.set(w * 0.1f, h * 0.2f, w * 0.9f, h * 0.2f + 680f * u)
        c.drawRoundRect(rect, 48f * u, 48f * u, fill)
        stroke.color = Color.rgb(255, 158, 107)
        stroke.strokeWidth = 6f * u
        c.drawRoundRect(rect, 48f * u, 48f * u, stroke)

        text.textAlign = Paint.Align.CENTER
        var y = h * 0.2f + 120f * u
        text.textSize = 92f * u
        shadowText(c, "CRASHED!", cx, y, Color.rgb(255, 120, 110))
        y += 90f * u
        text.textSize = 40f * u
        text.color = Color.rgb(190, 185, 220)
        c.drawText("SCORE", cx, y, text)
        y += 120f * u
        text.textSize = 120f * u
        shadowText(c, world.score.toInt().toString(), cx, y, Color.WHITE)
        y += 90f * u
        text.textSize = 50f * u
        if (ui.newBest) {
            text.color = Color.argb(pulseAlpha(), 255, 210, 63)
            c.drawText("NEW BEST!", cx, y, text)
        } else {
            text.color = Color.rgb(190, 185, 220)
            c.drawText("BEST ${ui.best}", cx, y, text)
        }
        y += 80f * u
        text.textSize = 50f * u
        text.color = COIN_LIGHT
        c.drawText("+${world.coins} coins", cx, y, text)

        if (world.crashT > 1.4f) {
            text.textSize = 64f * u
            text.color = Color.argb(pulseAlpha(), 255, 255, 255)
            c.drawText("TAP TO RUN AGAIN", cx, h * 0.2f + 800f * u, text)
        }
    }
}
