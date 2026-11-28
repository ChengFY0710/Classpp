package com.fangyi.classpp.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.navigation.AppTab

/**
 * 默认首页三选一（个性化页「通用」分组）：冷启动打开的页面，行文案复用底部导航的
 * 页签标签（日程 / 课表 / 待办）。只作用于冷启动（进程重建由 TabPagerState 的 Saver
 * 恢复上次选中页），通知点击落页不受它影响。
 */
enum class DefaultHomePage(
    @StringRes val labelRes: Int,
) {
    Agenda(R.string.nav_schedule),
    Timetable(R.string.nav_timetable),
    Todo(R.string.nav_todo),
    ;

    /** 冷启动落页：映射到底部导航的 tab */
    val appTab: AppTab
        get() = when (this) {
            Agenda -> AppTab.Agenda
            Timetable -> AppTab.Timetable
            Todo -> AppTab.Todo
        }
}

/**
 * 默认首页持久化：SharedPreferences 单键存储（模式同 [AgendaPreferences]）。
 * 只影响下一次冷启动 `rememberTabPagerState` 的初始 tab；改选即落盘，无需去抖。
 */
object DefaultHomePagePreferences {
    private const val FILE_NAME = "default_home_prefs"
    private const val KEY_PAGE = "default_home_page"

    /** 读取默认首页；无记录或记录无法识别时回退课表（历史默认落页） */
    fun load(context: Context): DefaultHomePage {
        val saved = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PAGE, null) ?: return DefaultHomePage.Timetable
        return DefaultHomePage.entries.firstOrNull { it.name == saved } ?: DefaultHomePage.Timetable
    }

    /** 保存默认首页（apply 异步落盘；UI 状态由调用方同步更新，不等落盘） */
    fun save(context: Context, page: DefaultHomePage) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PAGE, page.name)
            .apply()
    }
}
