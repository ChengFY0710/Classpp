package com.fangyi.classpp.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 非 focusable 菜单的「点外部收起」总闸。
 *
 * Compose 的 Popup 在非焦点窗口上依赖 `FLAG_WATCH_OUTSIDE_TOUCH` 的 ACTION_OUTSIDE 收起，
 * 但部分系统 / 机型上非焦点窗口收不到该事件——菜单会一直挂着，只有返回键或再次点锚点能收。
 * 这里改由应用窗口根部的一层「按下观察」兜底：菜单展开期间，窗口内任意按下都会通知收起。
 *
 * 之所以能等价替掉 ACTION_OUTSIDE：落进弹窗窗口内的点击（菜单卡与其投影留白）根本不会
 * 到达应用窗口，观察层只会看到窗口外的按下。观察层挂在窗口根部、作为整窗内容的祖先，
 * **不消费**事件——下层照常收到本次点击（点开着的行：既收菜单又执行该行动作，与
 * ACTION_OUTSIDE 的穿透语义一致）。
 *
 * 只给 `focusable = false` 的菜单接线：focusable 菜单由系统自己消费外部触摸并收起，
 * 两边都接会让同一次点击被收起两次。
 */
@Stable
class MenuOutsideTapRegistry {
    private val handlers = mutableMapOf<Any, () -> Unit>()

    internal fun attach(owner: Any, onDismiss: () -> Unit) {
        handlers[owner] = onDismiss
    }

    internal fun detach(owner: Any) {
        handlers.remove(owner)
    }

    /** 窗口内一次按下：通知所有在场菜单收起（通常只有一个，多菜单同屏属防御路径） */
    internal fun dispatchOutsideTap() {
        if (handlers.isEmpty()) return
        handlers.values.toList().forEach { it() }
    }
}

/** 窗口根部观察层的注册表；null = 未接线（菜单退回仅靠 ACTION_OUTSIDE） */
val LocalMenuOutsideTap = staticCompositionLocalOf<MenuOutsideTapRegistry?> { null }

/**
 * 把 [MenuOutsideTapRegistry] 的按下观察挂到窗口根部：必须是**整窗内容**的祖先，
 * 且与所有菜单处于同一窗口（分区 Dialog / 独立 Popup 窗口内的菜单不在覆盖范围）。
 * 观察只读不消费，子级手势、点击与滚动全部照常。
 */
@Composable
fun Modifier.menuOutsideTapDismiss(): Modifier {
    val registry = LocalMenuOutsideTap.current ?: return this
    return pointerInput(registry) {
        awaitEachGesture {
            // 不要求「未消费」：本层要看到所有按下，包括被子级 clickable 消费的那些
            awaitFirstDown(requireUnconsumed = false)
            registry.dispatchOutsideTap()
        }
    }
}
