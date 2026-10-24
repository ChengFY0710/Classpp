package com.fangyi.classpp.ui.note

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.isOverdue
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.TagEntry
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoGroup
import com.fangyi.classpp.ui.LocalToday
import com.fangyi.classpp.ui.components.BottomTextFieldCard
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.ConfirmDialogCard
import com.fangyi.classpp.ui.components.DialogPillButton
import com.fangyi.classpp.ui.components.FadeOverlayDialog
import com.fangyi.classpp.ui.components.GroupCard
import com.fangyi.classpp.ui.components.GroupCardItem
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SettingsCardwithIcon
import com.fangyi.classpp.ui.components.SettingsCardwithIconItem
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetPillButton
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.motion.Motion
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import com.fangyi.classpp.ui.theme.classppColors
import dev.chrisbanes.haze.rememberHazeState

/**
 * 标签卡的一行：用户标签与课程标签的统一视角。[entry] 非空 = 用户标签（可重命名/删除/拖拽
 * 重排，写回 [TagRepository] 时原样带颜色）；null = 课程标签（宿主按激活课表派生、不入库，
 * 只读——GroupCard 锁定前缀展示，编辑态里重命名/删除置灰）。
 */
private data class TagRow(
    val name: String,
    val isCourse: Boolean,
    val entry: TagEntry?,
)

