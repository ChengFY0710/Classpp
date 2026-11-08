package com.fangyi.classpp

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 架构守卫（指导 §四 Phase 2 验收）：静态锁死依赖方向，评审不依赖记忆。
 *
 * 1. UI / 宿主层的泛化文件不得 import 任何学校实现（`data.xmu`）——学校能力只经
 *    `data.sync` 契约与 `schools/<id>` 适配层进入；
 * 2. 通用层 `data/sync` 不得引用学校实现、不得 import `schools.*`、不得出现学校 id
 *    字符串字面量或 `R.string.xmu_*` 资源键（资源 id 只能由 spec 从学校适配层提供）。
 *
 * 只查源码文本（import 行与字符串字面量），不解析 Kotlin AST：约束本身就是「不要写」，
 * 注释里提到旧键名（反引号包裹）不算违规。
 */
class ArchitectureGuardTest {

    // ---------- 1. UI / 宿主层不依赖学校实现 ----------

    @Test
    fun `generalized UI and host files do not import school implementation`() {
        val files = listOf(
            "com/fangyi/classpp/MainActivity.kt",
            "com/fangyi/classpp/ui/settings/AccountSyncScreen.kt",
            "com/fangyi/classpp/ui/settings/CredentialLoginSheet.kt",
            "com/fangyi/classpp/ui/settings/SchoolPickerSheet.kt",
            "com/fangyi/classpp/ui/settings/SettingsScreen.kt",
            "com/fangyi/classpp/ui/schedule/SchoolImportSheet.kt",
            "com/fangyi/classpp/ui/schedule/ScheduleScreen.kt",
            "com/fangyi/classpp/ui/note/TodoScreen.kt",
            "com/fangyi/classpp/ui/day/DayScreen.kt",
        )
        for (relative in files) {
            val text = source(relative)
            assertFalse(
                "$relative must not import data.xmu",
                IMPORT_SCHOOL_IMPL.containsMatchIn(text),
            )
        }
    }

    @Test
    fun `generalized settings files use neutral or spec-provided copy only`() {
        // 含校名/服务名/学号的旧键只允许出现在学校适配层；通用 UI 的账号流文案由 spec 覆盖
        val forbidden = listOf(
            "R.string.xmu_school_name",
            "R.string.xmu_header_title",
            "R.string.xmu_error_account_changed",
            "R.string.xmu_auto_login_warning",
            "R.string.xmu_auto_login_blank",
            "R.string.xmu_auto_login_invalid_credentials",
            "R.string.xmu_auto_login_not_established",
            "R.string.xmu_auto_login_disable_message",
        )
        val files = listOf(
            "com/fangyi/classpp/ui/settings/AccountSyncScreen.kt",
            "com/fangyi/classpp/ui/settings/CredentialLoginSheet.kt",
        )
        for (relative in files) {
            val text = source(relative)
            for (key in forbidden) {
                assertFalse("$relative must not reference $key", text.contains(key))
            }
        }
    }

    // ---------- 2. 通用层不引用任何学校 ----------

    @Test
    fun `school-agnostic package has no school references`() {
        val dir = File(sourceRoot(), "com/fangyi/classpp/data/sync")
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("data/sync sources not found under $dir", files.isNotEmpty())
        for (file in files) {
            val text = file.readText(Charsets.UTF_8)
            assertFalse(
                "${file.name} must not import school implementation",
                IMPORT_SCHOOL_IMPL.containsMatchIn(text),
            )
            assertFalse(
                "${file.name} must not reference school implementation types",
                text.contains("data.xmu"),
            )
            assertFalse(
                "${file.name} must not import schools.*",
                IMPORT_SCHOOLS.containsMatchIn(text),
            )
            assertFalse(
                "${file.name} must not reference school resource keys",
                text.contains("R.string.xmu_"),
            )
            assertFalse(
                "${file.name} must not contain school id literals",
                SCHOOL_ID_LITERAL.containsMatchIn(text) || text.contains("\"xmu\""),
            )
        }
    }

    // ---------- 源码定位 ----------

    /** 单元测试工作目录通常是模块目录（app/）；兼容从仓库根运行 */
    private fun sourceRoot(): File =
        listOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: error("main source root not found (working dir = ${File(".").absolutePath})")

    private fun source(relative: String): String {
        val file = File(sourceRoot(), relative)
        assertTrue("source not found: ${file.absolutePath}", file.isFile)
        return file.readText(Charsets.UTF_8)
    }

    private companion object {
        val IMPORT_SCHOOL_IMPL = Regex(
            """(?m)^\s*import\s+com\.fangyi\.classpp\.data\.xmu(?:\.[\w.]*)?\s*$""",
        )
        val IMPORT_SCHOOLS = Regex(
            """(?m)^\s*import\s+com\.fangyi\.classpp\.schools(?:\.\w+)*\s*$""",
        )
        /** 学校 id 出现在任何字符串字面量里（`"xmu"`、`"xmu:homework"` 等） */
        val SCHOOL_ID_LITERAL = Regex(""""xmu[:"\-]""")
    }
}
