package com.fangyi.classpp.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.notification.NotificationScheduler
import com.fangyi.classpp.ui.components.BottomNumberSelectCard
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SheetBottomSlack
import com.fangyi.classpp.ui.motion.pressFeedback
import com.fangyi.classpp.ui.motion.rubberBandVerticalScroll
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.SettingsSectionSpacing
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch

/**
 * 通知与提醒子页（设置 → 通知与提醒）：由 [SettingsScreen] 以整页覆盖转场组合在根页之上
 * （与个性化子页同源，ui.motion 的 PageOverlayTransition），返回按钮/返回键经 [onBack]
 * 回到设置根页。
 *
 * 骨架与个性化页同构（Scaffold + 滚动内容列 + 顶栏叠加），正文全部用 [SettingsCard]：
 * 顶部「启用通知」「通知方式」两卡收进无题分组（组内 10dp 卡间距与带题分组一致），
 * 其后「课程通知」「待办通知」两个分组（分组内子开关 + 提前时间导航行，待办组另有
 * 一行通知类型）。总开关为同步语义：变化时两个子开关一并置同值。提前时间点行弹
 * [BottomNumberSelectCard] 横向滚轮（0–60 分钟，两处入口共用一个弹窗）。
 *
 * 普通通知已实装（data/notification 的 NotificationScheduler）：每次变更即时落盘并
 * 整体重排系统闹钟；总开关打开且未授权（API 33+）时请求 POST_NOTIFICATIONS，
 * 拒绝则整体回退为关——「启用通知」语义 = 已授权且已开启。
 */