/**
 * 待办分组浮层：待办页顶栏「更多 → 分组」进入，内容分两部分——
 *
 * - **第一部分（状态与留存，无标题 [CardSection]）**：已完成 / 未完成 / 已逾期 一张三行卡，
 *   已置顶 + 已归档一张两行卡、最近删除一张单行卡；
 * - **第二部分（带「分组」小标题的 [CardSection]）**：未分组、全部各一张单行卡，
 *   用户自定义分组一张 [GroupCard]——自定义分组无行首图标，与带图标的固定行区分
 *   （正是 [GroupCard] 与 [SettingsCardwithIcon] 的行型差异）；
 * - **第三部分（带「标签」小标题的 [CardSection]）**：全部标签一张 [GroupCard]——课程标签
 *   （[courseTags]，宿主按激活课表派生）作只读锁定前缀、用户标签随后，行首挂 `ic_tag`、
 *   颜色跟随标签色（用户标签 colorArgb null 与课程标签都取主题 primary），计数 = 挂该标签
 *   的待办数；无任何标签时整组（含标题）不渲染。普通态点击行 = 跳转该标签的待办过滤页
 *   （[onOpenTag]）。
 *
 * ## 标签编辑态（长按标签行进入，与分组编辑态同构、互斥）
 * 长按标签行 = 以该行快照进标签编辑态：顶栏换「左白✕取消 + 右蓝✓确认」，底栏操作行滑入
 * （同分组编辑态，无置顶钮——标签无置顶语义）；编辑态点击 = 多选切换，长按 = 拾起拖拽重排
 * （课程标签锁定不可拖、落点不进锁定区），顶栏「确认」把重排后的**用户标签**表经
 * [onTagsChange] 整表写回（同分组「确认写回草稿」模型，课程标签不入库位置随派生序）。
 * 重命名（仅选中恰好一个「用户标签」时可点）弹 [BottomTextFieldCard]、删除（批量，选中含
 * 课程标签时置灰——课程标签只读不可删改）经 [DeleteConfirmDialog] 二次确认——两者都
 * **确认即立即生效**（[onRenameTag] / [onDeleteTags]：宿主改标签库并回写全部待办，待办
 * 本身保留），不经顶栏「取消」撤销，删空自动退编辑。两种编辑态互斥：分组编辑态内标签行的
 * 长按/点击不触发；标签编辑态内分组行的长按/点击与全部跳转行同样不触发（避免静默丢弃
 * 另一边的草稿）。
 *
 * **顶栏普通态**：左蓝「新建」（`ic_add_circle` → [BottomTextFieldCard] 底部输入弹卡，
 * 经 [onGroupsChange] 整表写回落库）+ 居中标题 + 右白「关闭」，与课表切换浮层同款顶栏排布。
 * 浮层框架 [OverlaySheet]：两段式关闭（visible / onDismissed），系统返回与点遮罩、下拉、
 * 关闭胶囊同走 [requestClose]；列表型浮层走 [SheetImeBehavior.IgnoreIme]，内容列由浮层
 * 自带的橡皮筋滚动接管，这里不再加滚动。
 * **弹卡磨砂**：本组件自建 hazeState 一并交给 [OverlaySheet] 与新建 / 重命名弹卡——浮层滚动
 * 列作采样源，顶栏磨砂与弹卡背景模糊同源（同 NewTodoSheet 的接法），弹卡因此是磨砂玻璃
 * 质感而不是平底卡。
 *
 * ## 编辑态（长按 [GroupCard] 条目进入）
 * 编辑态状态（editing / selectedIds / 草稿）全部在本组件内 remember：进程重建即草稿丢失，
 * 等价按下「取消」，无数据损失（同理无需 Saver 样板）。
 *
 * - **进入**：长按某行 → 以 [groups] 快照草稿 [draft]、自动选中该行；顶栏换「左白✕取消 +
 *   居中标题 + 右蓝✓确认」（[OverlaySheet] 的 confirmAtEnd），底栏操作行从底部滑入。
 * - **底栏操作行**（[EditActionRow]）：置顶 / 重命名 / 删除三颗胶囊，进出场用浮层同源的
 *   [Motion.Overlay] 非线性曲线（进 [Motion.SheetEnterMillis]、出 [Motion.SheetExitMillis]），
 *   退出编辑态整块滑出。钉底规格（渐变兜底 / 12-24 内距 / 导航栏留白）自绘自持——
 *   OverlaySheet 传 `bottomScrim = false` 只留钉底定位，规格随动画层整体移动。
 * - **置顶**（[applyPin] / [pinAction]，只作用于草稿）：选中全未置顶 = `ic_pin`「置顶」、
 *   全已置顶 = `ic_pin_off`「取消」、空选择或混合选择 = negative 置灰不可点；提交后新置顶项
 *   落置顶前缀尾部（顺序即置顶操作顺序）、取消置顶项按原相对序落回非置顶区——稳定划分维持
 *   [GroupCard] 的置顶前缀契约。
 * - **删除**：底栏「删除」（空选择置灰）→ [DeleteGroupConfirmDialog] 两段式确认 → **立即生效**
 *   （[onDeleteGroups]，仅删分组），同时从草稿移除；不经顶栏「取消」撤销，删空自动退编辑。
 * - **重命名**：底栏「重命名」（仅选中恰好一个时可点，可点 = primary 着色）→
 *   [BottomTextFieldCard] 输入新名（空名/与其它组重名时确认置灰，同名 = 无操作可确认）→
 *   **确认即立即生效**：真实列表改名经 [onGroupsChange] 写回（宿主按 renames 映射让顶栏
 *   胶囊跟随到新名），草稿与选中集同步改名——**不经顶栏「取消」撤销**，同删除的立即生效
 *   模型；弹卡「取消」/遮罩/返回 = 不改名。立即生效使草稿与真实列表的名字集合始终一致，
 *   删除/拖拽等按名对齐的流程不会失配。
 * - **退出**：顶栏「确认」= [onGroupsChange] 写回草稿（顺序 + 置顶）并退编辑；「取消」/ 点遮罩 /
 *   系统返回 = 丢草稿退编辑（[requestClose] 三分支合一）；编辑态下拉关闭被 dragDismissEnabled
 *   禁用（松手回弹，不关浮层——只拦回调会把浮层卡在半拖出位置）。
 *
 * **计数口径**：已完成 / 未完成 / 已逾期 实时从 [todos]（活跃待办，仓库已剔除最近删除）
 * 统计，逾期判定与列表同一份（[IsoDate.today] + [currentMinuteOfDay]）；已置顶同为实时
 * 统计（`pinned` 标记计数）；最近删除 = 回收站条目数（[deletedCount]，仓库 deletedTodos
 * 流），点入即可撤回或彻底删除；已归档数据层暂无该概念，恒 0；未分组 = `group == null`
 * 的待办数、全部 = 待办总数、自定义分组 = 该组名的待办数（分组数据已入 [TodoFile.groups]，
 * 引用完整性由仓库归一化维护）。
 *
 * 第一部分（状态/留存）各行点击 = 跳转对应特殊分组页（[onOpenSpecial]：宿主收起浮层、
 * 顶栏分组条首位出现对应胶囊并常驻——再点已选胶囊退出关闭、点未选胶囊回页；已归档
 * 本期仅 UI）；普通态点击
 * 自定义分组 /「未分组」/「全部」/ 标签行 = 跳转对应过滤（[onOpenGroup] / [onOpenUngrouped] /
 * [onOpenAll] / [onOpenTag]：宿主收起浮层并切顶栏胶囊；标签页的胶囊着标签色）。
 *
 * @param groups 分组列表：顺序即显示序，置顶项须构成连续前缀（[TodoGroup] 不变式）
 * @param tags 标签列表（用户自定义标签，顺序即显示序）：第三部分标签 [GroupCard] 的数据源
 * @param courseTags 课程标签名（宿主按激活课表去重派生，不入库）：只展示**有待办挂载**的，
 *   以只读锁定前缀展示在用户标签之前，不可重命名/删除/拖拽；与用户标签重名时以用户标签
 *   为准（id 须唯一）
 * @param deletedCount 最近删除条目数（仓库 deletedTodos 流的大小）
 * @param onGroupsChange 整表写回统一入口：编辑态确认（顺序 + 置顶）、普通态新建（追加分组）、
 *   重命名（立即生效）；[renames] 为本次操作的重命名映射（原名 → 新名）——宿主让顶栏选中
 *   跟随到新名，仓库在同一次提交里让该组待办的归属跟随（不掉回未分组），无重命名时传空表
 * @param onDeleteGroups 删除：立即从真实列表移除选中分组，不可经顶栏「取消」撤销
 * @param onTagsChange 标签编辑态确认：整表写回重排后的标签表（顺序即显示序）
 * @param onOpenTag 普通态点击标签行：宿主收起浮层并打开该标签的待办过滤页
 * @param onRenameTag 标签重命名确认（立即生效）：宿主先改标签库再回写全部待办
 * @param onDeleteTags 标签删除确认（立即生效，批量）：宿主删标签并解绑全部待办（待办本身保留）
 * @param onOpenGroup 普通态点击自定义分组：宿主收起浮层并把顶栏胶囊切到该分组
 * @param onOpenUngrouped 普通态点击「未分组」行：宿主收起浮层并把顶栏胶囊切到「未分组」
 * @param onOpenAll 普通态点击「全部」行：宿主收起浮层并把顶栏胶囊切回「全部」
 * @param onOpenSpecial 点击第一部分（状态/留存）行：宿主收起浮层并打开对应特殊分组页
 *   （编辑态不触发——同第二部分行，避免静默丢弃草稿）
 */
