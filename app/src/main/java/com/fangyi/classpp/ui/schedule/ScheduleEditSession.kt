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
 * 编辑态草稿：进入编辑时快照一份课程列表，之后的添加只落在这里；
 * 保存时才逐条写回仓库，取消则整个丢弃——全程零写盘（数据层没有事务/回滚接口）。
 *
 * 本轮只做加课，故"新增" = 草稿里 id 不在快照里的课。将来做删除/修改时在这里补 diff 即可，
 * 快照一直留着可直接比对。
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

    /** 当前草稿（编辑态的网格直接渲染它） */
    var courses: List<CourseEntry> by mutableStateOf(draft)
        private set

    /** 待保存的新增课程 */
    fun addedCourses(): List<CourseEntry> = courses.filterNot { it.id in initialIds }

    fun add(course: CourseEntry) {
        courses = courses + course
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
