package com.fangyi.classpp.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetTopAction

/** 第三方项目致谢条目：项目名 + app 内用途文案 + 仓库链接。 */
private data class Acknowledgement(
    val name: String,
    @StringRes val usageRes: Int,
    val url: String,
)

/** 实际使用的第三方项目：按视觉 / 网络数据 / 动效与资源分组排列，便于阅读。 */
private val Acknowledgements = listOf(
    Acknowledgement(
        name = "Haze",
        usageRes = R.string.about_thanks_haze,
        url = "https://github.com/chrisbanes/haze",
    ),
    Acknowledgement(
        name = "Kyant Shapes",
        usageRes = R.string.about_thanks_shapes,
        url = "https://github.com/Kyant0/Shapes",
    ),
    Acknowledgement(
        name = "AndroidLiquidGlass",
        usageRes = R.string.about_thanks_liquid_glass,
        url = "https://github.com/Kyant0/AndroidLiquidGlass",
    ),
    Acknowledgement(
        name = "Coil",
        usageRes = R.string.about_thanks_coil,
        url = "https://github.com/coil-kt/coil",
    ),
    Acknowledgement(
        name = "Markdown Renderer",
        usageRes = R.string.about_thanks_markdown,
        url = "https://github.com/mikepenz/multiplatform-markdown-renderer",
    ),
    Acknowledgement(
        name = "OkHttp",
        usageRes = R.string.about_thanks_okhttp,
        url = "https://github.com/square/okhttp",
    ),
    Acknowledgement(
        name = "Kotlinx Serialization",
        usageRes = R.string.about_thanks_serialization,
        url = "https://github.com/Kotlin/kotlinx.serialization",
    ),
    Acknowledgement(
        name = "Cresto",
        usageRes = R.string.about_thanks_cresto,
        url = "https://github.com/Nevodev/Cresto",
    ),
    Acknowledgement(
        name = "Wakeup Script",
        usageRes = R.string.about_thanks_wakeup,
        url = "https://gitee.com/XingHeYuZhuan-gh/shiguang_warehouse",
    ),
    Acknowledgement(
        name = "Source Han Serif",
        usageRes = R.string.about_thanks_source_han_serif,
        url = "https://github.com/adobe-fonts/source-han-serif",
    ),
)

/**
 * 关于页「致谢」浮层：列出 app 实际使用的第三方项目。
 *
 * 每行标题为项目名，描述为该库/资源在本 app 内的具体用途；点击整行通过系统浏览器打开
 * 对应仓库，行尾箭头由 [SettingsCardItem.Nav] 自带。链接打开失败沿用项目既有做法
 * （[LocalUriHandler] + runCatching 静默降级）。
 *
 * 展示型浮层：无输入框，返回键 / 关闭胶囊 / 拖拽 / 点遮罩均走既有两段式关闭。
 */
@Composable
internal fun AboutThanksSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
) {
    // 返回键关浮层（与其余浮层一致的两段式：visible 复位后播退场，播完才卸载）
    BackHandler(enabled = visible) {
        onDismiss()
    }

    val uriHandler = LocalUriHandler.current
    OverlaySheet(
        title = stringResource(R.string.about_thanks),
        rightAction = SheetTopAction(
            label = stringResource(R.string.switcher_close),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = onDismiss,
        ),
        onDismiss = onDismiss,
        visible = visible,
        onDismissed = onDismissed,
        // 纯展示列表，无输入框，键盘不必让位
        imeBehavior = SheetImeBehavior.IgnoreIme,
    ) {
        CardSection {
            SettingsCard(
                items = Acknowledgements.map { item ->
                    SettingsCardItem.Nav(
                        label = item.name,
                        description = stringResource(item.usageRes),
                        onClick = {
                            // 与 ChatMarkdown 的链接处理一致：打开失败静默降级
                            runCatching { uriHandler.openUri(item.url) }
                        },
                    )
                },
            )
        }
    }
}
