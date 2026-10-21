package com.fangyi.classpp.ui.day

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.ui.LocalToday
import com.fangyi.classpp.ui.components.hazeSources
import com.fangyi.classpp.ui.navigation.NavReserve
import com.fangyi.classpp.ui.theme.SerifDisplay
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.util.GregorianCalendar
import java.util.TimeZone

// 上下磨砂层的覆盖高度：决定模糊/压暗各自渐隐的距离（对照日程页设计稿，可调）
private val TopScrimHeight = 135.dp
private val BottomScrimHeight = 360.dp

// 日期区与顶部提示的排版常量（对照设计稿，可调）
private val DateEdgePadding = 33.dp
private val DateBottomGap = 40.dp
private val HintTopPadding = 12.dp

private const val MillisPerDay = 86_400_000L

/**
 * 日程页（AppTab.Agenda）：全屏背景图 + 上下双向渐变磨砂的锁屏式版面。
 *
 * 背景图是唯一 [hazeSource] 采样源，上下两条磨砂层各自 [hazeBlur] 出「近边最强、向对边渐隐」
 * 的毛玻璃：模糊强度同待办顶栏（25dp + Balanced），压暗 tint 固定纯黑（照片底不随主题换色），
 * progressive 会把模糊与 tint 一起渐隐，正好得到设计稿的「24%/30% 黑 → 0% 黑」。
 *
 * 背景暂为固定测试图（drawable-nodpi/bg_day_test）；ic_image 换背景入口仅展示，
 * 呼起浮层的能力后续实现。上滑手势同样未实现，顶部提示只作静态展示。
 */
@Composable
fun DayScreen(modifier: Modifier = Modifier) {
    val hazeState = rememberHazeState()

    Box(modifier = modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.bg_day_test),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .matchParentSize()
                .hazeSource(hazeState),
        )

        DayEdgeScrim(
            hazeState = hazeState,
            bottomAnchored = false,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        DayEdgeScrim(
            hazeState = hazeState,
            bottomAnchored = true,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // 顶部提示：「上滑开启新一天」（本版本仅展示，无手势）
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = HintTopPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_lock_open),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.day_swipe_hint),
                fontSize = 15.sp,
                color = Color.White,
            )
        }

        // 日期区：压在导航栏胶囊上方（NavReserve 对齐胶囊顶），横跨整行好让图片入口靠右
        val today = LocalToday
        val (year, month, dayOfMonth) = remember(today) { today.toYearMonthDay() }
        val yearMonth = stringResource(R.string.date_year_month_format, year, month)
        val weekday = stringArrayResource(R.array.weekdays)[today.isoDayOfWeek() - 1]
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = DateEdgePadding)
                .padding(bottom = navBarBottom + NavReserve + DateBottomGap),
        ) {
            Text(
                text = dayOfMonth.toString(),
                fontSize = 64.sp,
                fontFamily = SerifDisplay,
                color = Color.White,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.date_title_format, yearMonth, weekday),
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    letterSpacing = (-0.3).sp,
                )
                Spacer(Modifier.weight(1f))
                // 换背景入口：本版本仅展示，点击呼起浮层后续实现
                Icon(
                    painter = painterResource(R.drawable.ic_image),
                    contentDescription = stringResource(R.string.cd_day_background),
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

/**
 * 日程页边缘渐变磨砂层：[bottomAnchored] 决定贴顶（顶部最强、向下渐隐）还是贴底
 * （底部最强、向上渐隐）。模糊强度同待办顶栏（25dp + Balanced 下采样档）。
 */
@Composable
private fun DayEdgeScrim(
    hazeState: HazeState,
    bottomAnchored: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(if (bottomAnchored) BottomScrimHeight else TopScrimHeight)
            .hazeBlur(
                input = hazeSources(hazeState),
                style = dayEdgeScrimStyle(bottomAnchored),
                performanceMode = HazePerformanceMode.Balanced,
            ),
    )
}

/**
 * 边缘磨砂配方：近边强度 1 渐隐到对边 0；压暗 tint 贴底 30% 黑、贴顶 24% 黑，
 * progressive 随模糊一起把 tint 渐隐到 0。照片底恒定用黑 tint，不读主题 hazeTint。
 */
@Composable
private fun dayEdgeScrimStyle(bottomAnchored: Boolean): HazeBlurStyle {
    val tintAlpha = if (bottomAnchored) 0.30f else 0.24f
    return HazeBlurStyle {
        blurRadius(25.dp)
        progressive(
            if (bottomAnchored) {
                HazeProgressive.verticalGradient(startIntensity = 0f, endIntensity = 1f)
            } else {
                HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f)
            },
        )
        colorEffects(listOf(HazeColorEffect.tint(Color.Black.copy(alpha = tintAlpha))))
        noiseFactor(0f)
    }
}

/** [IsoDate] → (年, 月, 日)（UTC 日历日换算，同 BottomDateSelectCard/RepeatSchedule 的既有模式） */
private fun IsoDate.toYearMonthDay(): Triple<Int, Int, Int> {
    val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = epochDay * MillisPerDay
    }
    return Triple(
        calendar.get(GregorianCalendar.YEAR),
        calendar.get(GregorianCalendar.MONTH) + 1,
        calendar.get(GregorianCalendar.DAY_OF_MONTH),
    )
}
