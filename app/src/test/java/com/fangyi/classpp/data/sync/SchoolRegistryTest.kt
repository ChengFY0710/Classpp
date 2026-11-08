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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
                            timetableIntro = TimetableIntro(SchoolText.Res(0), SchoolText.Res(0)),
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
                            timetableIntro = TimetableIntro(SchoolText.Res(0), SchoolText.Res(0)),
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
        assertEquals(listOf("xmu", "fzu"), registry.schools.value.map { it.id })
    }

    @Test
    fun `active school accepts known persisted id`() {
        val registry = buildRegistry(
            listOf(
                FakeProvider(schoolSpec("xmu")),
                FakeProvider(schoolSpec("fzu")),
            ),
            activeStore = FakeActiveSchoolStore("fzu"),
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
            activeStore = FakeActiveSchoolStore("gone"),
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
    fun `registry rejects overlapping web view whitelists`() = runBlocking {
        val first = FakeProvider(schoolSpec("xmu"))
        val second = FakeProvider(schoolSpec("fzu"))
        val shared = testWebViewLogin("shared")
        val registry = buildRegistry(
            listOf(first, second),
            assembler = { provider ->
                val fake = provider as FakeProvider
                FakeSession(
                    spec = fake.spec,
                    dailySync = fake.toggle,
                    autoRenew = FakeAutoRenew,
                    webViewLogin = shared,
                    scheduleImport = null,
                )
            },
        )
        registry.session() // 先装配的学校占用白名单

        val failure = runCatching { registry.session("fzu") }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("overlap"))
        assertTrue(registry.activeSession.value is SessionSlot.Ready)
    }

    @Test
    fun `registry rejects invalid web view spec`() = runBlocking {
        val provider = FakeProvider(schoolSpec())
        val registry = buildRegistry(
            listOf(provider),
            assembler = {
                FakeSession(
                    spec = provider.spec,
                    dailySync = provider.toggle,
                    autoRenew = FakeAutoRenew,
                    webViewLogin = WebViewLoginSpec(
                        entryUrl = "http://shared.example.test/login",
                        allowedHosts = setOf("shared.example.test"),
                        dataHost = "shared.example.test",
                        profilePath = "/api/profile",
                        todosPath = "/api/todos",
                        userAgent = null,
                    ),
                    scheduleImport = null,
                )
            },
        )

        val failure = runCatching { registry.session() }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("invalid webViewLogin spec"))
    }

    @Test
    fun `web view timetable requires web view login capability`() = runBlocking {
        val spec = schoolSpec(
            capabilities = setOf(Capability.NATIVE_LOGIN, Capability.SCHEDULE_IMPORT),
            timetableIntro = TimetableIntro(SchoolText.Res(0), SchoolText.Res(0)),
        )
        val provider = FakeProvider(spec)
        val registry = buildRegistry(
            listOf(provider),
            assembler = {
                fakeSession(
                    spec = spec,
                    toggle = provider.toggle,
                    scheduleImport = FakeTimetableImport(testWebViewTimetable()),
                )
            },
        )

        val failure = runCatching { registry.session() }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("webViewTimetable"))
    }

    @Test
    fun `registry rejects invalid web view timetable spec`() = runBlocking {
        val spec = schoolSpec(
            capabilities = setOf(
                Capability.NATIVE_LOGIN,
                Capability.WEBVIEW_LOGIN,
                Capability.SCHEDULE_IMPORT,
            ),
            timetableIntro = TimetableIntro(SchoolText.Res(0), SchoolText.Res(0)),
        )
        val provider = FakeProvider(spec)
        val registry = buildRegistry(
            listOf(provider),
            assembler = {
                fakeSession(
                    spec = spec,
                    toggle = provider.toggle,
                    scheduleImport = FakeTimetableImport(
                        WebViewTimetableSpec(
                            entryUrl = "http://jw.example.test/index.do",
                            allowedHosts = setOf("jw.example.test"),
                        ),
                    ),
                )
            },
        )

        val failure = runCatching { registry.session() }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("invalid webViewTimetable spec"))
    }

    @Test
    fun `unknown school id is rejected`() {
        val registry = buildRegistry(listOf(FakeProvider(schoolSpec())))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { registry.session("fzu") }
        }
    }

    // ---------- 切校（单活账号） ----------

    @Test
    fun `switchSchool assembles persists and activates target`() = runBlocking {
        val store = FakeActiveSchoolStore()
        val changes = mutableListOf<String>()
        val registry = buildRegistry(
            listOf(
                FakeProvider(schoolSpec("xmu")),
                FakeProvider(schoolSpec("fzu")),
            ),
            activeStore = store,
            onActiveSchoolChanged = { changes += it },
        )
        registry.session() // 冷启动先装配活跃校

        registry.switchSchool("fzu")

        assertEquals("fzu", registry.activeSchoolId.value)
        assertEquals("fzu", store.stored)
        assertEquals(listOf("fzu"), changes)
        assertEquals(1, store.writes)
        val slot = registry.activeSession.value
        assertTrue(slot is SessionSlot.Ready)
        assertEquals("fzu", (slot as SessionSlot.Ready).session.spec.id)
        // 缓存的会话与 session() 取值一致
        assertSame(slot.session, registry.session())
    }

    @Test
    fun `switchSchool to current school is a no-op`() = runBlocking {
        val store = FakeActiveSchoolStore()
        var changes = 0
        val registry = buildRegistry(
            listOf(FakeProvider(schoolSpec("xmu"))),
            activeStore = store,
            onActiveSchoolChanged = { changes++ },
        )
        registry.session()

        registry.switchSchool("xmu")

        assertEquals(0, store.writes)
        assertEquals(0, changes)
        assertEquals("xmu", registry.activeSchoolId.value)
    }

    @Test
    fun `switchSchool failure keeps previous active school`() = runBlocking {
        val store = FakeActiveSchoolStore()
        var changes = 0
        val registry = buildRegistry(
            listOf(
                FakeProvider(schoolSpec("xmu")),
                FakeProvider(schoolSpec("fzu")),
            ),
            activeStore = store,
            onActiveSchoolChanged = { changes++ },
            assembler = { provider ->
                val fake = provider as FakeProvider
                if (fake.spec.id == "fzu") error("boom")
                fakeSession(fake.spec, fake.toggle)
            },
        )
        registry.session()

        val failure = runCatching { registry.switchSchool("fzu") }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("xmu", registry.activeSchoolId.value)
        assertNull(store.stored)
        assertEquals(0, changes)
        val slot = registry.activeSession.value
        assertTrue(slot is SessionSlot.Ready)
        assertEquals("xmu", (slot as SessionSlot.Ready).session.spec.id)
    }

    @Test
    fun `switchSchool rejects unknown school without touching state`() = runBlocking {
        val store = FakeActiveSchoolStore()
        val registry = buildRegistry(listOf(FakeProvider(schoolSpec("xmu"))), activeStore = store)
        registry.session()

        val failure = runCatching { registry.switchSchool("fzu") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals("xmu", registry.activeSchoolId.value)
        assertEquals(0, store.writes)
    }

    @Test
    fun `switch is disabled while sync or auto renew is busy`() {
        assertTrue(SchoolRegistry.canSwitchSchool(syncBusy = false, autoRenewBusy = false))
        assertFalse(SchoolRegistry.canSwitchSchool(syncBusy = true, autoRenewBusy = false))
        assertFalse(SchoolRegistry.canSwitchSchool(syncBusy = false, autoRenewBusy = true))
        assertFalse(SchoolRegistry.canSwitchSchool(syncBusy = true, autoRenewBusy = true))
    }

    // ---------- 运行时追加远端声明学校 ----------

    @Test
    fun `registerRemote appends valid schools and updates the flow`() {
        val registry = buildRegistry(listOf(FakeProvider(schoolSpec("xmu"))))
        val remote = FakeProvider(schoolSpec("demo", remote = true))

        registry.registerRemote(listOf(remote))

        assertEquals(listOf("xmu", "demo"), registry.schools.value.map { it.id })
        assertTrue(registry.schools.value.last().remote)
        assertSame(remote.toggle, registry.dailySyncToggle("demo"))
    }

    @Test
    fun `registerRemote skips duplicates and invalid declarations`() {
        val registry = buildRegistry(listOf(FakeProvider(schoolSpec("xmu"))))

        registry.registerRemote(
            listOf(
                FakeProvider(schoolSpec("xmu")), // 与已注册学校重复
                FakeProvider(schoolSpec("Bad")), // 非法 id
                FakeProvider(
                    schoolSpec(
                        id = "nologin",
                        capabilities = setOf(Capability.SCHEDULE_IMPORT),
                        timetableIntro = TimetableIntro(SchoolText.Res(0), SchoolText.Res(0)),
                    ),
                ),
                FakeProvider(schoolSpec("demo")),
                FakeProvider(schoolSpec("demo")), // 本批内重复
            ),
        )

        assertEquals(listOf("xmu", "demo"), registry.schools.value.map { it.id })
    }

    @Test
    fun `registerRemote never removes registered schools or touches the active session`() = runBlocking {
        val registry = buildRegistry(
            listOf(
                FakeProvider(schoolSpec("xmu")),
                FakeProvider(schoolSpec("fzu")),
            ),
        )
        val active = registry.session()
        assertEquals("xmu", registry.activeSchoolId.value)

        registry.registerRemote(listOf(FakeProvider(schoolSpec("demo"))))

        assertEquals(listOf("xmu", "fzu", "demo"), registry.schools.value.map { it.id })
        assertEquals("xmu", registry.activeSchoolId.value)
        val slot = registry.activeSession.value
        assertTrue(slot is SessionSlot.Ready)
        assertSame(active, (slot as SessionSlot.Ready).session)
    }

    @Test
    fun `registerRemote skips overlapping web view hosts`() {
        val registry = buildRegistry(listOf(FakeProvider(schoolSpec("xmu"))))
        val shared = testWebViewLogin("shared")

        registry.registerRemote(listOf(FakeProvider(schoolSpec("one"), shared)))
        registry.registerRemote(listOf(FakeProvider(schoolSpec("two"), shared)))

        assertEquals(listOf("xmu", "one"), registry.schools.value.map { it.id })
    }

    @Test
    fun `remote registration sees lightweight hosts of existing providers`() {
        val intro = TimetableIntro(SchoolText.Res(0), SchoolText.Res(0))
        val capabilities = setOf(Capability.WEBVIEW_LOGIN, Capability.SCHEDULE_IMPORT)
        val registry = buildRegistry(
            listOf(
                FakeProvider(
                    schoolSpec("xmu", capabilities = capabilities, timetableIntro = intro),
                    webViewLoginSpec = testWebViewLogin("xmu"),
                    webViewTimetableSpec = testWebViewTimetable("xmu"),
                ),
            ),
        )

        val overlapping = FakeProvider(
            schoolSpec("demo", capabilities = capabilities, timetableIntro = intro),
            webViewLoginSpec = testWebViewLogin("demo"),
            webViewTimetableSpec = testWebViewTimetable("xmu"),
        )
        assertEquals(0, registry.registerRemote(listOf(overlapping)))

        val clean = FakeProvider(
            schoolSpec("clean", capabilities = capabilities, timetableIntro = intro),
            webViewLoginSpec = testWebViewLogin("clean"),
            webViewTimetableSpec = testWebViewTimetable("clean"),
        )
        assertEquals(1, registry.registerRemote(listOf(clean)))
    }

    @Test
    fun `registerRemote skips overlapping timetable hosts`() {
        val intro = TimetableIntro(SchoolText.Res(0), SchoolText.Res(0))
        val capabilities = setOf(Capability.WEBVIEW_LOGIN, Capability.SCHEDULE_IMPORT)
        val registry = buildRegistry(listOf(FakeProvider(schoolSpec("xmu"))))

        val first = FakeProvider(
            schoolSpec("one", capabilities = capabilities, timetableIntro = intro),
            webViewLoginSpec = testWebViewLogin("one"),
            webViewTimetableSpec = testWebViewTimetable("shared_timetable"),
        )
        val second = FakeProvider(
            schoolSpec("two", capabilities = capabilities, timetableIntro = intro),
            webViewLoginSpec = testWebViewLogin("two"),
            webViewTimetableSpec = testWebViewTimetable("shared_timetable"),
        )

        assertEquals(1, registry.registerRemote(listOf(first)))
        assertEquals(0, registry.registerRemote(listOf(second)))
        assertEquals(listOf("xmu", "one"), registry.schools.value.map { it.id })
    }

    @Test
    fun `remote timetable session passes capability and host validation`() = runBlocking {
        val intro = TimetableIntro(SchoolText.Res(0), SchoolText.Res(0))
        val capabilities = setOf(Capability.WEBVIEW_LOGIN, Capability.SCHEDULE_IMPORT)
        val spec = schoolSpec("demo", capabilities = capabilities, timetableIntro = intro)
        val provider = FakeProvider(
            spec,
            webViewLoginSpec = testWebViewLogin("demo"),
            webViewTimetableSpec = testWebViewTimetable("demo"),
        )
        val registry = buildRegistry(
            listOf(provider),
            assembler = {
                fakeSession(
                    spec = spec,
                    toggle = provider.toggle,
                    scheduleImport = FakeTimetableImport(testWebViewTimetable("demo")),
                )
            },
        )

        val session = registry.session()

        assertTrue(session.scheduleImport != null)
        assertTrue(session.scheduleImport?.webViewTimetable != null)
    }

    @Test
    fun `registered remote school can be switched to and assembled lazily`() = runBlocking {
        val store = FakeActiveSchoolStore()
        val registry = buildRegistry(
            listOf(FakeProvider(schoolSpec("xmu"))),
            activeStore = store,
        )
        registry.session()

        registry.registerRemote(listOf(FakeProvider(schoolSpec("demo"))))
        registry.switchSchool("demo")

        assertEquals("demo", registry.activeSchoolId.value)
        assertEquals("demo", store.stored)
        val slot = registry.activeSession.value
        assertTrue(slot is SessionSlot.Ready)
        assertEquals("demo", (slot as SessionSlot.Ready).session.spec.id)
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
        activeStore: ActiveSchoolStore = FakeActiveSchoolStore(),
        onActiveSchoolChanged: (String) -> Unit = {},
        assembler: suspend (SchoolProvider) -> SchoolSession = { provider ->
            val fake = provider as FakeProvider
            fakeSession(fake.spec, fake.toggle)
        },
    ): SchoolRegistry = SchoolRegistry(
        providers = providers,
        assembler = assembler,
        toggleOf = { provider -> (provider as FakeProvider).toggle },
        activeStore = activeStore,
        onActiveSchoolChanged = onActiveSchoolChanged,
    )

    private fun schoolSpec(
        id: String = "xmu",
        capabilities: Set<Capability> = setOf(
            Capability.NATIVE_LOGIN,
            Capability.WEBVIEW_LOGIN,
        ),
        timetableIntro: TimetableIntro? = null,
        remote: Boolean = false,
    ) = SchoolSpec(
        id = id,
        displayName = SchoolText.Res(0),
        capabilities = capabilities,
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
        timetableIntro = timetableIntro,
        remote = remote,
    )

    private fun fakeSession(
        spec: SchoolSpec,
        toggle: DailySyncToggle,
        autoRenew: AutoRenewService? =
            if (Capability.NATIVE_LOGIN in spec.capabilities) FakeAutoRenew else null,
        webViewLogin: WebViewLoginSpec? =
            if (Capability.WEBVIEW_LOGIN in spec.capabilities) testWebViewLogin(spec.id) else null,
        scheduleImport: ScheduleImportService? =
            if (Capability.SCHEDULE_IMPORT in spec.capabilities) FakeScheduleImport else null,
    ) = FakeSession(
        spec = spec,
        dailySync = toggle,
        autoRenew = autoRenew,
        webViewLogin = webViewLogin,
        scheduleImport = scheduleImport,
    )

    private class FakeProvider(
        override val spec: SchoolSpec,
        override val webViewLoginSpec: WebViewLoginSpec? = null,
        override val webViewTimetableSpec: WebViewTimetableSpec? = null,
    ) : SchoolProvider {
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

    /** 活跃学校 id 的内存持久化：记录读取值与写入次数，验证切校往返 */
    private class FakeActiveSchoolStore(initial: String? = null) : ActiveSchoolStore {
        var stored: String? = initial
        var writes = 0

        override fun read(): String? = stored

        override fun write(schoolId: String) {
            stored = schoolId
            writes++
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

    /** 带教务课表页声明的导入服务：只用于注册表的槽位一致性/声明校验 */
    private class FakeTimetableImport(
        override val webViewTimetable: WebViewTimetableSpec?,
    ) : ScheduleImportService {
        override val blockedReason = MutableStateFlow<ImportBlockReason?>(null)

        override suspend fun fetchSemesters(): SemestersOutcome =
            error("unused in registry tests")

        override suspend fun fetchSchedule(semester: ImportSemester): ImportOutcome =
            error("unused in registry tests")
    }

    private companion object {
        /** 每校一个独立 host：不同学校的 WebView 白名单不得重叠（共用认证域 = cookie 互串） */
        fun testWebViewLogin(id: String): WebViewLoginSpec {
            val host = "$id.example.test"
            return WebViewLoginSpec(
                entryUrl = "https://$host/login",
                allowedHosts = setOf(host),
                dataHost = host,
                profilePath = "/api/profile",
                todosPath = "/api/todos",
                userAgent = null,
            )
        }

        /** 教务课表页声明：入口与白名单同校域（与登录声明的重叠由真实学校各自保证） */
        fun testWebViewTimetable(id: String = "xmu"): WebViewTimetableSpec {
            val host = "$id.example.test"
            return WebViewTimetableSpec(
                entryUrl = "https://$host/gsapp/sys/wdkbapp/*default/index.do",
                allowedHosts = setOf(host),
                userAgent = null,
            )
        }
    }
}
