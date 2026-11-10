package com.railrush.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max

@SuppressLint("ViewConstructor")
class GameView(context: Context) : View(context), Choreographer.FrameCallback {

    private val world = World()
    private val ui = UiState()
    private val renderer = Renderer(world, ui)
    private val sfx = Sfx(context)
    private val prefs = context.getSharedPreferences("rail_rush", Context.MODE_PRIVATE)

    private var looping = false
    private var lastFrameNs = 0L

    private val swipeThreshold = 28f * resources.displayMetrics.density
    private var originX = 0f
    private var originY = 0f
    private var downX = 0f
    private var downY = 0f
    private var swipedThisTouch = false

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        ui.best = prefs.getInt("best", 0)
        ui.bank = prefs.getInt("coins", 0)
        ui.soundOn = prefs.getBoolean("sound", true)
        sfx.enabled = ui.soundOn
        world.onEvent = ::onGameEvent
    }

    // ------------------------------------------------------------ loop

    fun startLoop() {
        if (looping) return
        looping = true
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stopLoop() {
        looping = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!looping) return
        val dt = if (lastFrameNs == 0L) 0f else ((frameTimeNanos - lastFrameNs) / 1e9f).coerceIn(0f, 0.05f)
        lastFrameNs = frameTimeNanos
        world.update(dt)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
        startLoop()
    }

    override fun onDetachedFromWindow() {
        stopLoop()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        renderer.resize(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        renderer.draw(canvas)
    }

    fun onHostPause() {
        if (world.state == GameState.PLAYING) world.state = GameState.PAUSED
        stopLoop()
    }

    fun onHostResume() {
        startLoop()
    }

    fun release() {
        sfx.release()
    }

    // ------------------------------------------------------------ events

    private fun onGameEvent(ev: GameEvent) {
        when (ev) {
            GameEvent.COIN -> sfx.play(Sfx.Sound.COIN, 0.5f)
            GameEvent.JUMP -> sfx.play(Sfx.Sound.JUMP, 0.6f)
            GameEvent.ROLL -> sfx.play(Sfx.Sound.ROLL, 0.6f)
            GameEvent.LANE -> sfx.play(Sfx.Sound.SWISH, 0.35f)
            GameEvent.POWER_UP -> sfx.play(Sfx.Sound.POWER, 0.7f)
            GameEvent.STUMBLE -> {
                sfx.play(Sfx.Sound.STUMBLE, 0.8f)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            GameEvent.SHIELD_BREAK -> {
                sfx.play(Sfx.Sound.CRASH, 0.5f)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
            GameEvent.CRASH -> {
                sfx.play(Sfx.Sound.CRASH, 1f)
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                val score = world.score.toInt()
                ui.newBest = score > ui.best
                ui.best = max(ui.best, score)
                ui.bank += world.coins
                prefs.edit().putInt("best", ui.best).putInt("coins", ui.bank).apply()
            }
        }
    }

    private fun toggleSound() {
        ui.soundOn = !ui.soundOn
        sfx.enabled = ui.soundOn
        prefs.edit().putBoolean("sound", ui.soundOn).apply()
    }

    private fun onTap(x: Float, y: Float) {
        when (world.state) {
            GameState.MENU -> if (renderer.soundRect.contains(x, y)) toggleSound() else world.start()
            GameState.PLAYING -> if (renderer.pauseRect.contains(x, y)) world.state = GameState.PAUSED
            GameState.PAUSED -> if (renderer.soundRect.contains(x, y)) toggleSound() else world.state = GameState.PLAYING
            GameState.GAME_OVER -> if (world.crashT > 1.4f) world.start()
            GameState.CRASHED -> Unit
        }
    }

    // ------------------------------------------------------------ input

    private fun swipe(dx: Float, dy: Float) {
        if (abs(dx) > abs(dy)) {
            if (dx < 0) world.swipeLeft() else world.swipeRight()
        } else {
            if (dy < 0) world.swipeUp() else world.swipeDown()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                originX = event.x; originY = event.y
                downX = event.x; downY = event.y
                swipedThisTouch = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (world.state == GameState.PLAYING) {
                    val dx = event.x - originX
                    val dy = event.y - originY
                    if (max(abs(dx), abs(dy)) > swipeThreshold) {
                        swipe(dx, dy)
                        swipedThisTouch = true
                        // re-arm so several swipes can be chained without lifting the finger
                        originX = event.x; originY = event.y
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!swipedThisTouch) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (max(abs(dx), abs(dy)) > swipeThreshold && world.state == GameState.PLAYING) {
                        swipe(dx, dy)
                    } else {
                        onTap(event.x, event.y)
                    }
                }
            }
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> world.swipeLeft()
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> world.swipeRight()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_SPACE -> world.swipeUp()
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> world.swipeDown()
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> onTap(-1f, -1f)
            KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_ESCAPE -> {
                if (world.state == GameState.PLAYING) world.state = GameState.PAUSED
                else if (world.state == GameState.PAUSED) world.state = GameState.PLAYING
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}
