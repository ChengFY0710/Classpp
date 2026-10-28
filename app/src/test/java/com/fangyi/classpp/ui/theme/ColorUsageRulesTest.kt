package com.fangyi.classpp.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 颜色规范守卫：把《颜色使用规范》（docs/颜色使用规范.md）的静态检查固化为单元测试，
 * 随 `./gradlew :app:testDebugUnitTest` 执行。
 *
 * 规则：
 * 1. 业务代码（`ui/theme` 外）不得出现 `Color(0x…)` 裸色值；功能调色板与预览数据按白名单放行。
 * 2. 不得直接 import `ui/theme/Color.kt` 的顶层色值 val（`Error` / `Correct` / `Primary` 等），
 *    取色必须经 `MaterialTheme.colorScheme` / `MaterialTheme.classppColors`。
 * 3. `MaterialTheme.isDarkTheme` 只允许既定的深浅分档场景（课程调色板参数、按压反馈方向、
 *    标签文字压暗系数、台历视觉/remember key），不得用于手动挑选不同色值。
 * 4. `colorScheme.onSurfaceVariant` 已归一到 `classppColors.secondaryText`，业务代码零使用。
 *
 * 新增颜色请先在 `ui/theme/Color.kt` 定义浅/深成对值并在 `Theme.kt` 装配；确需例外时，
 * 先更新规范文档与这里的白名单。
 */
class ColorUsageRulesTest {

    private val sourceRoot = locateSourceRoot()
    private val themeRoot = File(sourceRoot, "com/fangyi/classpp/ui/theme")
    private val allFiles = sourceRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sortedBy { it.path }
        .toList()
    private val businessFiles = allFiles.filterNot { it.isUnder(themeRoot) }

    /** 调色板与组件私有色的文件级白名单（规范第 11 节 2–4 条） */
    private val rawColorFileWhitelist = setOf(
        "com/fangyi/classpp/ui/schedule/CourseCard.kt", // 课程 18 色浅/深双表
        "com/fangyi/classpp/ui/note/NewTodoSheet.kt", // 标签 8 色板（深浅同值系有意）
        "com/fangyi/classpp/ui/components/ClassppSlider.kt", // MarkerPassedColor 滑杆标记
        "com/fangyi/classpp/ui/components/SheetComponentsPreviews.kt", // 纯预览文件
    )

    /** 直连顶层色值的挂账白名单（规范第 12 节 3 条，待迁移到 colorScheme.error） */
    private val themeValImportWhitelist = setOf(
        "com/fangyi/classpp/ui/schedule/CourseGrid.kt",
        "com/fangyi/classpp/ui/schedule/ScheduleHeader.kt",
    )

    @Test
    fun sourceTreeIsDiscoverable() {
        assertTrue("未找到源码文件，工作目录可能不是 app/：${sourceRoot.absolutePath}", allFiles.size > 100)
        assertTrue("未找到 Color.kt", File(themeRoot, "Color.kt").isFile)
        assertTrue("未找到 Theme.kt", File(themeRoot, "Theme.kt").isFile)
    }

