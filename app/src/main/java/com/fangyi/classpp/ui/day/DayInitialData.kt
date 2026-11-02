package com.fangyi.classpp.ui.day

import android.content.Context

/**
 * 日程页首次组合所需的持久化快照。
 *
 * 由 MainActivity 在启动协程里经 Dispatchers.IO 预读，作为参数传给 DayScreen：
 * SharedPreferences 读取（5 项）与图片列表 JSON 解析不再落在首次切到日程页的
 * 组合帧上。字段默认值与各 Store 的「无记录」口径一致。
 */
internal data class DayInitialData(
    val images: List<String> = emptyList(),
    val selectedImage: String? = null,
    val randomEnabled: Boolean = false,
    val tiltEnabled: Boolean = true,
    val randomPick: Pair<Long, String>? = null,
    val record: DayOpenRecord = DayOpenRecord.Empty,
) {
    companion object {
        /** 必须在 IO 线程调用（SharedPreferences 首次访问与 JSON 解析都可能读盘）。 */
        fun load(context: Context): DayInitialData = DayInitialData(
            images = DayImageStore.loadImages(context),
            selectedImage = DayImageStore.loadSelected(context),
            randomEnabled = DayImageStore.loadRandomEnabled(context),
            tiltEnabled = DayImageStore.loadTiltParallaxEnabled(context),
            randomPick = DayImageStore.loadRandomPick(context),
            record = DayStateStore.load(context),
        )
    }
}
