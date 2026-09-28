package com.fangyi.classpp.ui.schedule

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.data.model.CourseEntry

/**
 * 编辑态草稿：进入编辑时快照一份课程列表，之后的增/改/删只落在草稿里；
 * 保存时**整份替换**写回仓库（见 [ScheduleRepository.replaceCourses]），取消则整个丢弃
 * ——全程零写盘，草稿要么整体生效、要么原样不动。
 *
 * 之所以不做"按 diff 逐条写回"：仓库的每条课程写操作都拿"当时的仓库"做全量校验，
 * 中间态一非法就整轮失败，而合法的草稿常常必然经过非法中间态（典型：原课缩周 +
 * 同格新增交替课）。整份替换只校验一次、只提交一次，没有中间态。
 *
 * [active] 标记"是否处于编辑态"：`rememberSaveable` 不接受可空值，非编辑态用 [Inactive]
 * 占位。Saver 连 [active] 一起存，所以旋转 / 进程重建后"在不在编辑态"与草稿都能对上
 * （清单未锁方向，旋转会重建 Activity）。
 */
@Stable
class ScheduleEditSession internal constructor(
    draft: List<CourseEntry> = emptyList(),
    val active: Boolean = true,
) {
    /** 当前草稿（编辑态的网格直接渲染它） */
    var courses: List<CourseEntry> by mutableStateOf(draft)
        private set

    fun add(course: CourseEntry) {
        courses = courses + course
    }

    /** 编辑确认：同 id 的新内容替换草稿条目 */
    fun update(id: String, entry: CourseEntry) {
        courses = courses.map { if (it.id == id) entry else it }
    }

    /** 删除确认：仅移出草稿（保存时才写回仓库，取消编辑即整体还原） */
    fun remove(id: String) {
        courses = courses.filterNot { it.id == id }
    }

    companion object {
        /** 非编辑态的占位会话 */
        val Inactive: ScheduleEditSession = ScheduleEditSession(active = false)

        val Saver: Saver<ScheduleEditSession, Any> = listSaver(
            save = { session ->
                listOf(
                    session.active.toString(),
                    ScheduleJson.encodeStorage<List<CourseEntry>>(session.courses),
                )
            },
            restore = { saved ->
                // 草稿取末项：旧格式曾把快照一并存（[active, 快照, 草稿]），末项同样是草稿
                if (saved.isEmpty()) {
                    Inactive
                } else {
                    ScheduleEditSession(
                        draft = ScheduleJson.decodeStorage<List<CourseEntry>>(saved.last()),
                        active = saved[0].toBooleanStrictOrNull() ?: false,
                    )
                }
            },
        )
    }
}
