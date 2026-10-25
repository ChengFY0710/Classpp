package com.fangyi.classpp

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fangyi.classpp.ui.day.DayStateStore
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 日程页三态手势烟测：01→02→04→02→01（重播）→02。
 *
 * 不截图、不写死日期/文案：导航标签、提示与卡片返回按钮的无障碍文案全部从资源取，
 * 换语言或跨天不会误报。坐标用屏幕比例，不依赖具体分辨率。
 */
@RunWith(AndroidJUnit4::class)
class DayAgendaUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    /**
     * 台历停留态按「同日恢复」落盘：先清掉记录再重建 Activity，保证用例一律从 01 起步，
     * 不受上次使用停在 02/04 的影响（恢复态下首次上滑会直接 02→04，用例必挂）。
     */
    @Before
    fun resetDayStateToCalendar() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(DayStateStore.FILE_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
    }

    /** 无障碍文案直接取目标应用资源，避免写死中文/日期。 */
    private val cardBack: String
        get() = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getString(R.string.cd_day_card_back)

    @Test
    fun threeStatesReplayAndReopen() {
        val navLabel = rule.activity.getString(R.string.nav_schedule)
        val hint = rule.activity.getString(R.string.day_swipe_hint)

        rule.onNode(hasText(navLabel) and hasClickAction()).performClick()
        rule.waitForIdle()
        waitUntilText(hint)

        // 慢速上滑（快速上甩在 01 段会直判 04，见 snapForward 的速度优先规则）
        swipeUp(fromFraction = 0.78f, toFraction = 0.30f, durationMillis = 900) // 01→02
        waitForCard()

        swipeUp(fromFraction = 0.55f, toFraction = 0.25f) // 02→04
        waitUntilCardGone()

        swipeDown(fromFraction = 0.30f, toFraction = 0.70f) // 04→02
        waitForCard()

        // 02 点图片卡：重播回 01，卡片返回按钮消失
        rule.onRoot().performTouchInput {
            click(Offset(width / 2f, height * 0.20f))
        }
        rule.waitForIdle()
        settle()
        waitUntilCardGone()

        // 重播后还能再次上滑开台历（慢速：快速上甩在 01 段会直判 04，见 snapForward）
        swipeUp(fromFraction = 0.78f, toFraction = 0.30f, durationMillis = 900)
        waitForCard()

        // 02 点图片卡下半部（内容列表视口与卡片重叠区）：同样要能重播回 01。
        // 0.20/0.27 都落在卡内；0.27 处于列表视口顶（白卡顶 − 顶栏高）与卡底之间，
        // 是列表 scrollable 节点盖住下层点击层的回归位。
        rule.onRoot().performTouchInput {
            click(Offset(width / 2f, height * 0.27f))
        }
        rule.waitForIdle()
        settle()
        waitUntilCardGone()
    }

    private fun waitUntilText(text: String) {
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForCard() {
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithContentDescription(cardBack).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }

    private fun waitUntilCardGone() {
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithContentDescription(cardBack).fetchSemanticsNodes().isEmpty()
        }
        rule.waitForIdle()
    }

    private fun swipeUp(fromFraction: Float, toFraction: Float, durationMillis: Long = 420) {
        rule.onRoot().performTouchInput {
            swipe(
                start = Offset(width / 2f, height * fromFraction),
                end = Offset(width / 2f, height * toFraction),
                durationMillis = durationMillis,
            )
        }
        settle()
    }

    private fun swipeDown(fromFraction: Float, toFraction: Float, durationMillis: Long = 420) {
        rule.onRoot().performTouchInput {
            swipe(
                start = Offset(width / 2f, height * fromFraction),
                end = Offset(width / 2f, height * toFraction),
                durationMillis = durationMillis,
            )
        }
        settle()
    }

    private fun settle() {
        rule.waitForIdle()
        Thread.sleep(600)
        rule.waitForIdle()
    }
}
