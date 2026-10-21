package com.fangyi.classpp.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.components.BottomFadeScrim
import com.fangyi.classpp.ui.motion.pressFeedback
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.PillShape
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens

enum class AppTab(
    @DrawableRes val iconRes: Int,
    @StringRes val labelRes: Int,
    /** 选中态改用的图标：待办页选中后胶囊变为「新建待办」入口，null 表示不变 */
    @DrawableRes val selectedIconRes: Int? = null,
    /** 选中态改用的文案，null 表示不变 */
    @StringRes val selectedLabelRes: Int? = null,
) {
    Agenda(R.drawable.ic_day, R.string.nav_schedule),
    Timetable(R.drawable.ic_calendar, R.string.nav_timetable),
    Todo(
        iconRes = R.drawable.ic_notes,
        labelRes = R.string.nav_todo,
        selectedIconRes = R.drawable.ic_note_add,
        selectedLabelRes = R.string.nav_todo_create,
    ),
}

internal val PillHeight = 56.dp
internal val PillBottomOffset = 8.dp
private val PillSpacing = 18.dp
private val PillShadowElevation = 8.dp
/** 选中指示器相对白胶囊边缘的内缩 */
private val PillIndicatorInset = 3.dp
private val PillHorizontalPadding = 33.dp

/** 玻璃模糊半径：胶囊小、底后是渐变遮罩淡出的页面内容，轻模糊够读 */
private val GlassBlurRadius = 4.dp
/** 玻璃折射高度/深度（官方 Glass Bottom Bar 教程同款起始值） */
private val GlassLensHeight = 16.dp
private val GlassLensDepth = 32.dp
/** 玻璃表面染色浓度：选中项主题蓝、未选中白玻璃（表面 alpha，目测定值） */
private const val GlassSelectedTintAlpha = 0.70f
private const val GlassIdleTintAlpha = 0.55f

/** 胶囊区上方被渐变遮罩盖住的延伸高度（遮罩已下沉为页面的 BottomFadeScrim，仅供其取用） */
internal val GradientOverhang = 40.dp

/** 课表网格等内容需为导航栏预留的底部高度（不含系统导航栏 inset） */
val NavReserve = PillBottomOffset + PillHeight

/**
 * 底部导航：三个胶囊按钮。渐变遮罩不在其中——已下沉为各页面自绘的
 * BottomFadeScrim（ui.components），高度与此处几何常量对齐。
 * 调用方负责对齐（如 Modifier.align(Alignment.BottomCenter)）。
 *
 * 玻璃样式（[glassEnabled] 且 [backdrop] 非空时启用）：胶囊白底改由 Backdrop 液态
 * 玻璃渲染——模糊 + 折射 + 表面染色（选中项主题蓝、未选中白玻璃），选中蓝底指示器
 * 取消、图标文字转白；尺寸/间距/形状/投影/按压反馈与白底实现完全一致。
 * [backdrop] 采样层由调用方创建（rememberLayerBackdrop 画底色 + layerBackdrop 标记
 * 页面世界），导航栏自身必须在采样层之外，否则玻璃会照见自己。
 */
