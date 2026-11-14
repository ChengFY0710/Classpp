package com.fangyi.classpp.data.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

/**
 * 把已校验的 APK 交给系统安装器（应用内更新链路的最后一步）：
 *
 * - API 26+ 先检查「安装未知应用」授权；未授权时只负责打开系统授权页，
 *   下载/安装由用户授权后重新触发；
 * - 通过既有 `${applicationId}.fileprovider` 授权单个 APK URI，不暴露目录；
 * - 不静默安装、不绕过系统签名校验——签名不一致时由系统安装器拒绝。
 */
object ApkInstaller {

    /** 当前是否允许本应用拉起安装器（API 26 以下安装即允许） */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** 打开「安装未知应用」授权页；返回是否成功拉起（失败时 UI 保持授权提示） */
    fun openInstallPermissionSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${context.packageName}".toUri(),
        )
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** 拉起系统安装器；返回是否成功发出 Intent */
    fun install(context: Context, apkFile: File): Boolean = runCatching {
        require(apkFile.isFile) { "apk file missing" }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.isSuccess

    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
}
