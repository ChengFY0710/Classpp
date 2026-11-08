package com.fangyi.classpp.data.sync

/**
 * 中性诊断日志出口（指导 §2.4 / Phase 4）：通用 UI 与通用编排只依赖本对象，不引用任何
 * 学校自己的日志实现。默认空实现（JVM 测试、未接入的学校零输出），学校适配层在装配时
 * 注入自己的 sink（例如复用现有诊断日志）。
 *
 * 日志纪律沿用全仓库约定：只写状态与分类，不写账号、密码、cookie 或带参 URL。
 */
object SyncDebugLog {

    @Volatile
    private var sink: (String) -> Unit = {}

    /** 注入日志实现；重复调用覆盖（进程内只有一处装配点） */
    fun init(sink: (String) -> Unit) {
        this.sink = sink
    }

    fun d(message: String) {
        sink(message)
    }
}
