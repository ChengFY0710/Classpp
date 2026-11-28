package com.fangyi.classpp.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动同步执行体的 JVM 可测面：[DailySyncRunner.startupGateBlocks] 的轻量门控
 * （「启动时自动同步」开关关 = 拦截且**不装配会话**——冷启动窗口不得拉起 Keystore/
 * 凭据解密的不变量）与 [DailySyncRunner.execute] 装配失败时的静默兜底（下拉同步不得
 * 把异常抛回手势协程，冷启动不得拖垮 UI）。
 *
 * prepareSync 之后的链路依赖 Android 运行时（Context/通知），不在纯 JVM 覆盖；
 * 同步编排行为由 engine 包的测试锁定。门控读开关不触碰 Context；execute 的测试路径
 * 在装配处即失败，appContext 传 null（仅 notify=true 的通知路径解引用它）。
 */
class DailySyncRunnerTest {

    @Test
    fun `startup gate blocks when toggle is off`() {
        val runner = DailySyncRunner(buildRegistry(listOf(FakeProvider(schoolSpec("xmu"), toggleOn = false))))

        assertTrue(runner.startupGateBlocks())
    }

    @Test
    fun `startup gate passes when toggle is on`() {
        val runner = DailySyncRunner(buildRegistry(listOf(FakeProvider(schoolSpec("xmu"), toggleOn = true))))

        assertFalse(runner.startupGateBlocks())
    }

    @Test
    fun `execute fails silently when session assembly fails`() = runBlocking {
        // 装配器故意抛错：门控之后的执行体必须静默吞掉，返回 Failed 而不是抛回调用方
        val runner = DailySyncRunner(buildRegistry(listOf(FakeProvider(schoolSpec("xmu"), toggleOn = true))))

        assertEquals(DailySyncRunner.Outcome.Failed, runner.execute(null, notify = false))
    }

    // ---------- 工具 ----------

    private fun buildRegistry(providers: List<SchoolProvider>): SchoolRegistry = SchoolRegistry(
        providers = providers,
        // 装配器故意抛错：门控测试里它不该被触达（触达即门控失效，测试变红）；
        // 静默失败测试里它就是「装配失败」的来源
        assembler = { error("runner tests must not assemble a session") },
        toggleOf = { provider -> (provider as FakeProvider).toggle },
        activeStore = NoPersistActiveSchoolStore,
    )

    private fun schoolSpec(id: String) = SchoolSpec(
        id = id,
        displayName = SchoolText.Res(0),
        capabilities = setOf(Capability.NATIVE_LOGIN),
        headerTitle = SchoolText.Res(0),
        headerDescription = SchoolText.Res(0),
        introTitle = SchoolText.Res(0),
        introBody = SchoolText.Res(0),
        usernameLabel = SchoolText.Res(0),
        usernameHint = SchoolText.Res(0),
        loginBlankHint = SchoolText.Res(0),
        loginNotes = SchoolText.Res(0),
        loginSheetTitle = SchoolText.Res(0),
        sourceBadge = SchoolText.Res(0),
        groupHomework = SchoolText.Res(0),
        groupQuiz = SchoolText.Res(0),
        defaultScheduleName = SchoolText.Res(0),
        syncNotifTitle = SchoolText.Res(0),
    )

    private class FakeProvider(
        override val spec: SchoolSpec,
        toggleOn: Boolean,
    ) : SchoolProvider {
        val toggle = FakeToggle(toggleOn)

        override fun dailySyncToggle(context: android.content.Context): DailySyncToggle = toggle

        override suspend fun createSession(deps: SessionDeps): SchoolSession =
            error("runner tests must not assemble a session")
    }

    private class FakeToggle(initial: Boolean) : DailySyncToggle {
        private val _enabled = MutableStateFlow(initial)
        override val enabled: StateFlow<Boolean> = _enabled
        override fun setEnabled(enabled: Boolean) {
            _enabled.value = enabled
        }
    }
}
