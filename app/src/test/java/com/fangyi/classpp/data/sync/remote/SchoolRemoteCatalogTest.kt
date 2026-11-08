package com.fangyi.classpp.data.sync.remote

import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.sync.net.HttpRequest
import com.fangyi.classpp.data.sync.net.HttpResponse
import com.fangyi.classpp.data.sync.net.HttpTransport
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 远端声明的拉取/缓存链路：成功往返、404 回退缓存、单校坏 JSON 跳过、
 * 路径穿越条目不发起请求、声明 id 与清单不一致时拒绝。
 */
class SchoolRemoteCatalogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val indexUrl = "https://example.test/index.json"
    private val baseUrl = "https://example.test/"

    @Test
    fun `refresh fetches validates and caches declarations`() = runBlocking {
        val dir = folder.newFolder()
        val transport = FakeTransport(
            mapOf(
                indexUrl to ok(indexJson("demo" to "schools/demo.json")),
                baseUrl + "schools/demo.json" to ok(ScheduleJson.encodeStorage(demoDeclaration())),
            ),
        )

        val refreshed = SchoolRemoteCatalog(transport, dir, indexUrl, baseUrl).refresh()
        assertEquals(listOf("demo"), refreshed!!.map { it.id })

        val cached = SchoolRemoteCatalog(FakeTransport(emptyMap()), dir, indexUrl, baseUrl)
            .loadCached()
        assertEquals(listOf("demo"), cached.map { it.id })
        assertEquals(emptyList<String>(), cached.single().validate())
    }

    @Test
    fun `timetable block survives cache round trip`() = runBlocking {
        val dir = folder.newFolder()
        val declaration = demoDeclarationWithTimetable()
        val transport = FakeTransport(
            mapOf(
                indexUrl to ok(indexJson("demo" to "schools/demo.json")),
                baseUrl + "schools/demo.json" to ok(ScheduleJson.encodeStorage(declaration)),
            ),
        )

        SchoolRemoteCatalog(transport, dir, indexUrl, baseUrl).refresh()

        val cached = SchoolRemoteCatalog(FakeTransport(emptyMap()), dir, indexUrl, baseUrl)
            .loadCached()
        val loaded = cached.single()
        assertEquals(emptyList<String>(), loaded.validate())
        assertEquals(
            "https://jw.demo.edu.cn/gsapp/sys/wdkbapp/*default/index.do",
            loaded.timetable?.entryUrl,
        )
        assertEquals("BITMAP", loaded.timetable?.courses?.weeksFormat)
        assertEquals(
            "/wdkcb/querySchedule.do",
            loaded.timetable?.requests?.schedule?.path,
        )
    }

    @Test
    fun `https redirects are followed manually`() = runBlocking {
        val dir = folder.newFolder()
        val cdn = "https://cdn.example.test/index.json"
        val transport = FakeTransport(
            mapOf(
                indexUrl to HttpResponse(status = 302, location = cdn),
                cdn to ok(indexJson("demo" to "schools/demo.json")),
                baseUrl + "schools/demo.json" to ok(ScheduleJson.encodeStorage(demoDeclaration())),
            ),
        )

        val refreshed = SchoolRemoteCatalog(transport, dir, indexUrl, baseUrl).refresh()

        assertEquals(listOf("demo"), refreshed!!.map { it.id })
        assertTrue(cdn in transport.requested)
    }

    @Test
    fun `http redirects are rejected`() = runBlocking {
        val dir = folder.newFolder()
        val transport = FakeTransport(
            mapOf(
                indexUrl to HttpResponse(status = 302, location = "http://cdn.example.test/index.json"),
            ),
        )

        assertNull(SchoolRemoteCatalog(transport, dir, indexUrl, baseUrl).refresh())
        assertEquals(listOf(indexUrl), transport.requested)
    }

    @Test
    fun `failed index fetch returns null and keeps the cache intact`() = runBlocking {
        val dir = folder.newFolder()
        val seed = FakeTransport(
            mapOf(
                indexUrl to ok(indexJson("demo" to "schools/demo.json")),
                baseUrl + "schools/demo.json" to ok(ScheduleJson.encodeStorage(demoDeclaration())),
            ),
        )
        SchoolRemoteCatalog(seed, dir, indexUrl, baseUrl).refresh()

        val offline = FakeTransport(emptyMap())
        val refreshed = SchoolRemoteCatalog(offline, dir, indexUrl, baseUrl).refresh()

        assertNull(refreshed)
        assertEquals(listOf(indexUrl), offline.requested)
        // 缓存原样保留：下次冷启动仍能加载
        assertEquals(
            listOf("demo"),
            SchoolRemoteCatalog(offline, dir, indexUrl, baseUrl).loadCached().map { it.id },
        )
    }

    @Test
    fun `one bad school is skipped without dropping the batch`() = runBlocking {
        val dir = folder.newFolder()
        val transport = FakeTransport(
            mapOf(
                indexUrl to ok(
                    indexJson("demo" to "schools/demo.json", "broken" to "schools/broken.json"),
                ),
                baseUrl + "schools/demo.json" to ok(ScheduleJson.encodeStorage(demoDeclaration())),
                baseUrl + "schools/broken.json" to ok("{not-json"),
            ),
        )

        val refreshed = SchoolRemoteCatalog(transport, dir, indexUrl, baseUrl).refresh()

        assertEquals(listOf("demo"), refreshed!!.map { it.id })
        assertTrue(baseUrl + "schools/broken.json" in transport.requested)
    }

    @Test
    fun `non 2xx school response is skipped`() = runBlocking {
        val dir = folder.newFolder()
        val transport = FakeTransport(
            mapOf(
                indexUrl to ok(indexJson("demo" to "schools/demo.json")),
                baseUrl + "schools/demo.json" to HttpResponse(status = 404, body = "not found"),
            ),
        )

        assertEquals(
            emptyList<SchoolDeclaration>(),
            SchoolRemoteCatalog(transport, dir, indexUrl, baseUrl).refresh(),
        )
    }

    @Test
    fun `path traversal entries are never requested`() = runBlocking {
        val dir = folder.newFolder()
        val transport = FakeTransport(
            mapOf(indexUrl to ok(indexJson("demo" to "../evil.json"))),
        )

        val refreshed = SchoolRemoteCatalog(transport, dir, indexUrl, baseUrl).refresh()

        assertEquals(emptyList<SchoolDeclaration>(), refreshed!!)
        assertEquals(listOf(indexUrl), transport.requested)
    }

    @Test
    fun `declaration id must match the index entry`() = runBlocking {
        val dir = folder.newFolder()
        val other = demoDeclaration().let { demo ->
            demo.copy(
                id = "other",
                todo = demo.todo.copy(
                    remoteKeyTemplate = "other:{courseId}:{id}",
                    localIdTemplate = "other-{courseId}-{id}",
                ),
            )
        }
        val transport = FakeTransport(
            mapOf(
                indexUrl to ok(indexJson("demo" to "schools/demo.json")),
                baseUrl + "schools/demo.json" to ok(ScheduleJson.encodeStorage(other)),
            ),
        )

        assertEquals(
            emptyList<SchoolDeclaration>(),
            SchoolRemoteCatalog(transport, dir, indexUrl, baseUrl).refresh(),
        )
    }

    @Test
    fun `unsupported index schema version is ignored`() = runBlocking {
        val dir = folder.newFolder()
        val transport = FakeTransport(
            mapOf(indexUrl to ok("""{"schemaVersion":2,"schools":[]}""")),
        )

        assertNull(SchoolRemoteCatalog(transport, dir, indexUrl, baseUrl).refresh())
    }

    // ---------- 工具 ----------

    private fun ok(body: String): HttpResponse = HttpResponse(status = 200, body = body)

    private fun indexJson(vararg entries: Pair<String, String>): String = buildString {
        append("""{"schemaVersion":1,"schools":[""")
        append(
            entries.joinToString(",") { (id, file) ->
                """{"id":"$id","file":"$file"}"""
            },
        )
        append("]}")
    }

    private class FakeTransport(
        private val routes: Map<String, HttpResponse>,
    ) : HttpTransport {
        val requested = mutableListOf<String>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            requested += request.url
            return routes[request.url] ?: HttpResponse(status = 404)
        }
    }

    @Suppress("unused")
    private fun cacheFiles(dir: File): List<File> = dir.listFiles()?.toList().orEmpty()
}
