package com.fangyi.classpp.ui.note

import android.content.Context
import com.fangyi.classpp.data.TodoSort

/**
 * 待办顶栏排序/分类/视图偏好：SharedPreferences 单文件四键存储（同 [TodoInboxPreferences]
 * 模式；apply 异步落盘，UI 状态由调用方同步更新）。
 *
 * 顶栏动作行的「排序」「分类」「视图」是跨会话选择：rememberSaveable 只覆盖旋转/切 tab/
 * 进程重建，普通冷启动会回默认——本对象把四个选择落盘，冷启动即回到上次口径。
 *
 * - **排序键**（[loadSort] / [saveSort]）：默认 [TodoSort.Deadline]——截止日期早的
 *   在前（Deadline 的降序定义见 [TodoSort]）；存枚举名，读到未知名字（旧版本写入/
 *   枚举重命名）回退默认。
 * - **排序方向**（[loadSortDescending] / [saveSortDescending]）：默认降序（true，
 *   更要紧的在前）。
 * - **分类显隐**（[loadVisibleSectionNames] / [saveVisibleSectionNames]）：存分组名
 *   的逗号分隔串（分组名由调用方以 `TodoSection.name` 提供）。默认返回 null = 无
 *   记录（调用方回四组全选）；空集编码为空串，是合法记录——用户把四组全部取消勾选
 *   （列表空白），必须与「无记录」区分，故不用 `getStringSet` 的「空集丢失」口径。
 * - **列表/收集箱视图选择**（[loadInboxView] / [saveInboxView]）：默认列表；新文件
 *   无记录时回读旧位置（本对象接管前存在 [TodoInboxPreferences] 的同名键），老用户
 *   的选择不因换文件重置。
 */
object TodoTopBarPreferences {
    private const val FILE_NAME = "todo_top_bar_prefs"
    private const val KEY_SORT = "sort"
    private const val KEY_SORT_DESCENDING = "sort_descending"
    private const val KEY_VISIBLE_SECTIONS = "visible_sections"
    private const val KEY_INBOX_VIEW = "inbox_view"

    /** 视图选择的旧存储文件（[TodoInboxPreferences] 的 FILE_NAME），仅作迁移回读 */
    private const val LEGACY_INBOX_FILE_NAME = "todo_inbox_prefs"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** 读取排序键；无记录或枚举名失效回退 [TodoSort.Deadline]（默认截止日期） */
    fun loadSort(context: Context): TodoSort {
        val saved = prefs(context).getString(KEY_SORT, null)
        return TodoSort.entries.firstOrNull { it.name == saved } ?: TodoSort.Deadline
    }

    /** 保存排序键（apply 异步落盘，不阻塞点击） */
    fun saveSort(context: Context, sort: TodoSort) {
        prefs(context).edit()
            .putString(KEY_SORT, sort.name)
            .apply()
    }

    /** 读取排序方向；无记录回退 true（降序，更要紧的在前） */
    fun loadSortDescending(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SORT_DESCENDING, true)

    /** 保存排序方向（apply 异步落盘，不阻塞点击） */
    fun saveSortDescending(context: Context, descending: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_SORT_DESCENDING, descending)
            .apply()
    }

    /**
     * 读取分类显隐的分组名集合；null = 无记录（调用方回默认全选），空集 = 四组全隐。
     * 空串（空集的编码）在拆分时滤除；未知名字原样返回，由调用方的枚举映射忽略。
     */
    fun loadVisibleSectionNames(context: Context): Set<String>? {
        val saved = prefs(context).getString(KEY_VISIBLE_SECTIONS, null) ?: return null
        return saved.split(',').filterTo(mutableSetOf()) { it.isNotEmpty() }
    }

    /** 保存分类显隐的分组名集合（空集编码为空串，与无记录区分；apply 异步落盘） */
    fun saveVisibleSectionNames(context: Context, names: Set<String>) {
        prefs(context).edit()
            .putString(KEY_VISIBLE_SECTIONS, names.joinToString(","))
            .apply()
    }

    /**
     * 读取上次选择的视图：true = 收集箱、false = 列表；无记录回退列表。
     * 新文件无记录时回读旧位置（`todo_inbox_prefs` 的同名键）——视图选择在本对象
     * 接管前存在 [TodoInboxPreferences]，直接换文件会把老用户的选择重置成列表。
     * 读取本身不写盘：宿主首帧的 LaunchedEffect 会把读到的值写进新文件（幂等）。
     */
    fun loadInboxView(context: Context): Boolean {
        val prefs = prefs(context)
        if (prefs.contains(KEY_INBOX_VIEW)) return prefs.getBoolean(KEY_INBOX_VIEW, false)
        return context.getSharedPreferences(LEGACY_INBOX_FILE_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_INBOX_VIEW, false)
    }

    /** 保存视图选择（apply 异步落盘，不阻塞切换） */
    fun saveInboxView(context: Context, inbox: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_INBOX_VIEW, inbox)
            .apply()
    }
}
