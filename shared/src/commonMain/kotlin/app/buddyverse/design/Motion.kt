package app.buddyverse.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/** design §5.1 springs and curves. Damping ratio = ζ; stiffness = k. */
object Motion {
    fun <T> snappy(): FiniteAnimationSpec<T> = spring(dampingRatio = .62f, stiffness = 700f)
    fun <T> bouncy(): FiniteAnimationSpec<T> = spring(dampingRatio = .45f, stiffness = 500f)
    fun <T> smooth(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 440f)
    fun <T> sheet(): FiniteAnimationSpec<T> = spring(dampingRatio = .86f, stiffness = 380f)
    fun <T> gentle(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 160f)
    val emphasized = CubicBezierEasing(.22f, 1f, .36f, 1f)
    fun <T> fade(): FiniteAnimationSpec<T> = tween(120, easing = CubicBezierEasing(.25f, .1f, .25f, 1f))
    fun <T> morph(): FiniteAnimationSpec<T> = tween(200, easing = emphasized)
    fun <T> replyIn(): FiniteAnimationSpec<T> = tween(220, easing = emphasized)
}
