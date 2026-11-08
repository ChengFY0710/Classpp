package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.ScheduleError
import com.fangyi.classpp.data.model.Schedule

/**
 * AI 识别导入的结果：UI 按分支显示错误条 / 弹出导入确认。
 *
 * 与 [AiChatRepository.recognizeSchedule] 一一对应——识别请求不读写对话、不落盘，
 * 所有可能结果都收敛在这里，调用方无需区分「通道失败」与「解析失败」的实现细节。
 */
sealed interface AiScheduleRecognition {

    /** 识别成功且课表通过导入校验（id 已重铸、可直接走 `importSchedule`） */
    data class Ok(val schedule: Schedule) : AiScheduleRecognition

    /** AI 通道失败（鉴权 / 额度 / 模型不存在 / 限流 / 服务端 / 网络 / 返回无法解析） */
    data class AiFailed(val error: AiError) : AiScheduleRecognition

    /** 模型有输出、但课表 JSON 解析或全量校验不通过 */
    data class Invalid(val error: ScheduleError) : AiScheduleRecognition

    /** 模型回复里没有 JSON 代码块（纯文字 / 拒答） */
    data object NoJson : AiScheduleRecognition

    /** JSON 合法但没有任何课程 */
    data object NoCourses : AiScheduleRecognition

    /** 图片文件不可读（已导入的临时副本丢失） */
    data object ImageUnavailable : AiScheduleRecognition

    /** 运行中配置被清空 / 未配置服务商 */
    data object NotConfigured : AiScheduleRecognition
}
