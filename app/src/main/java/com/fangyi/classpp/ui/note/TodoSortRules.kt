package com.fangyi.classpp.ui.note

import com.fangyi.classpp.data.TodoSort
import com.fangyi.classpp.data.model.TodoGroup
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 一个常规分组页的排序规则：排序键 [sort] + 方向 [descending]。
 *
 * [descending] 的口径与 [TodoSort.comparator] 一致（true = 降序 = 「更要紧的在前」）：
 * 截止日期降序 = 截止早的在前、无截止的排最后。默认 [TodoSort.Deadline] + true，
 * 即每个分组页开箱即用的「截止日期降序」。
 */
data class TodoSortRule(
    val sort: TodoSort = TodoSort.Deadline,
    val descending: Boolean = true,
)

/** 「全部」页的规则键（哨兵；自定义分组统一加 `group:` 前缀，不会与组名撞名） */
internal const val AllSortRuleKey = "all"

/** 「未分组」页的规则键（哨兵，同 [AllSortRuleKey]） */
internal const val UngroupedSortRuleKey = "ungrouped"

/** 自定义分组规则键的前缀：`group:<组名>`——组名叫 all / ungrouped 也不会与哨兵冲突 */
private const val GroupSortKeyPrefix = "group:"

/** 自定义分组 [name] 的规则键（分组身份按名字；重命名由 [remapSortRules] 搬移） */
internal fun groupSortRuleKey(name: String): String = GroupSortKeyPrefix + name

/**
 * 顶栏分组页签 → 排序规则键：0 = 全部、1 = 未分组、2 起 = `groups[index - 2]`
 * （与 [filterTodosByGroup] / TodoTopBar 的胶囊下标同口径）。下标越界（分组被删后
 * 残留的保存态等）返回 null，调用方回落全局排序。
 */
internal fun sortRuleKey(groupIndex: Int, groups: List<TodoGroup>): String? = when (groupIndex) {
    0 -> AllSortRuleKey
    1 -> UngroupedSortRuleKey
    else -> groups.getOrNull(groupIndex - 2)?.let { groupSortRuleKey(it.name) }
}

/**
 * 页签 token（[sortRuleKey] 编码）→ 顶栏分组下标的逆映射：冷启动恢复「上次选中的分组」
 * 用（token 由 [TodoTopBarPreferences] 落盘，与排序规则共用同一套分组身份编码，重排/
 * 改名不漂移）。组已删（组名找不到）与不认识的 token（旧版本写入等）回落 0（全部），
 * 与 [filterTodosByGroup] 的下标越界兜底同口径。
 */
internal fun selectedGroupIndexOf(token: String?, groups: List<TodoGroup>): Int = when {
    token == null || token == AllSortRuleKey -> 0
    token == UngroupedSortRuleKey -> 1
    else -> if (token.startsWith(GroupSortKeyPrefix)) {
        val name = token.removePrefix(GroupSortKeyPrefix)
        val index = groups.indexOfFirst { it.name == name }
        if (index >= 0) index + 2 else 0
    } else {
        0
    }
}

/**
 * 取 [groupIndex] 对应页签的排序规则：有单独设置用设置，没有（新建分组、升级前的
 * 老数据）回落 [fallback]——调用方传全局排序，其默认值即截止日期降序，所以没单独
 * 设置过的页签行为与升级前完全一致。
 */
internal fun Map<String, TodoSortRule>.sortRuleFor(
    groupIndex: Int,
    groups: List<TodoGroup>,
    fallback: TodoSortRule,
): TodoSortRule = sortRuleKey(groupIndex, groups)?.let { this[it] } ?: fallback

/**
 * 分组写回（[com.fangyi.classpp.data.TodoRepository.setGroups]）后的规则表维护：
 * - [renames]（原名 → 新名）：规则键跟着搬到新名，重命名不重置该组的排序；
 * - [removedNames]：被删分组的规则键清掉；新建/重排/置顶没有名字变化，原样保留。
 *
 * 单次遍历完成，不做链式搬移（a→b、b→c 只按原表搬一次）；同一新名下已有残留规则时
 * 以被改名分组的规则为准（它才是这个分组的当前选择）。
 */
internal fun remapSortRules(
    rules: Map<String, TodoSortRule>,
    renames: Map<String, String>,
    removedNames: Set<String>,
): Map<String, TodoSortRule> {
    if (renames.isEmpty() && removedNames.isEmpty()) return rules
    val removedKeys = removedNames.mapTo(mutableSetOf(), ::groupSortRuleKey)
    val renamedTargetKeys = renames.values.mapTo(mutableSetOf(), ::groupSortRuleKey)
    return buildMap {
        // 先落被改名分组的规则（占用新名，压过新名下可能残留的旧规则）
        for ((from, to) in renames) {
            rules[groupSortRuleKey(from)]?.let { put(groupSortRuleKey(to), it) }
        }
        for ((key, rule) in rules) {
            if (key in removedKeys || key in renamedTargetKeys) continue
            // 原组的键已搬到新名（组名与哨兵同名的极端情况下不误删哨兵）
            if (key.startsWith(GroupSortKeyPrefix) &&
                key.removePrefix(GroupSortKeyPrefix) in renames
            ) {
                continue
            }
            put(key, rule)
        }
    }
}

/** 规则表的 prefs 落盘形态：排序键存枚举名（前向兼容靠 [decodeSortRules] 的回落） */
@Serializable
private data class TodoSortRuleDto(
    val sort: String = TodoSort.Deadline.name,
    val descending: Boolean = true,
)

private val sortRuleJson = Json { ignoreUnknownKeys = true }

/** 规则表编码：组名作 JSON object key，任意字符（含 `:`、`,`、引号）都安全 */
internal fun encodeSortRules(rules: Map<String, TodoSortRule>): String =
    sortRuleJson.encodeToString(
        rules.mapValues { (_, rule) ->
            TodoSortRuleDto(sort = rule.sort.name, descending = rule.descending)
        },
    )

/**
 * 规则表解码：无记录 / 记录损坏返回空表（各页签回落全局排序）；单项的排序名无法识别
 * （未来枚举增删、旧版本写入）回落 [TodoSort.Deadline]，方向照读——与 [TodoSortRule]
 * 的默认值同口径。
 */
internal fun decodeSortRules(raw: String?): Map<String, TodoSortRule> {
    if (raw.isNullOrEmpty()) return emptyMap()
    return try {
        sortRuleJson.decodeFromString<Map<String, TodoSortRuleDto>>(raw).mapValues { (_, dto) ->
            TodoSortRule(
                sort = TodoSort.entries.firstOrNull { it.name == dto.sort } ?: TodoSort.Deadline,
                descending = dto.descending,
            )
        }
    } catch (e: SerializationException) {
        emptyMap()
    }
}