@Composable
fun NotificationScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 偏好真源：进页 load 一次，此后每次变更即时落盘（apply 异步，同 themeMode 模式）
    var prefs by remember { mutableStateOf(NotificationPreferences.load(context)) }
    val scope = rememberCoroutineScope()
    val setPrefs: (NotificationSettings) -> Unit = { value ->
        prefs = value
        NotificationPreferences.save(context, value)
        // 设置变化即时反映到系统闹钟：整体重排（幂等；总开关全关 = 全部取消）
        scope.launch { NotificationScheduler.reschedule(context) }
    }
    // 通知运行时权限（API 33+）：总开关打开且未授权时请求；拒绝则整体回退为关——
    // 「启用通知」语义 = 已授权且已开启，避免开关开着但系统侧一条也发不出来
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        setPrefs(
            if (granted) {
                prefs.copy(enabled = true, courseEnabled = true, todoEnabled = true)
            } else {
                prefs.copy(enabled = false, courseEnabled = false, todoEnabled = false)
            },
        )
        if (!granted) {
            AppToasts.show(context, context.getString(R.string.notification_permission_denied_toast))
        }
    }

    // 提前时间滚轮（课程/待办两处入口共用一个弹窗）：点行记下目标与初值再挂载，确认
    // 按目标写回；两段式挂载（visible/onDismissed）同 ScheduleSettingsSheet 的周数滚轮——
    // 取消/遮罩/返回只请求关闭，退场动画播完才卸载
    var leadPickerMounted by rememberSaveable { mutableStateOf(false) }
    var leadPickerVisible by rememberSaveable { mutableStateOf(false) }
    var leadPickerForCourse by rememberSaveable { mutableStateOf(true) }
    var leadPickerInitial by rememberSaveable { mutableStateOf(NotificationSettings.DefaultLeadMinutes) }

    // 选项表建一次：行值文字与弹菜单条目同源（load 已钳制 id，first 必然命中）
    val methodItems = listOf(
        NotificationSettings.METHOD_NORMAL to stringResource(R.string.notification_method_normal),
        NotificationSettings.METHOD_LIVE to stringResource(R.string.notification_method_live),
    )
    val todoTypeItems = listOf(
        NotificationSettings.TODO_TYPE_ALL to stringResource(R.string.notification_todo_type_all),
        NotificationSettings.TODO_TYPE_DEADLINE to stringResource(R.string.notification_todo_type_deadline),
        NotificationSettings.TODO_TYPE_START to stringResource(R.string.notification_todo_type_start),
        NotificationSettings.TODO_TYPE_END to stringResource(R.string.notification_todo_type_end),
    )

    // 采样源状态提到 Scaffold 之外：滚轮弹窗（Popup 窗口）也要用它对页面做卡片磨砂
    val hazeState = rememberHazeState()

    Scaffold(
        modifier = modifier,
        // 沉浸式：只吃左右 inset，状态栏/手势条区域交给内容与顶栏自己铺满（同设置根页）
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        // 顶栏叠在内容之上：内容整屏铺开（hazeSource），首屏经 topBarHeight 内缩到顶栏之下，
        // 上滑时从顶栏背后滚过，顶栏用 Haze 对其做自上而下的渐变背景模糊
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            var topBarHeight by remember { mutableStateOf(0.dp) }
            val density = LocalDensity.current
            // 手势条（小白条）区域：内容可铺到屏幕底，末尾留出这段避免被遮挡
            val navBarPadding = WindowInsets.navigationBars
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues()
                .calculateBottomPadding()

            // 采样源始终挂正文列：顶栏背后才有内容可采（不可省）
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState)
                    // 橡皮筋增强版 verticalScroll：内容不足一屏时也能拉出橡皮筋
                    .rubberBandVerticalScroll(rememberScrollState())
                    .padding(horizontal = PageHorizontalSpacing)
                    .padding(
                        // 首项距顶栏留 TopBarContentGap（页自有节奏），尾部余量与浮层同源
                        top = topBarHeight + TopBarContentGap,
                        bottom = navBarPadding + SheetBottomSlack,
                    ),
                verticalArrangement = Arrangement.spacedBy(SettingsSectionSpacing),
            ) {
                // 顶部两卡不带分组标题：无题 CardSection 收拢成组，组内 10dp 卡间距与
                // 下方两个带题分组同节奏；组与组之间仍走页面的 SettingsSectionSpacing
                CardSection {
                    SettingsCard(
                        items = listOf(
                            SettingsCardItem.Toggle(
                                label = stringResource(R.string.notification_enable),
                                description = stringResource(R.string.notification_permission_desc),
                                checked = prefs.enabled,
                                onCheckedChange = { on ->
                                    when {
                                        // 关：同步语义，两个子开关一并置同值
                                        !on -> setPrefs(
                                            prefs.copy(enabled = false, courseEnabled = false, todoEnabled = false),
                                        )
                                        // 开且未授权（API 33+）：先请求，落盘交给授权回调
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                            ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.POST_NOTIFICATIONS,
                                            ) != PackageManager.PERMISSION_GRANTED ->
                                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        // 开且已授权（或低版本无运行时权限）：照常同步全开
                                        else -> setPrefs(
                                            prefs.copy(enabled = true, courseEnabled = true, todoEnabled = true),
                                        )
                                    }
                                },
                            ),
                        ),
                    )
                    SettingsCard(
                        items = listOf(
                            SettingsCardItem.Select(
                                label = stringResource(R.string.notification_method),
                                description = stringResource(R.string.notification_method_desc),
                                value = methodItems.first { it.first == prefs.method }.second,
                                items = methodItems,
                                selectedId = prefs.method,
                                onPick = { id -> setPrefs(prefs.copy(method = id)) },
                            ),
                        ),
                    )
                    SettingsCard(
                        items = listOf(
                            // 测试入口：与真实提醒完全同款的实况预览（60 秒自动移除），
                            // 不受当前通知方式约束——目的是随时能看 chip 效果
                            SettingsCardItem.Custom(
                                label = stringResource(R.string.notification_test_label),
                                description = stringResource(R.string.notification_test_desc),
                                trailing = {
                                    val testPress = remember { MutableInteractionSource() }
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                if (!NotificationScheduler.postTestLiveUpdate(context)) {
                                                    AppToasts.show(
                                                        context,
                                                        context.getString(R.string.notification_test_permission_toast),
                                                    )
                                                }
                                            }
                                        },
                                        interactionSource = testPress,
                                        modifier = Modifier.pressFeedback(testPress, ButtonDefaults.textShape),
                                    ) {
                                        Text(
                                            text = stringResource(R.string.notification_test_publish),
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                },
                            ),
                        ),
                    )
                }
                CardSection(title = stringResource(R.string.section_course_notifications)) {
                    CourseNotificationCard(
                        enabled = prefs.courseEnabled,
                        leadMinutes = prefs.courseLeadMinutes,
                        masterEnabled = prefs.enabled,
                        onEnabledChange = { on -> setPrefs(prefs.copy(courseEnabled = on)) },
                        onLeadPickRequest = {
                            leadPickerForCourse = true
                            leadPickerInitial = prefs.courseLeadMinutes
                            leadPickerMounted = true
                            leadPickerVisible = true
                        },
                    )
                }
                CardSection(title = stringResource(R.string.section_todo_notifications)) {
                    TodoNotificationCard(
                        enabled = prefs.todoEnabled,
                        leadMinutes = prefs.todoLeadMinutes,
                        typeItems = todoTypeItems,
                        selectedTypeId = prefs.todoType,
                        masterEnabled = prefs.enabled,
                        onEnabledChange = { on -> setPrefs(prefs.copy(todoEnabled = on)) },
                        onLeadPickRequest = {
                            leadPickerForCourse = false
                            leadPickerInitial = prefs.todoLeadMinutes
                            leadPickerMounted = true
                            leadPickerVisible = true
                        },
                        onTypePick = { id -> setPrefs(prefs.copy(todoType = id)) },
                    )
                }
            }

            SettingsTopBar(
                title = stringResource(R.string.settings_notifications),
                onBack = onBack,
                hazeState = hazeState,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .onGloballyPositioned { coords ->
                        // 顶栏实测高度 = 内容首屏内缩量（px → dp）
                        topBarHeight = with(density) { coords.size.height.toFloat().toDp() }
                    },
            )
        }
    }

    // 提前时间横向滚轮画在页面之后（Popup 窗口，组合顺序即绘制顺序无关紧要，与浮层
    // 同约定挂内容之后）；确认把分钟数写回请求行的目标项
    if (leadPickerMounted) {
        BottomNumberSelectCard(
            visible = leadPickerVisible,
            onDismiss = { leadPickerVisible = false },
            onDismissed = { leadPickerMounted = false },
            title = stringResource(R.string.notification_lead_time),
            valueRange = NotificationPreferences.LEAD_RANGE,
            initialValue = leadPickerInitial,
            unit = stringResource(R.string.unit_min),
            hazeState = hazeState,
            onConfirm = { minutes ->
                leadPickerVisible = false
                setPrefs(
                    if (leadPickerForCourse) prefs.copy(courseLeadMinutes = minutes)
                    else prefs.copy(todoLeadMinutes = minutes),
                )
            },
        )
    }
}