@Composable
internal fun TodoGroupsSheet(
    todos: List<Todo>,
    deletedCount: Int,
    groups: List<TodoGroup>,
    tags: List<TagEntry>,
    courseTags: List<String>,
    visible: Boolean,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    onGroupsChange: (groups: List<TodoGroup>, renames: Map<String, String>) -> Unit,
    onDeleteGroups: (List<String>) -> Unit,
    onTagsChange: (List<TagEntry>) -> Unit,
    onOpenTag: (String) -> Unit,
    onRenameTag: (from: String, to: String) -> Unit,
    onDeleteTags: (List<String>) -> Unit,
    onOpenGroup: (String) -> Unit,
    onOpenUngrouped: () -> Unit,
    onOpenAll: () -> Unit,
    onOpenSpecial: (TodoSpecialSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    // ——— 编辑态状态机（全 remember：进程重建 = 草稿丢 = 等价取消，无数据损失；
    // 例外：重命名确认即立即生效，不经顶栏「取消」撤销——同删除的立即生效模型，
    // 详见重命名弹卡处注释）———
    var editing by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    // 编辑态显示源：进入时快照 groups，置顶/拖拽只改它，确认才写回
    var draft by remember { mutableStateOf(emptyList<TodoGroup>()) }
    // 删除确认请求：非空 = 对话框打开（值为按显示顺序快照的选中组名）
    var deleteTargets by remember { mutableStateOf<List<String>?>(null) }
    // 新建分组弹卡：期望态 + 挂载态两段式（同删除确认框），普通态顶栏「新建」触发；
    // createDraft 是受控草稿（打开时清空），确认才追加进分组表
    var createRequested by remember { mutableStateOf(false) }
    var createMounted by remember { mutableStateOf(false) }
    var createDraft by remember { mutableStateOf("") }
    if (createRequested) createMounted = true
    // 重命名弹卡：编辑态底栏「重命名」触发（选中恰好一个时可用）；renameFrom 是打开时的
    // 原组名快照（校验与确认都以它对齐），renameDraft 是受控草稿（打开时填原组名）
    var renameVisible by remember { mutableStateOf(false) }
    var renameMounted by remember { mutableStateOf(false) }
    var renameFrom by remember { mutableStateOf("") }
    var renameDraft by remember { mutableStateOf("") }
    if (renameVisible) renameMounted = true
    // 标签编辑态状态机（与分组编辑态同构、互斥——长按标签行触发，闸门保证两边不同时进
    // 编辑；全 remember：进程重建 = 草稿丢 = 等价取消，无数据损失）：tagDraft 是进入时
    // 快照的标签表，拖拽只改它，顶栏「确认」才经 [onTagsChange] 整表写回（重排）；
    // 重命名/删除同分组一样确认即立即生效
    var tagEditing by remember { mutableStateOf(false) }
    var tagSelectedIds by remember { mutableStateOf(emptySet<String>()) }
    var tagDraft by remember { mutableStateOf(emptyList<TagRow>()) }
    // 标签重命名弹卡（标签编辑态底栏「重命名」触发，仅选中恰好一个时可点）：挂载与草稿
    // 同分组重命名的两段式约定，tagRenameFrom 是打开时的原标签名快照（校验与确认都以它对齐）
    var tagRenameVisible by remember { mutableStateOf(false) }
    var tagRenameMounted by remember { mutableStateOf(false) }
    var tagRenameFrom by remember { mutableStateOf("") }
    var tagRenameDraft by remember { mutableStateOf("") }
    if (tagRenameVisible) tagRenameMounted = true
    // 标签批量删除确认请求（标签编辑态底栏「删除」触发）：非空 = 对话框打开（值为按显示
    // 顺序快照的选中标签名）
    var tagDeleteTargets by remember { mutableStateOf<List<String>?>(null) }

    // haze 采样源：自建后同时交给本浮层（[OverlaySheet] 的共享 hazeState 槽）与叠在其上的
    // 新建/重命名弹卡——浮层滚动列是采样源，顶栏磨砂与弹卡的背景模糊同源（同 NewTodoSheet
    // 的 sheetHazeState 接法）。不交给弹卡的话弹卡按 BottomSelectSheet 的默认平底渲染，
    // 与日期轮等其他 Bottom* 弹卡的磨砂玻璃质感不一致
    val sheetHazeState = rememberHazeState()

    val displayGroups = if (editing) draft else groups
    // 标签合并视图：课程标签（只读）作锁定前缀 + 用户标签。课程标签只展示**有待办挂载**的
    // （空课程标签不占位，与用户标签「建了就在」不同——它是派生行，随挂载出现/消失）；与
    // 用户标签重名时跳过课程标签（GroupCard 的 id 须唯一，用户标签可编辑、以它为准）。
    // 写回 [onTagsChange] 时只取用户标签（entry 非空，原样带颜色），课程标签不入库
    val userTagNames = tags.mapTo(mutableSetOf()) { it.name }
    val mergedTags = courseTags
        .filter { name -> todos.any { name in it.tags } }
        .filterNot { it in userTagNames }
        .map { TagRow(name = it, isCourse = true, entry = null) } +
        tags.map { TagRow(name = it.name, isCourse = false, entry = it) }
    val displayTags = if (tagEditing) tagDraft else mergedTags

    fun exitEdit() {
        editing = false
        selectedIds = emptySet()
        draft = emptyList()
    }

    fun enterEdit(id: String) {
        draft = groups
        selectedIds = setOf(id)
        editing = true
    }

    fun confirmEdit() {
        // 草稿写回无重命名映射（重命名在弹卡确认时已立即生效）
        onGroupsChange(draft, emptyMap())
        exitEdit()
    }

    fun exitTagEdit() {
        tagEditing = false
        tagSelectedIds = emptySet()
        tagDraft = emptyList()
    }

    fun enterTagEdit(id: String) {
        tagDraft = mergedTags
        tagSelectedIds = setOf(id)
        tagEditing = true
    }

    fun confirmTagEdit() {
        // 草稿写回只承载重排（重命名/删除在弹卡/确认框确认时已立即生效）；课程标签是
        // 派生的只读行，不在写回之列
        onTagsChange(tagDraft.mapNotNull { it.entry })
        exitTagEdit()
    }

    // 遮罩 / 下拉 / 系统返回 / 顶栏取消共用一个出口，逐层退出：先退其中之一的编辑态
    // （分组/标签编辑态互斥，闸门保证不会同时在场），非编辑态才真正关浮层
    val requestClose = {
        when {
            editing -> exitEdit()
            tagEditing -> exitTagEdit()
            else -> onDismiss()
        }
    }

    // 系统返回 = 退编辑（编辑态）或关浮层（普通态）；删除确认框在其挂载处后注册 BackHandler，
    // 返回键先到它（先关确认框、留在浮层），同 TodoDetailSheet
    BackHandler(enabled = visible, onBack = requestClose)

    // 逾期判定的"此刻"：当天日期取 [LocalToday]（跨零点自动跳天，同 TodoList）；分钟口径重组时现取
    val today = LocalToday
    val nowMinute = currentMinuteOfDay()
    val completedCount = todos.count { it.completed }
    val incompleteCount = todos.count { !it.completed }
    val overdueCount = todos.count { it.isOverdue(today, nowMinute) }
    val totalCount = todos.size
    // 已置顶 = pinned 标记计数（数据层已有该字段，与归档/最近删除的恒 0 占位不同）
    val pinnedCount = todos.count { it.pinned }
    // 未分组 = group == null（引用完整性由仓库归一化维护，读侧无需容错）
    val ungroupedCount = todos.count { it.group == null }

    // 置顶按钮三态（读草稿 + 选中集，编辑态内每次变化重组刷新）
    val currentPinAction = pinAction(displayGroups, selectedIds)
    val onPin: () -> Unit = {
        when (currentPinAction) {
            PinAction.Pin -> draft = applyPin(draft, selectedIds, pinned = true)
            PinAction.Unpin -> draft = applyPin(draft, selectedIds, pinned = false)
            PinAction.Disabled -> Unit
        }
    }

    OverlaySheet(
        title = stringResource(R.string.todo_groups_title),
        // 分组编辑态或标签编辑态（互斥）：确认到右侧、取消动作到左侧（两颗胶囊图标随位置镜像）
        confirmAtEnd = editing || tagEditing,
        confirmLabel = stringResource(
            if (editing || tagEditing) R.string.edit_confirm else R.string.todo_groups_create,
        ),
        confirmIcon = if (editing || tagEditing) R.drawable.ic_checkmark_circle else R.drawable.ic_add_circle,
        // 编辑态确认 = 提交草稿并退编辑（分组写回整表、标签写回重排）；普通态「新建」=
        // 挂载底部输入弹卡（两段式，同删除确认框）
        onConfirm = {
            when {
                editing -> confirmEdit()
                tagEditing -> confirmTagEdit()
                else -> {
                    createDraft = ""
                    createMounted = true
                    createRequested = true
                }
            }
        },
        rightAction = SheetTopAction(
            label = stringResource(
                if (editing || tagEditing) R.string.settings_cancel else R.string.todo_groups_close,
            ),
            // 两态都是 ✕ 圆：普通态 = 关闭（白胶囊深色圆），编辑态 = 取消（白胶囊深色圆）
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = requestClose,
        ),
        onDismiss = requestClose,
        visible = visible,
        // 层级动效：新建/重命名弹卡、删除确认框或标签重命名弹卡/删除确认框在场时，本浮层
        // 缩小后退（同 NewTodoSheet 的 covered 约定，用可见请求态而非挂载态——退场时与上层
        // 收场同帧反向恢复）
        covered = createRequested || renameVisible || deleteTargets != null ||
            tagRenameVisible || tagDeleteTargets != null,
        onDismissed = onDismissed,
        // 编辑态（分组/标签）禁用下拉关闭：下拉手势只回弹不触发关闭（拦成「退编辑」会与
        // 手势语义打架，只拦回调则闸门落下没人真关浮层、整层卡在半拖出位置）
        dragDismissEnabled = !(editing || tagEditing),
        // 列表型浮层：没有输入框，键盘不必让位（同课表切换浮层）
        imeBehavior = SheetImeBehavior.IgnoreIme,
        // 采样源交给容器（见 sheetHazeState 声明处）：顶栏磨砂与叠层弹卡的背景模糊同源
        hazeState = sheetHazeState,
        // 钉底规格由底栏动画层自持（见 EditActionRow），容器只给钉底定位
        bottomScrim = false,
        bottomContent = {
            // 分组编辑态与标签编辑态共用底部操作行（两者互斥，随当前编辑态切换内容）：
            // 分组多一颗置顶钮（标签无置顶语义）；重命名都是「选中恰好一个」时可点（多选
            // 置灰），标签额外要求选中的是用户标签——课程标签只读，选中含课程标签时
            // 重命名/删除一并置灰
            if (editing) {
                EditActionRow(
                    visible = true,
                    showPin = true,
                    pinAction = currentPinAction,
                    hasSelection = selectedIds.isNotEmpty(),
                    renameEnabled = selectedIds.size == 1,
                    onPin = onPin,
                    onRename = {
                        // 目标 = 草稿里选中的那个组（改名立即生效，快照原组名与草稿初值）
                        displayGroups.firstOrNull { it.name in selectedIds }?.let { target ->
                            renameFrom = target.name
                            renameDraft = target.name
                            renameMounted = true
                            renameVisible = true
                        }
                    },
                    onDelete = { deleteTargets = displayGroups.filter { it.name in selectedIds }.map { it.name } },
                )
            } else {
                val selectionRows = displayTags.filter { it.name in tagSelectedIds }
                val courseSelected = selectionRows.any { it.isCourse }
                EditActionRow(
                    visible = tagEditing,
                    showPin = false,
                    pinAction = PinAction.Disabled,
                    hasSelection = selectionRows.isNotEmpty() && !courseSelected,
                    renameEnabled = selectionRows.size == 1 && !courseSelected,
                    onPin = {},
                    onRename = {
                        // 目标 = 草稿里选中的那个用户标签（同分组：快照原名与草稿初值，确认立即生效）
                        selectionRows.firstOrNull { !it.isCourse }?.let { target ->
                            tagRenameFrom = target.name
                            tagRenameDraft = target.name
                            tagRenameMounted = true
                            tagRenameVisible = true
                        }
                    },
                    // 课程标签只读，防御性滤除（门槛置灰后本就不会出现在删除目标里）
                    onDelete = { tagDeleteTargets = selectionRows.filterNot { it.isCourse }.map { it.name } },
                )
            }
        },
        modifier = modifier,
    ) {
        // 部分间 12dp；每部分内部的卡间 10dp 由 CardSection 的 spacing 默认值负责
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
            // 第一部分：状态与留存。设计稿无小标题，CardSection 退化为纯分组容器
            CardSection {
                // 状态三行同卡：计数实时来自待办列表（值右对齐走 fieldValue）；
                // 点击 = 跳转对应特殊分组页（已完成/未完成/已逾期，编辑态不触发）
                SettingsCardwithIcon(
                    items = listOf(
                        SettingsCardwithIconItem(
                            icon = R.drawable.ic_checkbox_checked,
                            label = stringResource(R.string.todo_section_completed),
                            value = completedCount.toString(),
                            // 行右侧箭头是设计语言；点击跳转由宿主收起浮层 + 顶栏首位出胶囊
                            onClick = { if (!editing && !tagEditing) onOpenSpecial(TodoSpecialSection.Completed) },
                        ),
                        SettingsCardwithIconItem(
                            icon = R.drawable.ic_checkbox_unchecked,
                            label = stringResource(R.string.todo_section_incomplete),
                            value = incompleteCount.toString(),
                            onClick = { if (!editing && !tagEditing) onOpenSpecial(TodoSpecialSection.Incomplete) },
                        ),
                        SettingsCardwithIconItem(
                            icon = R.drawable.ic_checkbox_warning,
                            label = stringResource(R.string.todo_section_overdue),
                            value = overdueCount.toString(),
                            onClick = { if (!editing && !tagEditing) onOpenSpecial(TodoSpecialSection.Overdue) },
                        ),
                    ),
                )
                // 已置顶 + 已归档一张两行卡：置顶计数实时来自待办列表；归档数据层暂无概念，恒 0
                // （接入后由归档统计替换）；已置顶页内可单卡侧滑撤销置顶，归档页本期仅 UI
                SettingsCardwithIcon(
                    items = listOf(
                        SettingsCardwithIconItem(
                            icon = R.drawable.ic_pin,
                            label = stringResource(R.string.todo_section_pinned),
                            value = pinnedCount.toString(),
                            onClick = { if (!editing && !tagEditing) onOpenSpecial(TodoSpecialSection.Pinned) },
                        ),
                        SettingsCardwithIconItem(
                            icon = R.drawable.ic_archive,
                            label = stringResource(R.string.todo_section_archived),
                            value = "0",
                            onClick = { if (!editing && !tagEditing) onOpenSpecial(TodoSpecialSection.Archived) },
                        ),
                    ),
                )
                // 最近删除单行卡：计数 = 仓库回收站条目数；点击进入回收站页
                // （右撤回恢复、左滑彻底删除，不做 N 天自动清理）
                SettingsCardwithIcon(
                    items = listOf(
                        SettingsCardwithIconItem(
                            icon = R.drawable.ic_delete,
                            label = stringResource(R.string.todo_section_trash),
                            value = deletedCount.toString(),
                            onClick = { if (!editing && !tagEditing) onOpenSpecial(TodoSpecialSection.Trash) },
                        ),
                    ),
                )
            }
            // 第二部分：分组。标题复用顶栏动作块的「分组」文案（同一语义词）
            CardSection(title = stringResource(R.string.todo_action_group)) {
                // 未分组单行卡：group == null 的待办数（引用完整性由仓库归一化维护）；
                // 点击 = 跳转「未分组」过滤（宿主收起浮层并切顶栏胶囊）——编辑态不触发
                // （与 GroupCard 编辑态点击 = 多选对齐，避免静默丢弃草稿）
                SettingsCardwithIcon(
                    items = listOf(
                        SettingsCardwithIconItem(
                            icon = R.drawable.ic_folder_hint,
                            label = stringResource(R.string.todo_group_ungrouped),
                            value = ungroupedCount.toString(),
                            onClick = { if (!editing && !tagEditing) onOpenUngrouped() },
                        ),
                    ),
                )
                // 全部单行卡：待办总数；点击 = 跳转「全部」过滤（同未分组，编辑态不触发）
                SettingsCardwithIcon(
                    items = listOf(
                        SettingsCardwithIconItem(
                            icon = R.drawable.ic_folder,
                            label = stringResource(R.string.todo_group_all),
                            value = totalCount.toString(),
                            onClick = { if (!editing && !tagEditing) onOpenAll() },
                        ),
                    ),
                )
                // 用户自定义分组：GroupCard（无图标行），计数 = 该组名的待办数；
                // 编辑态显示草稿、普通态显示真实列表
                if (displayGroups.isNotEmpty()) {
                    GroupCard(
                        items = displayGroups.map { group ->
                            // 分组以名字为身份键：多选/拖拽/删除时按 id 跟随
                            GroupCardItem(
                                id = group.name,
                                label = group.name,
                                value = todos.count { it.group == group.name }.toString(),
                                pinned = group.pinned,
                            )
                        },
                        editing = editing,
                        selectedIds = selectedIds,
                        // 标签编辑态在场时分组行不可再进编辑、点击不跳转（避免静默丢弃标签草稿）
                        onEnterEdit = { if (!tagEditing) enterEdit(it) },
                        // 普通态点击 = 跳转该分组（宿主收起浮层并切顶栏胶囊）；编辑态点击是多选切换
                        onItemClick = { if (!tagEditing) onOpenGroup(it) },
                        onToggleSelect = { id ->
                            selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                        },
                        // 拖拽落位同步重排草稿（GroupCard 契约）；置顶项由组件内锁序
                        onMove = { from, to -> draft = draft.move(from, to) },
                    )
                }
            }
            // 第三部分：标签。分组内容之后；无任何标签时整组（含标题）不渲染。课程标签
            // （只读）作锁定前缀、用户标签随后，行首挂 ic_tag、颜色跟随标签色（用户标签
            // colorArgb null 与课程标签都取主题 primary），计数 = 挂该标签的待办数。普通态
            // 点击行 = 跳转该标签的待办过滤页；长按 = 进入标签编辑态（与分组编辑态同构、
            // 互斥——分组编辑草稿期间点击与长按都不触发，同上方各行「编辑态不触发」约定）：
            // 多选批量删除、重命名（仅选中恰好一个用户标签时可点，多选/含课程标签置灰）、
            // 长按拖拽重排用户标签（顶栏「确认」写回 onTagsChange；课程标签锁定不可拖）
            if (displayTags.isNotEmpty()) {
                CardSection(title = stringResource(R.string.todo_section_tags)) {
                    GroupCard(
                        items = displayTags.map { tag ->
                            // 标签以名字为身份键（同 Todo.tags 的字符串引用模型）：多选/拖拽/
                            // 删除时按 id 跟随
                            GroupCardItem(
                                id = tag.name,
                                label = tag.name,
                                value = todos.count { tag.name in it.tags }.toString(),
                                // 课程标签只读：锁定前缀，编辑态里重命名/删除置灰
                                locked = tag.isCourse,
                                icon = R.drawable.ic_tag,
                                iconTint = tag.entry?.colorArgb?.let(::Color)
                                    ?: MaterialTheme.colorScheme.primary,
                            )
                        },
                        editing = tagEditing,
                        selectedIds = tagSelectedIds,
                        onEnterEdit = { if (!editing) enterTagEdit(it) },
                        // 普通态点击 = 跳转该标签（宿主收起浮层并切顶栏标签胶囊）；标签编辑态
                        // 点击是多选切换（GroupCard 内部路由）；分组编辑态点击不触发
                        onItemClick = { if (!editing) onOpenTag(it) },
                        onToggleSelect = { id ->
                            tagSelectedIds =
                                if (id in tagSelectedIds) tagSelectedIds - id else tagSelectedIds + id
                        },
                        // 拖拽落位同步重排草稿（GroupCard 契约）；课程标签锁定不可拖、落点
                        // 不进锁定区（GroupCard 内部钳制），标签无置顶无锁序
                        onMove = { from, to -> tagDraft = tagDraft.move(from, to) },
                    )
                }
            }
        }
    }

    // 删除确认框：挂在 OverlaySheet 调用之后（画到浮层卡片之上），后注册 BackHandler——
    // 返回键先到它：取消/返回只关确认框、留在浮层，关掉后才轮到浮层的出口。
    // 挂载与可见性分离（同浮层两段式关闭）：确认/取消先清请求播淡出，播完才卸载
    var deleteDialogMounted by remember { mutableStateOf(false) }
    val deleteDialogVisible = deleteTargets != null
    if (deleteDialogVisible) deleteDialogMounted = true
    // 淡出期间文案快照：确认后 targets 已清、草稿已删，播动画的卡片不能跟着变空
    var shownDeleteTargets by remember { mutableStateOf(emptyList<String>()) }
    if (deleteDialogVisible) shownDeleteTargets = deleteTargets.orEmpty()
    if (deleteDialogMounted) {
        DeleteConfirmDialog(
            title = stringResource(R.string.todo_groups_delete_title),
            message = if (shownDeleteTargets.size == 1) {
                stringResource(R.string.todo_groups_delete_message, shownDeleteTargets.first())
            } else {
                stringResource(R.string.todo_groups_delete_message_multi, shownDeleteTargets.size)
            },
            visible = deleteDialogVisible,
            onDismissed = { deleteDialogMounted = false },
            onCancel = { deleteTargets = null },
            onConfirm = {
                val names = deleteTargets
                deleteTargets = null
                if (!names.isNullOrEmpty()) {
                    // 立即生效：真实列表先删（不经顶栏取消撤销），草稿同步移除保持显示一致
                    onDeleteGroups(names)
                    val nameSet = names.toSet()
                    draft = draft.filterNot { it.name in nameSet }
                    selectedIds = selectedIds - nameSet
                    if (draft.isEmpty()) exitEdit()
                }
            },
        )
    }

    // 标签删除确认框（标签编辑态底栏「删除」触发）：两段式挂载同分组删除确认框，支持批量；
    // 确认 = 立即生效（onDeleteTags：宿主删标签并解绑全部待办，待办本身保留），草稿同步
    // 移除保持显示一致，删空自动退编辑；「取消」/返回只关确认框、留在编辑态
    var tagDeleteMounted by remember { mutableStateOf(false) }
    val tagDeleteVisible = tagDeleteTargets != null
    if (tagDeleteVisible) tagDeleteMounted = true
    // 淡出期间文案快照：确认后目标已清、草稿已删，播动画的卡片不能跟着变空
    var shownTagDeleteTargets by remember { mutableStateOf(emptyList<String>()) }
    if (tagDeleteVisible) shownTagDeleteTargets = tagDeleteTargets.orEmpty()
    if (tagDeleteMounted) {
        DeleteConfirmDialog(
            title = stringResource(R.string.todo_tag_delete_title),
            message = if (shownTagDeleteTargets.size == 1) {
                stringResource(R.string.todo_tag_delete_message, shownTagDeleteTargets.first())
            } else {
                stringResource(R.string.todo_tag_delete_message_multi, shownTagDeleteTargets.size)
            },
            visible = tagDeleteVisible,
            onDismissed = { tagDeleteMounted = false },
            onCancel = { tagDeleteTargets = null },
            onConfirm = {
                val names = tagDeleteTargets
                tagDeleteTargets = null
                if (!names.isNullOrEmpty()) {
                    // 立即生效：真实列表先删（不经顶栏取消撤销），草稿同步移除保持显示一致
                    onDeleteTags(names)
                    val nameSet = names.toSet()
                    tagDraft = tagDraft.filterNot { it.name in nameSet }
                    tagSelectedIds = tagSelectedIds - nameSet
                    if (tagDraft.isEmpty()) exitTagEdit()
                }
            },
        )
    }

    // 新建分组弹卡（[BottomTextFieldCard]）：叠加在浮层之后组合（画到浮层之上），
    // 返回键/遮罩点击/键盘让位由弹卡框架接管；挂载与可见性分离（同删除确认框）。
    // 挂载即自动聚焦弹键盘、整卡被键盘顶起；确认 = 追加分组经 onGroupsChange 整表写回落库，
    // 落库失败的 Toast 由宿主（TodoScreen 的 commitGroups）统一处理
    if (createMounted) {
        val createName = createDraft.trim()
        BottomTextFieldCard(
            visible = createRequested,
            onDismiss = { createRequested = false },
            onDismissed = { createMounted = false },
            title = stringResource(R.string.todo_group_create_title),
            placeholder = stringResource(R.string.todo_group_create_hint),
            value = createDraft,
            onValueChange = { createDraft = it },
            // 实时校验：空名或与现有分组重名时确认钮置灰（同前一版居中弹窗约定）
            confirmEnabled = createName.isNotEmpty() && createName !in groups.map { it.name },
            // 卡片磨砂：采样源同本浮层顶栏（见 sheetHazeState 声明处）
            hazeState = sheetHazeState,
            onConfirm = {
                createRequested = false
                onGroupsChange(groups + TodoGroup(createName), emptyMap())
            },
        )
    }

    // 重命名弹卡（[BottomTextFieldCard]，编辑态底栏「重命名」触发）：位置同新建弹卡。
    // **确认即立即生效**（同删除的模型，不经顶栏「取消」撤销）：真实列表改名写回
    // （顶栏选中与该组待办的归属经 renames 映射跟随到新名），草稿与选中集同步改名——
    // 草稿与真实列表的名字集合因此始终一致，删除/拖拽等按名对齐的流程不会失配；
    // 「取消」/遮罩/返回只关弹卡不改名。校验基准取真实分组表（名字集合与草稿一致，
    // 仅顺序/置顶可不同）：空名禁用；与原组同名 = 无操作（允许确认）；与其它组重名禁用
    if (renameMounted) {
        val renameName = renameDraft.trim()
        BottomTextFieldCard(
            visible = renameVisible,
            onDismiss = { renameVisible = false },
            onDismissed = { renameMounted = false },
            title = stringResource(R.string.todo_group_rename_title),
            placeholder = stringResource(R.string.todo_group_create_hint),
            value = renameDraft,
            onValueChange = { renameDraft = it },
            confirmEnabled = renameName.isNotEmpty() &&
                groups.none { it.name == renameName && it.name != renameFrom },
            // 卡片磨砂：采样源同本浮层顶栏（见 sheetHazeState 声明处）
            hazeState = sheetHazeState,
            onConfirm = {
                renameVisible = false
                val to = renameDraft.trim()
                if (to.isNotEmpty() && to != renameFrom) {
                    // 立即生效：真实列表改名写回 + 草稿/选中集同步（保持名字集合一致）
                    onGroupsChange(
                        groups.map { g -> if (g.name == renameFrom) g.copy(name = to) else g },
                        mapOf(renameFrom to to),
                    )
                    draft = draft.map { g -> if (g.name == renameFrom) g.copy(name = to) else g }
                    selectedIds = selectedIds.mapTo(mutableSetOf()) { if (it == renameFrom) to else it }
                }
            },
        )
    }

    // 标签重命名弹卡（标签编辑态底栏「重命名」触发，仅选中恰好一个时可点）：位置与校验
    // 同分组重命名弹卡——空名禁用；与原标签同名 = 无操作（允许确认）；与其它标签重名
    // 禁用（校验基准取真实标签表）。**确认即立即生效**：onRenameTag 交宿主先改标签库
    // 再回写全部待办（不经浮层顶栏取消撤销，同分组重命名/删除的立即生效模型），草稿与
    // 选中集同步改名；「取消」/遮罩/返回只关弹卡、留在编辑态
    if (tagRenameMounted) {
        val tagRenameName = tagRenameDraft.trim()
        BottomTextFieldCard(
            visible = tagRenameVisible,
            onDismiss = { tagRenameVisible = false },
            onDismissed = { tagRenameMounted = false },
            title = stringResource(R.string.todo_tag_rename_title),
            placeholder = stringResource(R.string.todo_tag_rename_hint),
            value = tagRenameDraft,
            onValueChange = { tagRenameDraft = it },
            confirmEnabled = tagRenameName.isNotEmpty() &&
                mergedTags.none { it.name == tagRenameName && it.name != tagRenameFrom },
            // 卡片磨砂：采样源同本浮层顶栏（见 sheetHazeState 声明处）
            hazeState = sheetHazeState,
            onConfirm = {
                tagRenameVisible = false
                val to = tagRenameDraft.trim()
                if (to.isNotEmpty() && to != tagRenameFrom) {
                    // 立即生效：真实标签表改名写回（宿主先改标签库再回写全部待办），
                    // 草稿与选中集同步改名（保持名字集合一致，同分组重命名）
                    onRenameTag(tagRenameFrom, to)
                    tagDraft = tagDraft.map { if (it.name == tagRenameFrom) it.copy(name = to) else it }
                    tagSelectedIds = tagSelectedIds.mapTo(mutableSetOf()) { if (it == tagRenameFrom) to else it }
                }
            },
        )
    }
}

