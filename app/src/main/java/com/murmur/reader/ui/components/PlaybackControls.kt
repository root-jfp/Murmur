package com.murmur.reader.ui.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.murmur.reader.R
import com.murmur.reader.tts.TtsState
import kotlin.math.roundToInt

@Composable
fun PlaybackControls(
    state: TtsState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrev: () -> Unit,
    modifier: Modifier = Modifier,
    ratePercent: Float = 0f,
    onRateChange: ((Float) -> Unit)? = null,
    leadingAction: @Composable (() -> Unit)? = null,
) {
    // Track whether the user is currently dragging to change speed
    var isDragging by remember { mutableStateOf(false) }
    var dragRate by remember { mutableStateOf(ratePercent) }
    val density = LocalDensity.current

    Surface(
        tonalElevation = 4.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // State label — left-aligned, takes remaining space
            val stateLabel = when (state) {
                is TtsState.Idle -> stringResource(R.string.state_idle)
                is TtsState.Connecting -> stringResource(R.string.state_connecting)
                is TtsState.Synthesizing -> stringResource(
                    R.string.state_synthesizing,
                    state.chunkIndex + 1,
                    state.totalChunks
                )
                is TtsState.Playing -> stringResource(R.string.state_playing)
                is TtsState.Paused -> stringResource(R.string.state_paused)
                is TtsState.Error -> stringResource(R.string.state_error, state.message)
            }
            if (leadingAction != null) {
                leadingAction()
            }

            val displayText = if (isDragging) {
                val factor = 1.0 + (dragRate.roundToInt() / 100.0)
                "Speed: ${"%.1fx".format(factor)}"
            } else {
                stateLabel
            }

            val labelModifier = Modifier
                .weight(1f)
                .padding(start = if (leadingAction != null) 0.dp else 8.dp)
                .let { mod ->
                    if (onRateChange != null) {
                        mod.pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    isDragging = true
                                    dragRate = ratePercent
                                },
                                onDragEnd = {
                                    isDragging = false
                                },
                                onDragCancel = {
                                    isDragging = false
                                },
                                onHorizontalDrag = { _, dragAmount ->
                                    // Convert px to dp, then map: 1dp ≈ 0.5% rate change
                                    val dpDelta = with(density) { dragAmount.toDp().value }
                                    dragRate = (dragRate + dpDelta * 0.5f).coerceIn(-50f, 200f)
                                    onRateChange(dragRate)
                                },
                            )
                        }
                    } else mod
                }

            Text(
                text = displayText,
                style = MaterialTheme.typography.labelSmall,
                color = if (isDragging) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = labelModifier,
            )

            // Skip back
            IconButton(onClick = onSkipPrev, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = stringResource(R.string.action_skip_prev),
                    modifier = Modifier.size(24.dp)
                )
            }

            // Stop
            IconButton(onClick = onStop, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = stringResource(R.string.action_stop),
                    modifier = Modifier.size(24.dp)
                )
            }

            // Play / Pause (main button)
            FilledIconButton(
                onClick = if (state is TtsState.Playing) onPause else onPlay,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = if (state is TtsState.Playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state is TtsState.Playing)
                        stringResource(R.string.action_pause)
                    else
                        stringResource(R.string.action_play),
                    modifier = Modifier.size(26.dp)
                )
            }

            // Skip forward
            IconButton(onClick = onSkipNext, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = stringResource(R.string.action_skip_next),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
