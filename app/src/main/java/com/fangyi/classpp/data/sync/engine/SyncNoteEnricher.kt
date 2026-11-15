package com.fangyi.classpp.data.sync.engine

/**
 * 可选的备注补充器：同步时给「即将导入」与「已导入」的远端条目抓取额外正文
 * （如作业要求），返回 [remoteKey] → **完整备注文本**。
 *
 * 约定：
 * - 只返回确实需要写入的条目；缺失 = 保持原备注，不做任何修改；
 * - 完整备注由学校适配层格式化（课程/类型/要求/附件/链接的顺序归学校所有），
 *   引擎只负责替换 [com.fangyi.classpp.data.model.Todo.note]；
 * - 实现必须自行吞掉可恢复失败（网络、解析、登录过期）并返回空表：备注补充是增强项，
 *   绝不能因为取要求失败而阻断待办导入。协程取消需继续上抛。
 *
 * 预留为学校无关契约：只有具备稳定批量取数接口的学校才注入实现（如厦大课程活动列表），
 * 未注入时引擎行为与旧版本完全一致。
 */
fun interface SyncNoteEnricher<R : Any> {
    suspend fun notesFor(remotes: List<R>): Map<String, String>
}