/**
 * 编辑态底部操作栏（分组编辑态与标签编辑态共用，两者互斥由闸门保证）：置顶 / 重命名 /
 * 删除胶囊组，整块从底部滑入滑出。分组编辑态（[showPin] = true）多一颗置顶钮，标签编辑态
 * 只有重命名/删除（标签无置顶语义）；重命名按可用性着色（可点 = primary、置灰 = negative）
 * ——两组都是仅「选中恰好一个」时可用（[renameEnabled]，多选/空选置灰）；删除按
 * [hasSelection] 着色。胶囊的点击都只上报，行为由调用方接管。
 *
 * 进出场 = 浮层同源的 [Motion.Overlay] 非线性曲线（进 [Motion.SheetEnterMillis]、
 * 出 [Motion.SheetExitMillis]），与浮层本体同一套手感。渐变兜底 + 12/24 内距 +
 * 导航栏留白在这里自绘自持（OverlaySheet 传 `bottomScrim = false` 只给钉底定位）——
 * 规格随动画层整体移动，非编辑态不残留底部薄纱。胶囊参数同切换课表浮层的导入/导出按钮。
 */
@Composable
private fun EditActionRow(
    visible: Boolean,
    showPin: Boolean,
    pinAction: PinAction,
    hasSelection: Boolean,
    renameEnabled: Boolean,
    onPin: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(Motion.SheetEnterMillis, easing = Motion.Overlay)) { it } +
            fadeIn(tween(Motion.SheetEnterMillis, easing = Motion.Overlay)),
        exit = slideOutVertically(tween(Motion.SheetExitMillis, easing = Motion.Overlay)) { it } +
            fadeOut(tween(Motion.SheetExitMillis, easing = Motion.Overlay)),
        label = "todoGroupsEditActions",
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background.copy(alpha = 0f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                        ),
                    ),
                )
                .navigationBarsPadding()
                .padding(top = 12.dp, bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(30.dp, Alignment.CenterHorizontally),
            ) {
                // 置灰统一用 theme 的 negative（弱化规范色，非危险色）；可点时置顶蓝、删除红
                val negative = MaterialTheme.classppColors.negative
                val pinEnabled = pinAction != PinAction.Disabled
                if (showPin) {
                    SheetPillButton(
                        label = stringResource(
                            if (pinAction == PinAction.Unpin) R.string.settings_cancel else R.string.edit_pin,
                        ),
                        icon = if (pinAction == PinAction.Unpin) R.drawable.ic_pin_off else R.drawable.ic_pin,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = if (pinEnabled) MaterialTheme.colorScheme.primary else negative,
                        onClick = onPin,
                        enabled = pinEnabled,
                        iconSize = 25.dp,
                        topPadding = 10.dp,
                        bottomPadding = 10.dp,
                        startPadding = 15.dp,
                        endPadding = 10.dp,
                    )
                }
                SheetPillButton(
                    label = stringResource(R.string.edit_rename),
                    icon = R.drawable.ic_rename,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = if (renameEnabled) MaterialTheme.colorScheme.primary else negative,
                    onClick = onRename,
                    enabled = renameEnabled,
                    iconSize = 25.dp,
                    topPadding = 10.dp,
                    bottomPadding = 10.dp,
                    startPadding = 15.dp,
                    endPadding = 10.dp,
                )
                SheetPillButton(
                    label = stringResource(R.string.edit_delete),
                    icon = R.drawable.ic_delete,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = if (hasSelection) MaterialTheme.colorScheme.error else negative,
                    onClick = onDelete,
                    enabled = hasSelection,
                    iconSize = 25.dp,
                    topPadding = 10.dp,
                    bottomPadding = 10.dp,
                    startPadding = 15.dp,
                    endPadding = 10.dp,
                )
            }
        }
    }
}

