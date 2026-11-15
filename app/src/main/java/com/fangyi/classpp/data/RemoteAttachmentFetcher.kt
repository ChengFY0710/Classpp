package com.fangyi.classpp.data

import java.io.InputStream

/**
 * 远端附件下载的流式取数抽象（[TodoRepository.downloadRemoteAttachment] 使用）。
 *
 * 实现负责：只接受 https、按 URL 域名携带会话 Cookie、处理对象存储 302 跳转、
 * 设置超时，并根据 [expectedMimeType] 拒绝登录页等 HTML 响应（除非附件本身是 HTML）；
 * 任何失败返回 null（不抛给 UI 协程）。返回的流关闭时必须同时释放底层响应。
 */
fun interface RemoteAttachmentFetcher {
    suspend fun open(url: String, expectedMimeType: String): InputStream?
}
