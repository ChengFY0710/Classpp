package com.fangyi.classpp.ui.navigation

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * TabPagerState 的纯状态语义：构造挂载、Saver 往返（旋转/进程重建）、同 tab 幂等早退。
 *
 * 动画路径（selectTab 的 snapTo/animateTo、reRecordParkedTabs 的 withFrameNanos）依赖
 * MonotonicFrameClock，纯 JVM 单测无帧时钟、不可达（scope 也只在组合内经
 * rememberTabPagerState 注入），故不在本测试覆盖范围。
 */
class TabPagerStateTest {

    @Test
    fun constructorMountsInitialTab() {
        val state = TabPagerState(AppTab.Timetable)

        assertEquals(AppTab.Timetable, state.selectedTab)
        assertNotNull(state.transitionFor(AppTab.Timetable))
        assertNull(state.transitionFor(AppTab.Todo))
    }

    @Test
    fun saverRoundTripKeepsSelectedTabAsMountBase() {
        val state = TabPagerState(AppTab.Agenda)

        val saved = with(TabPagerState.Saver) { SaverScope { true }.save(state) }
        val restored = TabPagerState.Saver.restore(saved!!)!!

        // 挂载集合是派生态、不持久化：恢复以选中页为初始页重建，切历史页重新走滑入
        assertEquals(AppTab.Agenda, restored.selectedTab)
        assertNotNull(restored.transitionFor(AppTab.Agenda))
        assertNull(restored.transitionFor(AppTab.Timetable))
    }

    @Test
    fun selectSameTabIsNoOpBeforeTouchingScope() {
        val state = TabPagerState(AppTab.Todo)

        // 同 tab 早退先于任何 scope 使用：未经 rememberTabPagerState 挂载（scope 为 null）
        // 也不应抛错；异 tab 路径需要动画作用域，纯 JVM 不可达
        state.selectTab(AppTab.Todo)

        assertEquals(AppTab.Todo, state.selectedTab)
        assertSame(state.transitionFor(AppTab.Todo), state.transitionFor(AppTab.Todo))
    }
}
