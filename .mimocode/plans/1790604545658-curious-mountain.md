# 课表编辑态「切换课表」底部浮层

## 目标

编辑态点击 `切换课表` 按钮（当前为 no-op）后，从底部弹出浮层（设计图）：
- 列出全部课表：名称 + 起止日期（`xx年xx月xx日 - xx年xx月xx日`）；当前课表名称为蓝色主色 + 右侧对勾，点其它行切换；
- 底部三个蓝色文字按钮：**导出课表 / 导入课表 / 新建课表**。

已与用户确认的决策：
1. 草稿有未保存修改时切换 → 先弹确认，确认后丢弃草稿再切换；无改动直接切换；
2. 新建 / 导入成功后 → 自动切换激活新课表；
3. 导出走系统分享面板（ACTION_SEND + JSON 文本）；导入走系统文件选择器（OpenDocument 读 JSON）。

## 关键设计决策

- **切换后停留在编辑态**：`ScheduleScreen.kt:232` 的 session 创建 key 从 `editing` 改为 `editing, schedule.id` ——
  `rememberSaveable` 的 inputs 变化会丢弃旧值并用初始值重算，切换激活课表后草稿**隐式重置**为新课表的课程快照，Saver 不用动（旋转/进程重建时 inputs 与恢复值一致，不丢数据）。
- **草稿脏检测**：`editSession.courses != schedule.courses`（`CourseEntry` 是 data class，草稿增删改保序，结构相等成立）。每次重组直接算，无需 remember。
- **确认弹窗**：页内居中覆盖层（沿用 `AlternatePickerDialog` 的 Scrim 0.32 + 居中 Surface 卡片 + BackHandler 模式），**不用 AlertDialog**（项目惯例：独立窗口有 IME/ROM 问题，AddCoursePanel.kt:96-101 有说明）。在组合顺序上放最后，BackHandler 优先级最高。
- **导出对象** = 当前激活课表（即带对勾那行）；导出读的是仓库已保存状态，不含草稿——已接受的限制（写进 KDoc）。JSON 体积小，`EXTRA_TEXT` 即可，无需 FileProvider。
- **新建课表**：同一浮层内二级形态（列表 ↔ 紧凑表单原地切换；返回/遮罩在表单态先回列表）。日期吸附规则（周一/周五）与设置页必须一致 → 把纯函数抽到公共文件，避免两处漂移。

## 改动文件

### 新建

1. **`app/src/main/java/com/fangyi/classpp/ui/schedule/ScheduleSwitcherSheet.kt`**
   ```kotlin
   @Composable
   internal fun ScheduleSwitcherSheet(
       schedules: List<Schedule>,
       activeScheduleId: String,
       createError: String?,            // 新建表单内联错误，null 无
       onDismiss: () -> Unit,
       onSwitch: (String) -> Unit,      // 行点击；脏检测/确认由 screen 做
       onExport: () -> Unit,
       onImport: () -> Unit,
       onCreateConfirm: (name: String, start: IsoDate, end: IsoDate) -> Unit,
   )
   ```
   - 结构照抄 `AddCoursePanel.kt:251-284`：全屏 Box（`Scrim` alpha 0.32、无涟漪点击收起）→ `align(BottomCenter).fillMaxWidth()` → `Surface(RoundedCornerShape(topStart=20.dp, topEnd=20.dp), shadowElevation=8.dp)`。
   - 内部状态 `createMode by rememberSaveable`；列表态：圆角浅灰容器（`surfaceContainer`，同设置页 SectionCard）包课表行列表，`heightIn(max=360.dp) + verticalScroll`；行 = 名称（激活行 primary 色 + SemiBold + `✓` 文本，无 material-icons 依赖）+ 日期区间副标题（灰）。三按钮 Row 固定在滚动区之外。
   - 表单态：名称 `OutlinedTextField` + 两条日期行（点开 `DatePickerDialog`）+ 全宽创建按钮；吸附逻辑用抽出的公共 helper。
   - BackHandler：表单态 → 回列表；列表态 → 关闭。