/**
 * 删除确认框：[ConfirmDialogCard] 白卡 + 遮罩，叠加在浮层**之后**组合——返回键时后注册的
 * 它先收到，优先于浮层出口。分组删除 = 仅删分组（该组待办由仓库在同一次 setGroups 提交里
 * 级联置回未分组，不删除待办本身）；标签删除 = 删标签并解绑全部待办（同样不删待办本身）。
 * 标题/文案由调用方拼接（分组与标签各有单删/批删两套）；删除钮走 error（同删除课表/
 * 删除待办确认框先例）。
 */
@Composable
private fun DeleteConfirmDialog(
    title: String,
    message: String,
    visible: Boolean,
    onDismissed: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    FadeOverlayDialog(
        visible = visible,
        onDismiss = onCancel,
        onDismissed = onDismissed,
        cardHorizontalPadding = 32.dp,
    ) {
        ConfirmDialogCard(title = title, message = message) {
            DialogPillButton(
                label = stringResource(R.string.settings_cancel),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                enabled = visible,
                onClick = onCancel,
            )
            DialogPillButton(
                label = stringResource(R.string.edit_delete),
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                modifier = Modifier.weight(1f),
                enabled = visible,
                onClick = onConfirm,
            )
        }
    }
}

/** 置顶按钮三态：可点置顶 / 可点取消置顶 / 置灰（空选择或同时选中已置顶与未置顶） */
internal enum class PinAction { Pin, Unpin, Disabled }

