package com.fangyi.classpp.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import org.junit.Rule
import org.junit.Test

/**
 * 冷启动 Baseline Profile 采集：只覆盖「桌面点图标 → 首帧」这一段关键路径。
 *
 * 生成命令（本机连设备；按项目约定不在 CI/模拟器跑）：
 * ```
 * ./gradlew :app:generateReleaseBaselineProfile
 * ```
 *
 * 采集在 nonMinifiedRelease 变体上进行，规则以原始类名落盘，随后由插件 / AGP 的 R8
 * profile 重写映射到 release 的混淆名。
 *
 * 这里不生成 startup profile（`includeInStartupProfile = false`）：插件的 startup profile
 * 按设计不过滤，写回 `src` 时会把依赖库规则一起合回来；只保留 App 过滤后的 baseline
 * profile，既覆盖冷启动代码路径，也避免生成文件膨胀数倍。
 *
 * BaselineProfileRule 官方支持 Android 13+，或 root 的 Android 9+；低于该条件会直接失败，
 * 生成时请使用 API 33+ 设备。
 */
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun coldStartup() {
        baselineProfileRule.collect(
            packageName = PACKAGE_NAME,
            // 不生成 startup profile：插件对 startup 规则不做 package 过滤，写回源码时
            // 会把依赖库规则一并带回来；baseline 已覆盖冷启动路径
            includeInStartupProfile = false,
        ) {
            // 先回桌面，确保每轮都是进程未存活的冷启动路径
            pressHome()
            startActivityAndWait()
            // 容器装载与首屏内容在首帧之后才落定（首帧先是加载指示器）；等 UI 空闲，
            // 把「加载完成 → 真实课表/待办渲染」这段启动路径也纳入 profile
            device.waitForIdle()
        }
    }

    private companion object {
        const val PACKAGE_NAME = "com.fangyi.classpp"
    }
}
