package com.murmur.reader.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.murmur.reader.R
import com.murmur.reader.data.preferences.FontFamilyOption
import com.murmur.reader.data.preferences.ThemeMode
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.nav_settings)) })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_section_display),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            // Theme mode selector
            Text(
                text = stringResource(R.string.setting_theme),
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                ThemeChip(
                    label = stringResource(R.string.theme_light),
                    selected = prefs.themeMode == ThemeMode.LIGHT,
                    bgPreview = Color(0xFFFFF8F0),
                    fgPreview = Color(0xFF2C2318),
                    onClick = { viewModel.setThemeMode(ThemeMode.LIGHT) },
                )
                ThemeChip(
                    label = stringResource(R.string.theme_dark),
                    selected = prefs.themeMode == ThemeMode.DARK,
                    bgPreview = Color(0xFF1C1B1F),
                    fgPreview = Color(0xFFD4C5A9),
                    onClick = { viewModel.setThemeMode(ThemeMode.DARK) },
                )
                ThemeChip(
                    label = stringResource(R.string.theme_sepia),
                    selected = prefs.themeMode == ThemeMode.SEPIA,
                    bgPreview = Color(0xFFF5E6C8),
                    fgPreview = Color(0xFF5B4636),
                    onClick = { viewModel.setThemeMode(ThemeMode.SEPIA) },
                )
                ThemeChip(
                    label = stringResource(R.string.theme_high_contrast),
                    selected = prefs.themeMode == ThemeMode.HIGH_CONTRAST,
                    bgPreview = Color(0xFF000000),
                    fgPreview = Color(0xFFFFFFFF),
                    onClick = { viewModel.setThemeMode(ThemeMode.HIGH_CONTRAST) },
                )
            }

            // Keep screen on toggle
            SettingRow(label = stringResource(R.string.setting_keep_screen_on)) {
                Switch(
                    checked = prefs.keepScreenOn,
                    onCheckedChange = viewModel::setKeepScreenOn
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = stringResource(R.string.settings_section_reading),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            // Font family picker
            Text(
                text = stringResource(R.string.setting_font_family),
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                FontChip(
                    label = stringResource(R.string.font_serif),
                    selected = prefs.fontFamily == FontFamilyOption.SERIF,
                    fontFamily = FontFamily.Serif,
                    onClick = { viewModel.setFontFamily(FontFamilyOption.SERIF) },
                )
                FontChip(
                    label = stringResource(R.string.font_sans_serif),
                    selected = prefs.fontFamily == FontFamilyOption.SANS_SERIF,
                    fontFamily = FontFamily.SansSerif,
                    onClick = { viewModel.setFontFamily(FontFamilyOption.SANS_SERIF) },
                )
                FontChip(
                    label = stringResource(R.string.font_monospace),
                    selected = prefs.fontFamily == FontFamilyOption.MONOSPACE,
                    fontFamily = FontFamily.Monospace,
                    onClick = { viewModel.setFontFamily(FontFamilyOption.MONOSPACE) },
                )
                FontChip(
                    label = stringResource(R.string.font_cursive),
                    selected = prefs.fontFamily == FontFamilyOption.CURSIVE,
                    fontFamily = FontFamily.Cursive,
                    onClick = { viewModel.setFontFamily(FontFamilyOption.CURSIVE) },
                )
            }

            // Font size slider
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.setting_font_size),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${prefs.fontSize.roundToInt()}sp",
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = prefs.fontSize,
                    onValueChange = viewModel::setFontSize,
                    valueRange = 12f..32f,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = stringResource(R.string.settings_section_about),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = stringResource(R.string.settings_about_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThemeChip(
    label: String,
    selected: Boolean,
    bgPreview: Color,
    fgPreview: Color,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.padding(end = 6.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = bgPreview,
                    border = BorderStroke(1.dp, fgPreview.copy(alpha = 0.3f)),
                ) {
                    Text(
                        text = "A",
                        color = fgPreview,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                Text(label)
            }
        },
        colors = FilterChipDefaults.filterChipColors(),
    )
}

@Composable
private fun FontChip(
    label: String,
    selected: Boolean,
    fontFamily: FontFamily,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                fontFamily = fontFamily,
            )
        },
    )
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        content()
    }
}
