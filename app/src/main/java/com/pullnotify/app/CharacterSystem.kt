package com.pullnotify.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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

/** 60 distinct character identities. All are lightweight vector characters, so no sprite downloads are required. */
object CharacterRegistry {
    private val entries = listOf(
        "Mochi Bunny" to "bunny", "Peach Cat" to "cat", "Pudding Bear" to "bear", "Tiny Fox" to "fox",
        "Cloud Puppy" to "puppy", "Panda Bean" to "panda", "Red Panda" to "redpanda", "Baby Dragon" to "dragon",
        "Star Fairy" to "fairy", "Forest Elf" to "elf", "Little Witch" to "witch", "Moon Wizard" to "wizard",
        "Sunny Chick" to "chick", "Duckling" to "duck", "Penguin Pal" to "penguin", "Frog Prince" to "frog",
        "Mint Turtle" to "turtle", "Happy Axolotl" to "axolotl", "Ocean Otter" to "otter", "Sleepy Sloth" to "sloth",
        "Tiny Tiger" to "tiger", "Pocket Lion" to "lion", "Baby Deer" to "deer", "Koala Cub" to "koala",
        "Lambkin" to "lamb", "Hedgehog" to "hedgehog", "Little Mouse" to "mouse", "Raccoon Bandit" to "raccoon",
        "Bee Buddy" to "bee", "Ladybug" to "ladybug", "Butterfly" to "butterfly", "Daisy Sprite" to "flower",
        "Strawberry Kid" to "strawberry", "Peach Sprite" to "peach", "Blueberry Pal" to "blueberry", "Mushroom Folk" to "mushroom",
        "Cocoa Cookie" to "cookie", "Jellybean" to "jelly", "Marshmallow" to "marshmallow", "Cupcake Cutie" to "cupcake",
        "Little Astronaut" to "astronaut", "Space Explorer" to "space", "Cloud Angel" to "angel", "Tiny Devil" to "devil",
        "Mermaid Pal" to "mermaid", "Ocean Sprite" to "ocean", "Pirate Pup" to "pirate", "Knight Cub" to "knight",
        "Royal Prince" to "prince", "Royal Princess" to "princess", "K-pop Star" to "star", "Skater Kid" to "skater",
        "Garden Gnome" to "gnome", "Toy Soldier" to "soldier", "Paper Doll" to "doll", "Pixel Adventurer" to "pixel",
        "Ink Doodle" to "doodle", "Mini Stick Hero" to "stickman", "Chibi Ninja" to "ninja", "Robot Pal" to "robot",
        "Golden Phoenix" to "phoenix", "Rainbow Unicorn" to "unicorn"
    )
    val all: List<CharacterDefinition> = entries.mapIndexed { index, pair ->
        val id = pair.second
        CharacterDefinition(
            id = id,
            displayName = pair.first,
            style = when (id) {
                "pixel" -> "Pixel art"
                "stickman" -> "Hand-drawn line art"
                "robot" -> "Friendly toy mascot"
                else -> "Expressive chibi cartoon"
            },
            previewAsset = "procedural:$id",
            idleAnimation = "${id}_breathe_blink",
            grabAnimation = "${id}_reach_and_grip",
            pullAnimation = "${id}_lean_pull",
            struggleAnimation = "${id}_brace_and_tremble",
            successAnimation = "${id}_hop_celebrate",
            releaseAnimation = "${id}_recoil_wave",
            defaultScale = 0.94f + (index % 4) * 0.04f,
            defaultAnimationSpeed = 0.92f + (index % 3) * 0.08f
        )
    }
    fun find(id: String?): CharacterDefinition = all.firstOrNull { it.id == id } ?: all.first()
}

object CharacterAssetLoader {
    fun validate(definition: CharacterDefinition): Boolean =
        definition.previewAsset.startsWith("procedural:") && definition.id.isNotBlank() &&
            definition.idleAnimation.isNotBlank() && definition.grabAnimation.isNotBlank() &&
            definition.pullAnimation.isNotBlank() && definition.struggleAnimation.isNotBlank() &&
            definition.successAnimation.isNotBlank() && definition.releaseAnimation.isNotBlank()
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

/** Frame-based chibi animation: anticipatory squash, jointed limbs, gripping hands, recoil and celebration. */
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
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pixel = Paint().apply { isAntiAlias = false; isFilterBitmap = false }
    private val ink = Color.rgb(63, 48, 63)
    private val paper = Color.rgb(255, 249, 244)
    private val accent = Color.rgb(255, 135, 151)
    private val skin = Color.rgb(255, 218, 181)
    private val shirt = palette(characterId)
    private var closed = false

    private fun dp(v: Float) = v * density
    private fun smooth(t: Float) = t.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    private fun palette(id: String): Int {
        val colors = intArrayOf(
            Color.rgb(255, 157, 177), Color.rgb(255, 190, 113), Color.rgb(140, 194, 255),
            Color.rgb(160, 213, 164), Color.rgb(193, 164, 244), Color.rgb(255, 215, 125),
            Color.rgb(119, 204, 196), Color.rgb(244, 157, 130), Color.rgb(218, 177, 155),
            Color.rgb(156, 174, 226)
        )
        return colors[(id.hashCode() and Int.MAX_VALUE) % colors.size]
    }

