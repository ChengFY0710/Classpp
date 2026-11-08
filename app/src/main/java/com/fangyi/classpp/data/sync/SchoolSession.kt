package com.fangyi.classpp.data.sync

import android.app.PendingIntent
import android.content.Context
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.data.TodoRepository
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * 运行时会话契约（指导 §2.3）：槽位是否为 null 必须与 [SchoolSpec.capabilities] 一致，
 * 由 [SchoolRegistry] 在装配后硬校验。
 *
 * `autoRenew != null` ⇔ 支持原生登录（保存凭据 + 自动续期）；`webViewLogin != null`
 * ⇔ 支持 WebView 登录；两者可任选其一或都实现。UI 按槽位逐行显隐，不认学校 id。
 */
interface SchoolSession {
    val spec: SchoolSpec

    /** 必备：待办同步 */
    val sync: SyncService

    /** null ⇔ 无 [Capability.NATIVE_LOGIN] */
    val autoRenew: AutoRenewService?

    /** null ⇔ 无 [Capability.SCHEDULE_IMPORT] */
    val scheduleImport: ScheduleImportService?

    /** null ⇔ 无 [Capability.WEBVIEW_LOGIN] */
    val webViewLogin: WebViewLoginSpec?

    /**
     * 通用 WebView 登录浮层的登录态判定（profile 正文有效性）：
     * 由 provider 在装配驱动器时注入，UI 只转发给 [com.fangyi.classpp.data.sync.webview.WebSessionDriver]。
     * null = 通用默认口径（正文是 JSON 对象）。学校可在此做更严格校验（如必须含非空 uid）。
     *
     * 默认实现返回 null：不支持 WebView 登录的学校无需关心；自定义 UI 的学校不消费它。
     */
    val webViewProfileValidator: ((String) -> Boolean)?
        get() = null

    /** 每日同步开关（与 [SchoolProvider.dailySyncToggle] 同一存储） */
    val dailySync: DailySyncToggle
}

/**
 * 学校适配的装配入口：一校一个实现，注册进 [SchoolRegistry]。
 *
 * 通用层只通过本接口取学校能力，不 import 任何 `schools/<id>` 目录或学校协议类型。
 */
interface SchoolProvider {
    val spec: SchoolSpec

    /**
     * 轻量开关读写：后台重排不装配完整 session（与 [SchoolSession.dailySync] 共用同一存储，
     * 实现应让两者返回同一实例/同一份状态）。
     */
    fun dailySyncToggle(context: Context): DailySyncToggle

    /**
     * 每日同步的到点广播 PendingIntent 工厂（学校适配层声明）：接收类 + requestCode + action
     * 是已发布闹钟的冻结锚点，由各校薄壳持有，通用层不引用任何 receiver 类。
     * null = 本校不参与每日同步闹钟（[DailySyncController] 对该校跳过排期）。
     */
    val dailySyncFireIntent: ((Context) -> PendingIntent)? get() = null

    /**
     * WebView 登录声明的轻量访问（不装配完整会话）：远端声明学校在首次连接确认浮层里
     * 读取登录域（`dataHost`）用。默认 null（编译期学校不需要；确需时由适配层覆盖）。
     */
    val webViewLoginSpec: WebViewLoginSpec? get() = null

    /**
     * 教务课表页声明的轻量访问（不装配完整会话）：远端声明学校首次连接确认浮层展示
     * 课表页域名用。默认 null（无课表页的学校/未覆盖的编译期学校）。
     */
    val webViewTimetableSpec: WebViewTimetableSpec? get() = null

    /** 装配运行时会话；耗时操作应下沉 IO（注册表保证只装配一次） */
    suspend fun createSession(deps: SessionDeps): SchoolSession
}

/** 每日同步开关（按校持久化；写操作的闹钟重排由通用 scheduler 负责） */
interface DailySyncToggle {
    val enabled: StateFlow<Boolean>
    fun setEnabled(enabled: Boolean)
}

/** 会话装配所需的共享依赖（仓库与应用上下文；学校实现不得把仓库实例外泄到通用层） */
data class SessionDeps(
    val appContext: Context,
    val filesDir: File,
    val todoRepository: TodoRepository,
    val scheduleRepository: ScheduleRepository,
)
