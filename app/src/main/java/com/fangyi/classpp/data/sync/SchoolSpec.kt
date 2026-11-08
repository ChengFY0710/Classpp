package com.fangyi.classpp.data.sync

import android.content.Context
import androidx.annotation.StringRes
import java.util.Locale

/**
 * 学校可提供的能力：UI 按能力逐行显隐，不认学校 id。
 *
 * - [NATIVE_LOGIN]：原生登录（保存凭据 + 自动续期），对应 [SchoolSession.autoRenew]；
 * - [WEBVIEW_LOGIN]：WebView 登录，对应 [SchoolSession.webViewLogin]；
 * - [SCHEDULE_IMPORT]：教务课表导入（可选能力），对应 [SchoolSession.scheduleImport]
 *   与 [SchoolSpec.timetableIntro]。
 */
enum class Capability {
    NATIVE_LOGIN,
    WEBVIEW_LOGIN,
    SCHEDULE_IMPORT,
}

/**
 * 学校文案的双源值：编译期资源（学校适配层提供）或远端声明的内联文本。
 *
 * - [Res]：资源 id，随系统语言切换（与 `stringResource` 同语义）；
 * - [Inline]：远端声明学校（`SchoolSpec.remote = true`）携带的 zh/en 文本，en 缺省回退 zh；
 *   本地化口径与资源一致——只支持 zh/en 两种语言，其它语言回落中文（默认资源目录语义）。
 *
 * 解析入口：Compose 用 `ui/schools` 的 `schoolText()`；非 Compose（provider/通知）
 * 用 [resolve]（Context 重载）。纯函数 [SchoolText.Inline.resolve] 供 JVM 单测。
 */
sealed interface SchoolText {

    /** 编译期资源文案 */
    data class Res(@StringRes val id: Int) : SchoolText

    /** 远端声明内联文案：zh 必备，en 可缺（缺省回退 zh） */
    data class Inline(val zh: String, val en: String? = null) : SchoolText {

        /** 纯语言码解析（`Locale.language`，如 "zh"/"en"）：与资源回退口径一致，JVM 可测 */
        fun resolve(lang: String): String = if (lang == "en") (en ?: zh) else zh
    }
}

/** 非 Compose 侧解析：资源走 [Context.getString]，内联走系统语言 */
fun SchoolText.resolve(context: Context): String = when (this) {
    is SchoolText.Res -> context.getString(id)
    is SchoolText.Inline -> resolve(Locale.getDefault().language)
}

/**
 * 学校的静态声明。编译期学校由适配层以资源文案构造（常量）；远端声明学校由
 * `data/sync/remote` 从 JSON 构造（全部内联文案，[remote] = true）。
 *
 * [id] 同时是存储命名空间、待办 `source` 与本地 id 前缀的派生源，必须匹配
 * `^[a-z][a-z0-9_]{0,31}$`，不得含 `/`、`-` 等分隔符，也不得与既有 todo source
 * （`xmu` / `ai` / `wakeup` 等）冲突——注册表启动时硬校验。
 *
 * 文案纪律：学校专属文案全部由本声明提供。厦大把槽位指向现有 `xmu_*` 资源键，
 * 视觉零变化；新校自行提供（编译或远端声明）；含校名/「学号」的文案不允许落到
 * 通用默认值上——通用 UI 只在槽位为 null 时回落 `sync_*` 中性键。
 */
data class SchoolSpec(
    /** 稳定学校 id（`xmu` / `fzu` / …）：存储命名空间 / todo source / id 前缀都派生自它 */
    val id: String,
    /** 学校名（选择卡、入口卡用） */
    val displayName: SchoolText,
    val capabilities: Set<Capability>,
    val headerTitle: SchoolText,
    val headerDescription: SchoolText,
    val introTitle: SchoolText,
    val introBody: SchoolText,
    /** 原生登录账号框标签（「学号」/「账号」） */
    val usernameLabel: SchoolText,
    /** 账号框占位（「统一身份认证账号」） */
    val usernameHint: SchoolText,
    /** 空输入提示（「请先输入学号和密码」） */
    val loginBlankHint: SchoolText,
    /** 原生登录「注意事项」正文（含「学号」等字样） */
    val loginNotes: SchoolText,
    /** WebView 登录浮层标题（「登录课程中心」） */
    val loginSheetTitle: SchoolText,
    /** 待办来源徽标（「课程中心」） */
    val sourceBadge: SchoolText,
    /** 自动分组默认名（「作业」） */
    val groupHomework: SchoolText,
    /** 自动分组默认名（「测验」） */
    val groupQuiz: SchoolText,
    /** 教务导入默认课表名（「教务课表」） */
    val defaultScheduleName: SchoolText,
    /** 每日同步通知标题（「课程中心自动同步完成」） */
    val syncNotifTitle: SchoolText,
    /** 换号提示；null = 使用中性默认键 `sync_error_account_changed` */
    val accountChanged: SchoolText? = null,
    /** 退出确认正文；null = 使用中性默认键 `sync_logout_confirm_message` */
    val logoutMessage: SchoolText? = null,
    /** 账号变化确认正文；null = 使用中性默认键 `sync_switch_message` */
    val switchMessage: SchoolText? = null,
    /**
     * 服务繁忙文案（[SyncError.ServiceBusy] / [LoginError.ServiceBusy]）；原有实现里可能含
     * 服务名（如「课程中心服务繁忙」），故由 spec 覆盖；null = 中性默认键 `sync_error_busy`
     */
    val serviceBusy: SchoolText? = null,
    /**
     * 账号或密码错误文案（[LoginError.InvalidCredentials]）；原有实现里可能含「学号」等
     * 校别措辞，故由 spec 覆盖；null = 中性默认键 `sync_login_invalid_credentials`
     */
    val invalidCredentials: SchoolText? = null,
    /**
     * 「登录成功但取数仍判过期」文案（[LoginError.SessionNotEstablished]）；原有实现里可能
     * 含服务名，故由 spec 覆盖；null = 中性默认键 `sync_login_not_established`
     */
    val sessionNotEstablished: SchoolText? = null,
    /**
     * 关闭自动续期确认正文（含「学号/账号」措辞）；null = 中性默认键
     * `sync_auto_login_disable_message`
     */
    val autoLoginDisableMessage: SchoolText? = null,
    /** 原生登录浮层顶部图（drawable 资源；每次打开随机取一张）；空 = 不显示（新校不必提供） */
    val loginImages: List<Int> = emptyList(),
    /** 教务导入介绍卡；无 [Capability.SCHEDULE_IMPORT] 时必须为 null（注册表校验） */
    val timetableIntro: TimetableIntro? = null,
    /**
     * 是否远端声明学校：true = 由 `data/sync/remote` 从 JSON 下发构造（文案全部 Inline），
     * UI 在首次连接前做登录域确认（信任边界：远端白名单不再来自编译期代码）。
     */
    val remote: Boolean = false,
)

/** 教务导入介绍卡文案（学校可选提供） */
data class TimetableIntro(
    val title: SchoolText,
    val body: SchoolText,
)
