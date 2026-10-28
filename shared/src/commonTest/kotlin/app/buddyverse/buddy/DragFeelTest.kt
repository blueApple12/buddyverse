package app.buddyverse.buddy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DragFeelTest {
    /** Strokes of [len] dp back and forth along x, [steps] events each, [ms] apart. Returns the first feel read. */
    private fun rub(len: Float, strokes: Int, ms: Long, steps: Int = 5): DragFeel.Feel? {
        val f = DragFeel()
        var t = 0L
        for (s in 0 until strokes) {
            val dir = if (s % 2 == 0) 1f else -1f
            repeat(steps) { t += ms; f.add(dir * len / steps, .3f, t)?.let { return it } }
        }
        return null
    }

    @Test fun straightDragIsNothing() {
        val f = DragFeel()
        var t = 0L
        repeat(60) { t += 16; assertNull(f.add(4f, 1f, t)) }
    }

    @Test fun smallQuickRubIsATickle() = assertEquals(DragFeel.Feel.Tickle, rub(14f, 6, 16))

    @Test fun bigQuickBackAndForthIsAShake() = assertEquals(DragFeel.Feel.Shake, rub(90f, 6, 16))

    @Test fun slowBigStrokesAreJustDragging() = assertNull(rub(90f, 6, 120))
}
