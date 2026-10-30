package com.fangyi.classpp.data.model

import kotlinx.serialization.Serializable

/** 分享载荷信封：导出为 JSON 文本经系统分享面板发出，接收方原样导入 */
@Serializable
data class SharePayload(
    val formatVersion: Int = SCHEDULE_FORMAT_VERSION,
    /** 载荷类型标记，防止误导入其他来源的 JSON */
    val kind: String = SHARE_KIND,
    /** 导出时刻（epoch millis），规避 java.time */
    val exportedAtMillis: Long,
    /** 原样携带源 id；导入方一律重铸 */
    val schedule: Schedule,
) {
    companion object {
        const val SHARE_KIND = "classpp.schedule"
    }
}
