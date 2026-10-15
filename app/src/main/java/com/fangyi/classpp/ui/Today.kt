package com.fangyi.classpp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fangyi.classpp.data.model.IsoDate
import kotlinx.coroutines.delay

/** 前台对表间隔：常亮挂过夜时，零点多后一分钟内跳到新日期 */
private const val TodayResyncMillis = 60_000L

/**
 * UI 层的「今天」状态源：由 [com.fangyi.classpp.ui.theme.ClassppTheme] 在组合顶层 provide
 * 一份 [rememberTodayState]（主题之外裸组合时退化为静态当天值，供 @Preview 等场景兜底）。
 *
 * 携带 [State] 而非散值：需要「长期持有、读时现取」的闭包（如 Pager 的 pageCount）可以拿到
 * 稳定实例、在里面 `.value` 现读，不会冻结在创建那一刻。
 */
val LocalTodayState: ProvidableCompositionLocal<State<IsoDate>> = compositionLocalOf {
    // 无 provider 时的兜底：静态当天值（不自动跳天；真实页面都经 ClassppTheme 注入）
    mutableStateOf(IsoDate.today())
}

/**
 * 当前「今天」的值语义入口，用法：`val today = LocalToday`。
 *
 * 页面里**按天派生**的显示（倒计时剩余天数、截止配色、今天/明天相对词、逾期分组等）
 * 一律读它，而不是各自调 `IsoDate.today()`：后者只在重组那一刻取值，挂过夜或进程后台存活
 * 数日后会停在旧日期——读本值则跨零点由 [rememberTodayState] 统一推动重组。
 */
val LocalToday: IsoDate
    @Composable get() = LocalTodayState.current.value

/**
 * 「今天」的自动对表状态：跨本地零点自动前进（数字按日历日比较，与时刻无关）。
 *
 * - **回到前台立即对表**：覆盖后台被冻结多日的进程（delay 在进程冻结期间并不走，只靠定时器
 *   会停在旧日期）；
 * - **前台期间每 [TodayResyncMillis] 对一次表**：平板常亮/不息屏挂过夜时跨越零点；
 *   日期未变时写入同值不产生重组。
 *
 * 原为课表页私有的 `rememberTodayIso`，提为公共源后课表页与待办页共用一份实例。
 */
@Composable
fun rememberTodayState(): State<IsoDate> {
    val epochDay = remember { mutableLongStateOf(IsoDate.today().epochDay) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                epochDay.longValue = IsoDate.today().epochDay
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(TodayResyncMillis)
            epochDay.longValue = IsoDate.today().epochDay
        }
    }

    return remember { derivedStateOf { IsoDate(epochDay.longValue) } }
}
