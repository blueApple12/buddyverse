package app.buddyverse.buddy

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Matrix
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The 8 body shapes (Codync's exact geometry in a 1000×1000 box, design §4.1) with each one's face anchor. */
enum class BuddyShape(val faceX: Float, val faceY: Float) {
    Blob(.50f, .46f), Pebble(.50f, .50f), Squircle(.50f, .46f), Tablet(.50f, .40f),
    Wedge(.50f, .62f), Hex(.50f, .48f), Cloud(.50f, .58f), Teardrop(.50f, .62f);

    companion object {
        /** Hermes Bot Mode shape names (`ui_meta['hermes-bots'].shape`) and our own names. */
        fun parse(raw: String?, seed: String): BuddyShape = when (raw?.substringBefore(':')?.lowercase()) {
            "blob", "blobatar" -> Blob
            "circle", "pebble" -> Pebble
            "squircle" -> Squircle
            "pill", "tablet" -> Tablet
            "triangle", "wedge" -> Wedge
            "hexagon", "hex" -> Hex
            "cloud" -> Cloud
            "drop", "teardrop" -> Teardrop
            else -> entries[abs(seed.hashCode()) % entries.size]
        }
    }
}

object BuddyColors {
    val palette = listOf(
        0xFF2B2B2B, 0xFF936439, 0xFFFF263C, 0xFFFF6700, 0xFFFF9800, 0xFF00C972,
        0xFF00BCA6, 0xFF1084FE, 0xFF9159FE, 0xFFFF309B, 0xFF777777, 0xFFF1EFE9,
    ).map { Color(it) }
    val names = listOf("black", "brown", "red", "orange", "yellow", "green", "cyan", "blue", "violet", "magenta", "gray", "cream")

    fun forName(seed: String): Color = palette[2 + abs(seed.hashCode()) % 9]

    /** `#rgb`, `#rrggbb`, `hsl(h s% l%)` / `hsl(h, s%, l%)` or a palette name; null if unreadable. */
    fun parse(css: String?): Color? {
        val s = css?.trim()?.lowercase() ?: return null
        names.indexOf(s).takeIf { it >= 0 }?.let { return palette[it] }
        if (s.startsWith("#")) {
            val h = s.drop(1)
            val full = when (h.length) { 3 -> h.map { "$it$it" }.joinToString(""); 6 -> h; 8 -> h.take(6); else -> return null }
            return full.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
        }
        if (s.startsWith("hsl")) {
            val nums = Regex("[-0-9.]+").findAll(s.substringAfter('(')).map { it.value.toFloat() }.toList()
            if (nums.size < 3) return null
            return hsl(nums[0], nums[1] / 100f, nums[2] / 100f)
        }
        return null
    }

    private fun hsl(h: Float, s: Float, l: Float): Color {
        val c = (1 - abs(2 * l - 1)) * s
        val hp = ((h % 360 + 360) % 360) / 60f
        val x = c * (1 - abs(hp % 2 - 1))
        val (r, g, b) = when (hp.toInt()) { 0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x); 3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x) }
        val m = l - c / 2
        return Color(r + m, g + m, b + m)
    }

    /** Dark ink eyes on the light bodies (cream, yellow); white elsewhere. */
    fun eyeOn(body: Color): Color = if (body.luminance() > .6f) Color(0xFF1C1A1F) else Color.White

    fun Color.luminance(): Float = .2126f * red + .7152f * green + .0722f * blue

    fun desaturate(c: Color, amount: Float): Color {
        if (amount <= 0f) return c
        val g = c.luminance()
        return lerp(c, Color(g, g, g, c.alpha), amount)
    }

    fun mix(a: Color, b: Color, k: Float) = lerp(a, b, k)
}

object BuddyPaths {
    private val cache = HashMap<BuddyShape, Path>()

    /** The body outline in the 1000 box, built once per shape. */
    fun unit(shape: BuddyShape): Path = cache.getOrPut(shape) { build(shape) }

    /** Body path scaled into a [size]px square. */
    fun scaled(shape: BuddyShape, size: Float): Path {
        val p = Path()
        p.addPath(unit(shape))
        p.transform(Matrix().apply { scale(size / 1000f, size / 1000f) })
        return p
    }

    private fun build(shape: BuddyShape): Path = Path().apply {
        fillType = PathFillType.NonZero
        when (shape) {
            BuddyShape.Pebble -> addOval(Rect(0f, 100f, 1000f, 900f))
            BuddyShape.Squircle -> addRoundRect(RoundRect(40f, 40f, 960f, 960f, CornerRadius(300f)))
            BuddyShape.Tablet -> addRoundRect(RoundRect(140f, 0f, 860f, 1000f, CornerRadius(220f)))
            BuddyShape.Wedge -> { moveTo(500f, 40f); quadraticTo(900f, 400f, 980f, 860f); quadraticTo(500f, 1040f, 20f, 860f); quadraticTo(100f, 400f, 500f, 40f); close() }
            BuddyShape.Hex -> {
                // Pointy-top hexagon R 490 grown by a 40 round join (= Codync's 80-wide round stroke union).
                for (i in 0 until 6) {
                    val a = (i * 60 - 90).toFloat()
                    val vx = 500f + cos(a * PI.toFloat() / 180f) * 490f
                    val vy = 500f + sin(a * PI.toFloat() / 180f) * 490f
                    arcTo(Rect(Offset(vx, vy), 40f), a - 30f, 60f, forceMoveTo = i == 0)
                }
                close()
            }
            BuddyShape.Cloud -> {
                addOval(Rect(0f, 300f, 550f, 850f)); addOval(Rect(450f, 300f, 1000f, 850f)); addOval(Rect(180f, 80f, 820f, 720f))
                addRoundRect(RoundRect(100f, 500f, 900f, 850f, CornerRadius(150f)))
            }
            BuddyShape.Teardrop -> {
                moveTo(500f, 0f); cubicTo(620f, 200f, 940f, 380f, 940f, 620f)
                arcTo(Rect(60f, 180f, 940f, 1060f), 0f, 180f, false)
                cubicTo(60f, 380f, 380f, 200f, 500f, 0f); close()
            }
            BuddyShape.Blob -> {
                for (i in 0..64) {
                    val a = i / 64.0 * 2 * PI
                    val r = .46 + .035 * sin(a * 3 + .6)
                    val x = (500 + cos(a) * 1000 * r).toFloat(); val y = (500 + sin(a) * 1000 * r).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
        }
    }

    /** Lowest body y in the 1000 box (the ground line for squash pivots and the shadow). */
    fun groundY(shape: BuddyShape): Float = when (shape) {
        BuddyShape.Pebble -> 900f; BuddyShape.Squircle -> 960f; BuddyShape.Cloud -> 850f; BuddyShape.Blob -> 960f
        BuddyShape.Wedge -> 995f; BuddyShape.Hex -> 1000f; else -> 1000f
    }
}

/** Stable seed per bot id (xorshift needs a non-zero int). */
fun seedOf(id: String): Int = id.hashCode().let { if (it == 0) 7 else it }

internal fun Float.r2() = (this * 100).roundToInt() / 100f
