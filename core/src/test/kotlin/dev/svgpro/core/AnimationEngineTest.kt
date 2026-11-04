package dev.svgpro.core

import dev.svgpro.core.svg.Animation
import dev.svgpro.core.svg.AnimationKind
import dev.svgpro.core.svg.CalcMode
import dev.svgpro.core.svg.computeValue
import dev.svgpro.core.svg.AnimValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AnimationEngineTest {

    @Test
    fun byRelativeAnimationIsApplied() {
        // from="10" by="5" 線形補間: t=0.5 → 10 + 5*0.5 = 12.5
        val anim = Animation(
            kind = AnimationKind.ANIMATE,
            attributeName = "x",
            from = "10",
            by = "5",
            dur = "1s",
            calcMode = CalcMode.LINEAR,
        )
        val result = computeValue(anim, 0.5)
        assertNotNull(result)
        val (_, value) = result!!
        assertEquals(AnimValue.Number(12.5), value)
    }

    @Test
    fun byRelativeAnimationDefaultsToFromWhenTimeZero() {
        val anim = Animation(
            kind = AnimationKind.ANIMATE,
            attributeName = "x",
            from = "10",
            by = "5",
            dur = "1s",
            calcMode = CalcMode.LINEAR,
        )
        val result = computeValue(anim, 0.0)
        assertNotNull(result)
        val (_, value) = result!!
        assertEquals(AnimValue.Number(10.0), value)
    }
}
