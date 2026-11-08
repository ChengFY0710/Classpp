package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.Todo

/**
 * 通用课程标签派生（校无关，指导 §3.5）：同步来源的待办匹配到激活课表课程时，把对应
 * 课程标签直接并入 [Todo.tags]——与手动勾选的课程标签完全同口径：卡片按普通标签胶囊
 * 渲染（tagColors 取课程卡配色）、编辑/详情浮层显示为已选中、课程详情浮层按课程名关联
 * 待办也能命中。幂等；未匹配原样返回。只在 UI 展示源上派生，不写回仓库（用户编辑保存时
 * 随 selectedTags 自然落库）。
 *
 * 判定口径从「source == 某校」泛化为「source 非空且 sourceDetail 在课表课程名集内」：
 * 所有同步来源的学校自动获得同一能力，新增学校无需改 UI。
 */
fun Todo.withMatchedCourseTag(courseNames: Set<String>): Todo {
    val tag = matchedCourseTag(courseNames) ?: return this
    return if (tag in tags) this else copy(tags = tags + tag)
}

/** 来源课程名（去首尾空格）在 [courseNames] 内时返回课表内的课程名，否则 null */
private fun Todo.matchedCourseTag(courseNames: Set<String>): String? {
    if (source == null) return null
    return sourceDetail?.trim()?.takeIf { it in courseNames }
}
