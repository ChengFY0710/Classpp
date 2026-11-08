package com.fangyi.classpp.ui.schools

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.fangyi.classpp.data.sync.SchoolText

/**
 * 学校文案的 Compose 解析入口：资源文案走 `stringResource`（随系统语言），
 * 远端内联文案走本地语言回退（与资源回退口径一致，见 [SchoolText] KDoc）。
 */
@Composable
fun schoolText(text: SchoolText): String = when (text) {
    is SchoolText.Res -> stringResource(text.id)
    is SchoolText.Inline -> text.resolve(LocalContext.current.resources.configuration.locales[0].language)
}
