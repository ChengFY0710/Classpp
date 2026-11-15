package com.fangyi.classpp.data.xmu

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 课程活动列表取数：按课程批量、Cookie/请求头、解析与失败降级 */
class XmuActivityRequirementsTest {

    private fun client(
        transport: XmuHttpTransport,
        cookie: String? = "session=S1",
    ): XmuActivityRequirementsClient = XmuActivityRequirementsClient(
        transport = transport,
        cookie = { cookie },
    )

    @Test
    fun `fetches one request per course and parses description and uploads`() = runBlocking {
        val seen = mutableListOf<XmuHttpRequest>()
        val transport = FakeXmuTransport { request ->
            seen += request
            XmuHttpResponse(200, body = COURSE_ACTIVITIES)
        }

        val result = client(transport).fetch(listOf(105713L, 105713L, 105714L))

        assertEquals(2, seen.size)
        assertEquals(
            listOf(
                "https://lnt.xmu.edu.cn/api/courses/105713/activities?sub_course_id=0",
                "https://lnt.xmu.edu.cn/api/courses/105714/activities?sub_course_id=0",
            ),
            seen.map { it.url },
        )
        assertTrue(seen.all { it.headers["Accept"] == "application/json" })
        assertTrue(seen.all { it.headers["Cookie"] == "session=S1" })

        val course = requireNotNull(result[105713L])
        assertEquals(
            XmuActivityRequirement(
                description = "",
                attachments = listOf(
                    XmuActivityAttachment(
                        id = 17141442,
                        referenceId = 10898379,
                        name = "Talk9-作业.pdf",
                        type = "document",
                        sizeBytes = 1320718,
                    ),
                ),
            ),
            course[1029546L],
        )
        assertEquals(
            XmuActivityRequirement(
                description = "如图说明",
                attachments = listOf(
                    XmuActivityAttachment(
                        id = 17141370,
                        referenceId = 10898322,
                        name = "a.png",
                        type = "image",
                        sizeBytes = 592747,
                    ),
                ),
            ),
            course[1025592L],
        )
        assertTrue(result.containsKey(105714L))
    }

    @Test
    fun `invalid course ids are ignored and empty input makes no request`() = runBlocking {
        val seen = mutableListOf<XmuHttpRequest>()
        val transport = FakeXmuTransport { request ->
            seen += request
            XmuHttpResponse(200, body = COURSE_ACTIVITIES)
        }

        assertEquals(emptyMap<Long, Map<Long, XmuActivityRequirement>>(), client(transport).fetch(emptyList()))
        assertEquals(emptyMap<Long, Map<Long, XmuActivityRequirement>>(), client(transport).fetch(listOf(0L, -1L)))
        assertEquals(0, seen.size)
    }

    @Test
    fun `non 200 html and io failures skip the course without throwing`() = runBlocking {
        val transport = FakeXmuTransport { request ->
            when {
                request.url.contains("/courses/1/") -> XmuHttpResponse(500, body = "busy")
                request.url.contains("/courses/2/") ->
                    XmuHttpResponse(200, body = "<html>login</html>")
                request.url.contains("/courses/3/") -> throw IOException("offline")
                request.url.contains("/courses/4/") -> XmuHttpResponse(200, body = "not json")
                else -> XmuHttpResponse(200, body = """{"activities":""")
            }
        }

        val result = client(transport).fetch(listOf(1L, 2L, 3L, 4L, 5L))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `one failed course does not block the others`() = runBlocking {
        val transport = FakeXmuTransport { request ->
            if (request.url.contains("/courses/1/")) {
                XmuHttpResponse(500)
            } else {
                XmuHttpResponse(200, body = COURSE_ACTIVITIES)
            }
        }

        val result = client(transport).fetch(listOf(1L, 2L))

        assertNull(result[1L])
        assertTrue(result.containsKey(2L))
    }

    @Test
    fun `missing activities key or unknown fields are tolerated`() = runBlocking {
        val transport = FakeXmuTransport { XmuHttpResponse(200, body = """{"foo":1}""") }

        assertEquals(emptyMap<Long, Map<Long, XmuActivityRequirement>>(), client(transport).fetch(listOf(1L)))
    }

    @Test
    fun `request omits cookie header when session is blank`() = runBlocking {
        val seen = mutableListOf<XmuHttpRequest>()
        val transport = FakeXmuTransport { request ->
            seen += request
            XmuHttpResponse(200, body = """{"activities":[]}""")
        }

        client(transport, cookie = " ").fetch(listOf(1L))

        assertEquals(1, seen.size)
        assertTrue(seen.none { it.headers.containsKey("Cookie") })
    }

    private companion object {
        const val COURSE_ACTIVITIES = """
            {"activities":[
              {"id":1029546,"type":"homework",
               "data":{"description":""},
               "uploads":[
                 {"id":17141442,"reference_id":10898379,"name":"Talk9-作业.pdf",
                  "type":"document","size":1320718,"deleted":false,"allow_download":true},
                 {"id":1,"reference_id":1,"name":"deleted.pdf","deleted":true},
                 {"id":2,"reference_id":2,"name":"no-download.pdf","allow_download":false},
                 {"id":3,"reference_id":3,"name":""}
               ]},
              {"id":1025592,"type":"homework",
               "data":{"description":"<p>如图说明</p>"},
               "uploads":[
                 {"id":17141370,"reference_id":10898322,"name":"a.png",
                  "type":"image","size":592747,"allow_download":true}
               ]}
            ]}
        """
    }
}
