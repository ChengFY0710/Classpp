package com.fangyi.classpp.ui.motion

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverscrollPullProgressTest {

    private fun effect() = OffsetOverscrollEffect(
        animationScope = CoroutineScope(Dispatchers.Unconfined),
        animationSpec = OffsetOverscrollEffect.DefaultAnimationSpec,
        maxFraction = OffsetOverscrollEffect.DefaultMaxFraction,
    )

    /** 对某个实例下一次下拉（原始未阻尼拉距 [px]） */
    private fun pull(effect: OffsetOverscrollEffect, px: Float) {
        effect.applyToScroll(Offset(0f, px), NestedScrollSource.UserInput) { Offset.Zero }
    }

    @Test
    fun maxPullYReportsTheActiveInstance() {
        val progress = OverscrollPullProgress()
        val first = effect()
        val second = effect()
        progress.register(first)
        progress.register(second)

        // 未拉动时恒为 0
        assertEquals(0f, progress.maxPullY(), 0.001f)

        // 同一时刻只有一个实例有拉距，取最大值即当前手势的拉距
        pull(first, 120f)
        pull(second, 40f)
        assertEquals(120f, progress.maxPullY(), 0.001f)

        first.reset()
        assertEquals(40f, progress.maxPullY(), 0.001f)

        // 注销后不再参与取最大（切页卸载旧实例后不会残留拉距）
        progress.unregister(second)
        assertEquals(0f, progress.maxPullY(), 0.001f)
    }

    @Test
    fun registerIsIdempotentSoOneUnregisterClearsIt() {
        val progress = OverscrollPullProgress()
        val effect = effect()
        progress.register(effect)
        progress.register(effect)
        pull(effect, 80f)
        assertEquals(80f, progress.maxPullY(), 0.001f)

        progress.unregister(effect)
        assertEquals(0f, progress.maxPullY(), 0.001f)
    }

    @Test
    fun readingTheRegistryIsSnapshotObserved() {
        // 绘制期读者（graphicsLayer 的延迟读取）靠快照读订阅登记表：读空表也必须登记读，
        // 注册/注销本身才能让读者失效、重新读取当册实例。登记表若换回普通集合，读集合
        // 不产生任何读，此断言会失败——切页后第一次下拉「松手后同步」提示不出现即由此而来
        val progress = OverscrollPullProgress()
        var observed = false
        Snapshot.observe(readObserver = { observed = true }) {
            progress.maxPullY()
        }
        assertTrue(observed)
    }
}
