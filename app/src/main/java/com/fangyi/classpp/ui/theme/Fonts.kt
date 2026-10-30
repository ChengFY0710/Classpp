package com.fangyi.classpp.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.fangyi.classpp.R

/**
 * 展示用衬线：思源宋体（Source Han Serif CN）Heavy 子集，SIL OFL 授权随包分发。
 * 供日程页大日期数字与节日/节气文案使用。字表与再生成脚本在仓库 tools/fonts/——
 * 子集缺字不会报错，运行时会静默回退系统无衬线，新用字必须先进字表重新生成。
 */
val SerifDisplay = FontFamily(
    Font(R.font.source_han_serif_heavy_subset, FontWeight.Black),
)
