package com.fangyi.classpp.data.export

import com.fangyi.classpp.data.ReadResult
import com.fangyi.classpp.data.ScheduleError
import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.ScheduleValidator
import com.fangyi.classpp.data.model.SCHEDULE_FORMAT_VERSION
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.SharePayload
import com.fangyi.classpp.data.model.newUuid

/**
 * 分享载荷编解码：导出 = Schedule → pretty JSON；导入 = JSON → 解析 → 版本/kind 闸门 →
 * **id 全量重铸**（与本机已有课表零冲突的唯一保证机制，不依赖比对）→ 全量校验。
 * 名称去重与落盘由仓库负责（需要库内状态）。
 */
internal object ShareCodec {

    fun encode(schedule: Schedule, exportedAtMillis: Long): String =
        ScheduleJson.encodeShare(
            SharePayload(exportedAtMillis = exportedAtMillis, schedule = schedule),
        )

    /** 解析并产出可直接入库的新课表（新 id、课程/调课新 id、名称未去重） */
    fun decodeForImport(json: String): ReadResult<Schedule> {
        val payload = try {
            ScheduleJson.decodeShare<SharePayload>(json)
        } catch (e: Exception) {
            return ReadResult.Err(ScheduleError.ImportFormatInvalid(e.message ?: e::class.simpleName ?: "?"))
        }

        if (payload.formatVersion > SCHEDULE_FORMAT_VERSION) {
            return ReadResult.Err(ScheduleError.ImportVersionUnsupported(payload.formatVersion))
        }
        if (payload.kind != SharePayload.SHARE_KIND) {
            return ReadResult.Err(ScheduleError.ImportKindMismatch(payload.kind))
        }

        // id 全量重铸：课程按位置各自铸新 id（载荷里可能出现重复旧 id，必须彼此独立不合并）。
        // 旧 id → 新 id 的映射同时喂给调课的 courseId 重定向，否则重铸后全部调课都成悬空引用，
        // 带调课的课表必然被全量校验拒之门外。重复旧 id 时映射取最后一次（脏数据兜底，
        // 两条新 id 都真实存在、不会悬空）；调课引用载荷里不存在的课程时保留原 id，由校验统一报错
        val remappedCourses = payload.schedule.courses.map { it.copy(id = newUuid()) }
        val courseIds = payload.schedule.courses
            .zip(remappedCourses) { old, new -> old.id to new.id }
            .toMap()
        val rebuilt = payload.schedule.copy(
            id = newUuid(),
            courses = remappedCourses,
            adjustments = payload.schedule.adjustments.map { adjustment ->
                adjustment.copy(
                    id = newUuid(),
                    courseId = courseIds[adjustment.courseId] ?: adjustment.courseId,
                )
            },
        )

        val errors = ScheduleValidator.validate(rebuilt)
        if (errors.isNotEmpty()) {
            return ReadResult.Err(errors.first())
        }
        return ReadResult.Ok(rebuilt)
    }
}
