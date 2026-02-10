package com.openclaw.assistant

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openclaw.assistant.api.OpenClawClient
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.speech.tts.ElevenLabsTTSProvider
import com.openclaw.assistant.speech.tts.FishAudioTTSProvider
import com.openclaw.assistant.speech.tts.OpenAITTSProvider
import com.openclaw.assistant.speech.tts.TTSOrchestrator
import com.openclaw.assistant.ui.theme.OpenClawAssistantTheme
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {

    private lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository.getInstance(this)

        setContent {
            OpenClawAssistantTheme {
                SettingsScreen(
                    settings = settings,
                    onSave = { 
                        Toast.makeText(this, getString(R.string.saved), Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    onSave: () -> Unit,
    onBack: () -> Unit
) {
    var webhookUrl by remember { mutableStateOf(settings.webhookUrl) }
    var authToken by remember { mutableStateOf(settings.authToken) }
    var ttsEnabled by remember { mutableStateOf(settings.ttsEnabled) }
    var ttsSpeed by remember { mutableStateOf(settings.ttsSpeed) }
    var continuousMode by remember { mutableStateOf(settings.continuousMode) }
    var wakeWordPreset by remember { mutableStateOf(settings.wakeWordPreset) }
    var customWakeWord by remember { mutableStateOf(settings.customWakeWord) }
    
    var showAuthToken by remember { mutableStateOf(false) }
    var showWakeWordMenu by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val apiClient = remember { OpenClawClient() }
    
    var streamingEnabled by remember { mutableStateOf(settings.streamingEnabled) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }

    // TTS Engines
    var ttsEngine by remember { mutableStateOf(settings.ttsEngine) }
    var availableEngines by remember { mutableStateOf<List<com.openclaw.assistant.speech.TTSEngineUtils.EngineInfo>>(emptyList()) }
    var showEngineMenu by remember { mutableStateOf(false) }

    // Cloud TTS settings
    var ttsProvider by remember { mutableStateOf(settings.ttsProvider) }
    var openaiApiKey by remember { mutableStateOf(settings.openaiTtsApiKey) }
    var openaiVoice by remember { mutableStateOf(settings.openaiTtsVoice) }
    var openaiModel by remember { mutableStateOf(settings.openaiTtsModel) }
    var elevenlabsApiKey by remember { mutableStateOf(settings.elevenlabsTtsApiKey) }
    var elevenlabsVoice by remember { mutableStateOf(settings.elevenlabsTtsVoice.ifBlank { ElevenLabsTTSProvider.VOICES.first().id }) }
    var showProviderMenu by remember { mutableStateOf(false) }
    var showOpenaiVoiceMenu by remember { mutableStateOf(false) }
    var showOpenaiModelMenu by remember { mutableStateOf(false) }
    var showElevenlabsVoiceMenu by remember { mutableStateOf(false) }
    var fishAudioApiKey by remember { mutableStateOf(settings.fishAudioTtsApiKey) }
    var fishAudioReferenceId by remember { mutableStateOf(settings.fishAudioTtsReferenceId) }
    var fishAudioModel by remember { mutableStateOf(settings.fishAudioTtsModel) }
    var showFishAudioApiKey by remember { mutableStateOf(false) }
    var showFishAudioModelMenu by remember { mutableStateOf(false) }
    var showFishAudioVoiceMenu by remember { mutableStateOf(false) }
    var showOpenaiApiKey by remember { mutableStateOf(false) }
    var showElevenlabsApiKey by remember { mutableStateOf(false) }
    var isTestingVoice by remember { mutableStateOf(false) }
    var voiceTestResult by remember { mutableStateOf<TestResult?>(null) }

    LaunchedEffect(Unit) {
        // Load engines off-main thread ideally, but for now simple
        availableEngines = com.openclaw.assistant.speech.TTSEngineUtils.getAvailableEngines(context)
    }

    // Wake word options
    val wakeWordOptions = listOf(
        SettingsRepository.WAKE_WORD_OPEN_CLAW to stringResource(R.string.wake_word_openclaw),
        SettingsRepository.WAKE_WORD_HEY_ASSISTANT to stringResource(R.string.wake_word_hey_assistant),
        SettingsRepository.WAKE_WORD_JARVIS to stringResource(R.string.wake_word_jarvis),
        SettingsRepository.WAKE_WORD_COMPUTER to stringResource(R.string.wake_word_computer),
        SettingsRepository.WAKE_WORD_CUSTOM to stringResource(R.string.wake_word_custom)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_button))
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            settings.webhookUrl = webhookUrl
                            settings.authToken = authToken
                            settings.ttsEnabled = ttsEnabled
                            settings.ttsSpeed = ttsSpeed
                            settings.ttsEngine = ttsEngine
                            settings.ttsProvider = ttsProvider
                            settings.openaiTtsApiKey = openaiApiKey
                            settings.openaiTtsVoice = openaiVoice
                            settings.openaiTtsModel = openaiModel
                            settings.elevenlabsTtsApiKey = elevenlabsApiKey
                            settings.elevenlabsTtsVoice = elevenlabsVoice
                            settings.fishAudioTtsApiKey = fishAudioApiKey
                            settings.fishAudioTtsReferenceId = fishAudioReferenceId
                            settings.fishAudioTtsModel = fishAudioModel
                            settings.continuousMode = continuousMode
                            settings.wakeWordPreset = wakeWordPreset
                            settings.customWakeWord = customWakeWord
                            onSave()
                        },
                        enabled = webhookUrl.isNotBlank() && !isTesting
                    ) {
                        Text(stringResource(R.string.save_button))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // === CONNECTION SECTION ===
            Text(
                text = stringResource(R.string.connection),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Webhook URL
                    OutlinedTextField(
                        value = webhookUrl,
                        onValueChange = { 
                            webhookUrl = it
                            testResult = null
                        },
                        label = { Text(stringResource(R.string.webhook_url_label) + " *") },
                        placeholder = { Text(stringResource(R.string.webhook_url_hint)) },
                        leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        isError = webhookUrl.isBlank()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Auth Token
                    OutlinedTextField(
                        value = authToken,
                        onValueChange = { 
                            authToken = it
                            testResult = null
                        },
                        label = { Text(stringResource(R.string.auth_token_label)) },
                        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                        trailingIcon = {
                            IconButton(onClick = { showAuthToken = !showAuthToken }) {
                                Icon(
                                    if (showAuthToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        visualTransformation = if (showAuthToken) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Test Connection Button
                    Button(
                        onClick = {
                            if (webhookUrl.isBlank()) return@Button
                            scope.launch {
                                try {
                                    isTesting = true
                                    testResult = null
                                    val result = apiClient.testConnection(webhookUrl, authToken)
                                    result.fold(
                                        onSuccess = {
                                            testResult = TestResult(success = true, message = context.getString(R.string.connected))
                                            settings.webhookUrl = webhookUrl
                                            settings.authToken = authToken
                                            settings.isVerified = true
                                        },
                                        onFailure = {
                                            testResult = TestResult(success = false, message = context.getString(R.string.failed, it.message ?: ""))
                                        }
                                    )
                                } catch (e: Exception) {
                                    testResult = TestResult(success = false, message = context.getString(R.string.error, e.message ?: ""))
                                } finally {
                                    isTesting = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when {
                                testResult?.success == true -> Color(0xFF4CAF50)
                                testResult?.success == false -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            }
                        ),
                        enabled = webhookUrl.isNotBlank() && !isTesting
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.testing))
                        } else {
                            Icon(
                                when {
                                    testResult?.success == true -> Icons.Default.Check
                                    testResult?.success == false -> Icons.Default.Error
                                    else -> Icons.Default.NetworkCheck
                                },
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(testResult?.message ?: stringResource(R.string.test_connection_button))
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

                    // Streaming toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.streaming_label), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.streaming_description), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Switch(
                            checked = streamingEnabled,
                            onCheckedChange = {
                                streamingEnabled = it
                                settings.streamingEnabled = it
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // === VOICE SECTION ===
            Text(
                text = stringResource(R.string.voice),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.voice_output), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.read_ai_responses), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Switch(checked = ttsEnabled, onCheckedChange = { ttsEnabled = it })
                    }

                    if (ttsEnabled) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

                        // TTS Provider Selection
                        val providerOptions = listOf(
                            SettingsRepository.TTS_PROVIDER_LOCAL to stringResource(R.string.tts_provider_local),
                            SettingsRepository.TTS_PROVIDER_OPENAI to stringResource(R.string.tts_provider_openai),
                            SettingsRepository.TTS_PROVIDER_ELEVENLABS to stringResource(R.string.tts_provider_elevenlabs),
                            SettingsRepository.TTS_PROVIDER_FISH_AUDIO to stringResource(R.string.tts_provider_fish_audio)
                        )

                        ExposedDropdownMenuBox(
                            expanded = showProviderMenu,
                            onExpandedChange = { showProviderMenu = it }
                        ) {
                            OutlinedTextField(
                                value = providerOptions.find { it.first == ttsProvider }?.second ?: stringResource(R.string.tts_provider_local),
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.tts_provider_label)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showProviderMenu) },
                                modifier = Modifier.fillMaxWidth().menuAnchor()
                            )

                            ExposedDropdownMenu(
                                expanded = showProviderMenu,
                                onDismissRequest = { showProviderMenu = false }
                            ) {
                                providerOptions.forEach { (value, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            ttsProvider = value
                                            voiceTestResult = null
                                            showProviderMenu = false
                                        },
                                        leadingIcon = {
                                            if (ttsProvider == value) {
                                                Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // === LOCAL TTS SETTINGS ===
                        if (ttsProvider == SettingsRepository.TTS_PROVIDER_LOCAL) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                // TTS Engine Selection
                                ExposedDropdownMenuBox(
                                    expanded = showEngineMenu,
                                    onExpandedChange = { showEngineMenu = it }
                                ) {
                                    val currentLabel = if (ttsEngine.isEmpty()) {
                                        stringResource(R.string.tts_engine_auto)
                                    } else {
                                        availableEngines.find { it.name == ttsEngine }?.label ?: ttsEngine
                                    }

                                    OutlinedTextField(
                                        value = currentLabel,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text(stringResource(R.string.tts_engine_label)) },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showEngineMenu) },
                                        modifier = Modifier.fillMaxWidth().menuAnchor()
                                    )

                                    ExposedDropdownMenu(
                                        expanded = showEngineMenu,
                                        onDismissRequest = { showEngineMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.tts_engine_auto)) },
                                            onClick = {
                                                ttsEngine = ""
                                                showEngineMenu = false
                                            },
                                            leadingIcon = {
                                                if (ttsEngine.isEmpty()) {
                                                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                }
                                            }
                                        )

                                        availableEngines.forEach { engine ->
                                            DropdownMenuItem(
                                                text = { Text(engine.label) },
                                                onClick = {
                                                    ttsEngine = engine.name
                                                    showEngineMenu = false
                                                },
                                                leadingIcon = {
                                                    if (ttsEngine == engine.name) {
                                                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }

                                val effectiveEngine = if (ttsEngine.isEmpty()) {
                                    com.openclaw.assistant.speech.TTSEngineUtils.getDefaultEngine(context)
                                } else {
                                    ttsEngine
                                }

                                val isGoogleTTS = effectiveEngine == SettingsRepository.GOOGLE_TTS_PACKAGE

                                if (isGoogleTTS) {
                                    Spacer(modifier = Modifier.height(16.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(stringResource(R.string.voice_speed), style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            text = "%.1fx".format(ttsSpeed),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    Slider(
                                        value = ttsSpeed,
                                        onValueChange = { ttsSpeed = it },
                                        valueRange = 0.5f..3.0f,
                                        steps = 24,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }

                        // === OPENAI TTS SETTINGS ===
                        if (ttsProvider == SettingsRepository.TTS_PROVIDER_OPENAI) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                // API Key
                                OutlinedTextField(
                                    value = openaiApiKey,
                                    onValueChange = { openaiApiKey = it; voiceTestResult = null },
                                    label = { Text(stringResource(R.string.tts_api_key_label)) },
                                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                                    trailingIcon = {
                                        IconButton(onClick = { showOpenaiApiKey = !showOpenaiApiKey }) {
                                            Icon(
                                                if (showOpenaiApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = stringResource(R.string.show_api_key)
                                            )
                                        }
                                    },
                                    visualTransformation = if (showOpenaiApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                // Model dropdown
                                ExposedDropdownMenuBox(
                                    expanded = showOpenaiModelMenu,
                                    onExpandedChange = { showOpenaiModelMenu = it }
                                ) {
                                    OutlinedTextField(
                                        value = openaiModel,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text(stringResource(R.string.tts_model_label)) },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showOpenaiModelMenu) },
                                        modifier = Modifier.fillMaxWidth().menuAnchor()
                                    )

                                    ExposedDropdownMenu(
                                        expanded = showOpenaiModelMenu,
                                        onDismissRequest = { showOpenaiModelMenu = false }
                                    ) {
                                        OpenAITTSProvider.MODELS.forEach { model ->
                                            DropdownMenuItem(
                                                text = { Text(model) },
                                                onClick = {
                                                    openaiModel = model
                                                    showOpenaiModelMenu = false
                                                },
                                                leadingIcon = {
                                                    if (openaiModel == model) {
                                                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Voice dropdown
                                ExposedDropdownMenuBox(
                                    expanded = showOpenaiVoiceMenu,
                                    onExpandedChange = { showOpenaiVoiceMenu = it }
                                ) {
                                    OutlinedTextField(
                                        value = OpenAITTSProvider.VOICES.find { it.id == openaiVoice }?.displayName ?: openaiVoice,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text(stringResource(R.string.tts_voice_label)) },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showOpenaiVoiceMenu) },
                                        modifier = Modifier.fillMaxWidth().menuAnchor()
                                    )

                                    ExposedDropdownMenu(
                                        expanded = showOpenaiVoiceMenu,
                                        onDismissRequest = { showOpenaiVoiceMenu = false }
                                    ) {
                                        OpenAITTSProvider.VOICES.forEach { voice ->
                                            DropdownMenuItem(
                                                text = { Text(voice.displayName) },
                                                onClick = {
                                                    openaiVoice = voice.id
                                                    showOpenaiVoiceMenu = false
                                                },
                                                leadingIcon = {
                                                    if (openaiVoice == voice.id) {
                                                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Test Voice button
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isTestingVoice = true
                                            voiceTestResult = null
                                            try {
                                                val provider = OpenAITTSProvider(openaiApiKey, openaiVoice, openaiModel)
                                                val audio = provider.synthesize(context.getString(R.string.tts_test_sample))
                                                val player = com.openclaw.assistant.speech.AudioPlayer(context)
                                                val success = player.playFromBytes(audio)
                                                voiceTestResult = if (success) {
                                                    TestResult(true, context.getString(R.string.tts_test_success))
                                                } else {
                                                    TestResult(false, context.getString(R.string.tts_test_failed, "Playback failed"))
                                                }
                                            } catch (e: Exception) {
                                                voiceTestResult = TestResult(false, context.getString(R.string.tts_test_failed, e.message ?: "Unknown error"))
                                            } finally {
                                                isTestingVoice = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = openaiApiKey.isNotBlank() && !isTestingVoice,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = when {
                                            voiceTestResult?.success == true -> Color(0xFF4CAF50)
                                            voiceTestResult?.success == false -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.primary
                                        }
                                    )
                                ) {
                                    if (isTestingVoice) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.tts_testing))
                                    } else {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(voiceTestResult?.message ?: stringResource(R.string.tts_test_voice))
                                    }
                                }
                            }
                        }

                        // === FISH AUDIO TTS SETTINGS ===
                        if (ttsProvider == SettingsRepository.TTS_PROVIDER_FISH_AUDIO) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                // API Key
                                OutlinedTextField(
                                    value = fishAudioApiKey,
                                    onValueChange = { fishAudioApiKey = it; voiceTestResult = null },
                                    label = { Text(stringResource(R.string.tts_api_key_label)) },
                                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                                    trailingIcon = {
                                        IconButton(onClick = { showFishAudioApiKey = !showFishAudioApiKey }) {
                                            Icon(
                                                if (showFishAudioApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = stringResource(R.string.show_api_key)
                                            )
                                        }
                                    },
                                    visualTransformation = if (showFishAudioApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                // Model dropdown
                                ExposedDropdownMenuBox(
                                    expanded = showFishAudioModelMenu,
                                    onExpandedChange = { showFishAudioModelMenu = it }
                                ) {
                                    OutlinedTextField(
                                        value = fishAudioModel,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text(stringResource(R.string.tts_model_label)) },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showFishAudioModelMenu) },
                                        modifier = Modifier.fillMaxWidth().menuAnchor()
                                    )

                                    ExposedDropdownMenu(
                                        expanded = showFishAudioModelMenu,
                                        onDismissRequest = { showFishAudioModelMenu = false }
                                    ) {
                                        FishAudioTTSProvider.MODELS.forEach { model ->
                                            DropdownMenuItem(
                                                text = { Text(model) },
                                                onClick = {
                                                    fishAudioModel = model
                                                    showFishAudioModelMenu = false
                                                },
                                                leadingIcon = {
                                                    if (fishAudioModel == model) {
                                                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Voice preset dropdown
                                ExposedDropdownMenuBox(
                                    expanded = showFishAudioVoiceMenu,
                                    onExpandedChange = { showFishAudioVoiceMenu = it }
                                ) {
                                    OutlinedTextField(
                                        value = FishAudioTTSProvider.VOICES.find { it.id == fishAudioReferenceId }?.displayName
                                            ?: if (fishAudioReferenceId.isBlank()) "Default" else "Custom",
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text(stringResource(R.string.tts_voice_label)) },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showFishAudioVoiceMenu) },
                                        modifier = Modifier.fillMaxWidth().menuAnchor()
                                    )

                                    ExposedDropdownMenu(
                                        expanded = showFishAudioVoiceMenu,
                                        onDismissRequest = { showFishAudioVoiceMenu = false }
                                    ) {
                                        FishAudioTTSProvider.VOICES.forEach { voice ->
                                            DropdownMenuItem(
                                                text = { Text(voice.displayName) },
                                                onClick = {
                                                    fishAudioReferenceId = voice.id
                                                    showFishAudioVoiceMenu = false
                                                },
                                                leadingIcon = {
                                                    if (fishAudioReferenceId == voice.id) {
                                                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Custom reference ID (for community voices from fish.audio)
                                OutlinedTextField(
                                    value = fishAudioReferenceId,
                                    onValueChange = { fishAudioReferenceId = it; voiceTestResult = null },
                                    label = { Text(stringResource(R.string.tts_reference_id_label)) },
                                    placeholder = { Text(stringResource(R.string.tts_reference_id_hint)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                // Test Voice button
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isTestingVoice = true
                                            voiceTestResult = null
                                            try {
                                                val provider = FishAudioTTSProvider(fishAudioApiKey, fishAudioReferenceId, fishAudioModel)
                                                val audio = provider.synthesize(context.getString(R.string.tts_test_sample))
                                                val player = com.openclaw.assistant.speech.AudioPlayer(context)
                                                val success = player.playFromBytes(audio)
                                                voiceTestResult = if (success) {
                                                    TestResult(true, context.getString(R.string.tts_test_success))
                                                } else {
                                                    TestResult(false, context.getString(R.string.tts_test_failed, "Playback failed"))
                                                }
                                            } catch (e: Exception) {
                                                voiceTestResult = TestResult(false, context.getString(R.string.tts_test_failed, e.message ?: "Unknown error"))
                                            } finally {
                                                isTestingVoice = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = fishAudioApiKey.isNotBlank() && !isTestingVoice,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = when {
                                            voiceTestResult?.success == true -> Color(0xFF4CAF50)
                                            voiceTestResult?.success == false -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.primary
                                        }
                                    )
                                ) {
                                    if (isTestingVoice) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.tts_testing))
                                    } else {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(voiceTestResult?.message ?: stringResource(R.string.tts_test_voice))
                                    }
                                }
                            }
                        }

                        // === ELEVENLABS TTS SETTINGS ===
                        if (ttsProvider == SettingsRepository.TTS_PROVIDER_ELEVENLABS) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                // API Key
                                OutlinedTextField(
                                    value = elevenlabsApiKey,
                                    onValueChange = { elevenlabsApiKey = it; voiceTestResult = null },
                                    label = { Text(stringResource(R.string.tts_api_key_label)) },
                                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                                    trailingIcon = {
                                        IconButton(onClick = { showElevenlabsApiKey = !showElevenlabsApiKey }) {
                                            Icon(
                                                if (showElevenlabsApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = stringResource(R.string.show_api_key)
                                            )
                                        }
                                    },
                                    visualTransformation = if (showElevenlabsApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                // Voice dropdown
                                ExposedDropdownMenuBox(
                                    expanded = showElevenlabsVoiceMenu,
                                    onExpandedChange = { showElevenlabsVoiceMenu = it }
                                ) {
                                    OutlinedTextField(
                                        value = ElevenLabsTTSProvider.VOICES.find { it.id == elevenlabsVoice }?.displayName ?: elevenlabsVoice,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text(stringResource(R.string.tts_voice_label)) },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showElevenlabsVoiceMenu) },
                                        modifier = Modifier.fillMaxWidth().menuAnchor()
                                    )

                                    ExposedDropdownMenu(
                                        expanded = showElevenlabsVoiceMenu,
                                        onDismissRequest = { showElevenlabsVoiceMenu = false }
                                    ) {
                                        ElevenLabsTTSProvider.VOICES.forEach { voice ->
                                            DropdownMenuItem(
                                                text = { Text(voice.displayName) },
                                                onClick = {
                                                    elevenlabsVoice = voice.id
                                                    showElevenlabsVoiceMenu = false
                                                },
                                                leadingIcon = {
                                                    if (elevenlabsVoice == voice.id) {
                                                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                // Test Voice button
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isTestingVoice = true
                                            voiceTestResult = null
                                            try {
                                                val provider = ElevenLabsTTSProvider(elevenlabsApiKey, elevenlabsVoice)
                                                val audio = provider.synthesize(context.getString(R.string.tts_test_sample))
                                                val player = com.openclaw.assistant.speech.AudioPlayer(context)
                                                val success = player.playFromBytes(audio)
                                                voiceTestResult = if (success) {
                                                    TestResult(true, context.getString(R.string.tts_test_success))
                                                } else {
                                                    TestResult(false, context.getString(R.string.tts_test_failed, "Playback failed"))
                                                }
                                            } catch (e: Exception) {
                                                voiceTestResult = TestResult(false, context.getString(R.string.tts_test_failed, e.message ?: "Unknown error"))
                                            } finally {
                                                isTestingVoice = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = elevenlabsApiKey.isNotBlank() && !isTestingVoice,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = when {
                                            voiceTestResult?.success == true -> Color(0xFF4CAF50)
                                            voiceTestResult?.success == false -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.primary
                                        }
                                    )
                                ) {
                                    if (isTestingVoice) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.tts_testing))
                                    } else {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(voiceTestResult?.message ?: stringResource(R.string.tts_test_voice))
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.continuous_conversation), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.auto_start_mic), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Switch(checked = continuousMode, onCheckedChange = { continuousMode = it })
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // === WAKE WORD SECTION ===
            Text(
                text = stringResource(R.string.wake_word),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ExposedDropdownMenuBox(
                        expanded = showWakeWordMenu,
                        onExpandedChange = { showWakeWordMenu = it }
                    ) {
                        OutlinedTextField(
                            value = wakeWordOptions.find { it.first == wakeWordPreset }?.second ?: stringResource(R.string.wake_word_openclaw),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.activation_phrase)) },
                            leadingIcon = { Icon(Icons.Default.Mic, contentDescription = null) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showWakeWordMenu) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        
                        ExposedDropdownMenu(
                            expanded = showWakeWordMenu,
                            onDismissRequest = { showWakeWordMenu = false }
                        ) {
                            wakeWordOptions.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        wakeWordPreset = value
                                        showWakeWordMenu = false
                                    },
                                    leadingIcon = {
                                        if (wakeWordPreset == value) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    
                    if (wakeWordPreset == SettingsRepository.WAKE_WORD_CUSTOM) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = customWakeWord,
                            onValueChange = { customWakeWord = it.lowercase() },
                            label = { Text(stringResource(R.string.custom_wake_word)) },
                            placeholder = { Text(stringResource(R.string.custom_wake_word_hint)) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            supportingText = {
                                Text(stringResource(R.string.custom_wake_word_help), color = Color.Gray, fontSize = 12.sp)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

data class TestResult(
    val success: Boolean,
    val message: String
)