    private fun stateAt(t: Long): PullState {
        val enter = (420 / max(.35f, speed)).toLong()
        val grab = (620 / max(.35f, speed)).toLong()
        val cycle = (1050 / max(.35f, speed)).toLong()
        val pullingEnds = enter + grab + cycle * pullCycles.coerceIn(1, 5)
        return when {
            t < enter -> PullState.ENTER
            t < enter + grab -> PullState.GRAB
            t < pullingEnds -> {
                val local = (t - enter - grab) % cycle
                when {
                    local < cycle * .58f -> PullState.PULL
                    local < cycle * .82f -> PullState.STRUGGLE
                    else -> PullState.RELEASE
                }
            }
            t < pullingEnds + 600 -> PullState.SUCCESS
            t < pullingEnds + 1080 -> PullState.RELEASE
            else -> PullState.IDLE
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (closed || width <= 0 || height <= 0) return
        val elapsed = ((SystemClock.uptimeMillis() - startTime) * speed.coerceIn(.35f, 2.5f)).toLong()
        val state = stateAt(elapsed)
        drawScene(canvas, elapsed, state)
        val total = (420 + 620 + 1050 * pullCycles.coerceIn(1, 5) + 1080) / max(.35f, speed)
        if (elapsed < total) postInvalidateDelayed(16L)
    }

    private fun drawScene(c: Canvas, elapsed: Long, state: PullState) {
        val w = width.toFloat(); val h = height.toFloat()
        val u = dp(1f) * sizeFactor.coerceIn(.65f, 1.45f) * .76f
        val enter = 1f - smooth(elapsed / (420f / max(.35f, speed)))
        val phase = ((elapsed / (1050f / max(.35f, speed))) % 1f)
        val cutout = if (android.os.Build.VERSION.SDK_INT >= 28) rootWindowInsets?.displayCutout?.boundingRects?.firstOrNull() else null
        val exertion = when (state) {
            PullState.PULL -> smooth(phase / .58f)
            PullState.STRUGGLE -> .94f + .04f * sin(elapsed / 25.0).toFloat()
            PullState.RELEASE -> .30f * (1f - smooth(phase))
            PullState.SUCCESS -> 0f
            else -> 0f
        }
        val shake = if (state == PullState.STRUGGLE) dp(2.3f * sin(elapsed / 22.0).toFloat()) else 0f
        val bounce = when (state) {
            PullState.IDLE -> dp(2.5f * sin(elapsed / 250.0).toFloat())
            PullState.ENTER -> dp(7f * sin((1f-enter) * Math.PI).toFloat())
            PullState.SUCCESS -> dp(-7f * sin(elapsed / 55.0).toFloat())
            PullState.PULL -> dp(2f * sin(elapsed / 65.0).toFloat())
            else -> 0f
        }
        // Tiny mascot hangs just below the camera cutout, with little dangling legs.
        val x = (cutout?.centerX()?.toFloat() ?: (w * .5f)) - dp(20f) + dp(30f) * enter + shake
        val floor = dp(166f) + bounce
        val lean = when (state) {
            PullState.PULL -> 18f * exertion
            PullState.STRUGGLE -> 24f
            PullState.RELEASE -> -8f * exertion
            PullState.SUCCESS -> -5f
            else -> 0f
        }
        val headX = x + dp(lean * .48f) * u
        val headY = floor - dp(85f) * u + bounce * .25f
        val shoulderX = x + dp(lean * .7f) * u
        val shoulderY = floor - dp(57f) * u
        val handY = floor - dp(103f - lean * .65f) * u
        val handLX = shoulderX + dp(22f) * u
        val handRX = shoulderX + dp(37f) * u
        // Align the rope with the physical camera cutout when Android exposes it.
        val ropeX = cutout?.centerX()?.toFloat()?.coerceIn(dp(24f), w-dp(24f)) ?: (w * .5f)
        val cardW = min(dp(174f), w * .43f)
        val cardH = dp(49f)
        val cardX = min(w - cardW - dp(8f), w * .57f)
        val travel = when (state) {
            PullState.SUCCESS -> dp(38f)
            PullState.PULL -> dp(27f * exertion)
            PullState.STRUGGLE -> dp(22f + shake)
            PullState.RELEASE -> dp(7f * (1f-exertion))
            else -> 0f
        }
        val cardY = dp(24f) + travel
        p.color = Color.argb(35, 44, 25, 55)
        c.drawRoundRect(cardX, cardY + dp(4f), cardX + cardW, cardY + cardH + dp(4f), dp(16f), dp(16f), p)
        p.color = paper
        c.drawRoundRect(cardX, cardY, cardX + cardW, cardY + cardH, dp(16f), dp(16f), p)
        p.color = shirt
        c.drawRoundRect(cardX + dp(7f), cardY + dp(7f), cardX + dp(27f), cardY + dp(27f), dp(7f), dp(7f), p)
        p.color = Color.WHITE; p.textSize = dp(14f); p.textAlign = Paint.Align.CENTER
        p.typeface = android.graphics.Typeface.DEFAULT_BOLD
        c.drawText("✦", cardX + dp(17f), cardY + dp(22f), p)
        p.textAlign = Paint.Align.LEFT
        p.color = ink; p.textSize = dp(10f)
        c.drawText(if (appLabel.length > 18) appLabel.take(16) + "…" else appLabel, cardX + dp(33f), cardY + dp(18f), p)
        p.color = Color.rgb(125, 111, 126); p.textSize = dp(8f)
        c.drawText(if (state == PullState.SUCCESS) "Yay! ✨" else "A little ping", cardX + dp(33f), cardY + dp(36f), p)

        // Rope follows the character's actual grip, flexing when tension rises.
        p.color = Color.rgb(151, 115, 139); p.style = Paint.Style.STROKE
        p.strokeWidth = dp(if (state == PullState.PULL || state == PullState.STRUGGLE) 2.7f else 1.7f)
        val rope = Path().apply {
            moveTo(ropeX, if (cutout != null) cutout.bottom.toFloat() + dp(2f) else dp(7f))
            val curve = if (state == PullState.PULL || state == PullState.STRUGGLE) dp(2f) else dp(11f)
            cubicTo(ropeX + curve, dp(34f), handLX - dp(4f), handY - dp(28f), handLX, handY)
        }
        c.drawPath(rope, p); p.style = Paint.Style.FILL

        when (characterId) {
            "pixel" -> drawPixel(c, x, floor, u, state, elapsed, handLX, handRX, handY)
            "stickman", "doodle" -> drawLinePal(c, x, floor, u, lean, state, elapsed, handLX, handRX, handY)
            else -> drawChibi(c, x, floor, headX, headY, shoulderX, shoulderY, handLX, handRX, handY, u, lean, state, elapsed)
        }
        if (state == PullState.SUCCESS) drawSparkles(c, headX, headY - dp(24f), elapsed, u)
    }

    private fun drawChibi(c: Canvas, x: Float, floor: Float, hx: Float, hy: Float, sx: Float, sy: Float,
                          h1: Float, h2: Float, handY: Float, u: Float, lean: Float, state: PullState, t: Long) {
        val bob = dp(if (state == PullState.STRUGGLE) 2f * sin(t / 24.0).toFloat() else 0f)
        val bodyTop = floor - dp(55f) * u + bob
        val bodyBottom = floor - dp(19f) * u
        val squish = when (state) {
            PullState.PULL -> 1f + .07f * smooth(((t % 1050) / 610f))
            PullState.STRUGGLE -> .90f
            PullState.SUCCESS -> 1.10f
            else -> 1f
        }
        // Tiny legs are animated separately to give the character weight and foot grip.
        p.color = skin; p.strokeWidth = dp(7f) * u
        val step = if (state == PullState.PULL) dp(4f * sin(t / 90.0).toFloat()) else 0f
        c.drawLine(x - dp(8f)*u, bodyBottom, x - dp(13f)*u + step, floor, p)
        c.drawLine(x + dp(8f)*u, bodyBottom, x + dp(13f)*u - step, floor, p)
        p.color = shirt
        c.drawRoundRect(RectF(sx-dp(18f)*u, bodyTop, sx+dp(18f)*u, bodyBottom), dp(13f)*u, dp(13f)*u, p)
        p.color = Color.argb(35, 70, 40, 60)
        c.drawOval(RectF(sx-dp(13f)*u, bodyTop+dp(4f)*u, sx+dp(13f)*u, bodyTop+dp(12f)*u), p)
        drawOutfitDetails(c, sx, bodyTop, bodyBottom, u, state, t)

        // Head squash/stretch and tilt change with the exertion instead of rigidly sliding.
        val headW = dp(27f)*u
        val headH = dp(27f)*u * if (state == PullState.PULL) .96f else 1f
        c.save()
        c.rotate(if (state == PullState.STRUGGLE) 4f*sin(t/24.0).toFloat() else -lean*.23f, hx, hy)
        p.color = skin
        c.drawOval(RectF(hx-headW, hy-headH, hx+headW, hy+headH), p)
        drawEars(c, hx, hy, u)
        drawUniqueSilhouette(c, hx, hy, u, t)
        drawHairHat(c, hx, hy, u)
        val blink = (t % 3100L) < 135L
        p.color = ink
        if (state == PullState.STRUGGLE) {
            p.strokeWidth = dp(2f)*u
            c.drawLine(hx-dp(13f)*u, hy-dp(5f)*u, hx-dp(7f)*u, hy-dp(3f)*u, p)
            c.drawLine(hx+dp(7f)*u, hy-dp(3f)*u, hx+dp(13f)*u, hy-dp(5f)*u, p)
        } else if (blink) {
            p.strokeWidth = dp(2f)*u
            c.drawLine(hx-dp(12f)*u, hy-dp(2f)*u, hx-dp(7f)*u, hy-dp(2f)*u, p)
            c.drawLine(hx+dp(7f)*u, hy-dp(2f)*u, hx+dp(12f)*u, hy-dp(2f)*u, p)
        } else {
            p.color = Color.WHITE
            c.drawCircle(hx-dp(9f)*u, hy-dp(3f)*u, dp(5.2f)*u, p)
            c.drawCircle(hx+dp(9f)*u, hy-dp(3f)*u, dp(5.2f)*u, p)
            p.color = ink
            val eyeShift = if (state == PullState.GRAB || state == PullState.PULL) dp(1.5f)*u else 0f
            c.drawCircle(hx-dp(8f)*u+eyeShift, hy-dp(2f)*u, dp(2.7f)*u, p)
            c.drawCircle(hx+dp(10f)*u+eyeShift, hy-dp(2f)*u, dp(2.7f)*u, p)
            p.color = Color.WHITE
            c.drawCircle(hx-dp(9f)*u, hy-dp(3.5f)*u, dp(1.1f)*u, p)
            c.drawCircle(hx+dp(9f)*u, hy-dp(3.5f)*u, dp(1.1f)*u, p)
        }
        p.color = Color.rgb(255, 145, 159)
        c.drawOval(RectF(hx-dp(21f)*u, hy+dp(5f)*u, hx-dp(12f)*u, hy+dp(10f)*u), p)
        c.drawOval(RectF(hx+dp(12f)*u, hy+dp(5f)*u, hx+dp(21f)*u, hy+dp(10f)*u), p)
        p.color = ink
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1.8f)*u
        val smile = RectF(hx-dp(5f)*u, hy+dp(2f)*u, hx+dp(5f)*u, hy+dp(if (state == PullState.SUCCESS) 11f else 8f)*u)
        c.drawArc(smile, 10f, if (state == PullState.STRUGGLE) 160f else 145f, false, p)
        p.style = Paint.Style.FILL
        drawFaceAccessory(c, hx, hy, u)
        c.restore()

        // Shoulder joints and elbows bend towards the rope; fists close when gripping.
        p.color = shirt; p.strokeWidth = dp(9f)*u
        val elbowLX = sx - dp(4f)*u
        val elbowRX = sx + dp(22f)*u
        val elbowY = sy + dp(if (state == PullState.GRAB) 7f else 13f)*u
        c.drawLine(sx-dp(11f)*u, sy, elbowLX, elbowY, p)
        c.drawLine(elbowLX, elbowY, h1, handY, p)
        c.drawLine(sx+dp(11f)*u, sy, elbowRX, elbowY-dp(3f)*u, p)
        c.drawLine(elbowRX, elbowY-dp(3f)*u, h2, handY, p)
        p.color = skin
        val grip = if (state == PullState.GRAB || state == PullState.PULL || state == PullState.STRUGGLE) dp(5.2f)*u else dp(4.4f)*u
        c.drawCircle(h1, handY, grip, p); c.drawCircle(h2, handY, grip, p)
        p.color = Color.rgb(255,255,255); p.strokeWidth = dp(1.3f)*u
        c.drawLine(h1-dp(2f)*u, handY-dp(1f)*u, h1+dp(2f)*u, handY+dp(1f)*u, p)
        c.drawLine(h2-dp(2f)*u, handY-dp(1f)*u, h2+dp(2f)*u, handY+dp(1f)*u, p)
        drawShoes(c, x, floor, u, state, t)
    }


