package com.pullnotify.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.os.SystemClock
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.min

enum class PullState { IDLE, ENTER, GRAB, PULL, STRUGGLE, SUCCESS, RELEASE }

data class CharacterDefinition(
    val id: String,
    val displayName: String,
    val style: String,
    val previewAsset: String,
    val idleAnimation: String,
    val grabAnimation: String,
    val pullAnimation: String,
    val struggleAnimation: String,
    val successAnimation: String,
    val releaseAnimation: String,
    val defaultScale: Float = 1f,
    val defaultAnimationSpeed: Float = 1f,
    val supportedOrientations: Set<String> = setOf("portrait", "landscape")
)

object CharacterRegistry {
    val all = listOf(
        CharacterDefinition("cute", "Cute Cartoon", "Procedural cartoon", "procedural:cute", "cute_idle", "cute_grab", "cute_pull", "cute_struggle", "cute_success", "cute_release"),
        CharacterDefinition("stickman", "Stickman", "Articulated line character", "procedural:stickman", "stickman_idle", "stickman_grab", "stickman_pull", "stickman_struggle", "stickman_success", "stickman_release"),
        CharacterDefinition("pixel", "Pixel Character", "Crisp pixel art", "procedural:pixel", "pixel_idle", "pixel_grab", "pixel_pull", "pixel_struggle", "pixel_success", "pixel_release")
    )
    fun find(id: String?): CharacterDefinition = all.firstOrNull { it.id == id } ?: all.first()
}

object CharacterAssetLoader {
    fun validate(definition: CharacterDefinition): Boolean {
        // Every registered character is drawn procedurally; no external sprite files are referenced.
        return definition.previewAsset.startsWith("procedural:") &&
            definition.id.isNotBlank() && definition.idleAnimation.isNotBlank() &&
            definition.grabAnimation.isNotBlank() && definition.pullAnimation.isNotBlank() &&
            definition.struggleAnimation.isNotBlank() && definition.successAnimation.isNotBlank() &&
            definition.releaseAnimation.isNotBlank()
    }
}

interface CharacterAnimator {
    fun load(): Boolean
    fun playIdle()
    fun playGrab()
    fun playPull()
    fun playStruggle()
    fun playSuccess()
    fun playRelease()
    fun stop()
    fun dispose()
}

class AnimationController(private val onState: (PullState) -> Unit) : CharacterAnimator {
    var state: PullState = PullState.IDLE
        private set
    override fun load() = true
    override fun playIdle() = set(PullState.IDLE)
    override fun playGrab() = set(PullState.GRAB)
    override fun playPull() = set(PullState.PULL)
    override fun playStruggle() = set(PullState.STRUGGLE)
    override fun playSuccess() = set(PullState.SUCCESS)
    override fun playRelease() = set(PullState.RELEASE)
    override fun stop() = set(PullState.IDLE)
    override fun dispose() = stop()
    private fun set(value: PullState) { state = value; onState(value) }
}

