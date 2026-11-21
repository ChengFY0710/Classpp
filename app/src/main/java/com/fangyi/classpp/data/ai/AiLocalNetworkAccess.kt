package com.fangyi.classpp.data.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Android 17（API 37）本地网络保护：访问局域网内的 Ollama 需要
 * `ACCESS_LOCAL_NETWORK` 运行时权限。回环地址不在此列（不离开本机）。
 *
 * 低版本系统没有该权限，一律视为已放行；公网 / https 地址也不需要。
 */
object AiLocalNetworkAccess {

    /** 本地网络运行时权限名（API 37 新增，低版本系统上仅作为字符串存在） */
    @SuppressLint("InlinedApi") // 权限名是编译期常量，minSdk 24 读到它不会崩溃
    val permission: String = Manifest.permission.ACCESS_LOCAL_NETWORK

    private const val API_LOCAL_NETWORK = 37

    /** 该连接是否需要申请本地网络权限（url 为 http 且目标为局域网而非回环） */
    fun isPermissionRequired(context: Context, connection: AiConnection): Boolean =
        isPermissionRequired(context, connection.baseUrl)

    fun isPermissionRequired(context: Context, url: String): Boolean {
        if (Build.VERSION.SDK_INT < API_LOCAL_NETWORK) return false
        if (!AiLocalNetwork.isLocalHttpUrl(url)) return false
        val host = url.trim().toHttpUrlOrNull()?.host ?: return false
        if (AiLocalNetwork.isLoopbackHost(host)) return false
        return context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
    }
}
