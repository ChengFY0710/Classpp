package com.fangyi.classpp.ui.note

import com.fangyi.classpp.data.model.Todo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 待办编辑顶栏置顶钮的判定契约（[todoPinAction]）：
 *
 * - 空选择（含所选 id 已不在待办列表）= [PinAction.Disabled]，按钮置灰；
 * - 所选全部已置顶 = [PinAction.Unpin]，按钮切「取消」+ ic_pin_off，点击取消置顶；
 * - 所选全部未置顶 / 混合（既有已置顶又有未置顶）= [PinAction.Pin]——混合时仍显示
 *   「置顶」，点按整批置顶。
 */
class TodoEditBarPinTest {

    /** A、B 已置顶，C、D 未置顶 */
    private val todos = listOf(
        Todo(id = "A", name = "A", pinned = true),
        Todo(id = "B", name = "B", pinned = true),
        Todo(id = "C", name = "C"),
        Todo(id = "D", name = "D"),
    )

    @Test
    fun emptySelectionIsDisabled() {
        assertEquals(PinAction.Disabled, todoPinAction(todos, emptySet()))
        // 选中集里的 id 已不在待办列表（例如刚被删除）：同样视为空选择
        assertEquals(PinAction.Disabled, todoPinAction(todos, setOf("missing")))
    }

    @Test
    fun allPinnedSelectionUnpins() {
        assertEquals(PinAction.Unpin, todoPinAction(todos, setOf("A")))
        assertEquals(PinAction.Unpin, todoPinAction(todos, setOf("A", "B")))
    }

    @Test
    fun allUnpinnedSelectionPins() {
        assertEquals(PinAction.Pin, todoPinAction(todos, setOf("C")))
        assertEquals(PinAction.Pin, todoPinAction(todos, setOf("C", "D")))
    }

    @Test
    fun mixedSelectionStillPins() {
        assertEquals(PinAction.Pin, todoPinAction(todos, setOf("A", "C")))
        assertEquals(PinAction.Pin, todoPinAction(todos, setOf("A", "B", "C", "D")))
    }
}
