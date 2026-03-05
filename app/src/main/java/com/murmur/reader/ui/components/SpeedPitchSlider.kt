package com.murmur.reader.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.murmur.reader.R
import kotlin.math.roundToInt

@Composable
fun SpeedSlider(
    ratePercent: Float,
    onRateChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.label_speed),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatRate(ratePercent),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = ratePercent,
            onValueChange = onRateChange,
            valueRange = -50f..200f,  // -50% (slower) to +200% (3x speed)
            steps = 0,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        )
    }
}

@Composable
fun PitchSlider(
    pitchHz: Float,
    onPitchChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.label_pitch),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatPitch(pitchHz),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = pitchHz,
            onValueChange = onPitchChange,
            valueRange = -50f..50f,
            steps = 0,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        )
    }
}

private fun formatRate(percent: Float): String {
    val v = percent.roundToInt()
    return if (v == 0) "1.0x" else {
        val factor = 1.0 + (v / 100.0)
        "%.1fx".format(factor)
    }
}

private fun formatPitch(hz: Float): String {
    val v = hz.roundToInt()
    return if (v >= 0) "+${v}Hz" else "${v}Hz"
}
