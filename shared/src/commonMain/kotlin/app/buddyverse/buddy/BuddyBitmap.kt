package app.buddyverse.buddy

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * A still buddy for places that can't animate (the phone notification's large icon, design §3.11): the same draw code as
 * [Buddy], one fixed face. Needs you = wide round eyes, error = sad and greyed, anything else = happy.
 */
fun buddyBitmap(spec: BuddySpec, mood: Mood, px: Int): ImageBitmap {
    val bmp = ImageBitmap(px, px)
    val w = px.toFloat()
    val pose = stillPose(mood)
    val c = BuddyColors.desaturate(spec.color, pose.desaturate)
    val brush = Brush.linearGradient(listOf(BuddyColors.mix(c, Color.White, .35f), c, BuddyColors.mix(c, Color.Black, .25f)), Offset.Zero, Offset(w, w))
    val rim = with(BuddyColors) { spec.color.luminance() } < .22f
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(w, w)) {
        drawBuddy(spec, pose, BuddyBrain(seedOf(spec.id)), BuddyPaths.scaled(spec.shape, w), brush, BuddyPaths.groundY(spec.shape) / 1000f * w,
            BuddyColors.eyeOn(spec.color), rim, Path(), Path(), 1f)
    }
    return bmp
}

internal fun stillPose(mood: Mood): Pose = Pose().apply {
    val e = when (mood) { Mood.NeedsYou -> Expr.Surprised; Mood.Error -> Expr.Sad; else -> Expr.Happy }
    eyeOpenL = e.open; eyeOpenR = e.open * e.winkRight
    lidTop = e.lidTop; lidBottom = e.lidBottom; lidTilt = e.tilt
    eyeScale = e.eyeScale * (if (mood == Mood.NeedsYou) 1.15f else 1f); eyeRound = e.round
    mouthOpen = e.mouth; smile = e.smile; cheeks = e.cheeks
    desaturate = if (mood == Mood.Error) .35f else 0f
}
