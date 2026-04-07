/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.voicechat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.ui.common.AudioAnimation
import com.google.ai.edge.gallery.ui.modelmanager.ModelDownloadStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

@Composable
fun VoiceChatScreen(
  modelManagerViewModel: ModelManagerViewModel,
  navigateUp: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: VoiceChatViewModel = hiltViewModel(),
) {
  val voiceUiState by viewModel.voiceUiState.collectAsState()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val selectedModel = modelManagerUiState.selectedModel
  val task = modelManagerViewModel.getTaskById(id = BuiltInTaskId.LLM_VOICE_CHAT)!!
  val context = LocalContext.current

  // Check model readiness.
  val modelInitStatus = modelManagerUiState.modelInitializationStatus[selectedModel.name]
  val isModelReady = modelInitStatus?.status == ModelInitializationStatusType.INITIALIZED
  val isModelInitializing = modelInitStatus?.status == ModelInitializationStatusType.INITIALIZING

  var permissionGranted by remember { mutableStateOf(false) }
  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      permissionGranted = granted
    }

  LaunchedEffect(Unit) {
    when (PackageManager.PERMISSION_GRANTED) {
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) -> {
        permissionGranted = true
      }
      else -> {
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
      }
    }
  }

  // Ensure model is initialized for Voice Chat.
  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]
  LaunchedEffect(selectedModel, curDownloadStatus, modelInitStatus) {
    viewModel.initialize(selectedModel, task, modelManagerViewModel)
    // If model is downloaded but not initialized, trigger initialization.
    if (
      curDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED &&
        modelInitStatus?.status != ModelInitializationStatusType.INITIALIZED &&
        modelInitStatus?.status != ModelInitializationStatusType.INITIALIZING
    ) {
      modelManagerViewModel.initializeModel(context, task = task, model = selectedModel)
    }
  }

  DisposableEffect(Unit) { onDispose { viewModel.interrupt() } }

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(Color.Black),
  ) {
    // Audio animation background — active during LISTENING and SPEAKING.
    val showAnimation =
      voiceUiState.voiceState == VoiceChatState.LISTENING ||
        voiceUiState.voiceState == VoiceChatState.SPEAKING

    AudioAnimation(
      bgColor =
        if (showAnimation) Color.Black.copy(alpha = 0.85f)
        else Color.Black.copy(alpha = 0.92f),
      amplitude = if (showAnimation) voiceUiState.amplitude else 0,
      modifier = Modifier.fillMaxSize(),
    )

    // Main content.
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(horizontal = 24.dp)
          .clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
          ) {
            if (voiceUiState.voiceState == VoiceChatState.SPEAKING) {
              viewModel.interrupt()
            }
          },
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      // User transcription text.
      AnimatedVisibility(
        visible =
          voiceUiState.partialTranscription.isNotEmpty() ||
            voiceUiState.finalTranscription.isNotEmpty(),
        enter = fadeIn(),
        exit = fadeOut(),
      ) {
        Text(
          text =
            voiceUiState.finalTranscription.ifEmpty { voiceUiState.partialTranscription },
          color = Color.White.copy(alpha = 0.7f),
          style = MaterialTheme.typography.bodyMedium,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(horizontal = 16.dp),
        )
      }

      Spacer(modifier = Modifier.height(32.dp))

      // Central orb / mic button.
      if (!permissionGranted) {
        Text(
          "Microphone permission is required for voice chat.",
          color = Color.White.copy(alpha = 0.7f),
          textAlign = TextAlign.Center,
        )
      } else if (!isModelReady) {
        // Model loading state.
        VoiceChatOrbButton(
          voiceState = VoiceChatState.PROCESSING,
          onTap = {},
          enabled = false,
        )
      } else {
        VoiceChatOrbButton(
          voiceState = voiceUiState.voiceState,
          onTap = { viewModel.onMicTapped() },
          enabled = true,
        )
      }

      Spacer(modifier = Modifier.height(24.dp))

      // Status label.
      Text(
        text =
          when {
            !isModelReady && isModelInitializing -> "Initializing model\u2026"
            !isModelReady -> "Waiting for model\u2026"
            voiceUiState.voiceState == VoiceChatState.IDLE ->
              stringResource(R.string.voicechat_tap_to_talk)
            voiceUiState.voiceState == VoiceChatState.LISTENING ->
              stringResource(R.string.voicechat_listening)
            voiceUiState.voiceState == VoiceChatState.PROCESSING ->
              stringResource(R.string.voicechat_thinking)
            voiceUiState.voiceState == VoiceChatState.SPEAKING ->
              stringResource(R.string.voicechat_tap_to_interrupt)
            else -> ""
          },
        color = Color.White.copy(alpha = 0.8f),
        style = MaterialTheme.typography.bodyLarge,
      )

      Spacer(modifier = Modifier.height(32.dp))

      // Response text (shown during SPEAKING and after).
      AnimatedVisibility(
        visible = voiceUiState.responseText.isNotEmpty(),
        enter = fadeIn(),
        exit = fadeOut(),
      ) {
        Text(
          text = voiceUiState.responseText,
          color = Color.White,
          style = MaterialTheme.typography.bodyLarge,
          textAlign = TextAlign.Center,
          maxLines = 8,
          modifier = Modifier.padding(horizontal = 16.dp),
        )
      }
    }
  }
}

@Composable
private fun VoiceChatOrbButton(
  voiceState: VoiceChatState,
  onTap: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  val infiniteTransition = rememberInfiniteTransition(label = "orb_pulse")
  val pulseScale by
    infiniteTransition.animateFloat(
      initialValue = 1f,
      targetValue = 1.15f,
      animationSpec =
        infiniteRepeatable(animation = tween(800), repeatMode = RepeatMode.Reverse),
      label = "pulse",
    )

  val isActive = voiceState == VoiceChatState.LISTENING || voiceState == VoiceChatState.SPEAKING
  val scale = if (isActive) pulseScale else 1f

  val bgColor =
    when {
      !enabled -> Color.White.copy(alpha = 0.08f)
      voiceState == VoiceChatState.IDLE -> Color.White.copy(alpha = 0.15f)
      voiceState == VoiceChatState.LISTENING -> Color(0xFF4CAF50).copy(alpha = 0.8f)
      voiceState == VoiceChatState.PROCESSING -> Color.White.copy(alpha = 0.1f)
      voiceState == VoiceChatState.SPEAKING -> Color(0xFF2196F3).copy(alpha = 0.8f)
      else -> Color.White.copy(alpha = 0.15f)
    }

  Box(
    modifier =
      modifier
        .scale(scale)
        .size(120.dp)
        .clip(CircleShape)
        .background(bgColor)
        .then(if (enabled) Modifier.clickable { onTap() } else Modifier),
    contentAlignment = Alignment.Center,
  ) {
    when {
      !enabled -> {
        CircularProgressIndicator(
          modifier = Modifier.size(48.dp),
          color = Color.White.copy(alpha = 0.5f),
          strokeWidth = 3.dp,
        )
      }
      voiceState == VoiceChatState.PROCESSING -> {
        CircularProgressIndicator(
          modifier = Modifier.size(48.dp),
          color = Color.White,
          strokeWidth = 3.dp,
        )
      }
      voiceState == VoiceChatState.SPEAKING -> {
        Icon(
          Icons.Filled.Stop,
          contentDescription = "Stop speaking",
          tint = Color.White,
          modifier = Modifier.size(48.dp),
        )
      }
      else -> {
        Icon(
          Icons.Filled.Mic,
          contentDescription = "Microphone",
          tint = Color.White,
          modifier = Modifier.size(48.dp),
        )
      }
    }
  }
}
