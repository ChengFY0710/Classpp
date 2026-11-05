package com.fangyi.classpp.ui.schedule

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.data.model.CourseAdjustment
import com.fangyi.classpp.data.model.CourseEntry
import kotlinx.serialization.Serializable

/**
 * 编辑态草稿：进入编辑时快照一份课程列表 + 单次调课列表，之后的增/改/删只落在草稿里；
 * 保存时**整份替换**写回仓库（见 [ScheduleRepository.replaceCoursesAndAdjustments]），
 * 取消则整个丢弃——全程零写盘，草稿要么整体生效、要么原样不动。
 *
 * 之所以不做"按 diff 逐条写回"：仓库的每条课程写操作都拿"当时的仓库"做全量校验，
 * 中间态一非法就整轮失败，而合法的草稿常常必然经过非法中间态（典型：原课缩周 +
 * 同格新增交替课）。整份替换只校验一次、只提交一次，没有中间态。
 *
 * [adjustments] 与课程同批保存：调课可能引用本次编辑里新加、尚未落库的课程 id，
 * 逐条写回会产生悬空引用。
 *
 * [active] 标记"是否处于编辑态"：`rememberSaveable` 不接受可空值，非编辑态用 [Inactive]
 * 占位。Saver 连 [active] 一起存，所以旋转 / 进程重建后"在不在编辑态"与草稿都能对上
 * （清单未锁方向，旋转会重建 Activity）。
 */
@Stable
class ScheduleEditSession internal constructor(
    draft: List<CourseEntry> = emptyList(),
    draftAdjustments: List<CourseAdjustment> = emptyList(),
    val active: Boolean = true,
) {
    /** 当前课程草稿（编辑态的网格直接渲染它） */
    var courses: List<CourseEntry> by mutableStateOf(draft)
        private set

    /** 当前调课草稿；与 [courses] 同批保存 */
    var adjustments: List<CourseAdjustment> by mutableStateOf(draftAdjustments)
        private set

    fun add(course: CourseEntry) {
        courses = courses + course
    }

    /** 编辑确认：同 id 的新内容替换草稿条目；周规律改小后失效的调课同步清理 */
    fun update(id: String, entry: CourseEntry) {
        courses = courses.map { if (it.id == id) entry else it }
        adjustments = adjustments.filterNot { it.courseId == id && !entry.weeks.contains(it.fromWeek) }
    }

    /** 删除确认：仅移出草稿（保存时才写回仓库，取消编辑即整体还原），调课一并清理 */
    fun remove(id: String) {
        courses = courses.filterNot { it.id == id }
        adjustments = adjustments.filterNot { it.courseId == id }
    }

    /**
     * 整份替换草稿：「合并两小节为一大节」落库成功后同步重映射后的课程，
     * 避免后续「保存编辑」用旧 span 覆盖合并结果、或网格仍按旧跨度渲染。
     */
    fun replaceAll(courses: List<CourseEntry>) {
        this.courses = courses
    }

    /** 新增一条调课（同一课次的旧记录由调用方负责替换） */
    fun addAdjustment(adjustment: CourseAdjustment) {
        adjustments = adjustments + adjustment
    }

    /** 编辑确认：同 id 的调课替换草稿条目 */
    fun updateAdjustment(id: String, adjustment: CourseAdjustment) {
        adjustments = adjustments.map { if (it.id == id) adjustment else it }
    }

    fun removeAdjustment(id: String) {
        adjustments = adjustments.filterNot { it.id == id }
    }

    /** 整份替换调课草稿：节表合并后按新节次重映射的结果经此回写 */
    fun replaceAdjustments(adjustments: List<CourseAdjustment>) {
        this.adjustments = adjustments
    }

    /** 定位某门课在某周的调课（编辑面板初值用） */
    fun adjustmentFor(courseId: String, week: Int): CourseAdjustment? =
        adjustments.firstOrNull { it.courseId == courseId && it.fromWeek == week }

    companion object {
        /** 非编辑态的占位会话 */
        val Inactive: ScheduleEditSession = ScheduleEditSession(active = false)

        /**
         * 保存格式：[active, draftJson]。draftJson = [EditDraftPayload]，restore 时先按新格式
         * 解析，失败再回退到旧的 `List<CourseEntry>`（旧版本曾只存课程；更早还存过
         * `[active, 快照, 草稿]`，末项同样是草稿）。
         */
        val Saver: Saver<ScheduleEditSession, Any> = listSaver(
            save = { session ->
                listOf(
                    session.active.toString(),
                    ScheduleJson.encodeStorage(
                        EditDraftPayload(session.courses, session.adjustments),
                    ),
                )
            },
            restore = { saved ->
                if (saved.isEmpty()) {
                    Inactive
                } else {
                    val active = saved[0].toBooleanStrictOrNull() ?: false
                    val raw = saved.last()
                    val payload = runCatching {
                        ScheduleJson.decodeStorage<EditDraftPayload>(raw)
                    }.getOrNull()
                    if (payload != null) {
                        ScheduleEditSession(
                            draft = payload.courses,
                            draftAdjustments = payload.adjustments,
                            active = active,
                        )
                    } else {
                        ScheduleEditSession(
                            draft = ScheduleJson.decodeStorage<List<CourseEntry>>(raw),
                            active = active,
                        )
                    }
                }
            },
        )
    }
}

/** 编辑草稿的落盘形状（只用于 rememberSaveable，不进业务存储） */
@Serializable
internal data class EditDraftPayload(
    val courses: List<CourseEntry> = emptyList(),
    val adjustments: List<CourseAdjustment> = emptyList(),
)