/**
 * 置顶按钮状态判定：选中集内全未置顶 = [PinAction.Pin]、全已置顶 = [PinAction.Unpin]，
 * 空选择或混合（既有已置顶又有未置顶）= [PinAction.Disabled]。
 */
internal fun pinAction(groups: List<TodoGroup>, selected: Set<String>): PinAction {
    val chosen = groups.filter { it.name in selected }
    if (chosen.isEmpty()) return PinAction.Disabled
    return when {
        chosen.all { it.pinned } -> PinAction.Unpin
        chosen.none { it.pinned } -> PinAction.Pin
        else -> PinAction.Disabled
    }
}

/**
 * 置顶 / 取消置顶：改标记后做一次稳定划分（`filter { pinned } + filter { !pinned }`），
 * 维持 [TodoGroup] / [GroupCard] 的「置顶连续前缀」契约——
 * 新置顶项落到置顶前缀尾部（同批保持原相对序，前缀整体顺序即置顶操作顺序），
 * 取消置顶项按原相对序落回非置顶区（划分稳定）。
 */
internal fun applyPin(groups: List<TodoGroup>, selected: Set<String>, pinned: Boolean): List<TodoGroup> {
    val flagged = groups.map { if (it.name in selected) it.copy(pinned = pinned) else it }
    return flagged.filter { it.pinned } + flagged.filter { !it.pinned }
}

/** 列表重排（[GroupCard] 的 onMove 契约：调用方同步重排，分组表与标签表共用）；下标越界或原地则原样返回 */
internal fun <T> List<T>.move(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
