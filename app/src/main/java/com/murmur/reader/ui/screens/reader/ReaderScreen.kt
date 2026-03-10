package com.murmur.reader.ui.screens.reader

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.murmur.reader.tts.TtsState
import com.murmur.reader.ui.components.BookPager
import com.murmur.reader.ui.components.PitchSlider
import com.murmur.reader.ui.components.PlaybackControls
import com.murmur.reader.ui.components.SpeedSlider
import com.murmur.reader.ui.components.VoiceSelectorDialog
import com.murmur.reader.ui.theme.BookPageDark
import com.murmur.reader.ui.theme.BookPageLight
import com.murmur.reader.ui.theme.BookTextDark
import com.murmur.reader.ui.theme.BookTextLight
import com.murmur.reader.ui.theme.MurmurDarkPrimary
import com.murmur.reader.ui.theme.MurmurLightPrimary

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
    var showSliders by rememberSaveable { mutableStateOf(false) }
    var zoomScale by remember { mutableFloatStateOf(1f) }

    // Load initial content from share intent or library selection
    LaunchedEffect(initialText, initialUri) {
        when {
            initialUri != null  -> viewModel.loadDocument(initialUri, null)
            initialText != null -> viewModel.setText(initialText)
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
            // Take persistent permission so we can reopen from library later
            try {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { /* not all providers support persistent grants */ }
            val mime = context.contentResolver.getType(it)
            viewModel.loadDocument(it, mime)
        }
    }

    val isDark = prefs.useDarkTheme
    val hasContent = uiState.text.isNotBlank()
    val isLoading = uiState.isLoadingDocument

    // Initialised from saved preferences; re-keyed on prefs so a change from Settings
    // is immediately reflected in the sliders without losing in-session adjustments.
    var ratePercent by remember(prefs.ratePercent) { mutableStateOf(prefs.ratePercent) }
    var pitchHz by remember(prefs.pitchHz) { mutableStateOf(prefs.pitchHz) }

    when {
        // ─── Loading state ───
        isLoading -> {
            val bg = if (isDark) BookPageDark else BookPageLight
            val textColor = if (isDark) BookTextDark else BookTextLight
            val accent = if (isDark) MurmurDarkPrimary else MurmurLightPrimary

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bg),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = accent)
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = "Opening document\u2026",
                        style = TextStyle(
                            fontSize = 14.sp,
                            color = textColor.copy(alpha = 0.55f),
                        ),
                    )
                }
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        // ─── Reader mode ───
        hasContent -> {
            val bg = if (isDark) BookPageDark else BookPageLight

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                text = uiState.documentTitle.ifBlank { "Murmur" },
                                maxLines = 1,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { viewModel.clearDocument() }) {
                                Icon(Icons.Filled.Close, contentDescription = "Close document")
                            }
                        },
                        actions = {
                            Icon(
                                imageVector = if (isOffline) Icons.Filled.WifiOff else Icons.Filled.Wifi,
                                contentDescription = null,
                                tint = if (isOffline) MaterialTheme.colorScheme.error
                                       else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 4.dp),
                            )
                            IconButton(onClick = {
                                fileLauncher.launch(arrayOf(
                                    "application/pdf",
                                    "application/epub+zip",
                                    "text/plain",
                                    "text/html",
                                    "text/markdown",
                                    "application/octet-stream",
                                ))
                            }) {
                                Icon(Icons.Filled.FolderOpen, contentDescription = "Open file")
                            }
                            IconButton(onClick = { showVoiceDialog = true }) {
                                Icon(Icons.Filled.RecordVoiceOver, contentDescription = "Select voice")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = bg,
                        ),
                    )
                },
                bottomBar = {
                    Column {
                        AnimatedVisibility(
                            visible = showSliders,
                            enter = expandVertically(),
                            exit = shrinkVertically(),
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                SpeedSlider(
                                    ratePercent = ratePercent,
                                    onRateChange = { ratePercent = it; viewModel.setRate(it) },
                                )
                                Spacer(Modifier.height(4.dp))
                                PitchSlider(
                                    pitchHz = pitchHz,
                                    onPitchChange = { pitchHz = it; viewModel.setPitch(it) },
                                )
                            }
                        }
                        PlaybackControls(
                            state = ttsState,
                            onPlay = {
                                if (ttsState is TtsState.Paused) viewModel.resume()
                                else viewModel.play()
                            },
                            onPause = viewModel::pause,
                            onStop = viewModel::stop,
                            onSkipNext = {},
                            onSkipPrev = {},
                            leadingAction = {
                                IconButton(
                                    onClick = { showSliders = !showSliders },
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Tune,
                                        contentDescription = if (showSliders) "Hide controls" else "Show speed & pitch",
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            },
                        )
                    }
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .pointerInput(Unit) {
                            detectTransformGestures(
                                panZoomLock = true,
                            ) { _, _, zoom, _ ->
                                zoomScale = (zoomScale * zoom).coerceIn(0.5f, 3f)
                            }
                        }
                ) {
                    BookPager(
                        text = uiState.text,
                        highlightedWordIndex = uiState.currentWordIndex,
                        fontSize = prefs.fontSize.sp,
                        useSerifFont = prefs.useSerifFont,
                        isDark = isDark,
                        initialPage = uiState.currentPage,
                        onPageChange = viewModel::onPageChange,
                        onWordTapped = viewModel::playFromWordIndex,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = zoomScale,
                                scaleY = zoomScale,
                            ),
                    )
                }

                // Commit font size change when zoom gesture ends (scale != 1f)
                LaunchedEffect(zoomScale) {
                    if (zoomScale != 1f) {
                        // Wait briefly for gesture to settle
                        kotlinx.coroutines.delay(300)
                        val newSize = (prefs.fontSize * zoomScale).coerceIn(12f, 32f)
                        zoomScale = 1f
                        viewModel.setFontSize(newSize)
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

        // ─── Welcome screen ───
        else -> {
            WelcomeContent(
                onOpenFile = {
                    fileLauncher.launch(arrayOf(
                        "application/pdf",
                        "application/epub+zip",
                        "text/plain",
                        "text/html",
                        "text/markdown",
                        "application/octet-stream",
                    ))
                },
                onPasteClipboard = {
                    val clip = clipboard.getText()?.text
                    if (clip != null) viewModel.setText(clip)
                },
                isDark = isDark,
                snackbarHostState = snackbarHostState,
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Welcome screen — elegant landing page with file open actions
// ──────────────────────────────────────────────────────────────

@Composable
private fun WelcomeContent(
    onOpenFile: () -> Unit,
    onPasteClipboard: () -> Unit,
    isDark: Boolean,
    snackbarHostState: SnackbarHostState,
) {
    val backgroundColor = if (isDark) BookPageDark else BookPageLight
    val textColor = if (isDark) BookTextDark else BookTextLight
    val accentColor = if (isDark) MurmurDarkPrimary else MurmurLightPrimary

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 44.dp),
            ) {
                // Decorative book icon
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(accentColor.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.MenuBook,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(44.dp),
                    )
                }

                Spacer(Modifier.height(28.dp))

                // App name
                Text(
                    text = "Murmur",
                    style = TextStyle(
                        fontFamily = FontFamily.Serif,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 6.sp,
                        color = textColor,
                    ),
                )

                Spacer(Modifier.height(8.dp))

                // Tagline
                Text(
                    text = "Your personal reading companion",
                    style = TextStyle(
                        fontSize = 14.sp,
                        color = textColor.copy(alpha = 0.50f),
                        letterSpacing = 0.5.sp,
                    ),
                )

                Spacer(Modifier.height(52.dp))

                // Primary action — Open Document
                Button(
                    onClick = onOpenFile,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = Color.White,
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                ) {
                    Icon(
                        Icons.Filled.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Open a Document",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Secondary action — Paste Clipboard
                OutlinedButton(
                    onClick = onPasteClipboard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = textColor.copy(alpha = 0.70f),
                    ),
                    border = BorderStroke(1.dp, textColor.copy(alpha = 0.18f)),
                ) {
                    Icon(
                        Icons.Filled.ContentPaste,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Paste from Clipboard", fontSize = 16.sp)
                }

                Spacer(Modifier.height(36.dp))

                // Supported formats
                Text(
                    text = "PDF  \u00b7  EPUB  \u00b7  TXT  \u00b7  HTML",
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = textColor.copy(alpha = 0.30f),
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
        )
    }
}

private fun defaultVoices() = listOf(
    com.murmur.reader.tts.EdgeVoice(name = "", shortName = "en-US-AriaNeural",   gender = "Female", locale = "en-US", friendlyName = "Aria (en-US)"),
    com.murmur.reader.tts.EdgeVoice(name = "", shortName = "en-US-GuyNeural",    gender = "Male",   locale = "en-US", friendlyName = "Guy (en-US)"),
    com.murmur.reader.tts.EdgeVoice(name = "", shortName = "pt-PT-RaquelNeural", gender = "Female", locale = "pt-PT", friendlyName = "Raquel (pt-PT)"),
    com.murmur.reader.tts.EdgeVoice(name = "", shortName = "pt-PT-DuarteNeural", gender = "Male",   locale = "pt-PT", friendlyName = "Duarte (pt-PT)"),
)
