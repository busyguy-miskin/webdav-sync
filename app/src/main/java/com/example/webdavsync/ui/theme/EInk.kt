package com.example.webdavsync.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 电子墨水屏卡片:以 1dp 深灰描边代替阴影(elevation)。
 *
 * 墨水屏上 elevation 阴影几乎不可见,改用清晰的轮廓边框,
 * 既能划分层次又避免残影。背景固定为纸白。
 *
 * @param prominent 是否使用更深的边框(主卡片)以增强层次
 */
@Composable
fun EInkCard(
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (prominent) MaterialTheme.colorScheme.outline
            else MaterialTheme.colorScheme.outlineVariant
        ),
        content = content
    )
}

/** 简易的居中纵向内容包装,常用于空状态/错误占位,与 [EInkCard] 风格一致。 */
@Composable
fun EInkPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier, content = content)
}
