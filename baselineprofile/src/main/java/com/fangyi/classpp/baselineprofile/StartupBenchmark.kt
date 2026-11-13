package com.fangyi.classpp.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 冷启动基准：同一台设备、同一组迭代下对比「无预编译」与「Baseline Profile 部分编译」的
 * `timeToInitialDisplay`（App 未调用 `reportFullyDrawn`，不产出 `timeToFullDisplay`）。
 *
 * 运行（本机连设备；按项目约定不在 CI/模拟器跑）：
 * ```
 * ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
 * ```
 *
 * 生成 profile 之前只有 [coldStartupNoCompilation] 可作基线；[coldStartupBaselineProfile]
 * 走 [BaselineProfileMode.Require]，缺少已嵌入的规则会直接失败——这是有意的，避免把
 * 「profile 未生效」误读成收益。生成并重新安装后再跑完整两组做对比。
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartupNoCompilation() = coldStartup(CompilationMode.None())

    @Test
    fun coldStartupBaselineProfile() = coldStartup(
        CompilationMode.Partial(BaselineProfileMode.Require),
    )

    private fun coldStartup(compilationMode: CompilationMode) {
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            iterations = 10,
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
        }
    }

    private companion object {
        const val PACKAGE_NAME = "com.fangyi.classpp"
    }
}
