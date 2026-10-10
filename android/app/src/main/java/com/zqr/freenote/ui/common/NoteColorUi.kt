package com.zqr.freenote.ui.common

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.zqr.freenote.data.NoteColor

// 必须用 Color(Long)（内部做 shl 32 打包成 ARGB 编码）而非 Color(ULong) 原始构造：
// 后者把低 6 位当 colorSpace id，0xFFBBDEFB 这类颜色低 6 位 ≥ 18 会索引 ColorSpaces 数组越界崩溃
fun NoteColor.toColor(value: Long): Color = Color(value)

/**
 * 便签底色按主题适配：浅色主题原样使用粉彩色；
 * 深色主题下把浅色粉彩向暗底色混合（保留色相倾向），
 * 否则浅底 + 浅色 onSurface 文字不可读。
 */
fun NoteColor.themedColor(value: Long, scheme: ColorScheme): Color {
    val raw = Color(value)
    val schemeIsDark = scheme.background.luminance() < 0.5f
    if (!schemeIsDark) return raw
    return lerp(raw, scheme.surface, 0.72f)
}
