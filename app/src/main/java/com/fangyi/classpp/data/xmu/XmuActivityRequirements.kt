package com.fangyi.classpp.data.xmu

import com.fangyi.classpp.data.ScheduleJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一条作业要求：正文（已转纯文本）+ 可下载附件元数据。正文为空时仍可能有附件，
 * 两者都由同步层转成待办的备注/附件 UI；取不到活动时整条不产生。
 */
data class XmuActivityRequirement(
    val description: String,
    val attachments: List<XmuActivityAttachment>,
)

/**
 * 远端附件（课程活动 `uploads[]` 的安全子集）：
 * [referenceId] 用于下载路径，[id] 是上传 id（reference 缺失时兜底），
 * [name] 是展示名，[sizeBytes] 取远端上报大小（未知为 0）。
 */
data class XmuActivityAttachment(
    val id: Long,
    val referenceId: Long,
    val name: String,
    val type: String,
    val sizeBytes: Long,
)

/**
 * 课程活动要求取数抽象：courseId → (activityId → 要求)。
 * 生产实现为 [XmuActivityRequirementsClient]；测试注入假实现。
 */
internal fun interface XmuActivityRequirementSource {
    suspend fun fetch(courseIds: Collection<Long>): Map<Long, Map<Long, XmuActivityRequirement>>
}

/**
 * 课程活动列表取数（作业要求同步的协议实现）。
 *
 * 为什么按课程取而不是按活动取：`/api/todos` 没有要求正文，逐条调
 * `/api/activities/{id}` 会产生 N+1；实测课程活动列表
 * `/api/courses/{courseId}/activities?sub_course_id=0` 一次返回该课程全部活动，
 * 且带 `data.description` 与 `uploads`，按新条目涉及的课程去重后请求即可。
 *
 * 失败语义：这是同步的增强项，任何失败（非 200、登录页 HTML、解析失败、IO、超时）
 * 都只记日志并跳过该课程，**不抛给同步主流程**——要求没取到，待办仍然照常导入。
 */
internal class XmuActivityRequirementsClient(
    private val transport: XmuHttpTransport,
    private val cookie: () -> String?,
    private val baseUrl: String = "https://${XmuWebViewPolicy.LNT_HOST}",
) : XmuActivityRequirementSource {

    /**
     * 按课程批量取活动要求：courseId → (activityId → 要求)。
     * 只返回成功解析的课程；部分课程失败不影响其他课程。
     */
    override suspend fun fetch(
        courseIds: Collection<Long>,
    ): Map<Long, Map<Long, XmuActivityRequirement>> {
        val ids = courseIds.filter { it > 0 }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val result = LinkedHashMap<Long, Map<Long, XmuActivityRequirement>>()
        // 总预算兜底：课程很多或服务端慢时不必把整次同步拖到超时
        withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            for (courseId in ids) {
                val activities = withTimeoutOrNull(PER_REQUEST_TIMEOUT_MS) {
                    fetchCourse(courseId)
                } ?: continue
                // 空响应（课程没有活动/解析失败）不落表：调用方按缺失处理即可
                if (activities.isNotEmpty()) result[courseId] = activities
            }
        }
        return result
    }

    private suspend fun fetchCourse(courseId: Long): Map<Long, XmuActivityRequirement> {
        val response = try {
            transport.execute(
                XmuHttpRequest(
                    method = "GET",
                    url = "$baseUrl/api/courses/$courseId/activities?sub_course_id=0",
                    headers = buildMap {
                        put("Accept", "application/json")
                        cookie()?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
                    },
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            XmuDebugLog.d("requirements course=$courseId io=${e::class.simpleName}")
            return emptyMap()
        }
        if (response.status !in 200..299 ||
            !response.body.trimStart().startsWith("{")
        ) {
            XmuDebugLog.d("requirements course=$courseId status=${response.status} non-json")
            return emptyMap()
        }
        val dto = try {
            ScheduleJson.decodeStorage<XmuCourseActivitiesDto>(response.body)
        } catch (_: Exception) {
            XmuDebugLog.d("requirements course=$courseId parse failed")
            return emptyMap()
        }
        return dto.activities.orEmpty()
            .filter { it.id > 0L }
            .associate { activity ->
                activity.id to XmuActivityRequirement(
                    description = XmuHtmlText.toPlainText(activity.data?.description),
                    attachments = activity.uploads.orEmpty()
                        .filter {
                            !it.deleted && it.allowDownload && it.name.isNotBlank() &&
                                (it.referenceId > 0L || it.id > 0L)
                        }
                        .map {
                            XmuActivityAttachment(
                                id = it.id,
                                referenceId = it.referenceId,
                                name = it.name.trim(),
                                type = it.type,
                                sizeBytes = it.size.coerceAtLeast(0),
                            )
                        }
                        .distinctBy { if (it.referenceId > 0L) it.referenceId else it.id },
                )
            }
    }

    private companion object {
        /** 单门课预算：正常响应远小于该值，慢课时跳过、不拖累其他课程 */
        const val PER_REQUEST_TIMEOUT_MS = 5_000L

        /** 整批作业要求的总预算（多课程时按返回顺序取到哪算哪） */
        const val TOTAL_TIMEOUT_MS = 10_000L
    }
}
