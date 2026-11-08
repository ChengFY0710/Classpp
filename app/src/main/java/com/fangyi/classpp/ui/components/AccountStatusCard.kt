package com.fangyi.classpp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.fangyi.classpp.R
import com.fangyi.classpp.data.sync.AvatarImage
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import java.util.GregorianCalendar

/**
 * 已连接账户的状态快照：账号状态卡（[AccountStatusCard]）的数据来源。调用方只在已连接
 * 时构造（未连接传 null，不渲染该卡）。
 */
internal data class AccountStatusUi(
    val accountName: String,
    val lastSyncAtMillis: Long,
    val avatarImage: AvatarImage?,
)

/**
 * 已连接账户的状态卡（账号与同步页与教务导入浮层共用）：标题直接是账户名（不再加
 * 「已连接：」前缀，姓名缺省时退回「已连接」单字），描述为上次同步时刻，尾部头像圆。
 * 未连接时调用方不渲染本卡，头像圆随之不出现。
 */
@Composable
internal fun AccountStatusCard(
    account: AccountStatusUi,
    modifier: Modifier = Modifier,
) {
    SettingsCard(
        items = listOf(
            SettingsCardItem.Custom(
                label = account.accountName.ifBlank {
                    stringResource(R.string.sync_connected_label)
                },
                description = if (account.lastSyncAtMillis > 0L) {
                    stringResource(
                        R.string.sync_last_sync_at,
                        formatSyncTime(account.lastSyncAtMillis),
                    )
                } else {
                    stringResource(R.string.sync_last_sync_never)
                },
                // 头像圆放尾部槽、与文字块留 12dp（对齐原先左侧头像的行卡图标↔文本间距）
                trailing = {
                    Spacer(Modifier.width(12.dp))
                    AccountAvatar(
                        name = account.accountName,
                        image = account.avatarImage,
                    )
                },
            ),
        ),
        modifier = modifier,
    )
}

/**
 * 账户头像圆：[AccountAvatarSize] 圆底取 `primaryContainer` 蓝染，圆内先铺账户名首字
 * （`primary`，20sp SemiBold），有缓存头像时再用 [AsyncImage] 盖满整圆——图片加载中或
 * 加载失败都自然露出首字，不需要额外的占位/错误态。深浅模式随主题走。
 *
 * 换头像时文件路径不变（`avatar.img` 就地覆盖），所以用 [image] 的版本号做 `key`：
 * 版本变了整块重建，Coil 才会丢掉旧结果重新解码。
 */
@Composable
private fun AccountAvatar(name: String, image: AvatarImage?) {
    Box(
        modifier = Modifier
            .size(AccountAvatarSize)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val initial = name.trim().firstOrNull()
        if (initial != null) {
            Text(
                text = initial.toString(),
                style = ClassppTextTokens.avatarInitial,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (image != null) {
            key(image.version) {
                AsyncImage(
                    model = image.file,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}

/** 头像圆直径：52dp，行卡内与文字块垂直居中 */
private val AccountAvatarSize = 52.dp

/** 同步时刻展示（设备本地时区的 yyyy-MM-dd HH:mm；数字形态两种语言通用） */
private fun formatSyncTime(millis: Long): String {
    val cal = GregorianCalendar().apply { timeInMillis = millis }
    return "%d/%d/%d %d:%02d".format(
        cal.get(GregorianCalendar.YEAR),
        cal.get(GregorianCalendar.MONTH) + 1,
        cal.get(GregorianCalendar.DAY_OF_MONTH),
        cal.get(GregorianCalendar.HOUR_OF_DAY),
        cal.get(GregorianCalendar.MINUTE),
    )
}
