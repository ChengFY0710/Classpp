package com.fangyi.classpp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fangyi.classpp.ui.components.NoteCard
import com.fangyi.classpp.ui.components.NoteCardSwipe
import com.fangyi.classpp.ui.components.SwipeAction
import com.fangyi.classpp.ui.components.SwipeCoordinator
import com.fangyi.classpp.ui.theme.ClassppTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 侧滑命中回归测试：
 * - 收起态点击卡片任何位置（含贴边最小触控目标扩展区）不得触发删除/置顶；
 * - 最近删除页形态（Row 无 onClick）贴边点按不得触发彻底删除——0 宽动作卡挂 clickable 时
 *   会被 48dp 最小触控目标向外扩约 24dp 命中区，此前该页点卡片左缘会直接彻底删除
 *   （回归由动作卡「仅呼出后挂点按」的条件挂载挡住）；
 * - 展开态点呼出的动作卡按钮 = 设计内快捷执行；点卡片主体 = 收起；
 * - 二段式拖拽（展开态再拖满色松手）仍执行。
 */
@RunWith(AndroidJUnit4::class)
class NoteCardSwipeHitTest {

    @get:Rule
    val rule = createComposeRule()

    private var deleted = false
    private var pinned = false
    private var clicked = false

    private fun setContent(deletedStyle: Boolean = false) {
        deleted = false
        pinned = false
        clicked = false
        rule.setContent {
            ClassppTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFFEEEEEE))
                        .padding(horizontal = 15.dp, vertical = 40.dp),
                ) {
                    Box(Modifier.testTag("card")) {
                        if (deletedStyle) {
                            NoteCard(
                                title = "已删除待办",
                                time = "12:30",
                                deleted = true,
                                onRestore = {},
                                swipe = NoteCardSwipe(
                                    key = "t2",
                                    coordinator = SwipeCoordinator(),
                                    delete = SwipeAction(
                                        iconRes = R.drawable.ic_delete,
                                        tint = Color.Red,
                                        activeColor = Color.Red,
                                        onActiveColor = Color.White,
                                        contentDescription = "swipe_delete",
                                        onAction = { deleted = true },
                                    ),
                                    pin = null,
                                ),
                            )
                        } else {
                            NoteCard(
                                title = "测试待办标题",
                                time = "12:30",
                                onClick = { clicked = true },
                                onCheckedChange = {},
                                swipe = NoteCardSwipe(
                                    key = "t1",
                                    coordinator = SwipeCoordinator(),
                                    delete = SwipeAction(
                                        iconRes = R.drawable.ic_delete,
                                        tint = Color.Red,
                                        activeColor = Color.Red,
                                        onActiveColor = Color.White,
                                        contentDescription = "swipe_delete",
                                        onAction = { deleted = true },
                                    ),
                                    pin = SwipeAction(
                                        iconRes = R.drawable.ic_pin,
                                        tint = Color.Blue,
                                        activeColor = Color.Blue,
                                        onActiveColor = Color.White,
                                        contentDescription = "swipe_pin",
                                        onAction = { pinned = true },
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun cardBounds() = rule.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot

    private fun tapAt(x: Float, y: Float) {
        rule.onRoot().performTouchInput { click(Offset(x, y)) }
        rule.waitForIdle()
    }

    private fun dragBy(dx: Float) {
        val b = cardBounds()
        rule.onRoot().performTouchInput {
            down(Offset(b.center.x, b.center.y))
            repeat(15) { moveBy(Offset(dx / 15f, 0f)) }
            up()
        }
        rule.waitForIdle()
    }

    private fun px(dp: Float) = with(rule.density) { dp.dp.toPx() }

    @Test
    fun collapsedTapAtEdgesDoesNotExecuteActions() {
        setContent()
        val b = cardBounds()
        val probes = listOf(
            "left+2dp" to (b.left + px(2f)),
            "left+8dp" to (b.left + px(8f)),
            "left+16dp" to (b.left + px(16f)),
            "left+23dp" to (b.left + px(23f)),
            "right-2dp" to (b.right - px(2f)),
            "right-8dp" to (b.right - px(8f)),
        )
        for ((label, x) in probes) {
            tapAt(x, b.center.y)
            assertFalse("[$label] 误触发删除", deleted)
            assertFalse("[$label] 误触发置顶", pinned)
            assertTrue("[$label] 应打开详情（右缘复选框热区除外）", clicked)
            clicked = false
        }
        // 右缘复选框热区（约 36dp + 6dp 扩展）：点按落到复选框，不属于删除/置顶，也不开详情
        for ((label, x) in listOf("right-23dp" to (b.right - px(23f)), "right-16dp" to (b.right - px(16f)))) {
            tapAt(x, b.center.y)
            assertFalse("[$label] 误触发删除", deleted)
            assertFalse("[$label] 误触发置顶", pinned)
        }
    }

    @Test
    fun collapsedCenterTapOpensDetail() {
        setContent()
        val b = cardBounds()
        tapAt(b.center.x, b.center.y)
        assertFalse(deleted)
        assertFalse(pinned)
        assertTrue(clicked)
    }

    @Test
    fun deletedCardLeftEdgeTapDoesNotPurge() {
        // 最近删除页形态：无 onClick（Row 无指针输入）——回归场景：贴边点按曾直接彻底删除
        setContent(deletedStyle = true)
        val b = cardBounds()
        for (offsetDp in listOf(2f, 8f, 16f, 23f)) {
            tapAt(b.left + px(offsetDp), b.center.y)
            assertFalse("删除页卡片左缘 ${offsetDp}dp 点按误触发彻底删除", deleted)
        }
        tapAt(b.center.x, b.center.y)
        assertFalse(deleted)
    }

    @Test
    fun openStateTapOnRevealedButtonExecutes() {
        setContent()
        dragBy(px(150f))
        val b = cardBounds()
        // 展开态点呼出的删除按钮（左缘 40dp）= 设计内快捷执行
        tapAt(b.left + px(40f), b.center.y)
        assertTrue("展开态点删除按钮应执行删除", deleted)
        assertFalse("展开态点删除按钮不应触发置顶", pinned)
    }

    @Test
    fun openStateTapOnCardBodyCollapsesNotExecutes() {
        setContent()
        dragBy(px(150f))
        val b = cardBounds()
        // 展开态点卡片主体（按钮 80 + 间距 10 之后）= 收起，不执行
        tapAt(b.left + px(120f), b.center.y)
        assertFalse("展开态点卡主体误触发删除", deleted)
        assertFalse("展开态点卡主体误触发置顶", pinned)
        // 收起后卡片右缘不触发置顶
        tapAt(b.right - px(5f), b.center.y)
        assertFalse("展开态点右缘误触发置顶", pinned)
    }

    @Test
    fun pinSideOpenTapOnRevealedButtonExecutes() {
        setContent()
        dragBy(-px(150f))
        val b = cardBounds()
        // 展开态点呼出的置顶按钮（右缘 40dp）= 设计内快捷执行
        tapAt(b.right - px(40f), b.center.y)
        assertTrue("展开态点置顶按钮应执行置顶", pinned)
        assertFalse("展开态点置顶按钮不应触发删除", deleted)
    }

    @Test
    fun twoStageDragStillExecutes() {
        // 设计内的执行路径保持不变：呼出后二次拖拽满色（钉住）松手执行
        setContent()
        val b = cardBounds()
        val maxPx = px(216f)
        rule.onRoot().performTouchInput {
            down(Offset(b.center.x, b.center.y))
            repeat(15) { moveBy(Offset(px(150f) / 15f, 0f)) } // 呼出
            up()
        }
        rule.waitForIdle()
        rule.onRoot().performTouchInput {
            down(Offset(b.center.x + px(150f), b.center.y))
            // 卡片已在呼出位（90dp），再拖满色需要补满上限差值（216 − 90 = 126dp）；
            // 略微过冲避免浮点累积差半像素没到阈值（真实拖拽会被钳制在钉住点）
            repeat(20) { moveBy(Offset((maxPx - px(90f) + px(20f)) / 20f, 0f)) }
            up()
        }
        rule.waitForIdle()
        assertTrue("二段式拖拽满色松手应执行删除", deleted)
    }
}
