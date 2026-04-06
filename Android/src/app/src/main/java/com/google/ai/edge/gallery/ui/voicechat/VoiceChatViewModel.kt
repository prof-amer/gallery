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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModelBase
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGVoiceChatVM"

private const val AUDIO_METER_MIN_DB = -2.0f
private const val AUDIO_METER_MAX_DB = 100.0f

/** The state of the voice chat conversation loop. */
enum class VoiceChatState {
  IDLE,
  LISTENING,
  PROCESSING,
  SPEAKING,
}

data class VoiceChatUiState(
  val voiceState: VoiceChatState = VoiceChatState.IDLE,
  val partialTranscription: String = "",
  val finalTranscription: String = "",
  val responseText: String = "",
  val amplitude: Int = 0,
)

@HiltViewModel
class VoiceChatViewModel
@Inject
constructor(@ApplicationContext private val context: Context) :
  LlmChatViewModelBase(), RecognitionListener {

  private val _voiceUiState = MutableStateFlow(VoiceChatUiState())
  val voiceUiState = _voiceUiState.asStateFlow()

  private var speechRecognizer: SpeechRecognizer? = null
  private var ttsManager: TtsManager? = null

  private var currentModel: Model? = null
  private var currentTask: Task? = null
  private var currentModelManagerViewModel: ModelManagerViewModel? = null
  private var autoListenAfterSpeaking = true

  private val recognizerIntent: Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
      putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
      putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }

  fun initialize(model: Model, task: Task, modelManagerViewModel: ModelManagerViewModel) {
    currentModel = model
    currentTask = task
    currentModelManagerViewModel = modelManagerViewModel

    if (speechRecognizer == null) {
      speechRecognizer =
        SpeechRecognizer.createSpeechRecognizer(context).apply {
          setRecognitionListener(this@VoiceChatViewModel)
        }
    }
    if (ttsManager == null) {
      ttsManager = TtsManager(context)
    }
  }

  fun startListening() {
    val state = _voiceUiState.value.voiceState
    if (state == VoiceChatState.SPEAKING) {
      interrupt()
      return
    }
    if (state != VoiceChatState.IDLE) return

    _voiceUiState.update {
      it.copy(
        voiceState = VoiceChatState.LISTENING,
        partialTranscription = "",
        finalTranscription = "",
        amplitude = 0,
      )
    }
    speechRecognizer?.startListening(recognizerIntent)
  }

  fun stopListeningAndSend() {
    if (_voiceUiState.value.voiceState != VoiceChatState.LISTENING) return

    speechRecognizer?.stopListening()
    // The actual send happens in onResults callback
  }

  fun interrupt() {
    ttsManager?.stop()
    speechRecognizer?.cancel()
    setInProgress(false)
    _voiceUiState.update {
      it.copy(voiceState = VoiceChatState.IDLE, amplitude = 0)
    }
  }

  fun onMicTapped() {
    when (_voiceUiState.value.voiceState) {
      VoiceChatState.IDLE -> startListening()
      VoiceChatState.LISTENING -> stopListeningAndSend()
      VoiceChatState.SPEAKING -> interrupt()
      VoiceChatState.PROCESSING -> {
        // Stop the current inference
        currentModel?.let { stopResponse(it) }
        interrupt()
      }
    }
  }

  private fun sendToLlm(text: String) {
    val model = currentModel ?: return
    val task = currentTask ?: return

    _voiceUiState.update {
      it.copy(
        voiceState = VoiceChatState.PROCESSING,
        finalTranscription = text,
        responseText = "",
        amplitude = 0,
      )
    }

    // Add user message to chat history (for multi-turn context).
    addMessage(model = model, message = ChatMessageText(content = text, side = ChatSide.USER))

    generateResponse(
      model = model,
      input = text,
      onDone = { onLlmResponseDone(model) },
      onError = { errorMessage ->
        Log.e(TAG, "LLM error: $errorMessage")
        handleError(
          context = context,
          task = task,
          model = model,
          modelManagerViewModel = currentModelManagerViewModel!!,
          errorMessage = errorMessage,
        )
        _voiceUiState.update { it.copy(voiceState = VoiceChatState.IDLE) }
      },
    )
  }

  private fun onLlmResponseDone(model: Model) {
    // Extract the response text from the last agent message.
    val lastMessage =
      getLastMessageWithTypeAndSide(model, ChatMessageType.TEXT, ChatSide.AGENT) as? ChatMessageText
    val responseText = lastMessage?.content ?: ""

    _voiceUiState.update { it.copy(responseText = responseText) }

    if (responseText.isNotEmpty()) {
      _voiceUiState.update { it.copy(voiceState = VoiceChatState.SPEAKING) }
      ttsManager?.speak(
        text = responseText,
        onStart = { Log.d(TAG, "TTS started") },
        onDone = {
          viewModelScope.launch(Dispatchers.Main) {
            if (autoListenAfterSpeaking) {
              _voiceUiState.update {
                it.copy(
                  voiceState = VoiceChatState.LISTENING,
                  partialTranscription = "",
                  amplitude = 0,
                )
              }
              speechRecognizer?.startListening(recognizerIntent)
            } else {
              _voiceUiState.update { it.copy(voiceState = VoiceChatState.IDLE) }
            }
          }
        },
        onError = {
          viewModelScope.launch(Dispatchers.Main) {
            _voiceUiState.update { it.copy(voiceState = VoiceChatState.IDLE) }
          }
        },
      )
    } else {
      _voiceUiState.update { it.copy(voiceState = VoiceChatState.IDLE) }
    }
  }

  // RecognitionListener callbacks

  override fun onReadyForSpeech(params: Bundle?) {}

  override fun onBeginningOfSpeech() {}

  override fun onRmsChanged(rmsdB: Float) {
    val amplitude = convertRmsDbToAmplitude(rmsdB)
    _voiceUiState.update { it.copy(amplitude = amplitude) }
  }

  override fun onBufferReceived(buffer: ByteArray?) {}

  override fun onEndOfSpeech() {}

  override fun onError(error: Int) {
    Log.e(TAG, "Speech recognition error: $error")
    // Error 7 = no speech detected, 8 = recognizer busy — go back to idle
    viewModelScope.launch(Dispatchers.Main) {
      _voiceUiState.update {
        it.copy(voiceState = VoiceChatState.IDLE, amplitude = 0)
      }
    }
  }

  override fun onResults(results: Bundle?) {
    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
    val text = matches?.firstOrNull() ?: ""

    if (text.isNotEmpty()) {
      sendToLlm(text)
    } else {
      _voiceUiState.update { it.copy(voiceState = VoiceChatState.IDLE) }
    }
  }

  override fun onPartialResults(partialResults: Bundle?) {
    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
    val text = matches?.firstOrNull() ?: ""
    _voiceUiState.update { it.copy(partialTranscription = text) }
  }

  override fun onEvent(eventType: Int, params: Bundle?) {}

  override fun onCleared() {
    super.onCleared()
    ttsManager?.shutdown()
    speechRecognizer?.destroy()
    speechRecognizer = null
  }

  companion object {
    private fun convertRmsDbToAmplitude(rmsdB: Float): Int {
      val clamped = rmsdB.coerceIn(AUDIO_METER_MIN_DB, AUDIO_METER_MAX_DB)
      return ((clamped - AUDIO_METER_MIN_DB) * 65535f / (AUDIO_METER_MAX_DB - AUDIO_METER_MIN_DB))
        .toInt()
    }
  }
}
