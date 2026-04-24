package io.github.xororz.localdream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.TagMatchType
import io.github.xororz.localdream.data.TagSuggestion

@Composable
fun PromptTagTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: @Composable (() -> Unit),
    suggestions: List<TagSuggestion>,
    onSuggestionClick: (TagSuggestion) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showSuggestions: Boolean = true,
    onFocusChanged: (Boolean) -> Unit = {},
    highlightQuery: String? = null,
    maxCollapsedLines: Int = 2,
    minCollapsedLines: Int = 2,
    minExpandedLines: Int = 3
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorWidthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current

    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onFocusChanged(it.isFocused) }
                .onGloballyPositioned { coords -> anchorWidthPx = coords.size.width },
            enabled = enabled,
            label = label,
            maxLines = if (expanded) Int.MAX_VALUE else maxCollapsedLines,
            minLines = if (expanded) minExpandedLines else minCollapsedLines,
            shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            ),
            trailingIcon = {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null
                    )
                }
            }
        )
    }

    if (showSuggestions && suggestions.isNotEmpty() && anchorWidthPx > 0) {
        val widthDp = with(density) { anchorWidthPx.toDp() }
        Popup(
            popupPositionProvider = remember { AnchorPositionProvider() },
            properties = PopupProperties(
                focusable = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            Card(
                modifier = Modifier.width(widthDp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    suggestions.forEach { suggestion ->
                        SuggestionRow(
                            suggestion = suggestion,
                            highlightQuery = highlightQuery,
                            onClick = { onSuggestionClick(suggestion) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    suggestion: TagSuggestion,
    highlightQuery: String?,
    onClick: () -> Unit
) {
    val displayPrimary = suggestion.primaryText.replace('_', ' ')
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = categoryColor(suggestion.category),
                    shape = CircleShape
                )
        )
        Spacer(Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = highlightSubstring(displayPrimary, highlightQuery),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            suggestion.secondaryText?.takeIf { it.isNotBlank() }?.let { secondary ->
                Text(
                    text = highlightSubstring(secondary, highlightQuery),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (suggestion.postCount > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatPostCount(suggestion.postCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        MatchTypeBadge(suggestion.matchType)
    }
}

@Composable
private fun MatchTypeBadge(matchType: TagMatchType) {
    val label = when (matchType) {
        TagMatchType.Alias -> stringResource(R.string.tag_alias_label)
        TagMatchType.Correction -> stringResource(R.string.tag_correction_label)
        else -> return
    }
    val container = when (matchType) {
        TagMatchType.Correction -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val onContainer = when (matchType) {
        TagMatchType.Correction -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Spacer(Modifier.width(8.dp))
    Card(
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = onContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun categoryColor(category: Int): Color = when (category) {
    1 -> Color(0xFFE53935) // artist
    3 -> Color(0xFFAB47BC) // copyright
    4 -> Color(0xFF43A047) // character
    5 -> Color(0xFFFB8C00) // meta
    else -> MaterialTheme.colorScheme.outline // general / unknown
}

private fun formatPostCount(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 10_000 -> "${n / 1_000}k"
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}

private fun normalizeForHighlight(value: String): String =
    value.lowercase().replace(' ', '_').replace('-', '_')

private fun highlightSubstring(text: String, query: String?): AnnotatedString {
    if (query.isNullOrBlank()) return AnnotatedString(text)
    val normText = normalizeForHighlight(text)
    val normQuery = normalizeForHighlight(query.trim())
    if (normQuery.isEmpty()) return AnnotatedString(text)
    val idx = normText.indexOf(normQuery)
    if (idx < 0) return AnnotatedString(text)
    val end = (idx + normQuery.length).coerceAtMost(text.length)
    return buildAnnotatedString {
        append(text.substring(0, idx))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(text.substring(idx, end))
        }
        append(text.substring(end))
    }
}

private class AnchorPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val gap = 8
        val belowY = anchorBounds.bottom + gap
        val aboveY = anchorBounds.top - popupContentSize.height - gap
        val y = if (belowY + popupContentSize.height <= windowSize.height) {
            belowY
        } else if (aboveY >= 0) {
            aboveY
        } else {
            belowY.coerceAtMost(windowSize.height - popupContentSize.height).coerceAtLeast(0)
        }
        val x = anchorBounds.left.coerceIn(
            0,
            (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        )
        return IntOffset(x, y)
    }
}
