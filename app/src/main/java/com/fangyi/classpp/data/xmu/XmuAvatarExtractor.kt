package com.fangyi.classpp.data.xmu

import com.fangyi.classpp.data.ScheduleJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 从 `/api/profile` 原文里取头像地址（通用引擎（SchoolSyncEngine，经 schools/xmu 编码器）每次拿到 profile 时顺带调用）。
 *
 * 2026-11-03 实测（lnt.xmu.edu.cn 已登录账号）：响应顶层带 `avatar_big_url` 与
 * `avatar_small_url`，均为 lnt 域下的绝对地址（`/api/uploads/<id>/modified-image`），
 * 查询串只有 `thumbnail` / `crop_box` 两个图像参数、不带会过期的签名（多次请求逐字相同，
 * 所以按整串比较做缓存失效是安全的）。两档实测：big = 200×200 JPEG ≈ 8 KB，
 * small = 32×32 JPEG ≈ 1 KB——连接卡圆 56dp（≈168px @3x），取 big 才不糊。
 *
 * 除实测键外仍保留通用候选（各部署/平台改版兜底）：
 * - 候选键按序取第一个非空字符串：`avatar_big_url` → `avatar_small_url` →
 *   `avatar` / `avatar_url` / `avatarUrl` / `tiny_avatar` / `small_avatar` /
 *   `photo` / `portrait`；
 * - 顶层取不到时下探一层容器：`user` / `profile` / `data`；
 * - 相对路径（`/upload/...`）按课程中心域名补全，只接受 http(s)。
 *
 * 取不到、非字符串、脏 JSON 一律返回 null，调用方据此静默跳过——头像纯装饰，
 * 不能影响同步结果。
 */
internal object XmuAvatarExtractor {

    /** 逐级尝试的候选键（顺序即优先级） */
    private val CANDIDATE_KEYS = listOf(
        "avatar_big_url",
        "avatar_small_url",
        "avatar",
        "avatar_url",
        "avatarUrl",
        "tiny_avatar",
        "small_avatar",
        "photo",
        "portrait",
    )

    /** 可能再包一层资料的对象键：顶层取不到时下探 */
    private val CONTAINER_KEYS = listOf("user", "profile", "data")

    /** 头像地址基准域：相对路径按它补全（与 WebView 取数同源） */
    private const val BASE_URL = "https://${XmuWebViewPolicy.LNT_HOST}"

    fun extract(profileRaw: String): String? {
        val root = try {
            ScheduleJson.storage.parseToJsonElement(profileRaw).jsonObject
        } catch (_: Exception) {
            return null
        }
        return fromObject(root) ?: CONTAINER_KEYS.firstNotNullOfOrNull { key ->
            (root[key] as? JsonObject)?.let(::fromObject)
        }
    }

    private fun fromObject(json: JsonObject): String? {
        val raw = CANDIDATE_KEYS.firstNotNullOfOrNull { key ->
            (json[key] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } ?: return null
        return normalize(raw)
    }

    /** 绝对 http(s) 原样返回；以 `/` 开头的相对路径补上课程中心域名；其余（`data:` 等）丢弃 */
    private fun normalize(raw: String): String? = when {
        raw.startsWith("https://", ignoreCase = true) ||
            raw.startsWith("http://", ignoreCase = true) -> raw
        raw.startsWith("/") -> BASE_URL + raw
        else -> null
    }
}
