package com.fangyi.classpp.data.ai

/**
 * 小节合并（两小节 → 一大节）的节次换算规则：截图版与网页版提示词共用同一份文本，
 * 避免两处手写漂移。
 *
 * 换算必须先用 newEnd 反推 newSpan：`ceil(oldSpan / 2)` 在偶数节起始且跨对边界时
 * 会少算一节（旧第 2–3 节 → 新第 1–2 节，span 2 而非 1），span 变小不会触发冲突校验，
 * 导入后就是错课表。
 */
internal object SlotMergeRule {

    /** 提示词里的换算公式段落（含示例），调用方放在「合并模式换算公式」标题下 */
    const val PROMPT_TEXT: String = """- `newStart = floor((oldStart + 1) / 2)`
  - `newEnd = floor((oldStart + oldSpan) / 2)`；`newSpan = newEnd − newStart + 1`
  - 不要用 `ceil(oldSpan / 2)` 近似：偶数节起始且跨对边界的课会少算一节（如旧第 2–3 节 → 新第 1–2 节，span 2，而非 span 1）
  - 新节时间：取该对中首节的开始、末节的结束（如 8:00–8:45 与 8:55–9:40 合并为 8:00–9:40，中间课间不计入节次）
  - 例：旧 1 起跨 2 节 → 新 1 起跨 1 节；旧 2 起跨 2 节 → 新 1 起跨 2 节；旧 5 起跨 4 节 → 新 3 起跨 2 节"""
}
