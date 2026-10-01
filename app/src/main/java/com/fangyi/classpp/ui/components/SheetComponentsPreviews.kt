package com.fangyi.classpp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.CancelRed

/** 设计稿四态与各卡片的快速预览（Android Studio 中直接查看）。 */
@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390)
@Composable
private fun SheetTextFieldPreview() = ClassppTheme {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SheetTextField(
            label = "课程名",
            value = "",
            onValueChange = {},
            placeholder = "微积分 I-2",
        )
        SheetTextField(
            label = "课程名",
            value = "微积分 I-2",
            onValueChange = {},
        )
        SheetTextField(
            label = "课程名",
            value = "",
            onValueChange = {},
            isError = true,
        )
        SheetTextField(
            label = "教师",
            value = "XX 老师",
            onValueChange = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390)
@Composable
private fun RowChoiceCardPreview() = ClassppTheme {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RowChoiceCard(
            options = listOf("全选", "单周", "双周"),
            selectedIndex = 0,
            onSelect = {},
        )
        RowChoiceCard(
            options = listOf("全选", "单周", "双周"),
            selectedIndex = null,
            onSelect = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390)
@Composable
private fun PopupSelectCardPreview() = ClassppTheme {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PopupSelectCard(
            title = "结束节次",
            valueText = "2",
            items = (1..5).map { it to "第 $it 节" },
            selectedId = 2,
            onPick = {},
        )
        androidx.compose.material3.Text(
            text = "课程占 1 - 2 节，跨 2 节",
            fontSize = 14.sp,
            color = com.fangyi.classpp.ui.theme.Primary,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390)
@Composable
private fun ColorSwatchCardPreview() = ClassppTheme {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ColorSwatchCard(
            colors = listOf(
                Color(0xFFB1C4EE),
                Color(0xFF81D689),
                Color(0xFF98D651),
                Color(0xFFE3C160),
                Color(0xFFEBB8A7),
                Color(0xFFCBBCF0),
                Color(0xFF7CD3D0),
                Color(0xFFEDB5C9),
            ),
            selectedIndex = 0,
            onSelect = {},
        )
        // 顶栏胶囊按钮
        SheetPillButton(
            label = "确认",
            icon = R.drawable.ic_checkmark_circle,
            containerColor = androidx.compose.ui.graphics.Color(0xFF006FEF),
            contentColor = Color.White,
            onClick = {},
        )
        SheetPillButton(
            label = "取消",
            icon = R.drawable.ic_dismiss_circle,
            containerColor = Color.White,
            contentColor = Color(0xFF212121),
            iconAtEnd = true,
            onClick = {},
        )
        SheetPillButton(
            label = "删除",
            icon = R.drawable.ic_delete_dismiss,
            containerColor = CancelRed,
            contentColor = Color.White,
            iconAtEnd = true,
            onClick = {},
        )
    }
}
