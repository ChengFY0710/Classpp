package com.fangyi.classpp.data.shiguang

import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.wakeup.WakeupCourseConfig
import com.fangyi.classpp.data.wakeup.WakeupScheduleMapper
import com.fangyi.classpp.data.wakeup.WakeupSharePayload

/**
 * 拾光脚本结果 → Classpp 课表（纯函数）。
 *
 * 拾光适配脚本与 Wakeup 共用同一套桥接数据契约（课程/作息/学期配置字段完全一致），
 * 因此不重复实现映射规则：先装成 [WakeupSharePayload]，再复用
 * [WakeupScheduleMapper]（同名课合并、周次奇偶/断点切段、span > 5 拆分、颜色轮询、
 * 作息续生成、开学日推算）。本类只负责失败判定与课表名兜底。
 *
 * 已知取舍：
 * - `isCustomTime` 课程（典型为考试）在 Classpp 的节次网格里没有对应表达，沿用现有
 *   映射器行为——没有合法起止节次即被丢弃；
 * - `saveComboSchedule` 的分时段作息方案不迁移，只应用基础作息与学期配置。
 */
internal object ShiguangScheduleMapper {

    /**
     * @param fallbackName 课表名兜底（UI 传入本地化的「拾光课表」）
     * @return 结果失败、没有可导入课程、或映射后为空时返回 null
     */
    fun toScheduleOrNull(result: ShiguangScriptResult, fallbackName: String): Schedule? {
        if (!result.ok) return null
        val courses = result.courses.orEmpty()
        if (courses.isEmpty()) return null
        val payload = WakeupSharePayload(
            courses = courses,
            timeSlots = result.timeSlots.orEmpty(),
            config = result.config ?: WakeupCourseConfig(),
        )
        return WakeupScheduleMapper.toScheduleOrNull(
            payload = payload,
            shareText = "",
            fallbackName = fallbackName,
        )
    }
}
