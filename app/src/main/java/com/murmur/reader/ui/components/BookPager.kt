package com.murmur.reader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.murmur.reader.ui.theme.BookPageDark
import com.murmur.reader.ui.theme.BookPageLight
import com.murmur.reader.ui.theme.BookTextDark
import com.murmur.reader.ui.theme.BookTextLight
import com.murmur.reader.ui.theme.MurmurHighlight
import com.murmur.reader.ui.theme.MurmurHighlightOnDark
import kotlinx.coroutines.launch

private const val PAGE_MARGIN_DP = 10

private data class PageData(
    val text: String,
    /** Global word index of the first word on this page. */
    val startWordCount: Int,
)

/**
 * Splits [text] into pages that each fit within [contentHeightPx] at the given [textStyle].
 * Returns a list of [PageData] — at minimum one page even if text is blank.
 */
private fun buildBookPages(
    textMeasurer: TextMeasurer,
    text: String,
    textStyle: TextStyle,
    contentWidthPx: Int,
    contentHeightPx: Int,
): List<PageData> {
    if (text.isBlank() || contentWidthPx <= 0 || contentHeightPx <= 0) {
        return listOf(PageData(text.trim(), 0))
    }

    val paragraphs = text.split(Regex("\\n+")).filter { it.isNotBlank() }
    val pages = mutableListOf<PageData>()
    val currentPageText = StringBuilder()
    var currentPageHeight = 0
    var globalWordCount = 0
    var pageStartWordCount = 0

    fun flushPage() {
        val content = currentPageText.toString().trimEnd()
        if (content.isNotBlank()) {
            pages.add(PageData(content, pageStartWordCount))
        }
        currentPageText.clear()
        currentPageHeight = 0
        pageStartWordCount = globalWordCount
    }

    for (para in paragraphs) {
        val paraWithBreak = "$para\n\n"
        val paraWordCount = para.split(Regex("\\s+")).count { it.isNotBlank() }

        val measuredHeight = try {
            textMeasurer.measure(
                text = paraWithBreak,
                style = textStyle,
                constraints = Constraints(maxWidth = contentWidthPx),
            ).size.height
        } catch (_: Exception) {
            globalWordCount += paraWordCount
            continue
        }

        if (measuredHeight > contentHeightPx) {
            // Paragraph taller than a full page — split by sentences
            flushPage()
            val sentences = para.split(Regex("(?<=[.!?…])\\s+"))
            for (sentence in sentences) {
                val sentText = "$sentence "
                val sentWordCount = sentence.split(Regex("\\s+")).count { it.isNotBlank() }
                val sentHeight = try {
                    textMeasurer.measure(
                        text = sentText,
                        style = textStyle,
                        constraints = Constraints(maxWidth = contentWidthPx),
                    ).size.height
                } catch (_: Exception) {
                    globalWordCount += sentWordCount
                    continue
                }
                if (currentPageHeight + sentHeight > contentHeightPx) {
                    flushPage()
                }
                currentPageText.append(sentText)
                currentPageHeight += sentHeight
                globalWordCount += sentWordCount
            }
        } else {
            if (currentPageHeight + measuredHeight > contentHeightPx) {
                flushPage()
            }
            currentPageText.append(paraWithBreak)
            currentPageHeight += measuredHeight
            globalWordCount += paraWordCount
        }
    }

    flushPage()
    return pages.ifEmpty { listOf(PageData(text.trim(), 0)) }
}

/**
 * A book-like paged reader with:
 * - Warm paper background (light) / dark e-reader background (dark)
 * - HorizontalPager with page-slide navigation
 * - Subtle vignette at page edges
 * - Tap left/right third to turn pages; swipe also works
 * - Auto-advances to the page containing the currently spoken word
 * - Page X of Y indicator at the bottom
 */
