package com.fangyi.classpp.data.sync.engine

import com.fangyi.classpp.data.model.TodoAttachment

/**
 * 同步时给「即将导入」与「已导入」的远端条目补充的待办内容。
 *
 * @param note 完整备注文本；null = 保持原备注
 * @param remoteAttachments 远端附件元数据；null = 未取到（不修改），空表 = 远端已无附件
 *   （清空同步附件，保留用户本机添加的附件）
 */
data class SyncTodoExtras(
    val note: String? = null,
    val remoteAttachments: List<TodoAttachment>? = null,
)

/**
 * 可选的待办内容补充器：同步时抓取额外正文与附件元数据（如作业要求），
 * 返回 [remoteKey] → [SyncTodoExtras]。
 *
 * 约定：
 * - 只返回确实取到的条目；缺失 = 保持原内容，不做任何修改；
 * - 备注与附件由学校适配层格式化（课程/类型/要求/链接、远端附件 URL/MIME 归学校所有），
 *   引擎只负责替换备注、合并同步附件；
 * - 同步附件的文件**不在同步时下载**，只携带元数据（[TodoAttachment.remoteUrl] 非空），
 *   由用户在详情页点击时按需下载；
 * - 实现必须自行吞掉可恢复失败（网络、解析、登录过期）并返回空表：内容补充是增强项，
 *   绝不能因为取数失败而阻断待办导入。协程取消需继续上抛。
 *
 * 预留为学校无关契约：只有具备稳定批量取数接口的学校才注入实现（如厦大课程活动列表），
 * 未注入时引擎行为与旧版本完全一致。
 */
fun interface SyncTodoEnricher<R : Any> {
    suspend fun extrasFor(remotes: List<R>): Map<String, SyncTodoExtras>
}
