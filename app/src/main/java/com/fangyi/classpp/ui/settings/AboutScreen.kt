package com.fangyi.classpp.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.appVersionName
import com.fangyi.classpp.ui.components.AccountAvatarSize
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.Chevron
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SettingsInfoHeaderCard
import com.fangyi.classpp.ui.components.SheetBottomSlack
import com.fangyi.classpp.ui.motion.LocalPageOverlayActive
import com.fangyi.classpp.ui.motion.LocalPageOverlayPrewarm
import com.fangyi.classpp.ui.motion.rememberOffsetOverscrollEffect
import com.fangyi.classpp.ui.theme.AppIconShape
import com.fangyi.classpp.ui.theme.isDarkTheme
import com.fangyi.classpp.ui.theme.themeAccent
import dev.chrisbanes.haze.rememberHazeState

/** 关于页头部应用图标边长：比常规信息头部卡图标（40dp）更大，突出品牌 */
private val AboutAppIconSize = 64.dp

/** 应用图标在槽位内再放大，让品牌图标铺满连续曲率轮廓 */
private const val AboutAppIconScale = 1.7f

/** 作者主页：点击作者卡片经系统浏览器打开 */
private const val AboutAuthorUrl = "https://github.com/ChengFY0710"

/**
 * 关于子页：应用 Logo / 名称与副标题、作者、当前版本、更新日志、致谢，以及
 * 「用前须知」分组（免责声明 / 开源协议 / 隐私与政策）。
 *
 * 「用前须知」三行分别打开 [AboutDocumentSheet] 文档浮层，致谢行打开 [AboutThanksSheet]
 * 第三方项目列表；作者卡片经系统浏览器打开作者主页，检查更新 / 更新日志仍为占位
 * （两者同卡同款导航行），版本号经 [appVersionName] 运行时读取 Gradle `versionName`，
 * 不在界面层写死，也不依赖 BuildConfig 生成类。
 *
 * 页面骨架与其余设置子页一致：Scaffold 全宽出血 + [SettingsScrollContent] 统一页边距 /
 * 分组节奏 + [SettingsTopBar] 磨砂顶栏（Haze、玻璃返回钮、转场冻结、标题渐显）。
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    settingsTransforming: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val overlayActive = LocalPageOverlayActive.current
    Scaffold(
        modifier = modifier,
        // 统一不避让横向 inset：内容全宽出血（左右顶到屏幕边缘）；纵向由内容与顶栏自理
        contentWindowInsets = WindowInsets(0),
    ) { contentPadding ->
        // contentWindowInsets = WindowInsets(0)：padding 恒为 0，显式消费以满足 Scaffold 内距约定
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            val hazeState = rememberHazeState()
            val glassBackdrop = rememberSettingsGlassBackdrop()
            var topBarHeight by remember { mutableStateOf(0.dp) }
            val density = LocalDensity.current
            val scrollState = rememberScrollState()
            // 标题渐显要把过滚动算进内容位移（可滚页面读边缘橡皮筋，不足一屏读整页橡皮筋）
            val overscrollEffect = rememberOffsetOverscrollEffect()
            // 预热帧也要挂 Haze；隐藏态不挂、也不录制（同其余子页）
            val hazeActive = overlayActive || LocalPageOverlayPrewarm.current
            val darkTheme = MaterialTheme.isDarkTheme
            val themeAccent = MaterialTheme.themeAccent
            val freezeEpoch = LocalSettingsFreezeEpoch.current
            val context = LocalContext.current
            // 版本名运行时读取：Gradle versionName 是唯一来源，不依赖 BuildConfig 生成类
            val versionName = remember(context) { appVersionName(context) }
            val freezeKey: () -> Any = remember(darkTheme, themeAccent, scrollState, freezeEpoch) {
                {
                    SettingsTopBarFreezeKey(
                        page = SettingsPage.About,
                        darkTheme = darkTheme,
                        topBarHeight = topBarHeight,
                        scroll = scrollState.value,
                        epoch = freezeEpoch,
                        inputs = themeAccent,
                    )
                }
            }
            val navBarPadding = WindowInsets.navigationBars
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues()
                .calculateBottomPadding()
            // 占位回调：检查更新 / 更新日志后续再接目标；用前须知走文档浮层，致谢走列表浮层
            val placeholder = remember { {} }
            val uriHandler = LocalUriHandler.current
            // 作者卡片：与致谢列表一致，经系统浏览器打开作者主页，失败静默降级
            val openAuthorProfile: () -> Unit = {
                runCatching { uriHandler.openUri(AboutAuthorUrl) }
            }
            // 文档浮层两段式开关：visible 复位后播退场，mounted 在 onDismissed 才清空
            var documentVisible by remember { mutableStateOf<AboutDocument?>(null) }
            var documentMounted by remember { mutableStateOf<AboutDocument?>(null) }
            val openDocument: (AboutDocument) -> Unit = { document ->
                documentVisible = document
                documentMounted = document
            }
            // 致谢浮层同样两段式：展示期间保持 mounted，退场播完才卸载
            var thanksVisible by remember { mutableStateOf(false) }
            var thanksMounted by remember { mutableStateOf(false) }

            SettingsScrollContent(
                scrollState = scrollState,
                overscrollEffect = overscrollEffect,
                hazeState = hazeState,
                glassBackdrop = glassBackdrop,
                topPadding = topBarHeight + TopBarContentGap,
                bottomPadding = navBarPadding + SheetBottomSlack,
            ) {
                // 顶部无题分组：应用信息 + 作者 + 版本（检查更新 / 更新日志）+ 致谢，组内卡间距走默认
                CardSection {
                    SettingsInfoHeaderCard(
                        // 关于页应用 Logo：设计稿导出的位图资源
                        icon = R.drawable.app_logo,
                        title = stringResource(R.string.about_app_title),
                        description = stringResource(R.string.about_app_description),
                        // 应用图标保留原色，不做主题色渐变
                        tintIcon = false,
                        // 品牌图标比常规头部卡图标更大，并按连续曲率圆角裁出圆滑轮廓
                        iconSize = AboutAppIconSize,
                        iconShape = AppIconShape,
                        iconScale = AboutAppIconScale,
                    )
                    SettingsCard(
                        items = listOf(
                            SettingsCardItem.Custom(
                                label = stringResource(R.string.about_author_name),
                                description = stringResource(R.string.about_author_role),
                                leading = { AuthorAvatar() },
                                trailing = { Chevron() },
                                onClick = openAuthorProfile,
                            ),
                        ),
                    )
                    SettingsCard(
                        items = listOf(
                            SettingsCardItem.Nav(
                                label = stringResource(R.string.about_check_update),
                                // 版本号来自 Gradle versionName，随构建产物变化
                                description = stringResource(
                                    R.string.about_current_version,
                                    versionName,
                                ),
                                onClick = placeholder,
                            ),
                            // 与检查更新同卡同款行型：右侧统一主题色箭头，日志内容后续再接
                            SettingsCardItem.Nav(
                                label = stringResource(R.string.about_changelog),
                                onClick = placeholder,
                            ),
                        ),
                    )
                    SettingsCard(
                        items = listOf(
                            SettingsCardItem.Nav(
                                label = stringResource(R.string.about_thanks),
                                onClick = {
                                    thanksVisible = true
                                    thanksMounted = true
                                },
                            ),
                        ),
                    )
                }

                CardSection(title = stringResource(R.string.about_section_before_use)) {
                    SettingsCard(
                        items = listOf(
                            SettingsCardItem.Nav(
                                label = stringResource(R.string.about_disclaimer),
                                onClick = { openDocument(AboutDocument.Disclaimer) },
                            ),
                            SettingsCardItem.Nav(
                                label = stringResource(R.string.about_open_source_license),
                                onClick = { openDocument(AboutDocument.OpenSourceLicense) },
                            ),
                            SettingsCardItem.Nav(
                                label = stringResource(R.string.about_privacy_policy),
                                onClick = { openDocument(AboutDocument.PrivacyPolicy) },
                            ),
                        ),
                    )
                }
            }

            SettingsTopBar(
                title = stringResource(R.string.settings_about),
                onBack = onBack,
                // 只在活跃期挂 Haze 效果，避免对屏外页面做源捕获/模糊
                hazeState = if (hazeActive) hazeState else null,
                buttonBackdrop = glassBackdrop,
                freezeKey = freezeKey,
                recordWhen = { hazeActive },
                isTransforming = settingsTransforming,
                titleAlpha = rememberSettingsTitleAlpha(scrollState, overscrollEffect),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .onGloballyPositioned { coords ->
                        // 顶栏实测高度 = 内容首屏内缩量（px → dp）
                        topBarHeight = with(density) { coords.size.height.toFloat().toDp() }
                    },
            )

            // 文档浮层放在顶栏之后：画在顶栏之上，BackHandler 也后注册（与其余子页浮层一致）
            documentMounted?.let { document ->
                AboutDocumentSheet(
                    document = document,
                    visible = documentVisible == document,
                    onDismiss = { documentVisible = null },
                    onDismissed = { documentMounted = null },
                )
            }

            // 致谢列表浮层同样挂在顶栏之后；与文档浮层互斥（入口被浮层遮住）
            if (thanksMounted) {
                AboutThanksSheet(
                    visible = thanksVisible,
                    onDismiss = { thanksVisible = false },
                    onDismissed = { thanksMounted = false },
                )
            }
        }
    }
}

/** 作者头像圆：与账号与同步页同尺寸（52dp），本地图片裁圆后铺满 */
@Composable
private fun AuthorAvatar() {
    Image(
        painter = painterResource(R.drawable.about_avatar),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(AccountAvatarSize)
            .clip(CircleShape),
    )
}
