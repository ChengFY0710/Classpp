package com.fangyi.classpp.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 字体规范守卫：把《字体使用规范》的静态检查固化为单元测试，随
 * `./gradlew :app:testDebugUnitTest` 执行。
 *
 * 规则：
 * 1. 业务代码（`ui/theme` 外）不得出现数字 `.sp` 字面量、内联 `TextStyle(`、
 *    `lineHeight` / `letterSpacing` / `fontFamily` 赋值，也不得 import `sp`。
 * 2. 全项目不得使用 `FontWeight.Bold`；`FontWeight.Black` 只允许出现在 `ui/theme`。
 * 3. `fontFeatureSettings` 只允许作为等宽数字 token 的实现出现在 `TextTokens.kt`。
 *
 * 新增文字请使用 [ClassppTextTokens]、`MaterialTheme.classppTextStyles` 或
 * `MaterialTheme.typography`；确需例外时，先更新规范文档与这里白名单。
 */
class FontUsageRulesTest {

    private val sourceRoot = locateSourceRoot()
    private val themeRoot = File(sourceRoot, "com/fangyi/classpp/ui/theme")
    private val allFiles = sourceRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sortedBy { it.path }
        .toList()
    private val businessFiles = allFiles.filterNot { it.isUnder(themeRoot) }

    @Test
    fun sourceTreeIsDiscoverable() {
        assertTrue("未找到源码文件，工作目录可能不是 app/：${sourceRoot.absolutePath}", allFiles.size > 100)
        assertTrue("未找到 TextTokens.kt", File(themeRoot, "TextTokens.kt").isFile)
    }

    @Test
    fun businessCodeUsesTypographyTokensOnly() {
        val rules = listOf(
            Rule("数字 .sp 字面量", Regex("""\b\d+(?:\.\d+)?\.sp\b""")),
            Rule("内联 TextStyle", Regex("""\bTextStyle\s*\(""")),
            Rule("lineHeight 赋值", Regex("""\blineHeight\s*=""")),
            Rule("letterSpacing 赋值", Regex("""\bletterSpacing\s*=""")),
            Rule("fontFamily 赋值", Regex("""\bfontFamily\s*=""")),
            Rule("sp import", Regex("""import\s+androidx\.compose\.ui\.unit\.sp""")),
        )
        assertNoViolations(businessFiles, rules)
    }

    @Test
    fun boldWeightIsRetired() {
        assertNoViolations(
            allFiles,
            listOf(Rule("FontWeight.Bold", Regex("""FontWeight\.Bold\b"""))),
        )
    }

    @Test
    fun blackWeightStaysInTheme() {
        assertNoViolations(
            businessFiles,
            listOf(Rule("FontWeight.Black", Regex("""FontWeight\.Black\b"""))),
        )
    }

    @Test
    fun tabularNumbersAreTokenized() {
        val allowed = File(themeRoot, "TextTokens.kt").canonicalFile
        val violations = allFiles
            .filterNot { it.canonicalFile == allowed }
            .flatMap { it.violations(Regex("""fontFeatureSettings\s*="""), "fontFeatureSettings 赋值") }
        assertTrue(
            "fontFeatureSettings 只允许出现在 TextTokens.kt 的等宽数字 token 中：\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    private fun assertNoViolations(files: List<File>, rules: List<Rule>) {
        val violations = files.flatMap { file ->
            rules.flatMap { rule -> file.violations(rule.regex, rule.label) }
        }
        assertTrue(
            "字体规范守卫失败，请改用 ClassppTextTokens / classppTextStyles：\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    private fun File.violations(regex: Regex, label: String): List<String> =
        readLines(Charsets.UTF_8).mapIndexedNotNull { index, line ->
            if (regex.containsMatchIn(line)) {
                "${relativePath()}:${index + 1}: $label -> ${line.trim()}"
            } else {
                null
            }
        }

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
