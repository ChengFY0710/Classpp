package com.fangyi.classpp.ui.components

import android.app.Activity
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.fangyi.classpp.R
import com.fangyi.classpp.data.TodoAttachmentSource
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 待办附件的 UI 侧 IO 工具：SAF URI → [TodoAttachmentSource]（仓库负责复制到私有目录），
 * 以及把私有目录里的附件经 FileProvider 交给系统 App 打开。
 */

/**
 * 把系统文件选择器返回的 URI 转成仓库可消费的来源：查询显示名与 MIME（查询失败回落
 * 「未命名附件」/ `application/octet-stream`）。URI 授权在回调后仍属本 Activity，仓库
 * 随即在 IO 线程打开输入流复制，不长期保存外部 URI。
 */
internal suspend fun todoAttachmentSources(
    context: Context,
    uris: List<Uri>,
): List<TodoAttachmentSource> = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val fallbackName = context.getString(R.string.todo_attachment_unnamed)
    uris.map { uri ->
        val name = queryDisplayName(resolver, uri) ?: fallbackName
        val mimeType = runCatching { resolver.getType(uri) }.getOrNull()
            ?: "application/octet-stream"
        TodoAttachmentSource(displayName = name, mimeType = mimeType) {
            resolver.openInputStream(uri)
        }
    }
}

/**
 * 用系统 App 打开附件：FileProvider 生成 content URI（路径见 res/xml/file_paths.xml 的
 * `todo_attachments`），ACTION_VIEW + MIME + 读授权 + ClipData 兜住需要 clipData 才认授权的
 * 接收方。没有可处理的应用 / Provider 路径不匹配等异常统一返回 false，由调用方 Toast。
 */
internal fun openTodoAttachment(context: Context, file: File, mimeType: String): Boolean = runCatching {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeType.ifBlank { "application/octet-stream" })
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // 部分接收方只认 clipData 里的 URI 授权；setClipData 在新 SDK 返回 Unit，单独赋值
    intent.clipData = ClipData.newRawUri(null, uri)
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}.isSuccess

/** 查询 SAF 文档的显示名；Provider 不支持 DISPLAY_NAME 时返回 null */
private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index < 0) return@use null
        cursor.getString(index)
    }
}.getOrNull()?.takeIf { it.isNotBlank() }
