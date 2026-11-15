package com.fangyi.classpp.data

import java.io.InputStream

/**
 * 附件导入来源：显示名、MIME 与按需打开的输入流。
 *
 * UI 从 SAF URI 构造（`ContentResolver.openInputStream`），仓库在 IO 线程逐个消费并复制
 * 到应用私有目录。[openStream] 每次调用返回一个新的流，调用方负责关闭（仓库会 use）。
 */
class TodoAttachmentSource(
    val displayName: String,
    val mimeType: String,
    val openStream: () -> InputStream?,
)
