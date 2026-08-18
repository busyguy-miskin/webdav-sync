package com.example.webdavsync.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 电子墨水屏(e-ink)配色方案。
 *
 * 设计原则:
 * 1. 纯灰阶——墨水屏无法稳定呈现彩色,蓝/绿/红都会被渲染成模糊的灰。
 *    主动使用黑白灰可保证边缘锐利、对比明确、残影(ghosting)更轻。
 * 2. 强制浅色——墨水屏是反射式(白底),深色主题会显得灰蒙蒙且更易残影。
 * 3. 以「描边」代替「阴影」——e-ink 上 elevation 阴影几乎不可见,
 *    故卡片/分隔一律使用 outline 描边来划分层次。
 * 4. 状态语义不依赖颜色——成功/失败用符号(✓/✗)+ 粗体区分,
 *    而非仅靠色彩(详见各 Screen)。
 */
private val InkBlack = Color(0xFF000000)
private val InkPaper = Color(0xFFFFFFFF)
private val InkText = Color(0xFF1A1A1A)      // 主要正文:近黑
private val InkMuted = Color(0xFF555555)      // 次要文字/说明:中灰
private val InkBar = Color(0xFFEDEDED)        // 顶栏/容器底色:浅灰
private val InkSurfaceVariant = Color(0xFFF2F2F2)
private val InkOutline = Color(0xFF9A9A9A)    // 描边:中深灰
private val InkOutlineLight = Color(0xFFCCCCCC)

/**
 * 电子墨水屏配色。所有彩色 token 全部映射为灰阶:
 * - primary / 错误 = 纯黑(最高对比、最抓眼)
 * - secondary = 深灰(成功/统计类信息,比黑稍弱但仍清晰)
 * - surfaceVariant / outline 提供层次与描边
 */
private val EInkColors = lightColorScheme(
    primary = InkBlack,
    onPrimary = InkPaper,
    primaryContainer = InkBar,
    onPrimaryContainer = InkBlack,
    secondary = Color(0xFF333333),
    onSecondary = InkPaper,
    secondaryContainer = Color(0xFFE0E0E0),
    onSecondaryContainer = InkBlack,
    tertiary = Color(0xFF666666),
    onTertiary = InkPaper,
    tertiaryContainer = InkSurfaceVariant,
    onTertiaryContainer = InkBlack,
    background = InkPaper,
    onBackground = InkText,
    surface = InkPaper,
    onSurface = InkText,
    surfaceVariant = InkSurfaceVariant,
    onSurfaceVariant = InkMuted,
    surfaceTint = InkBlack,
    inverseSurface = InkText,
    inverseOnSurface = InkPaper,
    error = InkBlack,
    onError = InkPaper,
    errorContainer = InkBar,
    onErrorContainer = InkBlack,
    outline = InkOutline,
    outlineVariant = InkOutlineLight,
    scrim = InkBlack
)

/**
 * 应用主题。[darkTheme] 参数保留兼容但被忽略——墨水屏一律使用浅色。
 */
@Composable
fun WebDavSyncTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 透明状态栏,让纸白背景铺满;状态栏图标保持深色(浅底)
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }
    MaterialTheme(colorScheme = EInkColors, content = content)
}
