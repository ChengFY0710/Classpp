package com.fangyi.classpp.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.Base64
import java.util.UUID

/**
 * AI 对话的图片副本库：导入压缩副本目录 + 发送前 base64 编码 + 附件文件删除。
 *
 * 相册选图 / 相机拍照的来源 URI 都是临时授权（Photo Picker 无 persistable 权限、相机
 * 临时文件即删），不能直接引用——导入时解码转正压缩进 [dir]，消息持久化的只是文件名
 * （AiChatMessage.images），发送时再读文件编码。导入管线与 DayImageStore.importImage
 * 同构（边界解码定采样率 → EXIF 转正 → 最长边压缩 → JPEG 落盘）；上限压到
 * [MaxDimension]：AI 输入不需要壁纸级分辨率，控制单条请求体积（多图上限 4 张）。
 *
 * encode/delete 不触碰 Android API（纯 JVM），单测直接用临时目录验证。
 */
class AiImageStore(private val dir: File) {

    fun file(name: String): File = File(dir, name)

    /**
     * 从相册/相机 URI 导入一张图：解码 → EXIF 转正 → 最长边压进 [MaxDimension] →
     * JPEG 落盘。返回新文件名；解码/写入失败返回 null（调用方过滤，不影响同批其余图片）。
     * 涉及磁盘与位图解码，必须在 IO 线程调用。
     */
    fun import(context: Context, uri: Uri): String? = runCatching {
        val resolver = context.contentResolver
        // 边界解码先拿原尺寸定采样率（EXIF 转正不改变最长边，按原尺寸估算即可）
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 采样率取 2 的幂档，把解码尺寸压到 640~1280 档，避免全尺寸位图瞬时进内存
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= MaxDimension / 2) {
            sampleSize *= 2
        }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            )
        } ?: return null

        // BitmapFactory 不管 EXIF 方向，相机原片横竖颠倒要按 orientation 转正（旋转/镜像全覆盖）
        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f); matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f); matrix.postScale(-1f, 1f)
            }
        }
        val oriented = if (matrix.isIdentity) {
            decoded
        } else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }
        if (oriented !== decoded) decoded.recycle()

        // 采样率只保证落在档位内，转正后再做一次精确缩放兜底进 MaxDimension
        val longest = maxOf(oriented.width, oriented.height)
        val scaled = if (longest > MaxDimension) {
            val fraction = MaxDimension.toFloat() / longest
            Bitmap.createScaledBitmap(
                oriented,
                (oriented.width * fraction).toInt().coerceAtLeast(1),
                (oriented.height * fraction).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            oriented
        }
        if (scaled !== oriented) oriented.recycle()

        if (!dir.exists()) dir.mkdirs()
        val fileName = "${UUID.randomUUID()}.jpg"
        File(dir, fileName).outputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JpegQuality, out)
        }
        scaled.recycle()
        fileName
    }.getOrNull()

    /**
     * 编码为 wire 层图片附件（发送时调用，IO 线程执行）：文件缺失/读取失败返回 null，
     * 发送侧 mapNotNull 跳过——历史消息里被清理掉的图片不阻断整条请求。
     * 名单是自持数据，仍防一手路径穿越：只接受纯文件名。
     */
    fun encode(name: String): AiTurnImage? {
        if (name.isEmpty() || name.contains(File.separatorChar)) return null
        val bytes = file(name).takeIf { it.isFile }?.readBytes() ?: return null
        return AiTurnImage(
            mediaType = JPEG_MEDIA_TYPE,
            base64 = Base64.getEncoder().encodeToString(bytes),
        )
    }

    /** 删除一张图片附件副本（引用它的消息由调用方同步维护落盘）；文件不存在时静默忽略 */
    fun delete(name: String) {
        if (name.isEmpty() || name.contains(File.separatorChar)) return
        file(name).delete()
    }

    /**
     * 孤儿清理（bootstrap 装载后跑一次）：名单之外**且已过宽限期**的文件删除。名单来自
     * 装载后的全部对话消息——persist 截最旧、装载降级（历史重置）后，不再被引用的副本
     * 就没有存在意义了。目录不存在时静默返回。
     *
     * @param minAgeMillis 宽限期：UI 已导入、还没随消息发出的待发送图片没有任何消息
     *   引用（极端时序下可能撞上重新装载），靠文件 mtime 豁免——新导入的文件不删
     */
    fun deleteExcept(referenced: Set<String>, minAgeMillis: Long = 0) {
        if (!dir.isDirectory) return
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { file ->
            if (file.name !in referenced && now - file.lastModified() >= minAgeMillis) {
                file.delete()
            }
        }
    }

    private companion object {
        /** 落盘前把最长边压到该值：视觉模型识别足够，同时钉住多图请求体积 */
        const val MaxDimension = 1280
        const val JpegQuality = 85
        const val JPEG_MEDIA_TYPE = "image/jpeg"
    }
}