/** Lightweight procedural renderer: articulated cartoon/stickman and crisp pixel character share one timeline. */
class CharacterRendererView(
    context: Context,
    private val characterId: String,
    private val appLabel: String,
    private val speed: Float = 1f,
    private val sizeFactor: Float = 1f,
    private val pullCycles: Int = 3,
    private val overlayDurationMs: Long = 5600L
) : View(context) {
    private val startTime = SystemClock.uptimeMillis()
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pixelPaint = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    private val purple = Color.rgb(117, 87, 217)
    private val ink = Color.rgb(37, 34, 48)
    private val soft = Color.rgb(246, 243, 255)
    private val white = Color.WHITE
    private var closed = false

    private fun dp(v: Float) = v * density
    private fun smooth(t: Float) = t * t * (3f - 2f * t)
    private fun stateAt(elapsed: Long): PullState {
        val enter = (350 / max(.35f, speed)).toLong()
        val grab = (520 / max(.35f, speed)).toLong()
        val cycle = (920 / max(.35f, speed)).toLong()
        val endPull = enter + grab + cycle * pullCycles.coerceIn(1, 5)
        val success = (520 / max(.35f, speed)).toLong()
        return when {
            elapsed < enter -> PullState.ENTER
            elapsed < enter + grab -> PullState.GRAB
            elapsed < endPull -> {
                val local = (elapsed - enter - grab) % cycle
                when {
                    local < cycle * .48f -> PullState.PULL
                    local < cycle * .72f -> PullState.STRUGGLE
                    else -> PullState.RELEASE
                }
            }
            elapsed < endPull + success -> PullState.SUCCESS
            elapsed < endPull + success + 420 -> PullState.RELEASE
            else -> PullState.IDLE
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (closed) return
        val elapsed = ((SystemClock.uptimeMillis() - startTime) * speed.coerceIn(.35f, 2.5f)).toLong()
        val state = stateAt(elapsed)
        val end = ((350 + 520 + 920 * pullCycles.coerceIn(1, 5) + 520 + 420) / max(.35f, speed)).toLong()
        drawScene(canvas, elapsed, state)
        if (elapsed < end && state != PullState.IDLE) postInvalidateDelayed(30)
    }

    private fun drawScene(c: Canvas, elapsed: Long, state: PullState) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val u = dp(1f) * sizeFactor.coerceIn(.65f, 1.45f)
        val enterProgress = (elapsed / (350f / max(.35f, speed))).coerceIn(0f, 1f)
        val enterOffset = (1f - smooth(enterProgress)) * dp(75f)
        val baseX = w * .34f + enterOffset
        val baseY = h * .79f
        val phase = (elapsed / (920f / max(.35f, speed))) % 1f
        val pullAmount = when (state) {
            PullState.PULL -> smooth((phase / .48f).coerceIn(0f, 1f))
            PullState.STRUGGLE -> .95f
            PullState.RELEASE -> .35f + .18f * sin(phase * 25f)
            PullState.SUCCESS -> .1f
            else -> 0f
        }
        val lean = when (state) {
            PullState.PULL -> 17f * pullAmount
            PullState.STRUGGLE -> 20f
            PullState.RELEASE -> 12f * pullAmount
            PullState.SUCCESS -> -3f
            else -> 0f
        }
        val bob = if (state == PullState.IDLE) sin(elapsed / 280.0).toFloat() * dp(2f) else 0f
        val cx = baseX
        val by = baseY + bob
        val headY = by - 88f * u
        val handY = by - (109f - lean * .55f) * u
        val handX1 = cx - (11f + lean * .20f) * u
        val handX2 = cx + (11f + lean * .20f) * u
        val anchorX = (handX1 + handX2) / 2f
        val anchorY = dp(9f)

        // Notification-themed visual is owned by PullNotify; it does not move Android's system shade.
        val cardW = min(dp(190f), w * .46f)
        val cardH = dp(54f)
        val cardX = min(w - cardW - dp(10f), w * .50f)
        val pullTravel = if (state == PullState.SUCCESS) dp(36f) else dp(27f * pullAmount)
        val cardY = dp(34f) + pullTravel + if (state == PullState.STRUGGLE) dp(3f * sin(elapsed / 35.0).toFloat()) else 0f
        paint.color = Color.argb(30, 33, 20, 65)
        c.drawRoundRect(RectF(cardX, cardY + dp(3f), cardX + cardW, cardY + cardH + dp(3f)), dp(13f), dp(13f), paint)
        paint.color = white
        c.drawRoundRect(RectF(cardX, cardY, cardX + cardW, cardY + cardH), dp(13f), dp(13f), paint)
        paint.color = purple
        c.drawCircle(cardX + dp(19f), cardY + dp(19f), dp(9f), paint)
        paint.color = white
        paint.textSize = dp(10f)
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        c.drawText("✦", cardX + dp(15f), cardY + dp(22f), paint)
        paint.color = ink
        paint.textSize = dp(11f)
        c.drawText(if (appLabel.length > 22) appLabel.take(20) + "…" else appLabel, cardX + dp(34f), cardY + dp(19f), paint)
        paint.color = Color.rgb(125, 119, 143)
        paint.textSize = dp(9f)
        c.drawText("Notification pulled down!", cardX + dp(34f), cardY + dp(36f), paint)

        // Tension and spring recoil are linked to the hands' position.
        paint.color = Color.rgb(115, 91, 161)
        paint.strokeWidth = dp(if (state == PullState.PULL || state == PullState.STRUGGLE) 2.5f else 1.8f)
        paint.strokeCap = Paint.Cap.ROUND
        val ropePath = Path().apply {
            moveTo(anchorX, anchorY)
            val tension = if (state == PullState.PULL || state == PullState.STRUGGLE) dp(1f) else dp(12f)
            quadTo(anchorX + sin(elapsed / 48.0).toFloat() * tension, (anchorY + handY) / 2f, handX1, handY)
        }
        c.drawPath(ropePath, paint)
        c.drawLine(anchorX, anchorY, handX2, handY, paint)
        if (state == PullState.PULL || state == PullState.STRUGGLE) {
            paint.color = Color.argb(110, 117, 87, 217)
            paint.strokeWidth = dp(4f)
            c.drawLine(anchorX - dp(3f), anchorY + dp(4f), anchorX - dp(3f), handY - dp(6f), paint)
        }

        when (characterId) {
            "stickman" -> drawStickman(c, cx, by, u, lean, state, elapsed, handX1, handX2, handY)
            "pixel" -> drawPixel(c, cx, by, u, lean, state, elapsed, handX1, handX2, handY)
            else -> drawCute(c, cx, by, u, lean, state, elapsed, headY, handX1, handX2, handY)
        }
        if (state == PullState.SUCCESS) {
            paint.color = purple
            paint.textSize = dp(19f)
            c.drawText("✦  ✧  ✦", cx - dp(30f), by - dp(125f) * sizeFactor, paint)
        }
    }

    private fun drawCute(c: Canvas, x: Float, by: Float, u: Float, lean: Float, state: PullState, elapsed: Long, headY: Float, h1: Float, h2: Float, hy: Float) {
        val bounce = if (state == PullState.STRUGGLE) dp(2f * sin(elapsed / 28.0).toFloat()) else 0f
        val bodyX = x + lean * u * .45f
        val bodyY = by - 48f * u + bounce
        paint.color = Color.rgb(255, 193, 121)
        c.drawRoundRect(RectF(bodyX - 16*u, bodyY - 15*u, bodyX + 16*u, bodyY + 18*u), 13*u, 13*u, paint)
        paint.color = Color.rgb(255, 211, 151)
        c.drawCircle(bodyX, headY + bounce, 25*u, paint)
        paint.color = Color.rgb(70, 48, 54)
        c.drawCircle(bodyX - 8*u, headY - 2*u + bounce, 2.8f*u, paint)
        c.drawCircle(bodyX + 8*u, headY - 2*u + bounce, 2.8f*u, paint)
        paint.color = Color.rgb(235, 113, 124)
        c.drawCircle(bodyX - 15*u, headY + 7*u + bounce, 4*u, paint)
        c.drawCircle(bodyX + 15*u, headY + 7*u + bounce, 4*u, paint)
        paint.color = ink
        paint.strokeWidth = 2*u
        c.drawArc(RectF(bodyX - 5*u, headY + 3*u + bounce, bodyX + 5*u, headY + 10*u + bounce), 10f, 160f, false, paint)
        paint.color = Color.rgb(255, 175, 103)
        c.drawLine(bodyX - 7*u, bodyY + 13*u, x - 13*u, by - 1*u, paint)
        c.drawLine(bodyX + 7*u, bodyY + 13*u, x + 15*u, by - 1*u, paint)
        paint.strokeWidth = 6*u; paint.strokeCap = Paint.Cap.ROUND
        c.drawLine(bodyX - 12*u, bodyY - 7*u, h1, hy, paint)
        c.drawLine(bodyX + 12*u, bodyY - 7*u, h2, hy, paint)
        paint.color = Color.rgb(255, 211, 151)
        c.drawCircle(h1, hy, 5*u, paint); c.drawCircle(h2, hy, 5*u, paint)
        paint.strokeWidth = 7*u
        c.drawLine(x - 7*u, by - 1*u, x - 12*u, by + 9*u, paint)
        c.drawLine(x + 7*u, by - 1*u, x + 13*u, by + 9*u, paint)
        paint.color = Color.rgb(109, 90, 176)
        c.drawLine(x - 12*u, by + 9*u, x - 18*u, by + 9*u, paint)
        c.drawLine(x + 13*u, by + 9*u, x + 19*u, by + 9*u, paint)
    }

    private fun drawStickman(c: Canvas, x: Float, by: Float, u: Float, lean: Float, state: PullState, elapsed: Long, h1: Float, h2: Float, hy: Float) {
        paint.color = ink; paint.strokeWidth = 3*u; paint.strokeCap = Paint.Cap.ROUND
        val torsoX = x + lean*u*.45f
        val shoulderY = by - 56*u
        val hipY = by - 27*u
        c.drawCircle(torsoX, by - 88*u, 12*u, paint)
        paint.color = Color.rgb(247, 245, 252)
        c.drawCircle(torsoX, by - 88*u, 9*u, paint)
        paint.color = ink
        c.drawLine(torsoX, shoulderY, torsoX, hipY, paint)
        c.drawLine(torsoX, shoulderY, h1, hy, paint)
        c.drawLine(torsoX, shoulderY, h2, hy, paint)
        c.drawCircle(h1, hy, 3*u, paint); c.drawCircle(h2, hy, 3*u, paint)
        c.drawLine(torsoX, hipY, x - 12*u, by - 8*u, paint)
        c.drawLine(x - 12*u, by - 8*u, x - 19*u, by - 8*u, paint)
        c.drawLine(torsoX, hipY, x + 12*u, by - 8*u, paint)
        c.drawLine(x + 12*u, by - 8*u, x + 19*u, by - 8*u, paint)
        if (state == PullState.STRUGGLE) {
            paint.strokeWidth = 1.5f*u
            c.drawLine(torsoX - 9*u, by - 98*u, torsoX - 18*u, by - 105*u, paint)
            c.drawLine(torsoX + 9*u, by - 98*u, torsoX + 18*u, by - 105*u, paint)
        }
    }

    private fun drawPixel(c: Canvas, x: Float, by: Float, u: Float, lean: Float, state: PullState, elapsed: Long, h1: Float, h2: Float, hy: Float) {
        val p = dp(5f) * sizeFactor.coerceIn(.65f, 1.45f)
        val shift = if (state == PullState.PULL || state == PullState.STRUGGLE) p * 2 else 0f
        val px = x + lean*u*.4f + shift
        fun block(color: Int, left: Float, top: Float, width: Float, height: Float) {
            pixelPaint.color = color
            c.drawRect(left, top, left + width, top + height, pixelPaint)
        }
        val skin = Color.rgb(247, 188, 133)
        val shirt = Color.rgb(70, 173, 140)
        val pants = Color.rgb(72, 78, 153)
        val headX = px - p*2
        val headY = by - p*20
        block(skin, headX, headY, p*4, p*4)
        block(Color.rgb(72, 46, 57), headX, headY, p*4, p)
        block(ink, headX + p, headY + p*2, p, p)
        block(ink, headX + p*3, headY + p*2, p, p)
        block(shirt, px - p*2, by - p*15, p*4, p*6)
        block(skin, px - p*5, by - p*16, p*3, p*2)
        block(skin, px + p*2, by - p*16, p*3, p*2)
        block(skin, px - p*4, by - p*16, p*2, p*2)
        block(skin, px + p*2, by - p*16, p*2, p*2)
        block(pants, px - p*2, by - p*9, p*2, p*5)
        block(pants, px, by - p*9, p*2, p*5)
        block(ink, px - p*3, by - p*4, p*3, p)
        block(ink, px, by - p*4, p*3, p)
        if (state == PullState.SUCCESS) {
            block(Color.rgb(255, 215, 75), px - p*6, headY - p*2, p, p)
            block(Color.rgb(255, 215, 75), px + p*5, headY - p*2, p, p)
        }
    }

    fun dispose() {
        closed = true
        animate().cancel()
        invalidate()
    }
}