    /** Character-specific silhouettes: ears, horns, petals, snouts, wings, fins and food/creature shapes. */
    private fun drawUniqueSilhouette(c: Canvas, x: Float, y: Float, u: Float, t: Long) {
        fun oval(color: Int, l: Float, top: Float, r: Float, bottom: Float) {
            p.color = color; c.drawOval(RectF(l, top, r, bottom), p)
        }
        fun triangle(color: Int, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            p.color = color
            val path = Path().apply { moveTo(x1,y1); lineTo(x2,y2); lineTo(x3,y3); close() }
            c.drawPath(path,p)
        }
        val fur = when (characterId) {
            "panda" -> Color.WHITE
            "redpanda" -> Color.rgb(220,117,73)
            "tiger" -> Color.rgb(255,160,65)
            "lion" -> Color.rgb(234,154,57)
            "frog" -> Color.rgb(122,203,112)
            "axolotl" -> Color.rgb(255,155,190)
            "unicorn" -> Color.rgb(255,230,245)
            else -> shirt
        }
        when (characterId) {
            "cat", "tiger", "fox", "redpanda", "raccoon" -> {
                triangle(fur,x-dp(22f)*u,y-dp(11f)*u,x-dp(19f)*u,y-dp(31f)*u,x-dp(5f)*u,y-dp(18f)*u)
                triangle(fur,x+dp(22f)*u,y-dp(11f)*u,x+dp(19f)*u,y-dp(31f)*u,x+dp(5f)*u,y-dp(18f)*u)
                if (characterId == "fox" || characterId == "redpanda") {
                    triangle(Color.WHITE,x-dp(12f)*u,y+dp(1f)*u,x,y+dp(15f)*u,x+dp(12f)*u,y+dp(1f)*u)
                }
                if (characterId == "tiger") {
                    p.color=ink; p.strokeWidth=dp(2f)*u
                    c.drawLine(x-dp(17f)*u,y-dp(5f)*u,x-dp(12f)*u,y+dp(1f)*u,p)
                    c.drawLine(x+dp(17f)*u,y-dp(5f)*u,x+dp(12f)*u,y+dp(1f)*u,p)
                }
            }
            "bunny", "deer" -> {
                oval(fur,x-dp(19f)*u,y-dp(43f)*u,x-dp(8f)*u,y-dp(12f)*u)
                oval(fur,x+dp(8f)*u,y-dp(43f)*u,x+dp(19f)*u,y-dp(12f)*u)
                oval(Color.rgb(255,151,180),x-dp(16f)*u,y-dp(37f)*u,x-dp(11f)*u,y-dp(17f)*u)
                oval(Color.rgb(255,151,180),x+dp(11f)*u,y-dp(37f)*u,x+dp(16f)*u,y-dp(17f)*u)
                if (characterId == "deer") {
                    p.color=Color.rgb(145,98,67); p.strokeWidth=dp(2f)*u
                    c.drawLine(x-dp(9f)*u,y-dp(22f)*u,x-dp(15f)*u,y-dp(35f)*u,p)
                    c.drawLine(x-dp(15f)*u,y-dp(35f)*u,x-dp(20f)*u,y-dp(34f)*u,p)
                    c.drawLine(x+dp(9f)*u,y-dp(22f)*u,x+dp(15f)*u,y-dp(35f)*u,p)
                    c.drawLine(x+dp(15f)*u,y-dp(35f)*u,x+dp(20f)*u,y-dp(34f)*u,p)
                }
            }
            "bear", "panda", "koala", "puppy" -> {
                oval(fur,x-dp(29f)*u,y-dp(26f)*u,x-dp(12f)*u,y-dp(9f)*u)
                oval(fur,x+dp(12f)*u,y-dp(26f)*u,x+dp(29f)*u,y-dp(9f)*u)
                if (characterId == "panda" || characterId == "koala") {
                    oval(ink,x-dp(25f)*u,y-dp(22f)*u,x-dp(15f)*u,y-dp(12f)*u)
                    oval(ink,x+dp(15f)*u,y-dp(22f)*u,x+dp(25f)*u,y-dp(12f)*u)
                }
                if (characterId == "puppy") oval(Color.rgb(150,91,70),x+dp(12f)*u,y-dp(10f)*u,x+dp(25f)*u,y+dp(12f)*u)
            }
            "frog" -> {
                oval(fur,x-dp(22f)*u,y-dp(29f)*u,x-dp(4f)*u,y-dp(10f)*u)
                oval(fur,x+dp(4f)*u,y-dp(29f)*u,x+dp(22f)*u,y-dp(10f)*u)
                oval(Color.WHITE,x-dp(18f)*u,y-dp(25f)*u,x-dp(8f)*u,y-dp(14f)*u)
                oval(Color.WHITE,x+dp(8f)*u,y-dp(25f)*u,x+dp(18f)*u,y-dp(14f)*u)
            }
            "dragon", "devil" -> {
                triangle(Color.rgb(101,195,132),x-dp(20f)*u,y-dp(14f)*u,x-dp(17f)*u,y-dp(35f)*u,x-dp(5f)*u,y-dp(18f)*u)
                triangle(Color.rgb(101,195,132),x+dp(20f)*u,y-dp(14f)*u,x+dp(17f)*u,y-dp(35f)*u,x+dp(5f)*u,y-dp(18f)*u)
                if (characterId == "dragon") {
                    oval(Color.rgb(101,195,132),x-dp(30f)*u,y-dp(2f)*u,x-dp(20f)*u,y+dp(14f)*u)
                    oval(Color.rgb(101,195,132),x+dp(20f)*u,y-dp(2f)*u,x+dp(30f)*u,y+dp(14f)*u)
                }
            }
            "bee", "butterfly", "fairy", "angel", "phoenix" -> {
                val wing = if (characterId == "bee") Color.rgb(255,237,156) else Color.rgb(220,228,255)
                oval(wing,x-dp(35f)*u,y-dp(12f)*u,x-dp(17f)*u,y+dp(10f)*u)
                oval(wing,x+dp(17f)*u,y-dp(12f)*u,x+dp(35f)*u,y+dp(10f)*u)
                if (characterId == "butterfly") {
                    oval(Color.rgb(255,173,202),x-dp(34f)*u,y-dp(18f)*u,x-dp(19f)*u,y-dp(2f)*u)
                    oval(Color.rgb(255,173,202),x+dp(19f)*u,y-dp(18f)*u,x+dp(34f)*u,y-dp(2f)*u)
                }
            }
            "flower", "strawberry", "peach", "blueberry" -> {
                val petal = if (characterId == "flower") Color.rgb(255,209,96) else Color.rgb(88,165,92)
                if (characterId == "flower") {
                    for (i in 0..5) {
                        val a=i*Math.PI/3.0
                        oval(petal,x+dp((kotlin.math.cos(a)*13).toFloat())*u-dp(7f)*u,y+dp((kotlin.math.sin(a)*13).toFloat())*u-dp(7f)*u,
                            x+dp((kotlin.math.cos(a)*13).toFloat())*u+dp(7f)*u,y+dp((kotlin.math.sin(a)*13).toFloat())*u+dp(7f)*u)
                    }
                    oval(Color.rgb(255,145,174),x-dp(8f)*u,y-dp(8f)*u,x+dp(8f)*u,y+dp(8f)*u)
                } else {
                    oval(petal,x-dp(10f)*u,y-dp(32f)*u,x-dp(1f)*u,y-dp(21f)*u)
                    oval(petal,x+dp(1f)*u,y-dp(34f)*u,x+dp(10f)*u,y-dp(21f)*u)
                }
            }
            "unicorn" -> triangle(Color.WHITE,x-dp(6f)*u,y-dp(20f)*u,x,y-dp(39f)*u,x+dp(6f)*u,y-dp(20f)*u)
            "axolotl" -> {
                for (side in listOf(-1f,1f)) for (i in 0..2) {
                    oval(Color.rgb(255,133,177),x+dp(side*(19f+i*3f))*u-dp(4f)*u,y+dp((-10f+i*8f))*u-dp(4f)*u,
                        x+dp(side*(19f+i*3f))*u+dp(4f)*u,y+dp((-10f+i*8f))*u+dp(4f)*u)
                }
            }
            "astronaut", "space" -> {
                p.color=Color.WHITE; p.style=Paint.Style.STROKE; p.strokeWidth=dp(3f)*u
                c.drawOval(RectF(x-dp(29f)*u,y-dp(31f)*u,x+dp(29f)*u,y+dp(28f)*u),p); p.style=Paint.Style.FILL
                oval(Color.rgb(115,190,227),x-dp(18f)*u,y-dp(15f)*u,x+dp(18f)*u,y+dp(7f)*u)
            }
            "mermaid", "ocean" -> {
                p.color=Color.rgb(85,201,191); p.strokeWidth=dp(3f)*u
                c.drawArc(RectF(x-dp(27f)*u,y-dp(33f)*u,x+dp(27f)*u,y+dp(20f)*u),190f,160f,false,p)
                triangle(Color.rgb(90,197,190),x-dp(17f)*u,y+dp(12f)*u,x-dp(26f)*u,y+dp(25f)*u,x-dp(9f)*u,y+dp(20f)*u)
                triangle(Color.rgb(90,197,190),x+dp(17f)*u,y+dp(12f)*u,x+dp(26f)*u,y+dp(25f)*u,x+dp(9f)*u,y+dp(20f)*u)
            }
            "cookie", "jelly", "marshmallow", "cupcake" -> {
                if (characterId == "cupcake") {
                    triangle(Color.rgb(255,151,181),x-dp(20f)*u,y-dp(12f)*u,x,y-dp(36f)*u,x+dp(20f)*u,y-dp(12f)*u)
                    oval(Color.rgb(255,215,110),x-dp(4f)*u,y-dp(40f)*u,x+dp(4f)*u,y-dp(32f)*u)
                } else if (characterId == "cookie") {
                    oval(Color.rgb(255,225,160),x-dp(22f)*u,y-dp(12f)*u,x-dp(17f)*u,y-dp(7f)*u)
                    oval(Color.rgb(255,225,160),x+dp(15f)*u,y+dp(7f)*u,x+dp(21f)*u,y+dp(13f)*u)
                } else if (characterId == "marshmallow") {
                    p.color=Color.WHITE; c.drawRoundRect(RectF(x-dp(22f)*u,y-dp(26f)*u,x+dp(22f)*u,y+dp(20f)*u),dp(13f)*u,dp(13f)*u,p)
                }
            }
            "knight", "pirate", "soldier" -> {
                p.color=if(characterId=="pirate") Color.rgb(65,48,63) else Color.rgb(172,181,197)
                c.drawRoundRect(RectF(x-dp(25f)*u,y-dp(31f)*u,x+dp(25f)*u,y-dp(14f)*u),dp(5f)*u,dp(5f)*u,p)
                if(characterId=="pirate") {
                    p.color=Color.WHITE; c.drawCircle(x,y-dp(23f)*u,dp(4f)*u,p)
                } else {
                    p.color=Color.rgb(255,210,92); c.drawCircle(x,y-dp(22f)*u,dp(3f)*u,p)
                }
            }
            "robot" -> {
                p.color=Color.rgb(192,202,219)
                c.drawRoundRect(RectF(x-dp(24f)*u,y-dp(25f)*u,x+dp(24f)*u,y+dp(20f)*u),dp(8f)*u,dp(8f)*u,p)
                p.color=shirt; c.drawCircle(x-dp(9f)*u,y-dp(3f)*u,dp(4f)*u,p); c.drawCircle(x+dp(9f)*u,y-dp(3f)*u,dp(4f)*u,p)
            }
            else -> Unit
        }
    }

