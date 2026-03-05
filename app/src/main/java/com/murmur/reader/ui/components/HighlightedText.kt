package com.murmur.reader.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.murmur.reader.ui.theme.MurmurHighlight

/**
 * Displays text with a highlighted word at [highlightedWordIndex].
 *
 * [words] is the pre-split list of words from the document.
 * The word at [highlightedWordIndex] is shown with a yellow background.
 */
@Composable
fun HighlightedText(
    text: String,
    highlightedWordIndex: Int,
    fontSize: TextUnit = 18.sp,
    modifier: Modifier = Modifier,
) {
    // Split text into words, preserving whitespace between them
    val words = remember(text) { text.split(Regex("(?<=\\s)|(?=\\s)")) }

    var wordIdx = 0  // tracks which word we're at as we iterate tokens

    val annotated = buildAnnotatedString {
        words.forEach { token ->
            val isSpace = token.isBlank()
            val isHighlighted = !isSpace && wordIdx == highlightedWordIndex

            if (isHighlighted) {
                withStyle(
                    SpanStyle(
                        background = MurmurHighlight,
                        color = androidx.compose.ui.graphics.Color.Black,
                    )
                ) {
                    append(token)
                }
            } else {
                append(token)
            }

            if (!isSpace) wordIdx++
        }
    }

    val scrollState = rememberScrollState()

    // Auto-scroll when the highlight advances (rough heuristic — scroll by 20% per 50 words)
    LaunchedEffect(highlightedWordIndex) {
        if (highlightedWordIndex > 0 && words.isNotEmpty()) {
            val progress = highlightedWordIndex.toFloat() / words.count { it.isNotBlank() }.coerceAtLeast(1)
            val target = (scrollState.maxValue * progress).toInt()
            scrollState.animateScrollTo(target)
        }
    }

    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize),
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState),
        lineHeight = (fontSize.value * 1.6).sp,
    )
}
