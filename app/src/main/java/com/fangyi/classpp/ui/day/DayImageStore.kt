package com.fangyi.classpp.ui.day

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 日程页用户图片库：导入背景图的副本目录 + 图片列表/选中项的持久化。
 *
 * 相册选图走 Photo Picker，返回的 URI 是临时授权（无 persistable 权限，跨重启即失效），
 * 直接存 URI 当背景会失效——导入时把图片解码转正压缩后拷贝进 [imageDir]，持久化的只是
 * 文件名。列表、选中项与随机轮播状态（开关、当日命中）存 SharedPreferences 单文件
 * （模式同 GlassPreferences：单值不值得引入 DataStore），列表用 JSON 数组承载以保持导入顺序。
 */
object DayImageStore {
    private const val FILE_NAME = "day_image_prefs"
    private const val KEY_IMAGES = "images"
    private const val KEY_SELECTED = "selected"
    private const val KEY_RANDOM_ENABLED = "random_enabled"
    private const val KEY_TILT_PARALLAX_ENABLED = "tilt_parallax_enabled"
    private const val KEY_RANDOM_PICK_DATE = "random_pick_date"
    private const val KEY_RANDOM_PICK = "random_pick"
    private const val DIR_NAME = "day_images"

    // 落盘前把最长边压到该值：铺满最大屏幕足够（背景不做局部裁切放大），
    // 同时把多选导入的磁盘/内存占用钉在可控范围
    private const val MaxDimension = 2048
    private const val JpegQuality = 90

    private val json = Json

    fun imageDir(context: Context): File = File(context.filesDir, DIR_NAME)

    fun imageFile(context: Context, name: String): File = File(imageDir(context), name)

    /** 读取已导入的图片文件名列表（导入顺序）；无记录或记录损坏时回退空列表 */
    fun loadImages(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_IMAGES, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<String>>(raw)
        } catch (e: SerializationException) {
            emptyList()
        }
    }

    /** 保存图片列表（apply 异步落盘；UI 状态由调用方同步更新，不等落盘） */
    fun saveImages(context: Context, images: List<String>) {
        prefs(context).edit().putString(KEY_IMAGES, json.encodeToString(images)).apply()
    }

    /** 读取当前选中的背景文件名；未设置时回退 null（日程页用默认测试图） */
    fun loadSelected(context: Context): String? =
        prefs(context).getString(KEY_SELECTED, null)?.takeIf { it.isNotEmpty() }

    /** 保存选中背景（传 null 即恢复默认） */
    fun saveSelected(context: Context, name: String?) {
        prefs(context).edit().putString(KEY_SELECTED, name).apply()
    }

    /** 读取「随机轮播」开关（每天从图库随机抽一张当背景）；无记录默认关闭 */
    fun loadRandomEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RANDOM_ENABLED, false)

    /** 保存「随机轮播」开关（apply 异步落盘；UI 状态由调用方同步更新，不等落盘） */
    fun saveRandomEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_RANDOM_ENABLED, enabled).apply()
    }

    /** 读取「壁纸视差」开关（01 态背景图随重力倾斜微移）；无记录默认开启 */
    fun loadTiltParallaxEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TILT_PARALLAX_ENABLED, true)

    /** 保存「壁纸视差」开关（apply 异步落盘；UI 状态由调用方同步更新，不等落盘） */
    fun saveTiltParallaxEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TILT_PARALLAX_ENABLED, enabled).apply()
    }

    /**
     * 读取随机轮播的「当日命中」：返回（抽样日期 epochDay to 文件名），无记录返回 null。
     * 命中落盘后当天锁定——导入新图不重抽，跨零点才换（保证背景不随图库变动而切换）。
     */
    fun loadRandomPick(context: Context): Pair<Long, String>? {
        val prefs = prefs(context)
        val day = prefs.getLong(KEY_RANDOM_PICK_DATE, Long.MIN_VALUE)
        val name = prefs.getString(KEY_RANDOM_PICK, null)?.takeIf { it.isNotEmpty() }
        return if (day == Long.MIN_VALUE || name == null) null else day to name
    }

    /** 保存随机轮播的「当日命中」（日期 + 文件名成对写入，apply 异步落盘） */
    fun saveRandomPick(context: Context, epochDay: Long, name: String) {
        prefs(context).edit()
            .putLong(KEY_RANDOM_PICK_DATE, epochDay)
            .putString(KEY_RANDOM_PICK, name)
            .apply()
    }

    /**
     * 从相册 URI 导入一张图：解码 → EXIF 转正 → 最长边压进 [MaxDimension] → JPEG 落盘。
     * 返回新文件名；解码/写入失败返回 null（调用方过滤，不影响同批其余图片）。
     * 涉及磁盘与位图解码，必须在 IO 线程调用。
     */
    fun importImage(context: Context, uri: Uri): String? = runCatching {
        val resolver = context.contentResolver
        // 边界解码先拿原尺寸定采样率（EXIF 转正不改变最长边，按原尺寸估算即可）
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // 采样率取 2 的幂档，把解码尺寸压到 1024~2048 档，避免全尺寸位图瞬时进内存
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

        // BitmapFactory 不管 EXIF 方向，横竖颠倒的原片要按 orientation 转正（旋转/镜像全覆盖）
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

        val dir = imageDir(context).apply { if (!exists()) mkdirs() }
        val fileName = "${UUID.randomUUID()}.jpg"
        File(dir, fileName).outputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JpegQuality, out)
        }
        scaled.recycle()
        fileName
    }.getOrNull()

    /**
     * 删除一张已导入的图片副本（列表与选中项由调用方同步维护落盘）；文件不存在时静默忽略。
     * 名单是自持数据，仍防一手路径穿越：只接受纯文件名。
     */
    fun deleteImage(context: Context, name: String) {
        if (name.isEmpty() || name.contains(File.separatorChar)) return
        imageFile(context, name).delete()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