    /** Outfit-specific marks keep people, fantasy mascots, snacks and creatures visually distinct. */
    private fun drawOutfitDetails(c: Canvas, x: Float, top: Float, bottom: Float, u: Float, state: PullState, t: Long) {
        when (characterId) {
            "astronaut", "space" -> {
                p.color=Color.rgb(112,181,222)
                c.drawCircle(x,top+dp(15f)*u,dp(5f)*u,p)
                p.color=Color.WHITE; c.drawRoundRect(RectF(x-dp(8f)*u,top+dp(25f)*u,x+dp(8f)*u,top+dp(31f)*u),dp(2f)*u,dp(2f)*u,p)
            }
            "princess", "prince", "fairy", "angel", "unicorn" -> {
                p.color=Color.rgb(255,221,118)
                val gem=Path().apply { moveTo(x,top+dp(8f)*u); lineTo(x+dp(5f)*u,top+dp(14f)*u); lineTo(x,top+dp(20f)*u); lineTo(x-dp(5f)*u,top+dp(14f)*u); close() }
                c.drawPath(gem,p)
            }
            "ninja", "witch", "wizard", "pirate" -> {
                p.color=ink; c.drawRoundRect(RectF(x-dp(13f)*u,top+dp(5f)*u,x+dp(13f)*u,top+dp(11f)*u),dp(3f)*u,dp(3f)*u,p)
            }
            "bee" -> {
                p.color=Color.rgb(255,225,94); p.strokeWidth=dp(3f)*u
                c.drawLine(x-dp(12f)*u,top+dp(16f)*u,x+dp(12f)*u,top+dp(16f)*u,p)
                c.drawLine(x-dp(12f)*u,top+dp(25f)*u,x+dp(12f)*u,top+dp(25f)*u,p)
            }
            "strawberry", "peach", "blueberry", "cookie", "cupcake" -> {
                p.color=Color.WHITE
                for (i in -1..1) c.drawCircle(x+dp(i*6f)*u,top+dp(18f)*u,dp(1.6f)*u,p)
            }
            "frog", "turtle", "dragon", "axolotl" -> {
                p.color=Color.argb(130,255,255,255)
                c.drawOval(RectF(x-dp(10f)*u,top+dp(8f)*u,x+dp(10f)*u,top+dp(27f)*u),p)
            }
            "cat", "bunny", "fox", "puppy", "panda", "bear", "koala" -> {
                p.color=Color.argb(95,255,255,255)
                c.drawOval(RectF(x-dp(10f)*u,top+dp(8f)*u,x+dp(10f)*u,top+dp(20f)*u),p)
            }
            else -> {
                p.color=Color.argb(150,255,255,255)
                c.drawCircle(x,top+dp(16f)*u,dp(3f)*u,p)
            }
        }
    }