2. **`app/src/main/java/com/fangyi/classpp/ui/schedule/TermDateSnap.kt`**
   - 从 `SettingsScreen.kt:586-602` 移入：`DateTarget`、`TERM_DEFAULT_DAYS`、`snapToMonday`、`snapTermEnd`（改 public，`internal` 即可），SettingsScreen 改为 import，删除原 private 定义。（这些编码了硬校验规则，复制会漂移；日期行 UI 本身小，可适度重复。）

### 修改

3. **`app/src/main/java/com/fangyi/classpp/ui/schedule/ScheduleScreen.kt`**
   - `:232-238`：session key 加 `schedule.id` → `rememberSaveable(editing, schedule.id, saver = ScheduleEditSession.Saver)`。
   - 新增状态（`rememberSaveable`，约 :243 后）：`switcherVisible: Boolean`、`pendingSwitchId: String`。
   - `:434`：`onSwitchSchedule = { switcherVisible = true }`。
   - 新增处理函数：
     - `isDraftDirty = editSession != null && editSession.courses != schedule.courses`
     - `performSwitch(id)`：`scope.launch { repository.setActiveSchedule(id) }`；清 `pendingSwitchId`、`switcherVisible`、瞬时面板态（`addTarget`/`editTargetId`/`chooserSourceId`/`alternateSourceId`/`menuAnchor` —— 旧草稿 id 在新课表无意义）、`selectedWeek = 0`（落在新课表当前周）。
     - 行点击：`id == activeId` → 仅关浮层；脏 → `pendingSwitchId = id`；否则直接 `performSwitch(id)`。
     - 确认覆盖层：取消 → 清 `pendingSwitchId`（浮层保持打开）；丢弃并切换 → `performSwitch(pendingSwitchId)`。新建/导入成功后的自动切换走同一条路径（脏检测一致）。
   - 导出（在有 `context`/`scope` 的作用域内）：
     ```kotlin
     scope.launch {
         when (val r = repository.exportSchedule(schedule.id)) {
             is ReadResult.Ok -> {
                 val send = Intent(Intent.ACTION_SEND).apply {
                     type = "application/json"
                     putExtra(Intent.EXTRA_TEXT, r.value)
                 }
                 try { context.startActivity(Intent.createChooser(send, null)) }
                 catch (_: ActivityNotFoundException) { Toast… }
             }
             is ReadResult.Err -> Toast(r.error.toEditMessage(context))
         }
     }
     ```
     不关浮层（分享面板浮在上面）。
   - 导入：`rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument())`，mime `["application/json", "text/plain", "application/octet-stream"]`；回调里 `contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }`（IOException/SecurityException → Toast `import_read_failed`）→ `repository.importSchedule(json)`；Ok(id) → 走 `performSwitch` 同路径（先过脏确认）；Err → Toast。取消选择器 → 无操作。
   - 渲染位置：`~:506`（AddCoursePanel 各分支之后）加一个 Box 层：浮层 + 其后的确认覆盖层（组合顺序保证 BackHandler 优先级：确认 → 浮层 → 编辑取消 `:355`）。
   - `toEditMessage`（:547-558）补 `ImportFormatInvalid / ImportVersionUnsupported / ImportKindMismatch` 三个 case。
   - `activity-compose` 已在依赖里（BackHandler 同包），`rememberLauncherForActivityResult` 可直接用；minSdk 24 满足 OpenDocument(19+)。

4. **`app/src/main/java/com/fangyi/classpp/ui/settings/SettingsScreen.kt`**
   - 删除 private 的 `DateTarget`/`TERM_DEFAULT_DAYS`/`snapToMonday`/`snapTermEnd`（:586-602），改为 import `ui.schedule` 下的公共版本。

