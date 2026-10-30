package com.fangyi.classpp.ui.day

import android.content.Context
import com.fangyi.classpp.data.model.IsoDate

/**
 * 日程页「当日是否已开启 + 离开时状态」的落盘入口。
 *
 * 与 [DayImageStore] 同一套单文件 SharedPreferences 模式：记录很小，不值得引入
 * DataStore；读取在首帧组合前同步完成，避免先闪 01 再跳到 02/04。
 *
 * 只写 Compact/Schedule；Calendar 不落盘（01→02 取消、02 重播回 01 都不改变
 * 「当日已开启」标记）。损坏/未知值一律回退 [DayOpenRecord.Empty]。
 */
internal object DayStateStore {
    /** 暴露给 androidTest 清理落盘记录，保证用例从 01 起步。 */
    internal const val FILE_NAME = "day_state_prefs"
    private const val KEY_OPENED_DAY = "opened_day"
    private const val KEY_STAGE = "stage"

    fun load(context: Context): DayOpenRecord {
        val prefs = prefs(context)
        val openedDay = prefs.getString(KEY_OPENED_DAY, null)
            ?.let(IsoDate::parseOrNull)
            ?: return DayOpenRecord.Empty
        val stage = prefs.getString(KEY_STAGE, null)
            ?.let { name -> DayStage.entries.firstOrNull { it.name == name } }
            ?: return DayOpenRecord.Empty
        if (stage == DayStage.Calendar) return DayOpenRecord.Empty
        return DayOpenRecord(openedDay = openedDay, stage = stage)
    }

    /** 写入「今天已开启 + 当前状态」；Calendar 不写（见类注释）。 */
    fun save(context: Context, today: IsoDate, stage: DayStage) {
        if (stage == DayStage.Calendar) return
        prefs(context).edit()
            .putString(KEY_OPENED_DAY, today.toString())
            .putString(KEY_STAGE, stage.name)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
