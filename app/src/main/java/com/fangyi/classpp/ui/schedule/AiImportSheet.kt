package com.fangyi.classpp.ui.schedule

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.ai.AiAuroraAnchor
import com.fangyi.classpp.ui.ai.AiChatNotConfigured
import com.fangyi.classpp.ui.ai.AiThinkingAurora
import com.fangyi.classpp.ui.components.EmptyState
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetInfoBanner
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.components.TopBarHeight
import com.fangyi.classpp.ui.motion.PressShade
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.SettingsCardShape
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.isDarkTheme

/** 舞台高度下限：选图卡 / 图片在上半区垂直居中的定位基准（设计稿量取）。 */
private val AiImportStageHeight = 380.dp

/**
 * 光场图层高度：大于舞台高度，向上下自然溢出（图层本身不裁剪）。下方越过舞台边界后
 * 淡出在分析提示背后，不会被 EmptyState 的起始位置裁断。
 */
private val AiImportAuroraHeight = 600.dp

/** 选图卡与预览图的左右留白：叠加浮层内容的 12dp 页边距后约等于设计稿的 48dp。 */
private val AiImportSidePadding = 36.dp

/** 预览图容器宽高比（设计稿约 3:2）。 */
private const val AiImportPreviewAspectRatio = 3f / 2f

/**
 * 光场在整卡背景层里的纵向起点：让 600dp 光带的中心正对内容舞台（图片）中心。
 * 背景层不裁剪，光带顶部会伸到顶栏渐变遮罩下方——淡入过程连续，不会在顶栏下沿
 * 出现一条硬边（此前背景层自顶栏以下起算时，光带在该边界被截断）。
 */
private val AiImportAuroraTop =
    TopBarHeight + AiImportStageHeight * 0.5f - AiImportAuroraHeight * 0.5f

// 选图卡的内距与间距：镜像 SettingsInfoHeaderCard 的首屏规格（组件本身不为本浮层加参数，
// 这里独立维护；若共享组件规格调整，按需同步）。
private val PickCardTopPadding = 24.dp
private val PickCardBottomPadding = 20.dp
private val PickCardHorizontalPadding = 32.dp
private val PickCardIconSize = 40.dp
private val PickCardIconTitleGap = 8.dp
private val PickCardTitleDescriptionGap = 3.dp

/**
 * 「AI 识别导入」浮层：导入课表浮层的第一张入口卡打开，叠在其上（本浮层在场时导入浮层经
 * covered 缩小后退）。浮层只做展示与交互，识别请求 / 结果 / 临时图片生命周期都由宿主
 * （ScheduleScreen）持有——同 Wakeup 导入「浮层纯 UI、宿主做数据工作」的职责划分。
 *
 * 状态与顶栏（胶囊排布由 [OverlaySheet] 的 confirmAtEnd 规则给出）：
 * - 未配置 AI 服务商：正文复用 AI 对话浮层的 [AiChatNotConfigured]（「先配置 AI 服务商」+
 *   去 AI 设置），右上只有「关闭」；
 * - 未选图：白底选图卡（[AiImportPickCard]，图标 + 标题 + 说明，间距沿用
 *   SettingsInfoHeaderCard 的规格但组件独立），点卡拉起
 *   系统相册（Photo Picker，无需权限）；右上只有「关闭」；
 * - 已选图：预览图替换选图卡（点图可重选），左上「取消」+ 右上「开始」；
 * - 分析中（[analyzing]）：右上「取消」回调 [onStopAnalyze]；图片背后浮出
 *   [AiThinkingAurora] 的 [AiAuroraAnchor.Center] 光场（与 AI 对话浮层同一实现，走
 *   OverlaySheet 的卡片背景层：横向铺满整卡、向下淡出到分析提示背后），下方用
 *   [EmptyState] 显示分析提示；
 * - 识别失败（[errorText]）：下方改为信息错误条；图片与「开始」保留，点开始即可重试，
 *   换图会先回调 [onImageChanged] 清掉旧错误。
 *
 * 分析中禁用下拉关闭（下拉会被拦成回弹），返回键先停止分析、再按才关浮层。
 * 识别成功由宿主弹出导入确认弹窗，本浮层不感知结果。
 *
 * [onOpenSettings] 由宿主接线：先收起本浮层与导入浮层，再直达设置页 AI 子页。
 */
