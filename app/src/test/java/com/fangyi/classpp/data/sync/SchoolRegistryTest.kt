package com.fangyi.classpp.data.sync

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注册表契约测试（Phase 1）：id 校验、懒装配单飞、活跃槽位流转、capabilities 与槽位一致性。
 * 纯 JVM：装配动作由测试注入，不触碰 Android Context。
 */
class SchoolRegistryTest {

    // ---------- 启动校验 ----------

    @Test
    fun `registry rejects invalid school ids`() {
        val invalid = listOf("XMU", "xm-u", "xm/u", "_xmu", "1xmu", "x".repeat(33))
        for (id in invalid) {
            assertThrows(IllegalArgumentException::class.java) {
                buildRegistry(listOf(FakeProvider(schoolSpec(id))))
            }
        }
    }

    @Test
    fun `registry rejects empty provider list`() {
        assertThrows(IllegalArgumentException::class.java) {
            buildRegistry(emptyList())
        }
    }

    @Test
    fun `registry rejects duplicate ids`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            buildRegistry(
                listOf(
                    FakeProvider(schoolSpec("xmu")),
                    FakeProvider(schoolSpec("xmu")),
                ),
            )
        }
        assertTrue(error.message.orEmpty().contains("duplicate"))
    }

    @Test
    fun `registry rejects school without login capability`() {
        assertThrows(IllegalArgumentException::class.java) {
            buildRegistry(
                listOf(
                    FakeProvider(
                        schoolSpec(
                            capabilities = setOf(Capability.SCHEDULE_IMPORT),
                            timetableIntro = TimetableIntro(0, 0),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `registry requires timetableIntro iff schedule import capability`() {
        // 声明了导课能力却没有介绍卡
        assertThrows(IllegalArgumentException::class.java) {
            buildRegistry(
                listOf(
                    FakeProvider(
                        schoolSpec(
                            capabilities = setOf(
                                Capability.NATIVE_LOGIN,
                                Capability.SCHEDULE_IMPORT,
                            ),
                        ),
                    ),
                ),
            )
        }
        // 有介绍卡却没声明导课能力
        assertThrows(IllegalArgumentException::class.java) {
            buildRegistry(
                listOf(
                    FakeProvider(
                        schoolSpec(
                            capabilities = setOf(Capability.NATIVE_LOGIN),
                            timetableIntro = TimetableIntro(0, 0),
                        ),
                    ),
                ),
            )
        }
    }

    // ---------- 活跃学校 ----------

    @Test
    fun `active school defaults to first registered`() {
        val registry = buildRegistry(
            listOf(
                FakeProvider(schoolSpec("xmu")),
                FakeProvider(schoolSpec("fzu")),
            ),
        )
        assertEquals("xmu", registry.activeSchoolId.value)
        assertEquals(listOf("xmu", "fzu"), registry.schools.map { it.id })
    }

    @Test
    fun `active school accepts known initial id`() {
        val registry = buildRegistry(
            listOf(
                FakeProvider(schoolSpec("xmu")),
                FakeProvider(schoolSpec("fzu")),
            ),
            initialActiveSchoolId = "fzu",
        )
        assertEquals("fzu", registry.activeSchoolId.value)
    }

    @Test
    fun `active school falls back to first when persisted id is unknown`() {
        val registry = buildRegistry(
            listOf(
                FakeProvider(schoolSpec("xmu")),
                FakeProvider(schoolSpec("fzu")),
            ),
            initialActiveSchoolId = "gone",
        )
        assertEquals("xmu", registry.activeSchoolId.value)
    }

    @Test
    fun `active session starts as loading`() {
        val registry = buildRegistry(listOf(FakeProvider(schoolSpec())))
        assertTrue(registry.activeSession.value is SessionSlot.Loading)
    }

    @Test
    fun `non active school assembly does not touch active slot`() = runBlocking {
        val registry = buildRegistry(
            listOf(
                FakeProvider(schoolSpec("xmu")),
                FakeProvider(schoolSpec("fzu")),
            ),
        )
        registry.session("fzu")
        assertTrue(registry.activeSession.value is SessionSlot.Loading)
    }

    // ---------- 懒装配 / 单飞 ----------

    @Test
    fun `session is assembled lazily once and cached`() = runBlocking {
        var assemblies = 0
        val provider = FakeProvider(schoolSpec())
        val registry = buildRegistry(listOf(provider), assembler = {
            assemblies++
            fakeSession(provider.spec, provider.toggle)
        })

        val first = registry.session()
        val second = registry.session()

        assertSame(first, second)
        assertEquals(1, assemblies)
        val slot = registry.activeSession.value
        assertTrue(slot is SessionSlot.Ready)
        assertSame(first, (slot as SessionSlot.Ready).session)
    }

    @Test
    fun `concurrent first calls assemble the session only once`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var assemblies = 0
        val provider = FakeProvider(schoolSpec())
        val registry = buildRegistry(listOf(provider), assembler = {
            assemblies++
            started.complete(Unit)
            release.await()
            fakeSession(provider.spec, provider.toggle)
        })

        val first = async(Dispatchers.Default) { registry.session() }
        val second = async(Dispatchers.Default) { registry.session() }
        started.await()
        delay(50) // 让第二个协程排到互斥锁上（即便没排上，下面的断言也成立）
        release.complete(Unit)

        assertSame(first.await(), second.await())
        assertEquals(1, assemblies)
    }

    @Test
    fun `assembly failure is exposed as failed slot and can be retried`() = runBlocking {
        var attempts = 0
        val provider = FakeProvider(schoolSpec())
        val registry = buildRegistry(listOf(provider), assembler = {
            attempts++
            if (attempts == 1) error("boom")
            fakeSession(provider.spec, provider.toggle)
        })

        val failure = runCatching { registry.session() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        val failed = registry.activeSession.value
        assertTrue(failed is SessionSlot.Failed)
        assertSame(failure, (failed as SessionSlot.Failed).error)

        val retried = registry.session()
        assertEquals(2, attempts)
        assertTrue(registry.activeSession.value is SessionSlot.Ready)
        assertSame(retried, (registry.activeSession.value as SessionSlot.Ready).session)
    }

    @Test
    fun `capability and slot mismatch fails assembly`() = runBlocking {
        val spec = schoolSpec(
            capabilities = setOf(Capability.NATIVE_LOGIN, Capability.WEBVIEW_LOGIN),
        )
        val provider = FakeProvider(spec)
        val registry = buildRegistry(listOf(provider), assembler = {
            fakeSession(spec, provider.toggle, autoRenew = null)
        })

        val failure = runCatching { registry.session() }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(registry.activeSession.value is SessionSlot.Failed)
    }

    @Test
    fun `unknown school id is rejected`() {
        val registry = buildRegistry(listOf(FakeProvider(schoolSpec())))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { registry.session("fzu") }
        }
    }

    // ---------- 每日同步开关 ----------

    @Test
    fun `daily sync toggle is shared by registry and session`() = runBlocking {
        val provider = FakeProvider(schoolSpec())
        val registry = buildRegistry(listOf(provider))
        val session = registry.session() as FakeSession

        assertSame(provider.toggle, registry.dailySyncToggle())
        assertSame(session.dailySync, registry.dailySyncToggle())
        assertSame(registry.dailySyncToggle(), registry.dailySyncToggle())
    }

    // ---------- 测试替身 ----------

    private fun buildRegistry(
        providers: List<SchoolProvider>,
        initialActiveSchoolId: String? = null,
        assembler: suspend (SchoolProvider) -> SchoolSession = { provider ->
            val fake = provider as FakeProvider
            fakeSession(fake.spec, fake.toggle)
        },
    ): SchoolRegistry = SchoolRegistry(
        providers = providers,
        assembler = assembler,
        toggleOf = { provider -> (provider as FakeProvider).toggle },
        initialActiveSchoolId = initialActiveSchoolId,
    )

    private fun schoolSpec(
        id: String = "xmu",
        capabilities: Set<Capability> = setOf(
            Capability.NATIVE_LOGIN,
            Capability.WEBVIEW_LOGIN,
        ),
        timetableIntro: TimetableIntro? = null,
    ) = SchoolSpec(
        id = id,
        displayNameRes = 0,
        capabilities = capabilities,
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
        timetableIntro = timetableIntro,
    )

    private fun fakeSession(
        spec: SchoolSpec,
        toggle: DailySyncToggle,
        autoRenew: AutoRenewService? =
            if (Capability.NATIVE_LOGIN in spec.capabilities) FakeAutoRenew else null,
        webViewLogin: WebViewLoginSpec? =
            if (Capability.WEBVIEW_LOGIN in spec.capabilities) TEST_WEB_VIEW_LOGIN else null,
        scheduleImport: ScheduleImportService? =
            if (Capability.SCHEDULE_IMPORT in spec.capabilities) FakeScheduleImport else null,
    ) = FakeSession(
        spec = spec,
        dailySync = toggle,
        autoRenew = autoRenew,
        webViewLogin = webViewLogin,
        scheduleImport = scheduleImport,
    )

    private class FakeProvider(override val spec: SchoolSpec) : SchoolProvider {
        val toggle = FakeToggle()

        override fun dailySyncToggle(context: Context): DailySyncToggle = toggle

        override suspend fun createSession(deps: SessionDeps): SchoolSession =
            error("registry tests inject the assembler; createSession must not be called")
    }

    private class FakeToggle : DailySyncToggle {
        private val _enabled = MutableStateFlow(false)
        override val enabled: StateFlow<Boolean> = _enabled
        override fun setEnabled(enabled: Boolean) {
            _enabled.value = enabled
        }
    }

    private class FakeSession(
        override val spec: SchoolSpec,
        override val dailySync: DailySyncToggle,
        override val autoRenew: AutoRenewService?,
        override val webViewLogin: WebViewLoginSpec?,
        override val scheduleImport: ScheduleImportService?,
    ) : SchoolSession {
        override val sync: SyncService = FakeSync
    }

    private object FakeSync : SyncService {
        override val state = MutableStateFlow(SyncState())
        override val avatarImage = MutableStateFlow<AvatarImage?>(null)
        override val busy = MutableStateFlow(false)

        override suspend fun prepareSync(force: Boolean): PrepareOutcome =
            error("unused in registry tests")

        override suspend fun prepareFromLogin(raw: LoginRaw): PrepareOutcome =
            error("unused in registry tests")

        override suspend fun commitSync(
            plan: SyncPlan,
            selectedNewIds: Set<String>,
        ): CommitOutcome = error("unused in registry tests")

        override suspend fun markSynced(plan: SyncPlan): Boolean =
            error("unused in registry tests")

        override suspend fun resetSyncData(deleteSyncedTodos: Boolean) = Unit
    }

    private object FakeAutoRenew : AutoRenewService {
        override val state = MutableStateFlow(AutoRenewState())
        override val busy = MutableStateFlow(false)

        override suspend fun enable(username: String, password: String): EnableOutcome =
            error("unused in registry tests")

        override suspend fun disable() = Unit
    }

    private object FakeScheduleImport : ScheduleImportService {
        override val blockedReason = MutableStateFlow<ImportBlockReason?>(null)

        override suspend fun fetchSemesters(): SemestersOutcome =
            error("unused in registry tests")

        override suspend fun fetchSchedule(semester: ImportSemester): ImportOutcome =
            error("unused in registry tests")
    }

    private companion object {
        val TEST_WEB_VIEW_LOGIN = WebViewLoginSpec(
            entryUrl = "https://example.test/login",
            allowedHosts = setOf("example.test"),
            dataHost = "example.test",
            profilePath = "/api/profile",
            todosPath = "/api/todos",
            userAgent = null,
        )
    }
}
