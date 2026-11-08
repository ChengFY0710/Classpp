package com.fangyi.classpp.ui.note

import androidx.compose.runtime.compositionLocalOf

/**
 * 待办来源徽标资源（`source` → `@StringRes`）：由宿主按学校注册表注入（指导 §3.5）。
 *
 * 已注册学校自动获得徽标文案（`SchoolSpec.sourceBadgeRes`）；未注册来源与用户手建
 * （source = null）不显示徽标——不再硬编码任何学校 id。
 */
internal val LocalTodoSourceBadges = compositionLocalOf<Map<String, Int>> { emptyMap() }
