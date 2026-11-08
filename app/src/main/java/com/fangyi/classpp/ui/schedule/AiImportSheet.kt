package com.fangyi.classpp.ui.schedule

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.ai.AiAuroraAnchor
import com.fangyi.classpp.ui.ai.AiChatNotConfigured
import com.fangyi.classpp.ui.ai.AiThinkingAurora
import com.fangyi.classpp.ui.components.EmptyState
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SettingsInfoHeaderCard
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.theme.SettingsCardShape

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
 * 「AI 识别导入」浮层：导入课表浮层的第一张入口卡打开，叠在其上（本浮层在场时导入浮层经
 * covered 缩小后退）。本期只做 UI：选图、状态切换与光场动画真实可用，识别请求尚未接入。
 *
 * 四个状态与顶栏（胶囊排布由 [OverlaySheet] 的 confirmAtEnd 规则给出）：
 * - 未配置 AI 服务商：正文复用 AI 对话浮层的 [AiChatNotConfigured]（「先配置 AI 服务商」+
 *   去 AI 设置），右上只有「关闭」；
 * - 未选图：白底选图卡（图标 + 标题 + 说明，间距同 [SettingsInfoHeaderCard]），点卡拉起
 *   系统相册（Photo Picker，无需权限）；右上只有「关闭」；
 * - 已选图：预览图替换选图卡（点图可重选），左上「取消」+ 右上「开始」；
 * - 分析中：右上「取消」停止分析并回到已选图态；图片背后浮出 [AiThinkingAurora] 的
 *   [AiAuroraAnchor.Center] 光场（与 AI 对话浮层同一实现，走 OverlaySheet 的卡片背景层：
 *   横向铺满整卡、向下淡出到分析提示背后），下方用 [EmptyState] 显示分析提示。
 *   分析态一直保持到用户取消，本版不产出结果。
 *
 * 分析中禁用下拉关闭（下拉会被拦成回弹），返回键先停止分析、再按才关浮层。
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
    covered: Boolean = false,
) {
    // 选中的图片以 Uri 字符串保存：配置变更后仍能还原（Photo Picker 的读授权随进程存活）
    var imageUriString by rememberSaveable { mutableStateOf<String?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    val imageUri = remember(imageUriString) { imageUriString?.let(Uri::parse) }

    // 系统相册单图选择：ImageOnly 只出图片，视频等文件类型不可选；不申请任何权限
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            imageUriString = uri.toString()
            // 换图即退出分析态，重新走「已选图」编排
            analyzing = false
        }
    }
    val launchPicker: () -> Unit = {
        picker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    // 返回键：分析中先停止分析；否则关浮层（后注册先收到，优先于导入浮层）
    BackHandler(enabled = true) {
        if (analyzing) analyzing = false else onDismiss()
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
        onConfirm = { analyzing = true },
        rightAction = SheetTopAction(
            label = stringResource(actionLabel),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = { if (analyzing) analyzing = false else onDismiss() },
        ),
        onDismiss = onDismiss,
        visible = visible,
        covered = covered,
        // 分析中拦下拉：下拉只回弹，退出分析走右上「取消」，避免半拖出位置卡住
        dragDismissEnabled = !analyzing,
        onDismissed = onDismissed,
        // 无输入框，键盘不必让位
        imeBehavior = SheetImeBehavior.IgnoreIme,
        // 光场走卡片背景层：横向铺满整张卡片（不受内容列 12dp 页边距限制）；图层高度
        // 大于舞台并向上下溢出，下方淡出在分析提示背后，不被 EmptyState 裁断。
        // 背景层从顶栏以下开始，因此这个舞台高度的 Box 正好对齐内容里的图片区
        backgroundContent = {
            if (configured) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(AiImportStageHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    AiThinkingAurora(
                        // 只在分析中亮起：进场从中心轻微浮入，取消后冻结相位并淡出
                        active = analyzing,
                        anchor = AiAuroraAnchor.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .requiredHeight(AiImportAuroraHeight),
                    )
                }
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
                    SettingsInfoHeaderCard(
                        icon = R.drawable.ic_image_add,
                        title = stringResource(R.string.import_ai_pick_title),
                        description = stringResource(R.string.import_ai_pick_desc),
                        titleColor = MaterialTheme.colorScheme.primary,
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
            }
        }
    }
}
