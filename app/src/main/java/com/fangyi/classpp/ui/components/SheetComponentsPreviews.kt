package com.fangyi.classpp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.ui.theme.ClassppTheme

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
        // 超宽自动换行：值区宽度固定，行卡长高、上下内距不变
        SheetTextField(
            label = "课程名",
            value = "微积分 I-2微积分 I-2微积分 I-2微积分 I-2微积分 I-2",
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
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390)
@Composable
private fun SheetInfoCardPreview() = ClassppTheme {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SheetInfoCard(
            entries = listOf(
                SheetInfoEntry("教师", "XX 老师"),
                SheetInfoEntry("上课地点", "学武楼 C201"),
            ),
        )
        // 值超宽自动换行：卡片长高、上下边距不变、label 垂直居中
        SheetInfoCard(
            entries = listOf(
                SheetInfoEntry("教师", "XX 老师"),
                SheetInfoEntry("上课地点", "学武楼学武楼学武楼学武楼学武楼学武楼学武楼学武楼"),
            ),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390)
@Composable
private fun SheetTextAreaPreview() = ClassppTheme {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SheetTextArea(
            value = "",
            onValueChange = {},
            placeholder = "点击输入添加备注",
        )
        SheetTextArea(
            value = "",
            onValueChange = {},
            isError = true,
        )
        SheetTextArea(
            value = "这是备注这是备注这是备注这是备注这是备注这是备注这是备注这是备注" +
                "这是备注这是备注这是备注这是备注这是备注这是备注这是备注这是备注这是备注" +
                "这是备注这是备注这是备注",
            onValueChange = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390)
@Composable
private fun ClassppSwitchPreview() = ClassppTheme {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ClassppSwitch(
            checked = true,
            onCheckedChange = {},
        )
        ClassppSwitch(
            checked = false,
            onCheckedChange = {},
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
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            onClick = {},
        )
        SheetPillButton(
            label = "取消",
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            iconAtEnd = true,
            onClick = {},
        )
        SheetPillButton(
            label = "删除",
            icon = R.drawable.ic_delete_dismiss,
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
            iconAtEnd = true,
            onClick = {},
        )
    }
}
