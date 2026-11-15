package com.fangyi.classpp.data.xmu

/**
 * 课程中心附件 → 系统打开用的 MIME。
 *
 * 远端只给粗粒度 `type`（`document` / `image` / `video`…），不够 `ACTION_VIEW` 精确匹配；
 * 这里优先按文件名扩展名映射常用类型，未知扩展名再退回远端大类，最后 `octet-stream`。
 * 纯 JVM 实现，便于单测；不依赖 `android.webkit.MimeTypeMap`。
 */
internal object XmuAttachmentMime {

    fun resolve(name: String, remoteType: String): String {
        val extension = name.substringAfterLast('.', "").trim().lowercase()
        EXTENSIONS[extension]?.let { return it }
        return when (remoteType.trim().lowercase()) {
            "image" -> "image/*"
            "video" -> "video/*"
            "audio" -> "audio/*"
            "document" -> "application/octet-stream"
            else -> "application/octet-stream"
        }
    }

    private val EXTENSIONS = mapOf(
        "pdf" to "application/pdf",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "txt" to "text/plain",
        "csv" to "text/csv",
        "md" to "text/markdown",
        "json" to "application/json",
        "html" to "text/html",
        "htm" to "text/html",
        "zip" to "application/zip",
        "rar" to "application/vnd.rar",
        "7z" to "application/x-7z-compressed",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "heic" to "image/heic",
        "tif" to "image/tiff",
        "tiff" to "image/tiff",
        "svg" to "image/svg+xml",
        "mp4" to "video/mp4",
        "mov" to "video/quicktime",
        "webm" to "video/webm",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "m4a" to "audio/mp4",
        "apk" to "application/vnd.android.package-archive",
        "epub" to "application/epub+zip",
    )
}
