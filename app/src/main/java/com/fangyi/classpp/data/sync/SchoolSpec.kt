package com.fangyi.classpp.data.sync

import androidx.annotation.StringRes

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
 * 学校的静态声明（编译期常量，不接受网络下发或用户输入域名，见指导 §五.5）。
 *
 * [id] 同时是存储命名空间、待办 `source` 与本地 id 前缀的派生源，必须匹配
 * `^[a-z][a-z0-9_]{0,31}$`，不得含 `/`、`-` 等分隔符，也不得与既有 todo source
 * （`xmu` / `ai` / `wakeup` 等）冲突——注册表启动时硬校验。
 *
 * 文案纪律：学校专属文案全部由本声明提供。厦大把槽位指向现有 `xmu_*` 资源键，
 * 视觉零变化；新校自行提供；含校名/「学号」的键不允许落到通用默认值上。
 */
data class SchoolSpec(
    /** 稳定学校 id（`xmu` / `fzu` / …）：存储命名空间 / todo source / id 前缀都派生自它 */
    val id: String,
    /** 学校名（选择卡、入口卡用） */
    @StringRes val displayNameRes: Int,
    val capabilities: Set<Capability>,
    @StringRes val headerTitleRes: Int,
    @StringRes val headerDescriptionRes: Int,
    @StringRes val introTitleRes: Int,
    @StringRes val introBodyRes: Int,
    /** 原生登录账号框标签（「学号」/「账号」） */
    @StringRes val usernameLabelRes: Int,
    /** 账号框占位（「统一身份认证账号」） */
    @StringRes val usernameHintRes: Int,
    /** 空输入提示（「请先输入学号和密码」） */
    @StringRes val loginBlankHintRes: Int,
    /** 原生登录「注意事项」正文（含「学号」等字样） */
    @StringRes val loginNotesRes: Int,
    /** WebView 登录浮层标题（「登录课程中心」） */
    @StringRes val loginSheetTitleRes: Int,
    /** 待办来源徽标（「课程中心」） */
    @StringRes val sourceBadgeRes: Int,
    /** 自动分组默认名（「作业」） */
    @StringRes val groupHomeworkRes: Int,
    /** 自动分组默认名（「测验」） */
    @StringRes val groupQuizRes: Int,
    /** 教务导入默认课表名（「教务课表」） */
    @StringRes val defaultScheduleNameRes: Int,
    /** 每日同步通知标题（「课程中心自动同步完成」） */
    @StringRes val syncNotifTitleRes: Int,
    /** 换号提示；null = 使用中性默认键 `sync_error_account_changed` */
    @StringRes val accountChangedRes: Int? = null,
    /** 退出确认正文；null = 使用中性默认键 `sync_logout_confirm_message` */
    @StringRes val logoutMessageRes: Int? = null,
    /** 账号变化确认正文；null = 使用中性默认键 `sync_switch_message` */
    @StringRes val switchMessageRes: Int? = null,
    /**
     * 服务繁忙文案（[SyncError.ServiceBusy] / [LoginError.ServiceBusy]）；原有实现里可能含
     * 服务名（如「课程中心服务繁忙」），故由 spec 覆盖；null = 中性默认键 `sync_error_busy`
     */
    @StringRes val serviceBusyRes: Int? = null,
    /**
     * 账号或密码错误文案（[LoginError.InvalidCredentials]）；原有实现里可能含「学号」等
     * 校别措辞，故由 spec 覆盖；null = 中性默认键 `sync_login_invalid_credentials`
     */
    @StringRes val invalidCredentialsRes: Int? = null,
    /**
     * 「登录成功但取数仍判过期」文案（[LoginError.SessionNotEstablished]）；原有实现里可能
     * 含服务名，故由 spec 覆盖；null = 中性默认键 `sync_login_not_established`
     */
    @StringRes val sessionNotEstablishedRes: Int? = null,
    /**
     * 关闭自动续期确认正文（含「学号/账号」措辞）；null = 中性默认键
     * `sync_auto_login_disable_message`
     */
    @StringRes val autoLoginDisableMessageRes: Int? = null,
    /** 原生登录浮层顶部图（drawable 资源；每次打开随机取一张）；空 = 不显示（新校不必提供） */
    val loginImages: List<Int> = emptyList(),
    /** 教务导入介绍卡；无 [Capability.SCHEDULE_IMPORT] 时必须为 null（注册表校验） */
    val timetableIntro: TimetableIntro? = null,
)

/** 教务导入介绍卡文案（学校可选提供） */
data class TimetableIntro(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
)
