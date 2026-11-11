package com.fangyi.classpp

import android.content.Context

/**
 * 应用版本名：运行时读系统 PackageInfo 的 versionName（来源仍是 Gradle `versionName`），
 * 关于页与备份清单共用，避免在源码里写死版本号。
 */
@Suppress("DEPRECATION")
fun appVersionName(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull().orEmpty()