@Composable
internal fun AiImportSheet(
    visible: Boolean,
    onDismissed: () -> Unit,
    onDismiss: () -> Unit,
    configured: Boolean,
    onOpenSettings: () -> Unit,
    // 宿主持有的识别运行态：浮层只读，不发请求
    analyzing: Boolean,
    // 宿主映射后的错误文案；null = 无错误
    errorText: String?,
    // 开始分析：宿主负责图片落盘、发请求与结果处理
    onAnalyze: (Uri) -> Unit,
    // 分析中取消（顶栏取消 / 返回键）；回到已选图态
    onStopAnalyze: () -> Unit,
    // 换图后清掉宿主的旧错误
    onImageChanged: () -> Unit,
    covered: Boolean = false,
) {
    // 选中的图片以 Uri 字符串保存：配置变更后仍能还原（Photo Picker 的读授权随进程存活）
    var imageUriString by rememberSaveable { mutableStateOf<String?>(null) }
    val imageUri = remember(imageUriString) { imageUriString?.let(Uri::parse) }

    // 系统相册单图选择：ImageOnly 只出图片，视频等文件类型不可选；不申请任何权限
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            imageUriString = uri.toString()
            // 换图即作废旧错误；分析态由宿主在开始/停止时维护
            onImageChanged()
        }
    }
    val launchPicker: () -> Unit = {
        picker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    // 返回键：分析中先停止分析；否则关浮层（后注册先收到，优先于导入浮层）
    BackHandler(enabled = true) {
        if (analyzing) onStopAnalyze() else onDismiss()
    }

    val canStart = configured && imageUri != null && !analyzing
    // 动作胶囊文案：已选图时它排在左侧，与「开始」成对（取消导入）；分析中它回到右侧
    // 改为「取消」（停止分析）；未选图 / 未配置时只有它一颗，语义是关闭浮层
    val actionLabel = if (canStart || analyzing) {
        R.string.settings_cancel
    } else {
        R.string.switcher_close
    }

    OverlaySheet(
        title = stringResource(R.string.import_ai_sheet_title),
        // 选好图片后才出现「开始」（确认在右、取消在左）；分析态收起确认胶囊，
        // 动作胶囊自动回到右侧并改写成「取消」
        confirmLabel = if (canStart) stringResource(R.string.import_ai_start) else null,
        confirmAtEnd = true,
        onConfirm = { imageUri?.let(onAnalyze) },
        rightAction = SheetTopAction(
            label = stringResource(actionLabel),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = { if (analyzing) onStopAnalyze() else onDismiss() },
        ),
        onDismiss = onDismiss,
        visible = visible,
        covered = covered,
        // 分析中拦下拉：下拉只回弹，退出分析走右上「取消」，避免半拖出位置卡住
        dragDismissEnabled = !analyzing,
        onDismissed = onDismissed,
        // 无输入框，键盘不必让位
        imeBehavior = SheetImeBehavior.IgnoreIme,
        // 光场走整卡背景层：横向铺满卡片（不吃内容列 12dp 页边距），纵向由 AiImportAuroraTop
        // 对齐图片中心；600dp 光带向上下溢出，顶部伸进顶栏渐变遮罩下方连续淡入，下方越过
        // 舞台淡出在分析提示背后——两条边界都不再裁断光场
        backgroundContent = {
            if (configured) {
                AiThinkingAurora(
                    // 只在分析中亮起：进场从中心轻微浮入，取消后冻结相位并淡出
                    active = analyzing,
                    anchor = AiAuroraAnchor.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .requiredHeight(AiImportAuroraHeight)
                        .align(Alignment.TopCenter)
                        .offset(y = AiImportAuroraTop),
                )
            }
        },
    ) {
        if (!configured) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = AiImportStageHeight),
                contentAlignment = Alignment.Center,
            ) {
                AiChatNotConfigured(onOpenSettings = onOpenSettings)
            }
        } else {
            // 舞台：选图卡 / 预览图在同一条中线上，与背景层的光场中心对齐
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = AiImportStageHeight),
                contentAlignment = Alignment.Center,
            ) {
                if (imageUri == null) {
                    AiImportPickCard(
                        onClick = launchPicker,
                        modifier = Modifier.padding(horizontal = AiImportSidePadding),
                    )
                } else {
                    AsyncImage(
                        model = imageUri,
                        contentDescription = stringResource(R.string.cd_ai_import_image),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .padding(horizontal = AiImportSidePadding)
                            .fillMaxWidth()
                            .aspectRatio(AiImportPreviewAspectRatio)
                            // 分析中不再接受换图；点图重选只在「已选图」态可用
                            .then(
                                if (analyzing) {
                                    Modifier
                                } else {
                                    Modifier.pressClickable(
                                        shape = SettingsCardShape,
                                        onClick = launchPicker,
                                    )
                                },
                            )
                            .clip(SettingsCardShape)
                            .background(MaterialTheme.colorScheme.surface),
                    )
                }
            }
            if (analyzing) {
                // 分析提示：与 AI 对话浮层同一个空态组件；其上留白由 EmptyState 自带的
                // 48dp 与舞台下沿共同构成，与设计稿图片→提示的间距一致
                EmptyState(
                    icon = R.drawable.ic_ai,
                    title = stringResource(R.string.import_ai_analyzing_title),
                    description = stringResource(R.string.import_ai_analyzing_desc),
                )
            } else if (errorText != null) {
                // 识别失败：错误条替代分析提示；图片与「开始」都保留，点开始重试
                SheetInfoBanner(text = errorText, isError = true)
            }
        }
    }
}

/**
 * 选图卡：白底 [SettingsCardShape] + 纯色主题色图标/标题 + 说明，整卡可点拉起相册。
 *
 * 间距与排版沿用 `SettingsInfoHeaderCard` 的首屏规格（上下 24/20、左右 32、图标 40dp、
 * 图标↔标题 8dp、标题↔说明 3dp），但不改动那个共享组件：本卡只需要纯色图标与可点语义，
 * 且文案固定属于本浮层。
 */
@Composable
private fun AiImportPickCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pressShade = if (MaterialTheme.isDarkTheme) PressShade.Brighten else PressShade.Darken
    Box(
        modifier = modifier
            .fillMaxWidth()
            .pressClickable(shape = SettingsCardShape, shade = pressShade, onClick = onClick)
            .clip(SettingsCardShape)
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(
                start = PickCardHorizontalPadding,
                top = PickCardTopPadding,
                end = PickCardHorizontalPadding,
                bottom = PickCardBottomPadding,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_image_add),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(PickCardIconSize),
            )
            Spacer(Modifier.height(PickCardIconTitleGap))
            Text(
                text = stringResource(R.string.import_ai_pick_title),
                style = MaterialTheme.classppTextStyles.fieldLabel,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(PickCardTitleDescriptionGap))
            Text(
                text = stringResource(R.string.import_ai_pick_desc),
                style = ClassppTextTokens.settingsDescription.copy(
                    color = MaterialTheme.classppColors.secondaryText,
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