/**
 * 「课程通知」组卡片：启用开关 + 提前时间导航行。总开关关闭期间子开关点击不生效
 * （同步语义下不允许出现「总开关关而子开关开」的状态）。
 */
@Composable
private fun CourseNotificationCard(
    enabled: Boolean,
    leadMinutes: Int,
    masterEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onLeadPickRequest: () -> Unit,
) {
    SettingsCard(
        items = listOf(
            SettingsCardItem.Toggle(
                label = stringResource(R.string.notification_enable_course),
                checked = enabled,
                onCheckedChange = { on -> if (masterEnabled) onEnabledChange(on) },
            ),
            SettingsCardItem.Nav(
                label = stringResource(R.string.notification_lead_time),
                description = stringResource(R.string.notification_lead_course_desc),
                value = stringResource(R.string.notification_lead_value, leadMinutes),
                onClick = onLeadPickRequest,
            ),
        ),
    )
}

/**
 * 「待办通知」组卡片：启用开关 + 提前时间导航行 + 通知类型选择行（三行同卡无缝堆叠）；
 * 总开关关闭期间子开关点击不生效，语义同 [CourseNotificationCard]。
 */
@Composable
private fun TodoNotificationCard(
    enabled: Boolean,
    leadMinutes: Int,
    typeItems: List<Pair<Int, String>>,
    selectedTypeId: Int,
    masterEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onLeadPickRequest: () -> Unit,
    onTypePick: (Int) -> Unit,
) {
    SettingsCard(
        items = listOf(
            SettingsCardItem.Toggle(
                label = stringResource(R.string.notification_enable_todo),
                checked = enabled,
                onCheckedChange = { on -> if (masterEnabled) onEnabledChange(on) },
            ),
            SettingsCardItem.Nav(
                label = stringResource(R.string.notification_lead_time),
                description = stringResource(R.string.notification_lead_todo_desc),
                value = stringResource(R.string.notification_lead_value, leadMinutes),
                onClick = onLeadPickRequest,
            ),
            SettingsCardItem.Select(
                label = stringResource(R.string.notification_todo_type),
                value = typeItems.first { it.first == selectedTypeId }.second,
                items = typeItems,
                selectedId = selectedTypeId,
                onPick = onTypePick,
            ),
        ),
    )
}
