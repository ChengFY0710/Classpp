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

    /** 解析并产出可直接入库的新课表（新 id、课程新 id、名称未去重） */
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

        val rebuilt = payload.schedule.copy(
            id = newUuid(),
            courses = payload.schedule.courses.map { it.copy(id = newUuid()) },
        )

        val errors = ScheduleValidator.validate(rebuilt)
        if (errors.isNotEmpty()) {
            return ReadResult.Err(errors.first())
        }
        return ReadResult.Ok(rebuilt)
    }
}
