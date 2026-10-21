package com.fangyi.classpp.ui.day

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetTopAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 网格内部规格（对照设计稿）：横纵间距 12dp、单元约 3:5、圆角 16dp；
// 自适应列数的最小单元宽——常见手机（≈390-430dp 宽）出 3 列
private val GridSpacing = 12.dp
private val MinCellWidth = 110.dp
private val CellCorner = 16.dp
private const val CellAspectRatio = 3f / 5f

// 一次最多导入张数（PickMultipleVisualMedia 要求 ≥ 2）
private const val MaxPickCount = 20

/**
 * 日程页「选择图片」浮层：网格展示已导入的图片，点一张即设为日程页背景并关闭。
 *
 * 顶栏两颗胶囊走 OverlaySheet 自带规格：左上「导入」= 确认胶囊（同「新建」按钮的
 * 蓝底加号），唤起系统相册（Photo Picker，[ActivityResultContracts.PickVisualMedia.ImageOnly]
 * 从源头限制只能选图片，一次至多 [MaxPickCount] 张）；右上「关闭」= 动作胶囊（白底 ✕ 圆）。
 *
 * 相册 URI 是临时授权、跨重启失效，所以选择结果先拷贝进应用内部存储（[DayImageStore]，
 * IO 线程解码转正压缩）再入列——浮层只管选择流程，状态与落盘由日程页持有。
 *
 * 网格不写死列数：按内容区可用宽除以 [MinCellWidth] 动态求列数，单元宽扣除间隙均分
 * （FlowRow 而非 LazyVerticalGrid——OverlaySheet 的滚动内容列自带 verticalScroll，
 * 懒网格放进去会被无限高度约束测量直接崩溃）。
 *
 * 两段式关闭同既有浮层：[onDismiss]（关闭按钮 / 遮罩 / 下拉 / 点图选中）只翻转 visible，
 * 出场动画播完回调 [onDismissed]，调用方此刻才把浮层移出组合。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DayImageSheet(
    visible: Boolean,
    images: List<String>,
    onImported: (List<String>) -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(enabled = visible, onBack = onDismiss)

    // 系统相册（Photo Picker）：ImageOnly 只出图片，视频等文件类型不可选
    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MaxPickCount),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val imported = uris.mapNotNull { DayImageStore.importImage(context, it) }
            withContext(Dispatchers.Main) {
                if (imported.isNotEmpty()) onImported(imported)
            }
        }
    }

    OverlaySheet(
        title = stringResource(R.string.day_image_sheet_title),
        confirmLabel = stringResource(R.string.day_image_import),
        confirmIcon = R.drawable.ic_add_circle,
        onConfirm = {
            pickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        rightAction = SheetTopAction(
            label = stringResource(R.string.day_image_close),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = onDismiss,
        ),
        onDismiss = onDismiss,
        visible = visible,
        onDismissed = onDismissed,
        // 纯网格浮层没有输入框，键盘不必让位（同待办分组浮层）
        imeBehavior = SheetImeBehavior.IgnoreIme,
        modifier = modifier,
    ) {
        if (images.isEmpty()) {
            DayImageEmptyHint(modifier = Modifier.fillMaxWidth())
        } else {
            DayImageGrid(
                images = images,
                onSelect = onSelect,
            )
        }
    }
}

/** 图片网格：按可用宽动态求列数，单元宽扣除 12dp 间隙均分，横纵间距一致 */
@Composable
private fun DayImageGrid(
    images: List<String>,
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    // 内容区自带页边距（OverlaySheet 的 PageHorizontalSpacing），这里只管网格内部规格
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = (maxWidth / MinCellWidth).toInt().coerceAtLeast(1)
        val cellWidth = (maxWidth - GridSpacing * (columns - 1)) / columns
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(GridSpacing),
            verticalArrangement = Arrangement.spacedBy(GridSpacing),
        ) {
            images.forEach { name ->
                AsyncImage(
                    model = DayImageStore.imageFile(context, name),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(cellWidth)
                        .aspectRatio(CellAspectRatio)
                        .clip(RoundedCornerShape(CellCorner))
                        .clickable { onSelect(name) },
                )
            }
        }
    }
}

/** 空列表态：网格没有内容时给一条引导文案，否则浮层只剩顶栏一片空白 */
@Composable
private fun DayImageEmptyHint(modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(top = 140.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            text = stringResource(R.string.day_image_empty_hint),
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
