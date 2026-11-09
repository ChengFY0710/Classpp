package com.fangyi.classpp.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主题色板守卫：默认蓝必须与改动前逐位一致；半透明派生必须复现既有蓝值；
 * 各档 primary/onPrimary 对比度不得低于 WCAG AA 的 4.5:1。
 */
class ThemeAccentTest {

    @Test
    fun defaultBlueReusesExistingTokens() {
        assertEquals(Primary, ThemeAccent.Blue.primary(false))
        assertEquals(OnPrimary, ThemeAccent.Blue.onPrimary(false))
        assertEquals(PrimaryContainerSolid, ThemeAccent.Blue.containerSolid(false))
        assertEquals(Background, ThemeAccent.Blue.background(false))
        assertEquals(DarkPrimary, ThemeAccent.Blue.primary(true))
        assertEquals(DarkOnPrimary, ThemeAccent.Blue.onPrimary(true))
        assertEquals(DarkPrimaryContainerSolid, ThemeAccent.Blue.containerSolid(true))
        assertEquals(DarkBackground, ThemeAccent.Blue.background(true))
        assertEquals(Scrim, ThemeAccent.Blue.scrim(false))
        assertEquals(DarkScrim, ThemeAccent.Blue.scrim(true))
        assertEquals(SecondaryText, ThemeAccent.Blue.secondaryText(false))
        assertEquals(DarkSecondaryText, ThemeAccent.Blue.secondaryText(true))
        assertEquals(Surface, ThemeAccent.Blue.surface(false))
        assertEquals(DarkSurface, ThemeAccent.Blue.surface(true))
        assertEquals(Outline, ThemeAccent.Blue.outline(false))
        assertEquals(DarkOutline, ThemeAccent.Blue.outline(true))
        assertEquals(SecondaryGrey, ThemeAccent.Blue.secondaryGrey(false))
        assertEquals(DarkSecondaryGrey, ThemeAccent.Blue.secondaryGrey(true))
        assertEquals(OnSurfaceCard, ThemeAccent.Blue.onSurfaceCard(false))
        assertEquals(DarkOnSurfaceCard, ThemeAccent.Blue.onSurfaceCard(true))
    }

    @Test
    fun backgroundsFollowHslRule() {
        // 非默认档也必须是「H 取主色、S/L 取中性底」：绿色主色 H≈143° 的结果
        assertEquals(Color(0xFFF2F6F4), ThemeAccent.Green.background(false))
        assertEquals(Color(0xFF0F1411), ThemeAccent.Green.background(true))
    }

    @Test
    fun secondaryTextFollowsHslRule() {
        // 非默认档也必须是「H 取主色、S/L 取默认灰」：绿色主色 H≈143° 的结果
        assertEquals(Color(0xFFABB4AE), ThemeAccent.Green.secondaryText(false))
        assertEquals(Color(0xFF7E8D83), ThemeAccent.Green.secondaryText(true))
    }

    @Test
    fun outlineFollowsHslRuleForNonDefaultAccents() {
        // 绿色：H 取主色，S/L 取默认 outline
        assertEquals(Color(0xFFDCE7E0), ThemeAccent.Green.outline(false))
        assertEquals(Color(0xFF2A362E), ThemeAccent.Green.outline(true))
    }

    @Test
    fun secondaryGreyFollowsHslRuleForNonDefaultAccents() {
        // 绿色：H 取主色，S/L 取默认灰
        assertEquals(Color(0xFF606C65), ThemeAccent.Green.secondaryGrey(false))
        assertEquals(Color(0xFFC3CAC5), ThemeAccent.Green.secondaryGrey(true))
    }

    @Test
    fun onSurfaceCardFollowsHslRuleForNonDefaultAccents() {
        // 绿色：H 取主色，S/L 取默认值
        assertEquals(Color(0xFFDCE7E0), ThemeAccent.Green.onSurfaceCard(false))
        assertEquals(Color(0xFF4D5750), ThemeAccent.Green.onSurfaceCard(true))
    }

    @Test
    fun darkSurfaceFollowsHslRuleAndLightSurfaceStaysWhite() {
        ThemeAccent.entries.forEach { accent ->
            assertEquals(Surface, accent.surface(false))
        }
        // 默认蓝与厦大蓝保持 #292D32 的原 S (9.89%)
        assertEquals(Color(0xFF292C32), ThemeAccent.XmuBlue.surface(true))
        // 其余档位 S 降至 6%，避免低明度底过艳
        assertEquals(Color(0xFF2B3030), ThemeAccent.Teal.surface(true))
        assertEquals(Color(0xFF2B302D), ThemeAccent.Green.surface(true))
        assertEquals(Color(0xFF2C2B30), ThemeAccent.Purple.surface(true))
        assertEquals(Color(0xFF302D2B), ThemeAccent.Orange.surface(true))
        assertEquals(Color(0xFF302B2C), ThemeAccent.Pink.surface(true))
        assertEquals(Color(0xFF302B2B), ThemeAccent.SduRed.surface(true))
    }

    @Test
    fun everyAccentKeepsScrimAlpha() {
        val lightAlpha = ThemeAccent.Blue.scrim(false).alpha
        val darkAlpha = ThemeAccent.Blue.scrim(true).alpha
        ThemeAccent.entries.forEach { accent ->
            assertTrue(
                "${accent.name} 浅色 scrim alpha 应保持 $lightAlpha",
                accent.scrim(false).alpha == lightAlpha,
            )
            assertTrue(
                "${accent.name} 深色 scrim alpha 应保持 $darkAlpha",
                accent.scrim(true).alpha == darkAlpha,
            )
        }
    }

    @Test
    fun translucentDerivationReproducesExistingBlueTokens() {
        assertEquals(PrimaryContainer, Primary.copy(alpha = PrimaryContainerAlphaLight))
        assertEquals(InversePrimary, Primary.copy(alpha = InversePrimaryAlphaLight))
        assertEquals(DarkPrimaryContainer, DarkPrimary.copy(alpha = PrimaryContainerAlphaDark))
        assertEquals(DarkInversePrimary, DarkPrimary.copy(alpha = InversePrimaryAlphaDark))
    }

    @Test
    fun everyAccentMeetsAaContrastBetweenPrimaryAndOnPrimary() {
        ThemeAccent.entries.forEach { accent ->
            assertTrue(
                "${accent.name} 浅色 primary/onPrimary 对比度不足 4.5:1",
                contrastRatio(accent.primary(false), accent.onPrimary(false)) >= 4.5,
            )
            assertTrue(
                "${accent.name} 深色 primary/onPrimary 对比度不足 4.5:1",
                contrastRatio(accent.primary(true), accent.onPrimary(true)) >= 4.5,
            )
        }
    }

    private fun contrastRatio(first: Color, second: Color): Double {
        val firstLuminance = first.luminance().toDouble()
        val secondLuminance = second.luminance().toDouble()
        return (maxOf(firstLuminance, secondLuminance) + 0.05) /
            (minOf(firstLuminance, secondLuminance) + 0.05)
    }
}