@Composable
fun BookPager(
    text: String,
    highlightedWordIndex: Int,
    fontSize: TextUnit,
    useSerifFont: Boolean,
    isDark: Boolean,
    initialPage: Int,
    onPageChange: (page: Int, total: Int) -> Unit,
    onWordTapped: (globalWordIndex: Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val textMeasurer = rememberTextMeasurer()

    val backgroundColor = if (isDark) BookPageDark else BookPageLight
    val textColor = if (isDark) BookTextDark else BookTextLight
    val fontFamily = if (useSerifFont) FontFamily.Serif else FontFamily.SansSerif
    val highlightColor = if (isDark) MurmurHighlightOnDark else MurmurHighlight

    Box(modifier = modifier.background(backgroundColor)) {
        // BoxWithConstraints is needed to get the pixel size for TextMeasurer
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {
            val pageWidthPx = constraints.maxWidth
            val pageHeightPx = constraints.maxHeight
            val localDensity = LocalDensity.current

            val marginPx = with(localDensity) { PAGE_MARGIN_DP.dp.roundToPx() }
            val pageIndicatorPx = with(localDensity) { 24.dp.roundToPx() }

            val contentWidthPx = (pageWidthPx - marginPx * 2).coerceAtLeast(1)
            val contentHeightPx = (pageHeightPx - marginPx * 2 - pageIndicatorPx).coerceAtLeast(1)

            val textStyle = TextStyle(
                fontFamily = fontFamily,
                fontSize = fontSize,
                lineHeight = (fontSize.value * 1.5f).sp,
                color = textColor,
                textIndent = TextIndent(firstLine = 24.sp),
            )

            val pages = remember(text, fontSize.value, contentWidthPx, contentHeightPx, useSerifFont) {
                buildBookPages(textMeasurer, text, textStyle, contentWidthPx, contentHeightPx)
            }

            val totalPages = pages.size.coerceAtLeast(1)
            val pagerState = rememberPagerState(
                initialPage = initialPage.coerceIn(0, totalPages - 1),
                pageCount = { totalPages },
            )

            // Notify caller of total pages on first composition
            LaunchedEffect(totalPages) {
                onPageChange(pagerState.currentPage, totalPages)
            }

            // Auto-advance when the highlighted word moves to a new page
            LaunchedEffect(highlightedWordIndex) {
                if (highlightedWordIndex < 0 || pages.isEmpty()) return@LaunchedEffect
                val targetPage = pages.indexOfLast { it.startWordCount <= highlightedWordIndex }
                    .coerceIn(0, pages.lastIndex)
                if (targetPage != pagerState.currentPage) {
                    pagerState.animateScrollToPage(targetPage)
                }
            }

            // Notify caller on manual swipe
            LaunchedEffect(pagerState.currentPage) {
                onPageChange(pagerState.currentPage, totalPages)
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
            ) { pageIndex ->
                val page = pages.getOrNull(pageIndex) ?: return@HorizontalPager
                val localWordIndex = highlightedWordIndex - page.startWordCount

                BookPage(
                    page = page,
                    localWordIndex = localWordIndex,
                    textStyle = textStyle,
                    highlightColor = highlightColor,
                    backgroundColor = backgroundColor,
                    isDark = isDark,
                    pageIndex = pageIndex,
                    totalPages = totalPages,
                    marginDp = PAGE_MARGIN_DP.dp,
                    onTapLeft = {
                        scope.launch {
                            if (pagerState.currentPage > 0)
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                        }
                    },
                    onTapRight = {
                        scope.launch {
                            if (pagerState.currentPage < totalPages - 1)
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    },
                    onWordTapped = { globalIdx -> onWordTapped(globalIdx) },
                )
            }
        }
    }
}

@Composable
private fun BookPage(
    page: PageData,
    localWordIndex: Int,
    textStyle: TextStyle,
    highlightColor: Color,
    backgroundColor: Color,
    isDark: Boolean,
    pageIndex: Int,
    totalPages: Int,
    marginDp: Dp,
    onTapLeft: () -> Unit,
    onTapRight: () -> Unit,
    onWordTapped: (globalWordIndex: Int) -> Unit = {},
) {
    val vignetteColor = if (isDark) Color.Black else Color(0xFF3C2A14)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.Transparent, vignetteColor.copy(alpha = 0.07f)),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.width * 0.88f,
                    )
                )
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(vignetteColor.copy(alpha = 0.10f), Color.Transparent),
                        startX = 0f,
                        endX = size.width * 0.055f,
                    )
                )
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, vignetteColor.copy(alpha = 0.07f)),
                        startX = size.width * 0.945f,
                        endX = size.width,
                    )
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = marginDp, vertical = marginDp)
        ) {
            PageText(
                text = page.text,
                localWordIndex = localWordIndex,
                startWordCount = page.startWordCount,
                textStyle = textStyle,
                highlightColor = highlightColor,
                onWordTapped = onWordTapped,
                onTapLeft = onTapLeft,
                onTapRight = onTapRight,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

            Text(
                text = "Page ${pageIndex + 1} of $totalPages",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = textStyle.color.copy(alpha = 0.40f),
                    fontFamily = textStyle.fontFamily,
                ),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun PageText(
    text: String,
    localWordIndex: Int,
    startWordCount: Int,
    textStyle: TextStyle,
    highlightColor: Color,
    onWordTapped: (globalWordIndex: Int) -> Unit,
    onTapLeft: () -> Unit,
    onTapRight: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = remember(text) { text.split(Regex("(?<=\\s)|(?=\\s)")) }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    var wordIdx = 0
    val annotated = buildAnnotatedString {
        tokens.forEach { token ->
            val isSpace = token.isBlank()
            val isHighlighted = !isSpace && localWordIndex >= 0 && wordIdx == localWordIndex
            if (isHighlighted) {
                withStyle(SpanStyle(background = highlightColor, color = Color.Black)) {
                    append(token)
                }
            } else {
                withStyle(SpanStyle(color = textStyle.color)) {
                    append(token)
                }
            }
            if (!isSpace) wordIdx++
        }
    }

    Text(
        text = annotated,
        style = textStyle,
        onTextLayout = { layoutResult = it },
        modifier = modifier.pointerInput(startWordCount) {
            awaitEachGesture {
                // requireUnconsumed = false so we receive events even when
                // HorizontalPager's scrollable consumes the initial down
                val down = awaitFirstDown(requireUnconsumed = false)
                val downPos = down.position // capture where finger first touched
                val up = waitForUpOrCancellation()
                if (up == null) return@awaitEachGesture // drag/swipe — let pager handle it

                val tapOffset = downPos // use down position for accurate word targeting
                val layout = layoutResult
                if (layout == null) {
                    val third = size.width / 3f
                    if (tapOffset.x < third) onTapLeft()
                    else if (tapOffset.x > third * 2) onTapRight()
                    return@awaitEachGesture
                }

                val charOffset = layout.getOffsetForPosition(tapOffset)

                // Map char offset → local word index
                var charPos = 0
                var wIdx = 0
                var tappedWord = false
                for (token in tokens) {
                    val tokenEnd = charPos + token.length
                    val isSpace = token.isBlank()
                    if (charOffset in charPos until tokenEnd) {
                        if (isSpace) break // tapped whitespace → page turn
                        up.consume()
                        onWordTapped(startWordCount + wIdx)
                        tappedWord = true
                        break
                    }
                    charPos = tokenEnd
                    if (!isSpace) wIdx++
                }

                if (!tappedWord) {
                    val third = size.width / 3f
                    if (tapOffset.x < third) onTapLeft()
                    else if (tapOffset.x > third * 2) onTapRight()
                }
            }
        },
    )
}
