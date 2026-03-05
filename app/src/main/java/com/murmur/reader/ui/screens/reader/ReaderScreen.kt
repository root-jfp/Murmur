package com.murmur.reader.ui.screens.reader

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.murmur.reader.R
import com.murmur.reader.tts.TtsState
import com.murmur.reader.ui.components.BookPager
import com.murmur.reader.ui.components.PlaybackControls
import com.murmur.reader.ui.components.PitchSlider
import com.murmur.reader.ui.components.SpeedSlider
import com.murmur.reader.ui.components.VoiceSelectorDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    initialText: String? = null,
    initialUri: Uri? = null,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val ttsState by viewModel.ttsState.collectAsStateWithLifecycle()
    val isOffline by viewModel.isOfflineMode.collectAsStateWithLifecycle()
    val voices by viewModel.voices.collectAsStateWithLifecycle()
    val isLoadingVoices by viewModel.isLoadingVoices.collectAsStateWithLifecycle()
    val prefs by viewModel.displayPrefs.collectAsStateWithLifecycle()

    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var showVoiceDialog by remember { mutableStateOf(false) }
    var textInput by remember { mutableStateOf("") }

    // Load initial content from share/deep link
    LaunchedEffect(initialText, initialUri) {
        when {
            initialText != null -> { textInput = initialText; viewModel.setText(initialText) }
            initialUri != null  -> viewModel.loadDocument(initialUri, null)
        }
    }

    // Show errors as snackbar
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val mime = context.contentResolver.getType(it)
            viewModel.loadDocument(it, mime)
        }
    }

    // Initialised from saved preferences; re-keyed on prefs so a change from Settings
    // is immediately reflected in the sliders without losing in-session adjustments.
    var ratePercent by remember(prefs.ratePercent) { mutableStateOf(prefs.ratePercent) }
    var pitchHz by remember(prefs.pitchHz) { mutableStateOf(prefs.pitchHz) }

    androidx.compose.material3.Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.documentTitle.ifBlank { stringResource(R.string.app_name) },
                        maxLines = 1,
                    )
                },
                actions = {
                    Icon(
                        imageVector = if (isOffline) Icons.Filled.WifiOff else Icons.Filled.Wifi,
                        contentDescription = null,
                        tint = if (isOffline) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    IconButton(onClick = {
                        val clip = clipboard.getText()?.text ?: return@IconButton
                        textInput = clip
                        viewModel.setText(clip)
                    }) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = stringResource(R.string.action_paste))
                    }
                    IconButton(onClick = {
                        fileLauncher.launch(arrayOf(
                            "application/pdf", "application/epub+zip",
                            "text/plain", "text/html"
                        ))
                    }) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = stringResource(R.string.action_open_file))
                    }
                    IconButton(onClick = { showVoiceDialog = true }) {
                        Icon(Icons.Filled.RecordVoiceOver, contentDescription = stringResource(R.string.action_select_voice))
                    }
                }
            )
        },
        bottomBar = {
            Column {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SpeedSlider(
                        ratePercent = ratePercent,
                        onRateChange = { ratePercent = it; viewModel.setRate(it) }
                    )
                    Spacer(Modifier.height(4.dp))
                    PitchSlider(
                        pitchHz = pitchHz,
                        onPitchChange = { pitchHz = it; viewModel.setPitch(it) }
                    )
                }
                PlaybackControls(
                    state = ttsState,
                    onPlay = {
                        if (ttsState is TtsState.Paused) viewModel.resume()
                        else { viewModel.setText(textInput); viewModel.play() }
                    },
                    onPause = viewModel::pause,
                    onStop = viewModel::stop,
                    onSkipNext = {},
                    onSkipPrev = {},
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoadingDocument -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                uiState.text.isNotBlank() -> {
                    // Book-like paged reading surface
                    BookPager(
                        text = uiState.text,
                        highlightedWordIndex = uiState.currentWordIndex,
                        fontSize = prefs.fontSize.sp,
                        useSerifFont = prefs.useSerifFont,
                        isDark = prefs.useDarkTheme,
                        initialPage = uiState.currentPage,
                        onPageChange = viewModel::onPageChange,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                else -> {
                    // Entry state: text input field
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        label = { Text(stringResource(R.string.hint_enter_text)) },
                        placeholder = { Text(stringResource(R.string.placeholder_text)) },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .imePadding(),
                    )
                }
            }
        }
    }

    if (showVoiceDialog) {
        VoiceSelectorDialog(
            voices = voices.ifEmpty { defaultVoices() },
            selectedVoiceName = prefs.voiceName,
            isLoading = isLoadingVoices,
            onVoiceSelected = viewModel::selectVoice,
            onDismiss = { showVoiceDialog = false },
        )
    }
}

private fun defaultVoices() = listOf(
    com.murmur.reader.tts.EdgeVoice(name = "", shortName = "en-US-AriaNeural",   gender = "Female", locale = "en-US", friendlyName = "Aria (en-US)"),
    com.murmur.reader.tts.EdgeVoice(name = "", shortName = "en-US-GuyNeural",    gender = "Male",   locale = "en-US", friendlyName = "Guy (en-US)"),
    com.murmur.reader.tts.EdgeVoice(name = "", shortName = "pt-PT-RaquelNeural", gender = "Female", locale = "pt-PT", friendlyName = "Raquel (pt-PT)"),
    com.murmur.reader.tts.EdgeVoice(name = "", shortName = "pt-PT-DuarteNeural", gender = "Male",   locale = "pt-PT", friendlyName = "Duarte (pt-PT)"),
)
