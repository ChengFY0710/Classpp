package com.fangyi.classpp.ui.motion

import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖层视差判定与冻结帧重放判定（纯 JVM，不依赖 Compose 运行时）。
 *
 * 关键不变量：拿不准就回退实时绘制——尺寸无效、未录过帧、键或尺寸不匹配一律不重放；
 * 唯一的例外是无元数据的旧顶栏用法（每帧录制，尺寸有效即可重放）。
 */
class TransitionFreezeTest {

    @Test
    fun overlayProgressAtOrBeyondEndpointsIsNotTransforming() {
        assertFalse(isOverlayTransforming(0f))
        assertFalse(isOverlayTransforming(1f))
        assertFalse(isOverlayTransforming(-0.1f))
        assertFalse(isOverlayTransforming(1.1f))
    }

    @Test
    fun overlayProgressInsideOpenIntervalIsTransforming() {
        assertTrue(isOverlayTransforming(0.001f))
        assertTrue(isOverlayTransforming(0.5f))
        assertTrue(isOverlayTransforming(0.999f))
    }

    @Test
    fun invalidLayerSizeNeverReplays() {
        val frame = FreezeFrame().apply {
            hasFrame = true
            key = "k"
            size = IntSize(100, 80)
        }
        assertFalse(shouldReplayFreeze(layerWidth = 0, layerHeight = 80, frame = frame, key = "k"))
        assertFalse(shouldReplayFreeze(layerWidth = 100, layerHeight = 0, frame = frame, key = "k"))
        assertFalse(shouldReplayFreeze(layerWidth = 0, layerHeight = 0, frame = null, key = Unit))
    }

    @Test
    fun frameLessFreezeReplaysWheneverLayerHasSize() {
        // 顶栏旧行为：不设键、每帧录制，尺寸有效即可重放
        assertTrue(shouldReplayFreeze(layerWidth = 100, layerHeight = 80, frame = null, key = Unit))
    }

    @Test
    fun missingFrameMetadataFallsBackToLiveDraw() {
        assertFalse(shouldReplayFreeze(layerWidth = 100, layerHeight = 80, frame = FreezeFrame(), key = "k"))
    }

    @Test
    fun keyOrSizeMismatchFallsBackToLiveDraw() {
        val frame = FreezeFrame().apply {
            hasFrame = true
            key = "k"
            size = IntSize(100, 80)
        }
        assertFalse(shouldReplayFreeze(layerWidth = 100, layerHeight = 80, frame = frame, key = "other"))
        assertFalse(shouldReplayFreeze(layerWidth = 101, layerHeight = 80, frame = frame, key = "k"))
        assertFalse(shouldReplayFreeze(layerWidth = 100, layerHeight = 81, frame = frame, key = "k"))
    }

    @Test
    fun matchingKeyAndSizeReplays() {
        val frame = FreezeFrame().apply {
            hasFrame = true
            key = "k"
            size = IntSize(100, 80)
        }
        assertTrue(shouldReplayFreeze(layerWidth = 100, layerHeight = 80, frame = frame, key = "k"))
    }
}
