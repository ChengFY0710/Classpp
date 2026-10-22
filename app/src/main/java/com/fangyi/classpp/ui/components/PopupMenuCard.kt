package com.fangyi.classpp.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.theme.MenuShape
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur

/** 勾选图标边长；未勾选行保留同宽空槽，勾选/未勾选的文字左缘对齐（设计稿） */
private val CheckIconSize = 20.dp

/** 行首图标边长（[PopupMenuItem.icon]）：比勾选槽宽，槽宽随之取本值，图标型菜单内各行仍左缘对齐 */
private val MenuIconSize = 24.dp

private val RowHorizontalPadding = 25.dp
private val RowVerticalPadding = 12.dp

/** 勾选图标与文字的间距 */
private val CheckLabelSpacing = 12.dp

/** 分割线内缩：左右各收 8dp，比行内容更贴边（设计稿分割线明显宽于文字区） */
private val DividerHorizontalInset = 8.dp

/** 分割线上下留白 */
private val DividerVerticalPadding = 8.dp

/**
 * 弹出菜单项：文案 + 勾选态 + 行首图标 + 置灰态 + 点击动作
 * （收起菜单由调用方与动作一起处理，这里只管渲染）。
 *
 * @param checked 勾选态：行首画勾选标记、内容色取 primary
 * @param icon 行首图标（可选）：与勾选槽共位，传入时该槽改画图标（勾选标记让位，两者语义互斥）；
 *   图标型菜单的行首槽加宽到 [MenuIconSize]
 * @param enabled false = 置灰不可点：内容色取主题置灰色 `classppColors.negative`，行不吃点击与按压反馈
 */
data class PopupMenuItem(
    val label: String,
    val checked: Boolean = false,
    @DrawableRes val icon: Int? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit = {},
)

/** 弹出菜单分组：纯视觉分段；[showDivider] = 与上一分组之间画分割线（可选，首组忽略） */
data class PopupMenuSection(
    val items: List<PopupMenuItem>,
    val showDivider: Boolean = false,
)

/**
 * 弹出选择菜单卡（设计稿：白圆角卡 + 左侧勾选行 + 按压反馈 + 组间可选分割线）。
 * [MenuShape] 卡壳 + [PopupMenuContent] 内容；自带容器的宿主（如 DropdownMenu）
 * 直接用 [PopupMenuContent] 铺内容，避免双层卡片。
 *
 * 宽度：默认收窄到最宽行；要固定/铺满宽度给 [modifier] 加 width/widthIn/fillMaxWidth，
 * 行是 fillMaxWidth 会跟着撑开。
 *
 * 阴影走 graphicsLayer（WeekPill 同款）：形状必须与 [MenuShape] 一致——
 * CircleShape 是「短边一半」圆角，宽卡上会变成胶囊轮廓并裁掉四角内容。
 * [blurProgress] ∈ [0,1] 缩放投影强度，默认恒 1f（完整阴影）；lambda 延迟读取
 * （draw 阶段取值、动画全程零重组，ScheduleHeader 玻璃层同款），宿主想跟弹出动画
 * 联动时传投影进度，如 `{ (scale - Motion.PopupEnterStartScale) / (1f - Motion.PopupEnterStartScale) }`。
 *
 * 材质：默认不透明白卡；[hazeState] 非空时白底之上叠 Haze 毛玻璃（WeekPickerCardShell
 * 同款配方，跨 Popup 窗口采样宿主页面的 hazeSource）——hazeBlur 须画在背景之上、
 * 内容之下，故壳用 Box + background 而非 Surface（其 modifier 链插不进这一层）；
 * 圆角由投影层 clip 统一裁剪。Haze 2 的 HazeBlurStyle 在组合期构建（tint 由
 * [hazeCardStyle] 内部读主题）。
 *
 * 分层契约（弹出动画的前提）：卡壳（白底 + 毛玻璃 + 投影）对透明度恒为满强度、
 * 永不淡入淡出——整卡半透明会把锐利背景混进毛玻璃，模糊在动画期间看起来
 * 「由弱变强」（6f7cae7 存档的已知问题）。弹出动画只淡 [contentAlpha]（内容行）
 * 与 [blurProgress]（投影），均在 draw 阶段读值、零重组。
 *
 * 模糊对位：毛玻璃是独立子层，并按 [blurScale] 绕 [menuGrowth] 生长角**反向缩放**——
 * 外层生长缩放会把按最终位置画好的采样内容一起变换，动画中除生长角外处处与真实
 * 背景错位；反向缩放后两层相乘为恒等，毛玻璃全程 1:1 钉在真实背景上（模糊半径也
 * 不被放大），卡形状/白底/投影/内容照常随缩放生长。超出卡面的部分由壳 clip 兜住。
 */