5. **`app/src/main/res/values/strings.xml` + `values-en/strings.xml`（两个 locale 都要加）**

   | name | zh | en |
   |---|---|---|
   | `switcher_export` | 导出课表 | Export |
   | `switcher_import` | 导入课表 | Import |
   | `switcher_new` | 新建课表 | New schedule |
   | `date_cn_format` | %1$d年%2$d月%3$d日 | %2$1d/%3$1d/%1$1d |
   | `switcher_date_range` | %1$s - %2$s | %1$s – %2$s |
   | `switcher_discard_title` | 放弃未保存的修改？ | Discard unsaved changes? |
   | `switcher_discard_message` | 切换课表将丢弃当前未保存的编辑 | Switching will discard your unsaved edits |
   | `switcher_discard_confirm` | 丢弃并切换 | Discard & switch |
   | `switcher_discard_cancel` | 取消 | Cancel |
   | `import_read_failed` | 读取文件失败 | Failed to read file |
   | `error_import_format` | 文件格式无效 | Invalid file format |
   | `error_import_version` | 不支持的文件版本 | Unsupported file version |
   | `error_import_kind` | 不是课表文件 | Not a schedule file |

   复用已有：`edit_switch_schedule`（浮层标题/复用）、`create_schedule`、`schedule_name_label/hint`、`term_start`、`term_end`、`settings_confirm`、`settings_cancel`。
   日期区间格式化：`IsoDate` 无年月日访问器 → `toString().split('-')` 取 Int 走 `date_cn_format`，再拼 `switcher_date_range`。

## 边界情况

- 列表不可能为空（编辑栏只在 `schedule != null` 时组合）。
- 旋转/进程重建：`switcherVisible`/`pendingSwitchId`/表单字段均 `rememberSaveable`；ActivityResult 由 registry 恢复；session 分析见上。
- 导入失败 → Toast（扩充后的 mapper）；分享无目标 Activity → Toast；新建校验失败 → 表单内联红字（`createError`）。
- Pager：新课表周数变小由 `week` 钳制（:126）+ `LaunchedEffect(week)`（:304）收敛；`selectedWeek=0` 重置避免残留周次。
- 导出不含草稿未保存修改（已接受，KDoc 注明）。

## 验证清单（手动）

1. 编辑 → 切换课表 → 浮层从底部弹出：全部课表 + 日期区间，当前课表蓝色 + 对勾；点遮罩/返回关闭。
2. 草稿无改动 → 点其它行 → 立即切换，仍在编辑态，草稿=新课表课程，落在新课表当前周，旧面板瞬时状态已清。
3. 有草稿改动 → 点行 → 确认弹窗；取消 → 不切、浮层仍在、草稿保留；丢弃并切换 → 切换成功；弹窗态旋转可恢复。
4. 导出 → 系统分享面板带 JSON 文本；无分享应用 → Toast。
5. 导入合法 .json → 选择器 → 导入 + 切换（重名自动加"(导入)"后缀）；非法文件 → `error_import_format` Toast；取消选择器 → 无操作。
6. 新建课表 → 紧凑表单；开始吸附周一、结束吸附周五（与设置页一致）；空名称内联报错；创建 + 脏时确认后切换。
7. 切换后保存/取消作用于新课表；编辑态返回键仍=取消。
8. 旋转（浮层/表单/确认弹窗各态）；中英文 locale 文案正确。

## 实施步骤

1. 抽出 `TermDateSnap.kt`，SettingsScreen 改 import（先行，保证后续两处吸附一致）。
2. 写 `ScheduleSwitcherSheet.kt`（列表态 + 表单态 + 样式对照设计图）。
3. ScheduleScreen 接线：状态、session key、按钮回调、performSwitch、确认覆盖层。
4. 导出/导入（Intent + OpenDocument launcher）、`toEditMessage` 扩充。
5. 双语 strings 补齐。
6. 按验证清单手动过一遍。
