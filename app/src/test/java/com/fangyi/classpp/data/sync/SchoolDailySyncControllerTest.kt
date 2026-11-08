package com.fangyi.classpp.data.sync

import android.content.Context
import android.app.PendingIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每日同步通用控制器的 JVM 可测部分：锚点换轨的「先取消旧锚点」语义
 * （换校后旧校闹钟不得残留）与注册表按学校路由锚点工厂。
 *
 * `scheduleNext` 的 AlarmManager 交互（set/cancel 系统服务）与 PendingIntent 构造
 * 依赖 Android 运行时，不在纯 JVM 覆盖；控制器的换锚决策经 [SyncAlarmAnchorSwap]
 * 与 [SchoolRegistry.dailySyncFireIntent] 两块可测面间接锁定。
 */
class SchoolDailySyncControllerTest {

    // ---------- 锚点换轨 ----------

    @Test
    fun `first swap does not cancel`() {
        val swap = SyncAlarmAnchorSwap<String>()
        val cancelled = mutableListOf<String>()
        val made = swap.next(cancelLast = cancelled::add) { "A1" }
        assertEquals("A1", made)
        assertTrue(cancelled.isEmpty())
    }

    @Test
    fun `swap cancels previous anchor before making the new one`() {
        val swap = SyncAlarmAnchorSwap<String>()
        val events = mutableListOf<String>()
        swap.next(cancelLast = { events += "cancel:" + it }) { events += "make:A1"; "A1" }

        val second = swap.next(cancelLast = { events += "cancel:" + it }) { events += "make:A2"; "A2" }

        assertEquals("A2", second)
        // 顺序不变量：先取消旧锚点、再构造新锚点
        assertEquals(listOf("make:A1", "cancel:A1", "make:A2"), events)
    }

    @Test
    fun `repeated swaps keep cancelling the latest anchor`() {
        val swap = SyncAlarmAnchorSwap<String>()
        val cancelled = mutableListOf<String>()
        var round = 0
        repeat(3) {
            round++
            swap.next(cancelLast = cancelled::add) { "A" + round }
        }
        // 第二、三次各取消上一锚点；首次无可取消
        assertEquals(listOf("A1", "A2"), cancelled)
    }

    // ---------- 注册表锚点路由 ----------

    @Test
    fun `registry routes fire intent factory by school`() {
        val xmuFactory = notInvokedFactory()
        val anchored = FakeProvider(schoolSpec("xmu"), factory = xmuFactory)
        val plain = FakeProvider(schoolSpec("fzu"))
        val registry = buildRegistry(listOf(anchored, plain))

        // 按显式 id 路由：工厂引用原样透传（不触发构造）
        assertSame(xmuFactory, registry.dailySyncFireIntent("xmu"))
        assertNull(registry.dailySyncFireIntent("fzu"))

        // 默认路由 = 活跃学校（注册首项）
        assertSame(xmuFactory, registry.dailySyncFireIntent())
    }

    @Test
    fun `provider without anchor reports null so controller skips scheduling`() {
        val registry = buildRegistry(listOf(FakeProvider(schoolSpec("fzu"))))
        // 控制器 scheduleNext 的 skip 判定：锚点为 null 即不参与每日同步
        assertNull(registry.dailySyncFireIntent())
    }

    @Test
    fun `xmu provider declares its frozen alarm anchor`() {
        val provider = com.fangyi.classpp.schools.xmu.XmuSchoolProvider()
        assertNotNull(provider.dailySyncFireIntent)
    }

    // ---------- 工具 ----------

    /** 工厂在 JVM 侧不被调用（PendingIntent 无法脱离 Android 运行时构造） */
    private fun notInvokedFactory(): (Context) -> PendingIntent =
        { error("fire intent factory must not be invoked in JVM tests") }

    private fun buildRegistry(providers: List<SchoolProvider>): SchoolRegistry = SchoolRegistry(
        providers = providers,
        assembler = { error("anchor routing tests must not assemble a session") },
        toggleOf = { provider -> (provider as FakeProvider).toggle },
        activeStore = NoPersistActiveSchoolStore,
    )

    private fun schoolSpec(id: String) = SchoolSpec(
        id = id,
        displayNameRes = 0,
        capabilities = setOf(Capability.NATIVE_LOGIN),
        headerTitleRes = 0,
        headerDescriptionRes = 0,
        introTitleRes = 0,
        introBodyRes = 0,
        usernameLabelRes = 0,
        usernameHintRes = 0,
        loginBlankHintRes = 0,
        loginNotesRes = 0,
        loginSheetTitleRes = 0,
        sourceBadgeRes = 0,
        groupHomeworkRes = 0,
        groupQuizRes = 0,
        defaultScheduleNameRes = 0,
        syncNotifTitleRes = 0,
    )

    private class FakeProvider(
        override val spec: SchoolSpec,
        factory: ((Context) -> PendingIntent)? = null,
    ) : SchoolProvider {
        val toggle = FakeToggle()

        override val dailySyncFireIntent: ((Context) -> PendingIntent)? = factory

        override fun dailySyncToggle(context: Context): DailySyncToggle = toggle

        override suspend fun createSession(deps: SessionDeps): SchoolSession =
            error("anchor routing tests must not assemble a session")
    }

    private class FakeToggle : DailySyncToggle {
        private val _enabled = MutableStateFlow(false)
        override val enabled: StateFlow<Boolean> = _enabled
        override fun setEnabled(enabled: Boolean) {
            _enabled.value = enabled
        }
    }
}