@Composable
fun BottomNavBar(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
    glassEnabled: Boolean = false,
    backdrop: Backdrop? = null,
) {
    val useGlass = glassEnabled && backdrop != null
    Box(
        modifier = modifier
            .fillMaxWidth()
            // 系统 inset + 胶囊区：遮罩不再属于导航栏，页面滚进胶囊区上方的淡出
            // 由各页面的 BottomFadeScrim 负责
            .height(
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                    NavReserve,
            ),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = PillHorizontalPadding)
                .navigationBarsPadding()
                .padding(bottom = PillBottomOffset)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(PillSpacing),
        ) {
            AppTab.entries.forEach { tab ->
                NavPill(
                    tab = tab,
                    selected = tab == selectedTab,
                    useGlass = useGlass,
                    backdrop = backdrop,
                    onClick = { onTabSelected(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NavPill(
    tab: AppTab,
    selected: Boolean,
    useGlass: Boolean,
    backdrop: Backdrop?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 玻璃模式下选中项转白：染了主题蓝的玻璃上用主题蓝/深色图文都读不清
    val contentColor =
        when {
            useGlass && selected -> Color.White
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        }
    // 玻璃表面染色：选中项主题蓝（深色模式自动用 DarkPrimary），未选中白玻璃。
    // 读取在组合侧完成——onDrawSurface 是 DrawScope，读不了 Composable 上下文
    val surfaceTint =
        if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = GlassSelectedTintAlpha)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = GlassIdleTintAlpha)
        }
    // 选中态的胶囊内容可整体换装（待办 → 新建入口），未定义则与常态相同
    val iconRes = if (selected) tab.selectedIconRes ?: tab.iconRes else tab.iconRes
    val labelRes = if (selected) tab.selectedLabelRes ?: tab.labelRes else tab.labelRes
    // 按压态的唯一来源：pressFeedback 与 selectable 共用（涟漪由按压反馈取代，故 selectable 不再要 Indication）
    val pressInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(PillHeight)
            // 按压反馈：整体放大 + 主体提亮，光晕式的柔影按原样保留（接在带 clip 的图层之前）
            .pressFeedback(
                interactionSource = pressInteraction,
                shape = PillShape,
                scale = 1.15f,
            )
            .graphicsLayer {
                shape = PillShape
                clip = true
                shadowElevation = 45.dp.toPx()
                spotShadowColor = Color.Black.copy(alpha = 0.3f)
            }
            // 白底必须不透明：PrimaryContainer 仅 12% alpha，若直接铺在胶囊上，
            // graphicsLayer 的投影会从半透明底里透上来，在图标后形成一条淡横线。
            // 玻璃模式改由 drawBackdrop 出底：采样层内容经模糊/折射后裁进胶囊形状，
            // 表面再叠染色——白玻璃（未选中）/主题蓝（选中）；玻璃受同一 clip 约束，
            // 投影环境不变。lens 折射走 RuntimeShader 仅 API 33+，低版本只留模糊+染色
            .then(
                if (useGlass && backdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { PillShape },
                        effects = {
                            blur(GlassBlurRadius.toPx())
                            if (Build.VERSION.SDK_INT >= 33) {
                                lens(GlassLensHeight.toPx(), GlassLensDepth.toPx())
                            }
                        },
                        onDrawSurface = { drawRect(surfaceTint) },
                    )
                } else {
                    Modifier.background(MaterialTheme.colorScheme.surface, PillShape)
                },
            )
            .selectable(
                selected = selected,
                interactionSource = pressInteraction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 选中指示器：浮在白胶囊内侧，四周内缩 PillIndicatorInset。
        // 玻璃模式的选中态由整胶囊主题蓝染色 + 白色图文承担，蓝底指示器退场
        if (selected && !useGlass) {
            Box(
                Modifier
                    .matchParentSize()
                    .padding(PillIndicatorInset)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        PillShape,
                    ),
            )
        }
        Column(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(5.dp))
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(32.dp),
            )
            Text(
                text = stringResource(labelRes),
                fontSize = 11.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Medium,
                color = contentColor,
                modifier = Modifier.offset(y = - 1.dp),
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 360, name = "日程选中")
@Composable
private fun AgendaSelectedPreview() = BottomNavBarPreview(AppTab.Agenda)

@Preview(showBackground = true, widthDp = 412, heightDp = 360, name = "课表选中")
@Composable
private fun TimetableSelectedPreview() = BottomNavBarPreview(AppTab.Timetable)

@Preview(showBackground = true, widthDp = 412, heightDp = 360, name = "待办选中")
@Composable
private fun TodoSelectedPreview() = BottomNavBarPreview(AppTab.Todo)

/** 模拟页面内容置于导航栏之后，便于观察渐变遮罩的淡出效果 */
@Composable
private fun BottomNavBarPreview(selectedTab: AppTab) {
    ClassppTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            ) {
                repeat(12) {
                    Text(
                        text = "16:40  形式与政策  XX老师  @学武楼 B101",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
            }
            BottomFadeScrim(modifier = Modifier.align(Alignment.BottomCenter))
            BottomNavBar(
                selectedTab = selectedTab,
                onTabSelected = {},
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