@Composable
fun PopupMenuCard(
    sections: List<PopupMenuSection>,
    modifier: Modifier = Modifier,
    blurProgress: () -> Float = { 1f },
    hazeState: HazeState? = null,
    contentAlpha: () -> Float = { 1f },
    blurScale: () -> Float = { 1f },
    menuGrowth: MenuGrowth = MenuGrowth.TopLeft,
) {
    Box(
        // propagateMinConstraints：卡被外部 modifier 撑宽（如固定 200dp）时，内容 Column
        // 至少跟着撑满——否则 Column 仍收在 IntrinsicSize 宽度，右侧卡面成为点按断区
        //（Surface 内部同款行为，换壳时不能丢）
        propagateMinConstraints = true,
        modifier = modifier
            .graphicsLayer {
                shape = MenuShape
                clip = true
                shadowElevation = 36.dp.toPx() * blurProgress()
                spotShadowColor = Color.Black.copy(alpha = 0.15f)
            }
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (hazeState != null) {
            // 白卡毛玻璃：12dp + 60% tint（跨 Popup 窗口采样宿主页面 hazeSource）。
            // 独立子层 + 反向缩放（理由见上方 KDoc）：锚点取与外层缩放同一个卡片
            // 边缘生长角（本层即卡面尺寸，角分数恒为 0/1），两层相乘才是恒等变换。
            // 生长缩放与落定后果冻脉动都会短暂偏离 1，反向缩放对任意 s 成立，故恒生效
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val s = blurScale().coerceAtLeast(0.1f)
                        scaleX = 1f / s
                        scaleY = 1f / s
                        transformOrigin = when (menuGrowth) {
                            MenuGrowth.TopLeft -> TransformOrigin(0f, 0f)
                            MenuGrowth.TopRight -> TransformOrigin(1f, 0f)
                            MenuGrowth.BottomLeft -> TransformOrigin(0f, 1f)
                            MenuGrowth.BottomRight -> TransformOrigin(1f, 1f)
                        }
                    }
                    .hazeBlur(
                        input = hazeSources(hazeState),
                        style = hazeCardStyle(),
                        performanceMode = HazePerformanceMode.Quality,
                    ),
            )
        }
        PopupMenuContent(
            sections = sections,
            // 只淡内容行：白底 + 毛玻璃壳恒为满 alpha，模糊首帧即最终强度
            modifier = Modifier.graphicsLayer { alpha = contentAlpha() },
        )
    }
}

/**
 * 弹出选择菜单的内容（无卡片壳）：勾选行 + 组间可选分割线。
 *
 * - 行：fieldLabel（16sp SemiBold）；勾选行 = primary 色 + 左侧 ic_checkmark，
 *   未勾选行 = onSurface、勾选槽占位保文字对齐；传 [PopupMenuItem.icon] 的图标型菜单
 *   在行首槽画 24dp 图标（槽随之加宽）；置灰行（`enabled = false`）= negative 色、
 *   图标/文案同色、不吃点击；点击走按压反馈（放大 + 提亮）；
 * - 分组：[PopupMenuSection.showDivider] 控制与上一分组之间的 [HorizontalDivider]
 *   （outlineVariant）——单组菜单不传分割线即为设计稿中右两态的无缝形态；
 * - 宽度：收窄到最宽行（IntrinsicSize.Max，CourseContextMenu 同款），各行等宽、
 *   分割线贯通；宿主用 modifier 给固定宽度（width/widthIn/fillMaxWidth）时以其为准；
 * - 收起由调用方与动作一起处理（这里只管渲染）。
 */
@Composable
fun PopupMenuContent(
    sections: List<PopupMenuSection>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.width(IntrinsicSize.Max)) {
        sections.forEachIndexed { index, section ->
            if (index > 0 && section.showDivider) {
                HorizontalDivider(
                    modifier = Modifier.padding(
                        horizontal = DividerHorizontalInset,
                        vertical = DividerVerticalPadding,
                    ),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            section.items.forEach { item -> PopupMenuRow(item) }
        }
    }
}

/** 菜单行：行首槽（勾选标记或图标，未勾选/无图标时占位对齐）+ 文案；
 *  内容色：置灰（negative）> 勾选（primary）> 默认（onSurface），图标与文案同色 */
@Composable
private fun PopupMenuRow(item: PopupMenuItem) {
    val contentColor = when {
        !item.enabled -> MaterialTheme.classppColors.negative
        item.checked -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 行无自有 clip：矩形提亮原位接在行上，卡壳 MenuShape clip（祖先）兜住缩放溢出
            .pressClickable(RectangleShape, enabled = item.enabled, onClick = item.onClick)
            .padding(start = 20.dp, end = RowHorizontalPadding, top = RowVerticalPadding, bottom = RowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            // 图标型菜单的行首槽取图标边长，勾选型维持原槽宽（既有菜单的文字左缘不动）
            Modifier.width(if (item.icon != null) MenuIconSize else CheckIconSize),
            contentAlignment = Alignment.CenterStart,
        ) {
            when {
                item.icon != null -> Icon(
                    painter = painterResource(item.icon),
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(MenuIconSize),
                )
                item.checked -> Icon(
                    painter = painterResource(R.drawable.ic_checkmark),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(CheckIconSize),
                )
            }
        }
        Spacer(Modifier.width(CheckLabelSpacing))
        Text(
            text = item.label,
            style = MaterialTheme.classppTextStyles.fieldLabel,
            color = contentColor,
        )
    }
}
