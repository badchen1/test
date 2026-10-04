package com.kugo.mobile.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 可展开的长文本。
 *
 * 交互模式「Animated Text Disclosure」：
 *  - 收起时按 [collapsedMaxLines] 截断，尾部落一个「展开」；
 *  - 展开时高度用 [animateContentSize] 连续过渡，而不是瞬变；
 *  - 箭头做 0→180° 的旋转，给状态变化一个视觉锚点。
 *
 * 工程约束：文本长度不足以触发截断时**不渲染展开按钮**，避免出现
 * 「点了没反应」的死按钮——这是这类组件最常见的实现缺陷。
 */
@Composable
fun ExpandableText(
    text: String,
    modifier: Modifier = Modifier,
    collapsedMaxLines: Int = 1,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = Color.Unspecified,
    /**
     * 字符数超过这个阈值才认为「值得展开」。
     * 中文一行约 20 字，超过两行才给按钮，否则是噪音。
     */
    minLengthForToggle: Int = 44,
) {
    var expanded by remember(text) { mutableStateOf(false) }
    val collapsible = text.length > minLengthForToggle

    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "disclosureArrow",
    )

    Column(
        modifier = modifier.animateContentSize(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        ),
    ) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = if (expanded || !collapsible) Int.MAX_VALUE else collapsedMaxLines,
            overflow = TextOverflow.Ellipsis,
        )

        if (collapsible) {
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (color == Color.Unspecified) MaterialTheme.colorScheme.primary else color,
                )
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier
                        .size(14.dp)
                        .rotate(arrowRotation),
                    tint = if (color == Color.Unspecified) MaterialTheme.colorScheme.primary else color,
                )
            }
        }
    }
}

/** 一行「标签：值」的可展开文本，供设置页复用。 */
@Composable
fun ExpandableLabeledText(
    label: String,
    text: String,
    modifier: Modifier = Modifier,
    collapsedMaxLines: Int = 1,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(3.dp))
        ExpandableText(
            text = text,
            collapsedMaxLines = collapsedMaxLines,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
