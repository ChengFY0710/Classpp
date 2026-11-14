package com.fangyi.classpp

import android.content.Context
import android.os.Build

/**
 * 应用版本名：运行时读系统 PackageInfo 的 versionName（来源仍是 Gradle `versionName`），
 * 关于页与备份清单共用，避免在源码里写死版本号。
 */
@Suppress("DEPRECATION")
fun appVersionName(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull().orEmpty()

/**
 * 应用版本号：更新检查的唯一比较依据（远端 `update.json` 的 versionCode）。
 *
 * API 28+ 读 `longVersionCode`（兼容 versionCode 超过 Int 上限的未来版本），
 * 更早版本回落 `versionCode`。读取失败返回 0，检查器据此直接判失败，
 * 避免在版本未知时误报「发现新版本」。
 */
@Suppress("DEPRECATION")
fun appVersionCode(context: Context): Long = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        info.versionCode.toLong()
    }
}.getOrDefault(0L)
