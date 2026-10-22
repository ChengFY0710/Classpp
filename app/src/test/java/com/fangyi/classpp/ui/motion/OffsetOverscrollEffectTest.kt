package com.fangyi.classpp.ui.motion

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

class OffsetOverscrollEffectTest {

    private fun effect(maxDistanceOverridePx: Float? = null) = OffsetOverscrollEffect(
        animationScope = CoroutineScope(Dispatchers.Unconfined),
        animationSpec = OffsetOverscrollEffect.DefaultAnimationSpec,
        maxFraction = 0.65f,
        maxDistanceOverridePx = maxDistanceOverridePx,
    )

    @Test
    fun scrollConsumesIntoRawOffsetAndUpReturnsIt() {
        val effect = effect(maxDistanceOverridePx = 100f)

        val down = effect.applyToScroll(
            delta = Offset(0f, 200f),
            source = NestedScrollSource.UserInput,
        ) { Offset.Zero }
        assertEquals(200f, down.y, 0.001f)
        assertEquals(200f, effect.currentOffset.y, 0.001f)

        val up = effect.applyToScroll(
            delta = Offset(0f, -50f),
            source = NestedScrollSource.UserInput,
        ) { Offset.Zero }
        assertEquals(-50f, up.y, 0.001f)
        assertEquals(150f, effect.currentOffset.y, 0.001f)

        effect.reset()
        assertEquals(Offset.Zero, effect.currentOffset)
    }
}