    @Test
    fun businessCodeHasNoRawColorLiterals() {
        val rules = listOf(Rule("裸色值 Color(0x…)", Regex("""\bColor\(0x""")))
        val violations = businessFiles
            .filterNot { it.relativePath() in rawColorFileWhitelist }
            .flatMap { file -> file.violationsOutsidePreviews(rules) }
        assertTrue(
            "颜色规范守卫失败：业务代码不得写裸色值，请改用 colorScheme / classppColors / " +
                "CourseColor.barColor / NewTagColorPalette：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun themeColorValsAreNotImportedDirectly() {
        // ui/theme/Color.kt 的顶层色值名单（Dark* 全族含在内）
        val themeVals =
            "Primary|OnPrimary|PrimaryContainer|OnPrimaryContainer|InversePrimary|Background|" +
                "Surface|OnSurface|OnSurfaceVariant|SurfaceContainerHigh|Outline|OnSurfaceCard|" +
                "BottomSheetColor|Error|ErrorContainer|OnErrorContainer|Scrim|Correct|" +
                "SwitchDisabled|UrgentOrange|UrgentYellow|UrgentGreen|Dark\\w+"
        val rule = Rule(
            "直连主题顶层色值",
            Regex("""import\s+com\.fangyi\.classpp\.ui\.theme\.($themeVals)\b"""),
        )
        val violations = businessFiles
            .filterNot { it.relativePath() in themeValImportWhitelist }
            .flatMap { file -> file.violations(listOf(rule)) }
        assertTrue(
            "颜色规范守卫失败：请改用 MaterialTheme.colorScheme / MaterialTheme.classppColors：" +
                "\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun isDarkThemeUsageIsBounded() {
        val allowedPatterns = listOf(
            // 课程调色板参数：CourseColor.barColor(isDarkTheme)
            Rule("调色板参数", Regex("""\bbarColor\(""")),
            // 按压反馈明暗方向：PressShade.Brighten / PressShade.Darken
            Rule("按压反馈方向", Regex("""\bPressShade\.""")),
            // 标签文字压暗系数：TagTextDimFactor* / TaskTagTextDimFactor*
            Rule("压暗系数", Regex("""\w*DimFactor""")),
            // 台历视觉 / 标签色 remember key 的局部变量
            Rule("局部变量", Regex("""\bval\s+(dark|darkTheme)\s*=""")),
        )
        val violations = businessFiles.flatMap { file ->
            file.readLines(Charsets.UTF_8).mapIndexedNotNull { index, line ->
                val trimmed = line.trim()
                if (!trimmed.contains("isDarkTheme")) return@mapIndexedNotNull null
                if (trimmed.startsWith("import ") || trimmed.startsWith("//") ||
                    trimmed.startsWith("/*") || trimmed.startsWith("*")
                ) {
                    return@mapIndexedNotNull null
                }
                val matched = allowedPatterns.firstOrNull { it.regex.containsMatchIn(line) }
                if (matched == null) {
                    "${file.relativePath()}:${index + 1}: isDarkTheme 越界使用 -> ${trimmed}"
                } else {
                    null
                }
            }
        }
        assertTrue(
            "颜色规范守卫失败：isDarkTheme 只允许调色板参数 / 按压反馈方向 / 压暗系数 / " +
                "台历视觉与 remember key 四类场景（规范 6.4）：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun onSurfaceVariantStaysRetired() {
        assertNoViolations(
            businessFiles,
            listOf(
                Rule(
                    "colorScheme.onSurfaceVariant",
                    Regex("""colorScheme\.onSurfaceVariant\b"""),
                ),
            ),
            "颜色规范守卫失败：onSurfaceVariant 已归一到 classppColors.secondaryText" +
                "（规范第 12 节 9 条），不要再新增使用：\n",
        )
    }

    private fun assertNoViolations(files: List<File>, rules: List<Rule>, message: String) {
        val violations = files.flatMap { file -> file.violations(rules) }
        assertTrue(message + violations.joinToString("\n"), violations.isEmpty())
    }

    /**
     * 逐行找 [rules] 违规，但跳过文件末尾的预览段。预览段从首个预览标记开始到文件末尾：
     * `@Preview` 注解、`val XxxPreview…` 预览常量（含 GroupCard `PreviewIconItems` 这类
     * 多行预览数据）或「// ---- 预览 ----」分节注释。预览专用的辅助 composable 可能不带
     * `@Preview`（如 `NoteCardShowcase`），故按文件布局放行，**预览段必须保持在文件末尾**。
     */
    private fun File.violationsOutsidePreviews(rules: List<Rule>): List<String> {
        val violations = mutableListOf<String>()
        var inPreviewRegion = false
        readLines(Charsets.UTF_8).forEachIndexed { index, line ->
            if (!inPreviewRegion && previewMarkerRegex.containsMatchIn(line)) {
                inPreviewRegion = true
                return@forEachIndexed
            }
            if (!inPreviewRegion) violations += violations(line, index, rules)
        }
        return violations
    }

    private val previewMarkerRegex = Regex(
        """@Preview\b|\bval\s+\w*Preview\w*\b|//\s*-+\s*预览\s*-+""",
    )

    private fun File.violations(line: String, index: Int, rules: List<Rule>): List<String> =
        rules.mapNotNull { rule ->
            if (rule.regex.containsMatchIn(line)) {
                "${relativePath()}:${index + 1}: ${rule.label} -> ${line.trim()}"
            } else {
                null
            }
        }

    private fun File.violations(rules: List<Rule>): List<String> =
        readLines(Charsets.UTF_8).flatMapIndexed { index, line -> violations(line, index, rules) }

    private fun File.relativePath(): String = relativeTo(sourceRoot).invariantSeparatorsPath

    private fun File.isUnder(dir: File): Boolean {
        val path = canonicalPath
        val dirPath = dir.canonicalPath
        return path == dirPath || path.startsWith(dirPath + File.separator)
    }

    private fun locateSourceRoot(): File {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Cannot locate app source root from ${File(".").absolutePath}")
    }

    private data class Rule(val label: String, val regex: Regex)
}