    private fun drawEars(c: Canvas, x: Float, y: Float, u: Float) {
        val id = characterId
        p.color = skin
        when (id) {
            "bunny", "fox", "cat", "mouse", "deer", "tiger", "lion", "puppy", "bear", "koala", "panda", "redpanda", "raccoon" -> {
                val earHeight = if (id == "bunny" || id == "fox" || id == "deer") 19f else 10f
                c.drawOval(RectF(x-dp(20f)*u, y-dp(24f+earHeight)*u, x-dp(8f)*u, y-dp(7f)*u), p)
                c.drawOval(RectF(x+dp(8f)*u, y-dp(24f+earHeight)*u, x+dp(20f)*u, y-dp(7f)*u), p)
                p.color = Color.rgb(255, 155, 176)
                c.drawOval(RectF(x-dp(17f)*u, y-dp(21f+earHeight)*u, x-dp(11f)*u, y-dp(10f)*u), p)
                c.drawOval(RectF(x+dp(11f)*u, y-dp(21f+earHeight)*u, x+dp(17f)*u, y-dp(10f)*u), p)
            }
            "dragon", "devil" -> {
                val path = Path().apply { moveTo(x-dp(20f)*u,y-dp(12f)*u); lineTo(x-dp(17f)*u,y-dp(27f)*u); lineTo(x-dp(7f)*u,y-dp(15f)*u); close() }
                c.drawPath(path,p)
                val path2 = Path().apply { moveTo(x+dp(20f)*u,y-dp(12f)*u); lineTo(x+dp(17f)*u,y-dp(27f)*u); lineTo(x+dp(7f)*u,y-dp(15f)*u); close() }
                c.drawPath(path2,p)
            }
            else -> Unit
        }
    }

