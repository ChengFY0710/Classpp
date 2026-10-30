package com.fangyi.classpp.ui.motion

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize

/**
 * 与下层兄弟共享命中：本 Layout 命中指针后，不再截断 z 序更低兄弟子树的命中检索。
 *
 * Compose 的命中测试按 z 序从最前面的兄弟开始找，一旦某子树命中、且其修饰符链上没有
 * `sharePointerInputWithSiblings() == true` 的指针输入节点，检索立即停止——被它盖住的
 * 兄弟（即使视觉上只是透明空白）完全收不到事件。滚动容器（LazyColumn 等）的 scrollable
 * 节点即使 `userScrollEnabled = false` 也始终构成命中：全尺寸列表抬到上层元素区域内时
 * （如日程页 02 态内容视口顶垫进图片卡下半部），下层可点击元素会被整片「挡死」。
 *
 * 本修饰符不消费任何事件，只把所在 Layout 标记为共享：挂在滚动容器的外层 Box 修饰链上，
 * 被盖住的兄弟点击层即可重新参与命中；事件仍先经过本子树，上层可滚动/可点击行为的
 * 消费优先级不变。
 */
fun Modifier.sharePointerInputWithSiblings(): Modifier =
    then(SharePointerInputElement)

/** 单例元素：无参数，引用相等即可复用节点。 */
private object SharePointerInputElement :
    ModifierNodeElement<SharePointerInputNode>() {

    override fun create() = SharePointerInputNode

    override fun update(node: SharePointerInputNode) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "sharePointerInputWithSiblings"
    }

    // ui 1.10+ 把 equals/hashCode 重声明为抽象成员：单例元素引用相等即等价（同
    // OffsetOverscrollFactory 的处理）。
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}

/** 只声明共享命中的透传节点：不接收、不消费任何指针事件。 */
private object SharePointerInputNode : Modifier.Node(), PointerInputModifierNode {

    override fun sharePointerInputWithSiblings(): Boolean = true

    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize,
    ) = Unit

    override fun onCancelPointerInput() = Unit
}
