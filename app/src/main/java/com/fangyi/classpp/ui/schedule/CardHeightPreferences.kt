package com.fangyi.classpp.ui.schedule

import android.content.Context
import kotlin.math.roundToInt

/**
 * 课程卡片高度倍率：5 天 / 7 天模式各存一份，切换模式互不覆盖自定义。
 * 网格行高随每节课实际时长伸缩（见 [slotRowHeight]）：行高 = 基准行高（100 分钟节，
 * 5 天 [GridRowHeight] / 7 天加 [SevenDayRowExtraHeight]）×（该节时长 ÷ 100）× 倍率，
 * 1.0 倍即基准，默认渲染与固定行高时代逐像素一致。
 * 存的是**用户倍率**；实际渲染倍率不低于「最短节触发卡片最低高度」的下限
 * （见 [cardHeightMinScale]，随显示教师开关），抬高结果不回写偏好，
 * 短节移除/课表变长后自动回落。
 */
data class CardHeightScales(
    val five: Float = CardHeightPreferences.DefaultScale,
    val seven: Float = CardHeightPreferences.DefaultScale,
)

/**
 * 课程卡片高度倍率全局偏好：SharedPreferences 双键存储（同 [com.fangyi.classpp.ui.theme.ThemePreferences]
 * 模式，单值不值得引入 DataStore；apply 异步落盘，UI 状态由调用方同步更新）。
 * load 时按范围钳制，脏数据不会把滑条顶出可调区间。
 *
 * 旧版本存的是绝对 dp 行高（card_height_5 / card_height_7）：无新键时按旧值 ÷ 各自基准行高
 * 换算成倍率——100 分钟节的默认作息下换算前后行高完全一致，用户无感迁移；save 写入新键
 * 并清掉旧键，此后不再走换算。
 */
object CardHeightPreferences {
    private const val FILE_NAME = "card_height_prefs"
    private const val KEY_SCALE_5 = "card_scale_5"
    private const val KEY_SCALE_7 = "card_scale_7"

    /** 旧版绝对 dp 键：只读不写，仅在迁移换算时出现 */
    private const val LEGACY_KEY_HEIGHT_5 = "card_height_5"
    private const val LEGACY_KEY_HEIGHT_7 = "card_height_7"

    /** 用户倍率的默认值：1.0 = 基准行高 */
    const val DefaultScale = 1f

    /**
     * 可存储范围（倍率）：load/save 钳制与个性化页写库共用（下限 0.1 覆盖 200 分钟节的
     * 最低触发倍率）。滑条的可调下限另由当前课表最短节与「显示教师」开关动态计算
     * （见 [cardHeightMinScale]），落在本范围内；网格渲染也按不低于该下限的倍率绘制，
     * 但不写回偏好。
     */
    val Range = 0.1f..1.5f

    /** 读取两个模式的倍率；新键优先，无新键时从旧 dp 值迁移，均无回退默认值 */
    fun load(context: Context): CardHeightScales {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        return CardHeightScales(
            five = loadScale(prefs, KEY_SCALE_5, LEGACY_KEY_HEIGHT_5, GridRowHeight.value),
            seven = loadScale(prefs, KEY_SCALE_7, LEGACY_KEY_HEIGHT_7, gridRowHeight(7).value),
        )
    }

    /** 保存两个模式的倍率（apply 异步落盘，不阻塞拖动），顺带清掉旧版 dp 键完成迁移 */
    fun save(context: Context, scales: CardHeightScales) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_SCALE_5, scales.five)
            .putFloat(KEY_SCALE_7, scales.seven)
            .remove(LEGACY_KEY_HEIGHT_5)
            .remove(LEGACY_KEY_HEIGHT_7)
            .apply()
    }

    /** 单模式读取：新倍率键 → 旧 dp 键 ÷ 基准行高（保留两位小数）→ 默认值；结果钳回 [Range] */
    private fun loadScale(
        prefs: android.content.SharedPreferences,
        key: String,
        legacyKey: String,
        baseDp: Float,
    ): Float {
        val raw = when {
            prefs.contains(key) -> prefs.getFloat(key, DefaultScale)
            prefs.contains(legacyKey) ->
                (prefs.getInt(legacyKey, baseDp.toInt()) / baseDp * 100f).roundToInt() / 100f
            else -> DefaultScale
        }
        return raw.coerceIn(Range.start, Range.endInclusive)
    }
}
