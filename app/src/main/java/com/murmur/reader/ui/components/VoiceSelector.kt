package com.murmur.reader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Female
import androidx.compose.material.icons.filled.Male
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.murmur.reader.R
import com.murmur.reader.tts.EdgeVoice

private enum class GenderFilter { All, Female, Male }

@Composable
fun VoiceSelectorDialog(
    voices: List<EdgeVoice>,
    selectedVoiceName: String,
    isLoading: Boolean,
    onVoiceSelected: (EdgeVoice) -> Unit,
    onPreviewVoice: (EdgeVoice) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var genderFilter by remember { mutableStateOf(GenderFilter.All) }

    val filtered = remember(voices, searchQuery, genderFilter) {
        voices
            .filter { voice ->
                when (genderFilter) {
                    GenderFilter.Female -> voice.gender.equals("Female", ignoreCase = true)
                    GenderFilter.Male   -> voice.gender.equals("Male", ignoreCase = true)
                    GenderFilter.All    -> true
                }
            }
            .filter { voice ->
                searchQuery.isBlank() ||
                voice.friendlyName.contains(searchQuery, ignoreCase = true) ||
                voice.locale.contains(searchQuery, ignoreCase = true) ||
                voice.shortName.contains(searchQuery, ignoreCase = true)
            }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_select_voice)) },
        text = {
            Column {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.hint_search_voices)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Gender filter chips
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    FilterChip(
                        selected = genderFilter == GenderFilter.All,
                        onClick = { genderFilter = GenderFilter.All },
                        label = { Text("All") },
                    )
                    FilterChip(
                        selected = genderFilter == GenderFilter.Female,
                        onClick = { genderFilter = GenderFilter.Female },
                        label = { Text("Female") },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Female,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                    FilterChip(
                        selected = genderFilter == GenderFilter.Male,
                        onClick = { genderFilter = GenderFilter.Male },
                        label = { Text("Male") },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Male,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }

                if (isLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 16.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                        Text(stringResource(R.string.loading_voices))
                    }
                }

                LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                    items(filtered, key = { it.shortName }) { voice ->
                        VoiceListItem(
                            voice = voice,
                            isSelected = voice.shortName == selectedVoiceName,
                            onClick = {
                                onVoiceSelected(voice)
                                onDismiss()
                            },
                            onPreview = { onPreviewVoice(voice) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
private fun VoiceListItem(
    voice: EdgeVoice,
    isSelected: Boolean,
    onClick: () -> Unit,
    onPreview: () -> Unit,
) {
    val isFemale = voice.gender.equals("Female", ignoreCase = true)
    val genderIcon = if (isFemale) Icons.Filled.Female else Icons.Filled.Male
    val genderTint = if (isFemale) MaterialTheme.colorScheme.tertiary
                     else MaterialTheme.colorScheme.secondary

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onClick,
        )
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                text = voice.friendlyName,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = voice.locale,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onPreview, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "Preview voice",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Icon(
            imageVector = genderIcon,
            contentDescription = voice.gender,
            tint = genderTint,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .size(20.dp)
        )
        if (isSelected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}
