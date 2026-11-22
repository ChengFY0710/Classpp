package com.fangyi.classpp.data.model

/**
 * 课程编辑面板的表单状态 → [CourseEntry]（纯函数，添加/编辑课程面板与单测共用）。
 *
 * 面板里可编辑的字段就是构造入参；面板**不出现**的字段必须从 [existing] 原样保留——
 * 逐字段重新构造时漏掉 [CourseEntry.note]，编辑改个配色就会把课程详情里写好的备注
 * 清空（编辑态确认后整条替换草稿，丢失无法找回）。新建（[existing] 为空）各字段取
 * 面板值、备注为空串；编辑沿用原 id（写回时按 id 覆盖草稿条目）。
 *
 * 文本字段在这里统一去首尾空白，与面板提交口径一致。
 */
fun buildCourseEntry(
    existing: CourseEntry?,
    name: String,
    teacher: String,
    location: String,
    dayOfWeek: Int,
    startSlot: Int,
    span: Int,
    weeks: WeekPattern,
    color: CourseColor,
): CourseEntry = CourseEntry(
    // 编辑态沿用原 id（写回时按 id 覆盖），添加态生成新 id
    id = existing?.id ?: newUuid(),
    name = name.trim(),
    teacher = teacher.trim(),
    location = location.trim(),
    dayOfWeek = dayOfWeek,
    startSlot = startSlot,
    span = span,
    weeks = weeks,
    color = color,
    // 面板不编辑备注：原条目的备注原样保留（新建时为空串）
    note = existing?.note.orEmpty(),
)

/**
 * 课程内容更新后的调课清理口径：新周规律已不含原课次（[CourseAdjustment.fromWeek]
 * 落出新周数）的单次调课一并移除——原课次不成立，留着只会被校验报
 * AdjustmentSourceInvalid。
 *
 * 编辑会话的 update() 与编辑面板的预校验共用同一条规则，保证「面板放行 = 保存放行」：
 * 面板若拿未清理的旧调课做校验，合法的缩周编辑会被误报成错误，而确认后本该正常清掉它。
 */
fun List<CourseAdjustment>.prunedForCourse(courseId: String, entry: CourseEntry): List<CourseAdjustment> =
    filterNot { it.courseId == courseId && !entry.weeks.contains(it.fromWeek) }