    private fun drawHairHat(c: Canvas, x: Float, y: Float, u: Float) {
        p.color = when (characterId) {
            "astronaut", "space" -> Color.rgb(226, 231, 243)
            "princess", "fairy", "mermaid", "unicorn" -> Color.rgb(117, 75, 121)
            "witch", "wizard" -> Color.rgb(77, 61, 103)
            "pirate", "knight", "soldier" -> Color.rgb(65, 68, 87)
            "mushroom" -> Color.rgb(225, 92, 113)
            "strawberry" -> Color.rgb(201, 59, 76)
            "phoenix" -> Color.rgb(238, 119, 62)
            else -> Color.rgb(91, 62, 70)
        }
        when (characterId) {
            "astronaut", "space" -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = dp(3f)*u
                c.drawOval(RectF(x-dp(25f)*u,y-dp(26f)*u,x+dp(25f)*u,y+dp(23f)*u),p); p.style = Paint.Style.FILL
            }
            "witch", "wizard", "gnome" -> {
                val hat = Path().apply { moveTo(x-dp(21f)*u,y-dp(13f)*u); lineTo(x-dp(3f)*u,y-dp(39f)*u); lineTo(x+dp(13f)*u,y-dp(12f)*u); close() }
                c.drawPath(hat,p); c.drawOval(RectF(x-dp(22f)*u,y-dp(16f)*u,x+dp(18f)*u,y-dp(10f)*u),p)
            }
            "princess", "fairy", "mermaid", "unicorn" -> {
                c.drawArc(RectF(x-dp(25f)*u,y-dp(29f)*u,x+dp(25f)*u,y+dp(18f)*u),180f,180f,false,p)
                p.color = Color.rgb(255,211,108)
                val crown = Path().apply { moveTo(x-dp(13f)*u,y-dp(18f)*u); lineTo(x-dp(12f)*u,y-dp(29f)*u); lineTo(x-dp(4f)*u,y-dp(21f)*u); lineTo(x,y-dp(31f)*u); lineTo(x+dp(5f)*u,y-dp(21f)*u); lineTo(x+dp(13f)*u,y-dp(28f)*u); lineTo(x+dp(13f)*u,y-dp(17f)*u); close() }
                c.drawPath(crown,p)
            }
            "strawberry", "mushroom" -> {
                p.color = if (characterId == "mushroom") Color.rgb(229,85,102) else Color.rgb(72,154,88)
                c.drawOval(RectF(x-dp(25f)*u,y-dp(34f)*u,x+dp(25f)*u,y-dp(9f)*u),p)
                if (characterId == "mushroom") {
                    p.color = Color.WHITE
                    c.drawCircle(x-dp(10f)*u,y-dp(23f)*u,dp(3f)*u,p); c.drawCircle(x+dp(8f)*u,y-dp(25f)*u,dp(3f)*u,p)
                }
            }
            "robot" -> {
                p.color = Color.rgb(187,195,209)
                c.drawRoundRect(RectF(x-dp(25f)*u,y-dp(27f)*u,x+dp(25f)*u,y-dp(9f)*u),dp(8f)*u,dp(8f)*u,p)
                p.color = shirt
                c.drawCircle(x,y-dp(29f)*u,dp(3f)*u,p)
            }
            else -> {
                c.drawArc(RectF(x-dp(25f)*u,y-dp(29f)*u,x+dp(25f)*u,y+dp(17f)*u),180f,180f,false,p)
            }
        }
    }

    private fun drawFaceAccessory(c: Canvas, x: Float, y: Float, u: Float) {
        when (characterId) {
            "astronaut", "space" -> {
                p.color = Color.argb(90, 92, 156, 205)
                c.drawOval(RectF(x-dp(18f)*u,y-dp(16f)*u,x+dp(18f)*u,y+dp(8f)*u),p)
            }
            "knight", "soldier" -> {
                p.color = Color.rgb(176,184,198)
                c.drawRoundRect(RectF(x-dp(24f)*u,y-dp(8f)*u,x+dp(24f)*u,y-dp(1f)*u),dp(2f)*u,dp(2f)*u,p)
            }
            "pirate" -> {
                p.color = ink; p.strokeWidth = dp(2f)*u
                c.drawLine(x-dp(15f)*u,y-dp(5f)*u,x-dp(4f)*u,y-dp(3f)*u,p)
            }
            else -> Unit
        }
    }

    private fun drawShoes(c: Canvas, x: Float, floor: Float, u: Float, state: PullState, t: Long) {
        val hop = if (state == PullState.SUCCESS) dp(6f * kotlin.math.abs(sin(t/55.0).toFloat())) else 0f
        p.color = if (characterId == "knight" || characterId == "robot") Color.rgb(107,111,131) else Color.rgb(111,75,87)
        c.drawOval(RectF(x-dp(22f)*u, floor-dp(4f)*u-hop, x-dp(4f)*u, floor+dp(2f)*u-hop),p)
        c.drawOval(RectF(x+dp(4f)*u, floor-dp(4f)*u+hop, x+dp(22f)*u, floor+dp(2f)*u+hop),p)
    }

    private fun drawLinePal(c: Canvas, x: Float, floor: Float, u: Float, lean: Float, state: PullState, t: Long, h1: Float, h2: Float, hy: Float) {
        val torsoX = x + dp(lean*.5f)*u
        val headY = floor-dp(82f)*u
        p.color = shirt; p.strokeWidth = dp(4f)*u
        c.drawCircle(torsoX,headY,dp(13f)*u,p)
        p.color = ink; p.strokeWidth = dp(4f)*u
        c.drawLine(torsoX,headY+dp(13f)*u,torsoX,floor-dp(25f)*u,p)
        c.drawLine(torsoX,headY+dp(20f)*u,h1,hy,p)
        c.drawLine(torsoX,headY+dp(20f)*u,h2,hy,p)
        c.drawLine(torsoX,floor-dp(25f)*u,x-dp(14f)*u,floor-dp(2f)*u,p)
        c.drawLine(torsoX,floor-dp(25f)*u,x+dp(15f)*u,floor-dp(2f)*u,p)
        p.color = skin; c.drawCircle(h1,hy,dp(4f)*u,p); c.drawCircle(h2,hy,dp(4f)*u,p)
        p.color = ink; p.textSize = dp(20f)*u
        c.drawText(if (state == PullState.SUCCESS) "⌣" else "•",torsoX-dp(4f)*u,headY+dp(4f)*u,p)
    }

    private fun drawPixel(c: Canvas, x: Float, floor: Float, u: Float, state: PullState, t: Long, h1: Float, h2: Float, hy: Float) {
        val q = dp(5f)*u
        fun block(color: Int, left: Float, top: Float, ww: Float, hh: Float) {
            pixel.color = color; c.drawRect(left,top,left+ww,top+hh,pixel)
        }
        val px=x+if(state==PullState.PULL) dp(5f)*u else 0f
        block(skin,px-q*2,floor-q*19,q*4,q*4)
        block(Color.rgb(76,49,58),px-q*2,floor-q*19,q*4,q)
        block(ink,px-q,floor-q*17,q,q); block(ink,px+q,floor-q*17,q,q)
        block(shirt,px-q*2,floor-q*15,q*4,q*6)
        block(skin,px-q*4,floor-q*15,q*2,q*2); block(skin,px+q*2,floor-q*15,q*2,q*2)
        block(skin,px-q*3,floor-q*9,q*2,q*4); block(skin,px+q,floor-q*9,q*2,q*4)
        block(ink,px-q*4,floor-q*5,q*3,q); block(ink,px+q,floor-q*5,q*3,q)
    }

    private fun drawSparkles(c: Canvas, x: Float, y: Float, t: Long, u: Float) {
        p.color = Color.rgb(255,190,74)
        for (i in 0..4) {
            val a = i * 1.256f + t / 180f
            val sx = x + dp(30f)*u*kotlin.math.cos(a.toDouble()).toFloat()
            val sy = y + dp(16f)*u*kotlin.math.sin(a.toDouble()).toFloat()
            val r = dp(if (i%2==0) 3f else 2f)*u
            c.drawLine(sx-r*2,sy,sx+r*2,sy,p); c.drawLine(sx,sy-r*2,sx,sy+r*2,p)
        }
    }

    fun dispose() { closed = true; animate().cancel(); invalidate() }
}
