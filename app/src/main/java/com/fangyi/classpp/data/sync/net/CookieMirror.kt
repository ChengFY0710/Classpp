package com.fangyi.classpp.data.sync.net

import android.webkit.CookieManager

/**
 * 登录成功后把原生会话 cookie 镜像进 WebView 的 CookieManager（best-effort）。
 *
 * 目的：用户关闭自动续期后，网页登录路径仍能复用当前会话，而不是立刻提示过期。
 * [host] 由学校声明（业务数据域），通用层不内置任何域名。
 */
fun interface CookieMirror {
    fun mirror(host: String, cookies: Map<String, String>)
}

/**
 * [CookieMirror] 的 Android 实现：逐条 `setCookie` 后 flush 持久化。
 *
 * 失败只影响网页路径复用，原生会话不受影响，不向用户报错；日志不带 cookie 值
 * （安全纪律见指导 §五.5）。
 */
object AndroidCookieMirror : CookieMirror {

    override fun mirror(host: String, cookies: Map<String, String>) {
        if (cookies.isEmpty()) return
        try {
            val manager = CookieManager.getInstance()
            val url = "https://$host"
            for ((name, value) in cookies) {
                manager.setCookie(url, "$name=$value; Path=/; Secure; HttpOnly")
            }
            manager.flush()
        } catch (_: Exception) {
            // 镜像失败只影响 WebView 复用；原生会话不受影响
        }
    }
}
