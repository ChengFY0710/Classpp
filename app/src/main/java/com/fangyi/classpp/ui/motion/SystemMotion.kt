package com.fangyi.classpp.ui.motion

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 系统是否允许动画：API 26+ 用 [ValueAnimator.areAnimatorsEnabled]（覆盖开发者选项里的
 * 「动画程序时长缩放」关闭，以及无障碍「移除动画」把缩放置零），API 24–25 回退读
 * `ANIMATOR_DURATION_SCALE`。
 *
 * 全 app 的「减少动态效果」判定都从这里取，**不要在各组件里各写一份**：
 * - Compose 的 tween/Animatable 会随系统动画时长缩放一起加速或归零，但「位移幅度」
 *   「循环呼吸」这类幅度语义不会因此消失——所以装饰性位移、持续帧循环必须自己门控；
 * - 关闭时调用点应只做瞬时的出现 / 收起，并保证落点就是终态（alpha 1、位移 0），
 *   不能出现「动画被跳过、内容停在透明或偏移位置」。
 *
 * 当前调用点：AI 思考光场（[com.fangyi.classpp.ui.ai.AiThinkingAurora] 的进出场与
 * 帧循环）、AI 对话新气泡落位与滚动接住（[com.fangyi.classpp.ui.ai] 的 ChatEntrance）。
 */
@Composable
fun rememberSystemAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) { systemAnimationsEnabled(context) }
}

/** [rememberSystemAnimationsEnabled] 的非组合实现，便于在组合外取同一个判定。 */
internal fun systemAnimationsEnabled(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ValueAnimator.areAnimatorsEnabled()
    } else {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    }
