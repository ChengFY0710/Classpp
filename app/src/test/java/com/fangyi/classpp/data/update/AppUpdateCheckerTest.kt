package com.fangyi.classpp.data.update

import com.fangyi.classpp.data.sync.net.HttpRequest
import com.fangyi.classpp.data.sync.net.HttpResponse
import com.fangyi.classpp.data.sync.net.HttpTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新清单的拉取/校验/比较链路：版本比较、强制更新、包名/schema/URL 校验、
 * https 重定向与分语言 changelog 选择。下载链路不在这里覆盖（见 ApkVerifierTest）。
 */
class AppUpdateCheckerTest {

    private val manifestUrl = "https://example.test/update.json"
    private val cdnUrl = "https://cdn.example.test/update.json"

    @Test
    fun `newer remote version is available`() = runBlocking {
        val transport = FakeTransport(
            mapOf(manifestUrl to ok(manifestJson(versionCode = 6))),
        )

        val result = checker(transport).check()

        val update = result.available()
        assertEquals("0.1.7-alpha", update.versionName)
        assertEquals(6L, update.versionCode)
        assertFalse(update.forced)
        assertTrue(update.canDownload)
        assertEquals(listOf("https://example.test/app.apk"), update.apkUrls)
    }

    @Test
    fun `same or older remote version is up to date`() = runBlocking {
        val same = checker(
            FakeTransport(mapOf(manifestUrl to ok(manifestJson(versionCode = 5)))),
        ).check().upToDate()
        val older = checker(
            FakeTransport(mapOf(manifestUrl to ok(manifestJson(versionCode = 4)))),
        ).check().upToDate()

        assertEquals(listOf(5L), same.releases.map { it.versionCode })
        assertEquals(listOf("新增 A"), same.releases.single().changelog)
        assertEquals(listOf(4L), older.releases.map { it.versionCode })
        assertEquals(listOf("新增 A"), older.releases.single().changelog)
    }

