package com.zqr.freenote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zqr.freenote.data.NoteColor
import com.zqr.freenote.ui.common.toColor

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPicker(
    selectedColor: Long,
    onColorSelected: (Long) -> Unit
) {
    // FlowRow：8 个 48dp 触控目标在窄屏上自动换行，避免最后一个颜色被裁掉
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        NoteColor.DEFAULT_COLORS.forEach { color ->
            val isSelected = color == selectedColor
            // 48dp 触控目标 + selectable 语义：TalkBack 可朗读「已选中/未选中」并可点选
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onColorSelected(color) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(NoteColor.toColor(color)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        )
                    }
                }
            }
        }
    }
}
