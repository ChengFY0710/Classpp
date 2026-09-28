package com.fangyi.classpp.ui.schedule

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.model.CourseEntry

/**
 * 编辑态草稿：进入编辑时快照一份课程列表，之后的增/改/删只落在草稿里；
 * 保存时才按"草稿 vs 快照"的 diff（新增/修改/删除）逐条写回仓库，取消则整个丢弃
 * ——全程零写盘（数据层没有事务/回滚接口）。
 *
 * [active] 标记"是否处于编辑态"：`rememberSaveable` 不接受可空值，非编辑态用 [Inactive]
 * 占位。Saver 连 [active] 一起存，所以旋转 / 进程重建后"在不在编辑态"与草稿都能对上
 * （清单未锁方向，旋转会重建 Activity）。
 */
@Stable
class ScheduleEditSession internal constructor(
    private val initial: List<CourseEntry>,
    draft: List<CourseEntry> = initial,
    val active: Boolean = true,
) {
    private val initialIds: Set<String> = initial.map { it.id }.toSet()
    private val initialById: Map<String, CourseEntry> = initial.associateBy { it.id }

    /** 当前草稿（编辑态的网格直接渲染它） */
    var courses: List<CourseEntry> by mutableStateOf(draft)
        private set

    /** 待保存的新增课程：草稿里 id 不在快照里的 */
    fun addedCourses(): List<CourseEntry> = courses.filterNot { it.id in initialIds }

    /** 待保存的修改课程：快照里已有、内容与快照不同的 */
    fun updatedCourses(): List<CourseEntry> =
        courses.filter { it.id in initialIds && it != initialById[it.id] }

    /** 待保存的删除课程 id：快照里有、草稿里已不存在的 */
    fun removedCourseIds(): List<String> =
        initial.filterNot { c -> courses.any { it.id == c.id } }.map { it.id }

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
        val Inactive: ScheduleEditSession =
            ScheduleEditSession(initial = emptyList(), active = false)

        val Saver: Saver<ScheduleEditSession, Any> = listSaver(
            save = { session ->
                listOf(
                    session.active.toString(),
                    ScheduleJson.encodeStorage<List<CourseEntry>>(session.initial),
                    ScheduleJson.encodeStorage<List<CourseEntry>>(session.courses),
                )
            },
            restore = { saved ->
                if (saved.size < 3) {
                    Inactive
                } else {
                    ScheduleEditSession(
                        initial = ScheduleJson.decodeStorage<List<CourseEntry>>(saved[1]),
                        draft = ScheduleJson.decodeStorage<List<CourseEntry>>(saved[2]),
                        active = saved[0].toBooleanStrictOrNull() ?: false,
                    )
                }
            },
        )
    }
}