    @Test
    fun `release history is sorted deduplicated and localized`() = runBlocking {
        val transport = FakeTransport(
            mapOf(
                manifestUrl to ok(
                    manifestJson(
                        versionCode = 6,
                        releases = """
                            [
                              {
                                "versionCode": 5,
                                "versionName": "0.1.6-alpha",
                                "publishedAt": "2026-11-20",
                                "changelog": { "zh": ["v5 说明"], "en": ["v5 notes"] }
                              },
                              {
                                "versionCode": 6,
                                "versionName": "0.1.7-alpha",
                                "publishedAt": "2026-12-01",
                                "changelog": { "zh": ["v6 说明"], "en": ["v6 notes"] }
                              },
                              {
                                "versionCode": 4,
                                "versionName": "0.1.5-alpha",
                                "publishedAt": "2026-10-30",
                                "changelog": { "zh": ["v4 说明"], "en": ["v4 notes"] }
                              },
                              {
                                "versionCode": 6,
                                "versionName": "0.1.7-alpha duplicate",
                                "publishedAt": "2026-12-02",
                                "changelog": { "zh": ["重复"], "en": ["duplicate"] }
                              }
                            ]
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        val update = checker(transport, currentVersionCode = 5).check().available()
        assertEquals(listOf(6L, 5L, 4L), update.releases.map { it.versionCode })
        assertEquals(listOf("v6 说明"), update.changelog)

        val english = checker(
            transport,
            currentVersionCode = 5,
            language = "en",
        ).check().available()
        assertEquals(
            listOf("v5 notes"),
            english.releases.first { it.versionCode == 5L }.changelog,
        )
    }

    @Test
    fun `unreleased future versions are excluded from history`() = runBlocking {
        val transport = FakeTransport(
            mapOf(
                manifestUrl to ok(
                    manifestJson(
                        versionCode = 6,
                        releases = """
                            [
                              {
                                "versionCode": 7,
                                "versionName": "0.1.8-alpha",
                                "publishedAt": "2027-01-01",
                                "changelog": { "zh": ["未发布"], "en": ["unreleased"] }
                              },
                              {
                                "versionCode": 5,
                                "versionName": "0.1.6-alpha",
                                "publishedAt": "2026-11-20",
                                "changelog": { "zh": ["v5 说明"], "en": ["v5 notes"] }
                              }
                            ]
                        """.trimIndent(),
                    ),
                ),
            ),
        )

        val update = checker(transport, currentVersionCode = 5).check().available()
        assertEquals(listOf(6L, 5L), update.releases.map { it.versionCode })
        assertEquals(listOf("新增 A"), update.releases.first().changelog)
    }

    @Test
    fun `release history falls back to top level latest entry`() = runBlocking {
        val transport = FakeTransport(
            mapOf(manifestUrl to ok(manifestJson(versionCode = 6))),
        )

        val history = checker(transport, currentVersionCode = 7).check().upToDate().releases
        val release = history.single()
        assertEquals(6L, release.versionCode)
        assertEquals("0.1.7-alpha", release.versionName)
        assertEquals("2026-11-20", release.publishedAt)
        assertEquals(listOf("新增 A"), release.changelog)
    }

    @Test
    fun `version below minSupportedVersionCode is forced`() = runBlocking {
        val transport = FakeTransport(
            mapOf(manifestUrl to ok(manifestJson(versionCode = 9, minSupportedVersionCode = 6))),
        )

        assertTrue(checker(transport, currentVersionCode = 5).check().available().forced)
    }

    @Test
    fun `wrong package name is rejected`() = runBlocking {
        val transport = FakeTransport(
            mapOf(manifestUrl to ok(manifestJson(versionCode = 6, packageName = "com.other.app"))),
        )

        assertTrue(checker(transport).check() is UpdateCheckResult.Failed)
    }

    @Test
    fun `unsupported manifest schema is rejected`() = runBlocking {
        val transport = FakeTransport(
            mapOf(manifestUrl to ok(manifestJson(versionCode = 6, schemaVersion = 2))),
        )

        assertTrue(checker(transport).check() is UpdateCheckResult.Failed)
    }

    @Test
    fun `malformed manifest is rejected`() = runBlocking {
        val transport = FakeTransport(mapOf(manifestUrl to ok("{not-json")))

        assertTrue(checker(transport).check() is UpdateCheckResult.Failed)
    }

    @Test
    fun `manifest without usable apk or release page is rejected`() = runBlocking {
        val transport = FakeTransport(
            mapOf(
                manifestUrl to ok(
                    manifestJson(
                        versionCode = 6,
                        apkUrls = """["http://example.test/app.apk"]""",
                        releasePageUrl = "http://example.test/release",
                    ),
                ),
            ),
        )

        assertTrue(checker(transport).check() is UpdateCheckResult.Failed)
    }

    @Test
    fun `invalid apk metadata falls back to release page`() = runBlocking {
        val transport = FakeTransport(
            mapOf(
                manifestUrl to ok(
                    manifestJson(
                        versionCode = 6,
                        sha256 = "not-a-sha256",
                        releasePageUrl = "https://example.test/release",
                    ),
                ),
            ),
        )

        val update = checker(transport).check().available()
        assertFalse(update.canDownload)
        assertTrue(update.canOpenRelease)
        assertEquals(emptyList<String>(), update.apkUrls)
        assertEquals("https://example.test/release", update.releasePageUrl)
    }

    @Test
    fun `https redirects are followed manually`() = runBlocking {
        val transport = FakeTransport(
            mapOf(
                manifestUrl to HttpResponse(status = 302, location = cdnUrl),
                cdnUrl to ok(manifestJson(versionCode = 6)),
            ),
        )

        assertTrue(checker(transport).check() is UpdateCheckResult.Available)
        assertEquals(listOf(manifestUrl, cdnUrl), transport.requested)
    }

    @Test
    fun `http redirects are rejected`() = runBlocking {
        val transport = FakeTransport(
            mapOf(
                manifestUrl to HttpResponse(
                    status = 302,
                    location = "http://cdn.example.test/update.json",
                ),
            ),
        )

        assertTrue(checker(transport).check() is UpdateCheckResult.Failed)
        assertEquals(listOf(manifestUrl), transport.requested)
    }

    @Test
    fun `changelog follows app language with chinese fallback`() = runBlocking {
        val transport = FakeTransport(
            mapOf(
                manifestUrl to ok(
                    manifestJson(
                        versionCode = 6,
                        changelogZh = """["新增 A"]""",
                        changelogEn = """["New A"]""",
                    ),
                ),
            ),
        )

        assertEquals(listOf("New A"), checker(transport, language = "en").check().available().changelog)
        assertEquals(listOf("新增 A"), checker(transport, language = "zh").check().available().changelog)
        assertEquals(listOf("新增 A"), checker(transport, language = "fr").check().available().changelog)
    }

    // ---------- 工具 ----------

    private fun checker(
        transport: HttpTransport,
        currentVersionCode: Long = 5,
        language: String = "zh",
    ): AppUpdateChecker = AppUpdateChecker(
        transport = transport,
        manifestUrl = manifestUrl,
        packageName = PACKAGE_NAME,
        currentVersionCode = currentVersionCode,
        language = language,
    )

    private fun UpdateCheckResult.available(): AvailableUpdate =
        (this as? UpdateCheckResult.Available)?.update
            ?: error("expected Available but was $this")

    private fun UpdateCheckResult.upToDate(): UpdateCheckResult.UpToDate =
        this as? UpdateCheckResult.UpToDate
            ?: error("expected UpToDate but was $this")

    private fun ok(body: String): HttpResponse = HttpResponse(status = 200, body = body)

    private class FakeTransport(
        private val routes: Map<String, HttpResponse>,
    ) : HttpTransport {
        val requested = mutableListOf<String>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            requested += request.url
            return routes[request.url] ?: HttpResponse(status = 404)
        }
    }

    private companion object {
        const val PACKAGE_NAME = "com.fangyi.classpp"

        fun manifestJson(
            versionCode: Long,
            versionName: String = "0.1.7-alpha",
            packageName: String = PACKAGE_NAME,
            schemaVersion: Int = 1,
            minSupportedVersionCode: Long = 0,
            apkUrls: String = """["https://example.test/app.apk"]""",
            sizeBytes: Long = 3_000_000,
            sha256: String = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            releasePageUrl: String? = null,
            changelogZh: String = """["新增 A"]""",
            changelogEn: String = """["New A"]""",
            releases: String = "[]",
        ): String = """
            {
              "schemaVersion": $schemaVersion,
              "packageName": "$packageName",
              "versionCode": $versionCode,
              "versionName": "$versionName",
              "minSupportedVersionCode": $minSupportedVersionCode,
              "publishedAt": "2026-11-20",
              "apk": {
                "urls": $apkUrls,
                "sizeBytes": $sizeBytes,
                "sha256": "$sha256"
              },
              "releasePageUrl": ${releasePageUrl?.let { "\"$it\"" } ?: "null"},
              "changelog": {
                "zh": $changelogZh,
                "en": $changelogEn
              },
              "releases": $releases
            }
        """.trimIndent()
    }
}
