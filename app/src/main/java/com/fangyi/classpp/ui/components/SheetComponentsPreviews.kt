package com.fangyi.classpp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.TodoUrgency
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
private fun TimeRangeChoicePreview() = ClassppTheme {
    // 「无 / 全天 / 时段」整卡可交互：选「时段」展开滑块（RowChoiceCard 展开插槽的实际用法）；
    // 下方两张静态卡对照设计稿：常规间距 8:00–18:00、贴靠态 17:59–18:00
    var choice by remember { mutableStateOf(2) }
    var range by remember { mutableStateOf(780 to 1260) } // 13:00–21:00
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RowChoiceCard(
            options = listOf("无", "全天", "时段"),
            selectedIndex = choice,
            onSelect = { choice = it },
            expandContent = if (choice == 2) {
                {
                    TimeRangeSlider(
                        startMinutes = range.first,
                        endMinutes = range.second,
                        onRangeChange = { start, end -> range = start to end },
                    )
                }
            } else {
                null
            },
        )
        SheetCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(6.dp)) {
            TimeRangeSlider(
                startMinutes = 480,
                endMinutes = 1080,
                onRangeChange = { _, _ -> },
                modifier = Modifier.weight(1f),
            )
        }
        SheetCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(6.dp)) {
            TimeRangeSlider(
                startMinutes = 1079,
                endMinutes = 1080,
                onRangeChange = { _, _ -> },
                modifier = Modifier.weight(1f),
            )
        }
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
private fun ClassppSliderPreview() = ClassppTheme {
    // 受控组件：预览自持状态，可直接拖动
    var middleDefault by remember { mutableStateOf(0.25f) }
    var quarterDefault by remember { mutableStateOf(0.55f) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // 默认值在中点
        ClassppSlider(
            value = middleDefault,
            onValueChange = { middleDefault = it },
            defaultValue = 0.5f,
            modifier = Modifier.fillMaxWidth(),
        )
        // 默认值在 1/4 处
        ClassppSlider(
            value = quarterDefault,
            onValueChange = { quarterDefault = it },
            defaultValue = 0.25f,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390, heightDp = 1100)
@Composable
private fun SettingsCardPreview() = ClassppTheme {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 单行导航
        SettingsCard(
            items = listOf(
                SettingsCardItem.Nav("设置项label") {},
            ),
        )
        // 单行开关
        SettingsCard(
            items = listOf(
                SettingsCardItem.Toggle("设置项label", checked = true, onCheckedChange = {}),
            ),
        )
        // 值 + 导航：右侧展示当前设置项的值（primary 色）
        SettingsCard(
            items = listOf(
                SettingsCardItem.Nav(
                    label = "设置项label",
                    value = "设置项的值",
                    onClick = {},
                ),
            ),
        )
        // 选择行：点行弹出菜单（PopupSelectCard 同款交互）
        SettingsCard(
            items = listOf(
                SettingsCardItem.Select(
                    label = "结束节次",
                    value = "2",
                    items = (1..5).map { it to "第 $it 节" },
                    selectedId = 2,
                    onPick = {},
                ),
            ),
        )
        // 自定义行：尾部槽位放任意控件（节次卡加减按钮 / 时间胶囊同款）
        SettingsCard(
            items = listOf(
                SettingsCardItem.Custom(
                    label = "设置项label",
                    trailing = {
                        androidx.compose.material3.Text(
                            text = "操作",
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                ),
            ),
        )
        // 多行导航
        SettingsCard(
            items = listOf(
                SettingsCardItem.Nav("设置项label") {},
                SettingsCardItem.Nav("设置项label") {},
            ),
        )
        // 多行开关
        SettingsCard(
            items = listOf(
                SettingsCardItem.Toggle("设置项label", checked = true, onCheckedChange = {}),
                SettingsCardItem.Toggle("设置项label", checked = true, onCheckedChange = {}),
                SettingsCardItem.Toggle("设置项label", checked = true, onCheckedChange = {}),
            ),
        )
        // 多行导航 + 行距：rowSpacing > 0 时行间留白（四周边距不变，间隙不可点）
        SettingsCard(
            items = listOf(
                SettingsCardItem.Nav("设置项label") {},
                SettingsCardItem.Nav("设置项label") {},
            ),
            rowSpacing = 12.dp,
        )
        // 描述 + 导航
        SettingsCard(
            items = listOf(
                SettingsCardItem.Nav(
                    label = "设置项label",
                    description = "描述性文字描述性文字描述性文字",
                    onClick = {},
                ),
            ),
        )
        // 描述 + 开关：描述超宽自动换行，行卡长高
        SettingsCard(
            items = listOf(
                SettingsCardItem.Toggle(
                    label = "设置项label",
                    description = "描述性文字描述性文字描述性文字描述性文" +
                        "字描述性文字描述性文字",
                    checked = true,
                    onCheckedChange = {},
                ),
            ),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390, heightDp = 640)
@Composable
private fun SettingsCardwithIconPreview() = ClassppTheme {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 单行导航
        SettingsCardwithIcon(
            items = listOf(
                SettingsCardwithIconItem(R.drawable.ic_paint_brush, "设置项label") {},
            ),
        )
        // 双行导航
        SettingsCardwithIcon(
            items = listOf(
                SettingsCardwithIconItem(R.drawable.ic_paint_brush, "设置项label") {},
                SettingsCardwithIconItem(R.drawable.ic_paint_brush, "设置项label") {},
            ),
        )
        // 三行导航
        SettingsCardwithIcon(
            items = listOf(
                SettingsCardwithIconItem(R.drawable.ic_paint_brush, "设置项label") {},
                SettingsCardwithIconItem(R.drawable.ic_paint_brush, "设置项label") {},
                SettingsCardwithIconItem(R.drawable.ic_paint_brush, "设置项label") {},
            ),
        )
        // 值 + 导航：右侧展示当前设置项的值（primary 色）
        SettingsCardwithIcon(
            items = listOf(
                SettingsCardwithIconItem(
                    icon = R.drawable.ic_paint_brush,
                    label = "设置项label",
                    value = "设置项的值",
                    onClick = {},
                ),
            ),
        )
        // 描述 + 导航：描述超宽自动换行，行卡长高
        SettingsCardwithIcon(
            items = listOf(
                SettingsCardwithIconItem(
                    icon = R.drawable.ic_paint_brush,
                    label = "设置项label",
                    description = "描述性文字描述性文字描述性文字描述性文" +
                        "字描述性文字描述性文字",
                    onClick = {},
                ),
            ),
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

@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390)
@Composable
private fun TagChoosingCardPreview() = ClassppTheme {
    // 预览内模拟宿主数据流：标签列表/选中集合都在内存，输入新增、点击选中、长按删除皆可交互。
    // 新增标签恒用 Primary（颜色选择器接入前的默认色）。
    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val userTags = remember {
        mutableStateListOf(
            TagItem("紧急", primaryColor),
            TagItem("本周", Color(0xFF22B14C)),
            TagItem("实验报告", Color(0xFFE3C160)),
            TagItem("复习", errorColor),
        )
    }
    // 课程标签的宿主映射示例：激活课表课程按名去重 + 课程卡配色（CourseColor.barColor 的色值）
    val courseTags = listOf(
        TagItem("高等数学", Color(0xFFB1C4EE)),
        TagItem("大学英语", Color(0xFF81D689)),
        TagItem("数据结构", Color(0xFF98D651)),
        TagItem("大学物理", Color(0xFFE3C160)),
        TagItem("有机化学", Color(0xFFEBB8A7)),
        TagItem("毛概", Color(0xFFCBBCF0)),
    )
    var selected by remember { mutableStateOf(setOf("紧急")) }
    val onAddTag: (String) -> Unit = { name ->
        userTags.add(TagItem(name, primaryColor))
    }
    // 删除时同步从选中集合移除：选中键是标签名，残留会让同名新标签带着选中态复活
    val onDeleteTag: (String) -> Unit = { name ->
        userTags.removeAll { it.name == name }
        selected = selected - name
    }

    // clearFocusOnTap：点空白失焦提交输入（真实宿主 OverlaySheet 内容列已内置，此处对齐）
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clearFocusOnTap(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TagChoosingCard(
            userTags = userTags,
            courseTags = courseTags,
            selectedNames = selected,
            onSelectionChange = { selected = it },
            onAddTag = onAddTag,
            onDeleteTag = onDeleteTag,
        )
        // 展开态：课程标签 FlowRow 换行显示
        TagChoosingCard(
            userTags = userTags,
            courseTags = courseTags,
            selectedNames = selected,
            onSelectionChange = { selected = it },
            onAddTag = onAddTag,
            onDeleteTag = onDeleteTag,
            initialCourseExpanded = true,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390)
@Composable
private fun UrgentFlagCardPreview() = ClassppTheme {
    // 设计稿五态：每张卡固定一个当前档位，右侧展示其余四档旗标
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TodoUrgency.entries.forEach { urgency ->
            UrgentFlagCard(selected = urgency, onSelect = {})
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390)
@Composable
private fun PopupMenuCardPreview() = ClassppTheme {
    // 设计稿左图：排序菜单两分组（组间分割线）；下方附单组形态（无分割线）
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PopupMenuCard(
            sections = listOf(
                PopupMenuSection(
                    items = listOf(
                        PopupMenuItem("时间", checked = true, onClick = {}),
                        PopupMenuItem("截止日期", onClick = {}),
                        PopupMenuItem("地点", onClick = {}),
                        PopupMenuItem("标签", onClick = {}),
                        PopupMenuItem("紧急程度", onClick = {}),
                    ),
                ),
                PopupMenuSection(
                    showDivider = true,
                    items = listOf(
                        PopupMenuItem("升序", checked = true, onClick = {}),
                        PopupMenuItem("降序", onClick = {}),
                    ),
                ),
            ),
        )
        PopupMenuCard(
            sections = listOf(
                PopupMenuSection(
                    items = listOf(
                        PopupMenuItem("时间", checked = true, onClick = {}),
                        PopupMenuItem("截止日期", onClick = {}),
                    ),
                ),
            ),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390)
@Composable
private fun DateSelectionCardPreview() = ClassppTheme {
    // 设计稿四态：无（值模式）/ 快捷选项（选项模式）/ 自定义+每周 / 自定义+每天（全交互）
    var interactiveValue by remember {
        mutableStateOf<DateSelection>(DateSelection.Custom(IsoDate.of(2026, 10, 3), RepeatFrequency.Daily))
    }
    var interactiveExpanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DateSelectionCard(
            value = DateSelection.None,
            expanded = false,
            onValueChange = {},
            onExpandedChange = {},
        )
        DateSelectionCard(
            value = DateSelection.None,
            expanded = true,
            onValueChange = {},
            onExpandedChange = {},
        )
        DateSelectionCard(
            value = DateSelection.Custom(IsoDate.of(2026, 10, 3), RepeatFrequency.Weekly),
            expanded = false,
            onValueChange = {},
            onExpandedChange = {},
        )
        DateSelectionCard(
            value = interactiveValue,
            expanded = interactiveExpanded,
            onValueChange = { interactiveValue = it },
            onExpandedChange = { interactiveExpanded = it },
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390)
@Composable
private fun DeadlineCardPreview() = ClassppTheme {
    // 两态：未设置（灰「无」）/ 已设置（2026-9-7 8:00，全交互：日期弹窗 → 时刻弹窗）
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DeadlineCard(
            deadlineDate = null,
            deadlineMinute = null,
            onChange = { _, _ -> },
        )
        DeadlineCard(
            deadlineDate = IsoDate.of(2026, 9, 7),
            deadlineMinute = 8 * 60,
            onChange = { _, _ -> },
        )
    }
}
