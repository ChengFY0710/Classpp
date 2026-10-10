package com.fangyi.classpp.ui.schedule

import android.content.Context

/**
 * 课程卡片高度（网格行高）：5 天 / 7 天模式各存一份 dp 值，切换模式互不覆盖自定义。
 * 默认值与 [gridRowHeight] 同源（5 天 [GridRowHeight]、7 天加 [SevenDayRowExtraHeight]），
 * 改 CourseGrid 常量后偏好默认值自动跟随。
 */
data class CardHeights(
    val five: Int = CardHeightPreferences.Default5,
    val seven: Int = CardHeightPreferences.Default7,
)

/**
 * 课程卡片高度全局偏好：SharedPreferences 双键存储（同 [com.fangyi.classpp.ui.theme.ThemePreferences]
 * 模式，单值不值得引入 DataStore；apply 异步落盘，UI 状态由调用方同步更新）。
 * load 时按各自范围钳制，脏数据不会把滑条顶出可调区间。
 */
object CardHeightPreferences {
    private const val FILE_NAME = "card_height_prefs"
    private const val KEY_HEIGHT_5 = "card_height_5"
    private const val KEY_HEIGHT_7 = "card_height_7"

    /** 默认行高（dp）：与 [gridRowHeight] 同一来源 */
    val Default5: Int get() = GridRowHeight.value.toInt()
    val Default7: Int get() = gridRowHeight(7).value.toInt()

    /** 可调范围（dp）：个性化页滑条 valueRange 与 load 钳制共用 */
    val Range5 = 100f..200f
    val Range7 = 160f..300f

    /** 读取两个模式的高度；无记录回退默认值，越界值钳回范围 */
    fun load(context: Context): CardHeights {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        return CardHeights(
            five = prefs.getInt(KEY_HEIGHT_5, Default5)
                .coerceIn(Range5.start.toInt(), Range5.endInclusive.toInt()),
            seven = prefs.getInt(KEY_HEIGHT_7, Default7)
                .coerceIn(Range7.start.toInt(), Range7.endInclusive.toInt()),
        )
    }

    /** 保存两个模式的高度（apply 异步落盘，不阻塞拖动） */
    fun save(context: Context, heights: CardHeights) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_HEIGHT_5, heights.five)
            .putInt(KEY_HEIGHT_7, heights.seven)
            .apply()
    }
}
