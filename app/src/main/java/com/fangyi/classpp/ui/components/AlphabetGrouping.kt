package com.fangyi.classpp.ui.components

/**
 * 首字母分组结果：组标题（A…Z，或兜底组的 [AlphabetOtherLetter]）与该组条目，
 * 组内保持入参顺序。
 */
internal data class AlphabetGroup<T>(
    val letter: String,
    val items: List<T>,
)

/** 取不到 A–Z 首字母的条目统一进 `#` 组，排在所有字母之后 */
internal const val AlphabetOtherLetter = "#"

/**
 * 按 [letterOf] 给出的组标题分组：字母 A→Z 依次排列，`#` 最后；组内保持入参顺序。
 *
 * [letterOf] 应返回单个大写字母或 [AlphabetOtherLetter]；这里不对返回值再做归一，
 * 调用方负责把名称/首字母串解析成组标题（见 [firstLatinLetter]）。
 */
internal fun <T> groupInAlphabetOrder(
    items: List<T>,
    letterOf: (T) -> String,
): List<AlphabetGroup<T>> =
    items
        .groupBy(letterOf)
        .map { (letter, group) -> AlphabetGroup(letter, group) }
        .sortedWith(
            compareBy(
                { if (it.letter == AlphabetOtherLetter) 1 else 0 },
                { it.letter },
            ),
        )

/** 名称/首字母串里的首个拉丁字母（大小写均可）；用于归一成组标题 */
internal fun String.firstLatinLetter(): Char? =
    firstOrNull { it in 'A'..'Z' || it in 'a'..'z' }
