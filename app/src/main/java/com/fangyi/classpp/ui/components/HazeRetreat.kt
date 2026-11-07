package com.fangyi.classpp.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 落库回流退让的兜底帧数：回流迟迟未到时按帧挂回，防磨砂被永久停用 */
private const val HazeCommitFallbackFrames = 30

/**
 * Haze 退让窗口的可复用状态机（参考实现：`AiProfileSheet` 内的同名机制）。
 *
 * 崩溃史（Haze 2 自引用 RenderNode，RenderThread 在 prepareTreeImpl 无限递归栈溢出）：
 * 浮层内容列是顶栏磨砂与叠层弹卡的共享采样源，源子树在效果节点在场时被结构重写
 * （行增删 / 列表塌缩 / 整块换页）即可能构建自引用 RenderNode。项目内两次设备验证过的
 * 对策：**重写与 Haze 卸载落同帧重组**，该帧所有窗口零效果节点；内容落定两帧后挂回。
 * 本状态机固化这套时序，替代各调用点手写：
 *
 * - [forRewrite]：同步改写（菜单点选等），两帧后挂回；
 * - [forCommit]：落库类确认（改名 / 保存 / 删除），重写随仓库回流异步到达——退让保持到
 *   [onReflow]（调用方在 `LaunchedEffect(数据键)` 里调），回流后再延两帧；回流迟迟不来
 *   （内容相等未再发射，本就无重写）由 [HazeCommitFallbackFrames] 兜底挂回。
 *
 * 用法：`val retreat = rememberHazeRetreat()`；`OverlaySheet(hazeEnabled = !retreat.suspended)`；
 * 叠层弹卡 / 菜单以 `if (retreat.suspended) null else sheetHazeState` 下发采样源；
 * 同步改写包 [forRewrite]，落库确认包 [forCommit]。
 *
 * 退让只覆盖「重写窗口」——稳态磨砂、弹卡外观与在场期间的弹窗磨砂均不受影响。
 */
@Stable
internal class HazeRetreatState internal constructor(private val scope: CoroutineScope) {
    /** 退让中（组合期读取）：驱动 `hazeEnabled` 与叠层弹卡的采样源下发 */
    var suspended by mutableStateOf(false)
        private set

    // 确认类落库已发出、等待数据回流（不在组合期读取，普通字段即可）
    private var awaitingReflow = false
    // 挂回计时任务：新一轮退让先取消再重排，保证挂回永不与重写同帧
    private var resumeJob: Job? = null

    /** 同步改写退让：[block] 内的状态写入与 Haze 卸载落同帧重组，两帧后内容稳定再挂回 */
    fun forRewrite(block: () -> Unit) {
        resumeJob?.cancel()
        awaitingReflow = false
        suspended = true
        block()
        resumeJob = scope.launch {
            withFrameNanos { }
            withFrameNanos { }
            suspended = false
        }
    }

    /**
     * 落库回流退让：[block] 触发的重写由仓库回流异步带到、时机未知，退让保持到 [onReflow]
     * 再延两帧；回流不来由 [HazeCommitFallbackFrames] 兜底。
     */
    fun forCommit(block: () -> Unit) {
        resumeJob?.cancel()
        suspended = true
        awaitingReflow = true
        block()
        resumeJob = scope.launch {
            repeat(HazeCommitFallbackFrames) { withFrameNanos { } }
            if (awaitingReflow) {
                awaitingReflow = false
                suspended = false
            }
        }
    }

    /**
     * 回流落定：重写帧必然仍在退让窗口内，这里只推迟挂回；等待期间再来的回流会重启
     * 调用方 LaunchedEffect、重新计两帧。未处于退让窗口时为空操作。
     */
    suspend fun onReflow() {
        if (!awaitingReflow) return
        withFrameNanos { }
        withFrameNanos { }
        awaitingReflow = false
        suspended = false
    }
}

@Composable
internal fun rememberHazeRetreat(): HazeRetreatState {
    val scope = rememberCoroutineScope()
    return remember(scope) { HazeRetreatState(scope) }
}
