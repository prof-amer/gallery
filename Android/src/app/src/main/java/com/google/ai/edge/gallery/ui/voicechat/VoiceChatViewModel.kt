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

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.common.calculatePeakAmplitude
import com.google.ai.edge.gallery.data.MAX_AUDIO_CLIP_DURATION_SEC
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.SAMPLE_RATE
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageAudioClip
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModelBase
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "AGVoiceChatVM"

private const val AUDIO_METER_MIN_DB = -2.0f
private const val AUDIO_METER_MAX_DB = 100.0f

private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

/** The state of the voice chat conversation loop. */
enum class VoiceChatState {
  IDLE,
  LISTENING,
  PROCESSING,
  SPEAKING,
}

/** Supported recognition languages for the voice chat language toggle (STT fallback only). */
enum class RecognitionLanguage(val tag: String, val label: String) {
  ENGLISH("en-US", "EN"),
  JAPANESE("ja-JP", "JP"),
}

data class VoiceChatUiState(
  val voiceState: VoiceChatState = VoiceChatState.IDLE,
  val partialTranscription: String = "",
  val finalTranscription: String = "",
  val responseText: String = "",
  val amplitude: Int = 0,
  val recognitionLanguage: RecognitionLanguage = RecognitionLanguage.ENGLISH,
  /** True when the selected model natively understands audio (e.g. Gemma 4 E2B/E4B). */
  val nativeAudioMode: Boolean = false,
)

@HiltViewModel
class VoiceChatViewModel
@Inject
constructor(@ApplicationContext private val context: Context) :
  LlmChatViewModelBase(), RecognitionListener {

  private val _voiceUiState = MutableStateFlow(VoiceChatUiState())
  val voiceUiState = _voiceUiState.asStateFlow()

  // STT fallback components.
  private var speechRecognizer: SpeechRecognizer? = null
  private var ttsManager: TtsManager? = null

  // Native audio recording components.
  private var audioRecord: AudioRecord? = null
  private var audioStream = ByteArrayOutputStream()
  private var recordingJob: Job? = null

  private var currentModel: Model? = null
  private var currentTask: Task? = null
  private var currentModelManagerViewModel: ModelManagerViewModel? = null
  private var autoListenAfterSpeaking = true

  private val useNativeAudio: Boolean
    get() = _voiceUiState.value.nativeAudioMode

  // --- STT fallback recognizer intent ---

  private fun createRecognizerIntent(): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, _voiceUiState.value.recognitionLanguage.tag)
      putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
      putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }

  fun toggleLanguage() {
    if (_voiceUiState.value.voiceState != VoiceChatState.IDLE) return
    _voiceUiState.update {
      val next = if (it.recognitionLanguage == RecognitionLanguage.ENGLISH)
        RecognitionLanguage.JAPANESE else RecognitionLanguage.ENGLISH
      it.copy(recognitionLanguage = next)
    }
  }

  // --- Initialization ---

  fun initialize(model: Model, task: Task, modelManagerViewModel: ModelManagerViewModel) {
    currentModel = model
    currentTask = task
    currentModelManagerViewModel = modelManagerViewModel

    val nativeAudio = model.llmSupportAudio
    _voiceUiState.update { it.copy(nativeAudioMode = nativeAudio) }

    if (!nativeAudio && speechRecognizer == null) {
      speechRecognizer =
        SpeechRecognizer.createSpeechRecognizer(context).apply {
          setRecognitionListener(this@VoiceChatViewModel)
        }
    }
    if (ttsManager == null) {
      ttsManager = TtsManager(context)
    }
  }

  // --- Public actions ---

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

    if (useNativeAudio) {
      startNativeAudioRecording()
    } else {
      speechRecognizer?.startListening(createRecognizerIntent())
    }
  }

  fun stopListeningAndSend() {
    if (_voiceUiState.value.voiceState != VoiceChatState.LISTENING) return

    if (useNativeAudio) {
      stopNativeAudioAndSend()
    } else {
      speechRecognizer?.stopListening()
    }
  }

  fun interrupt() {
    ttsManager?.stop()
    if (useNativeAudio) {
      stopNativeAudioRecording()
    } else {
      speechRecognizer?.cancel()
    }
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
        currentModel?.let { stopResponse(it) }
        interrupt()
      }
    }
  }

  // --- Native audio recording (for Gemma 4 E2B/E4B) ---

  @SuppressLint("MissingPermission")
  private fun startNativeAudioRecording() {
    val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    audioRecord?.release()
    audioStream.reset()

    val recorder = AudioRecord(
      MediaRecorder.AudioSource.MIC,
      SAMPLE_RATE,
      CHANNEL_CONFIG,
      AUDIO_FORMAT,
      minBufferSize,
    )
    audioRecord = recorder
    recorder.startRecording()

    recordingJob = viewModelScope.launch(Dispatchers.IO) {
      val buffer = ByteArray(minBufferSize)
      val startMs = System.currentTimeMillis()
      while (isActive && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
        val bytesRead = recorder.read(buffer, 0, buffer.size)
        if (bytesRead > 0) {
          audioStream.write(buffer, 0, bytesRead)
          val amplitude = calculatePeakAmplitude(buffer = buffer, bytesRead = bytesRead)
          _voiceUiState.update { it.copy(amplitude = amplitude) }
        }
        val elapsedMs = System.currentTimeMillis() - startMs
        if (elapsedMs >= MAX_AUDIO_CLIP_DURATION_SEC * 1000) {
          launch(Dispatchers.Main) { stopListeningAndSend() }
          break
        }
      }
    }
  }

  private fun stopNativeAudioRecording() {
    recordingJob?.cancel()
    recordingJob = null
    val recorder = audioRecord
    if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
      recorder.stop()
    }
    recorder?.release()
    audioRecord = null
  }

  private fun stopNativeAudioAndSend() {
    stopNativeAudioRecording()
    val audioData = audioStream.toByteArray()
    audioStream.reset()

    if (audioData.isEmpty()) {
      _voiceUiState.update { it.copy(voiceState = VoiceChatState.IDLE) }
      return
    }

    sendAudioToLlm(audioData)
  }

  private fun sendAudioToLlm(audioData: ByteArray) {
    val model = currentModel ?: return
    val task = currentTask ?: return

    if (model.instance == null) {
      Log.w(TAG, "Model instance is null, cannot run inference.")
      _voiceUiState.update {
        it.copy(
          voiceState = VoiceChatState.IDLE,
          responseText = "Model is still loading. Please wait and try again.",
        )
      }
      ttsManager?.speak(text = "Model is still loading. Please wait and try again.")
      return
    }

    _voiceUiState.update {
      it.copy(
        voiceState = VoiceChatState.PROCESSING,
        finalTranscription = "(audio sent directly to model)",
        responseText = "",
        amplitude = 0,
      )
    }

    val audioClip = ChatMessageAudioClip(
      audioData = audioData,
      sampleRate = SAMPLE_RATE,
      side = ChatSide.USER,
    )

    // Add a text placeholder to chat history for multi-turn context.
    addMessage(model = model, message = ChatMessageText(content = "[voice input]", side = ChatSide.USER))

    generateResponse(
      model = model,
      input = "",
      audioMessages = listOf(audioClip),
      onDone = {
        Log.d(TAG, "LLM inference done (native audio), extracting response")
        onLlmResponseDone(model)
      },
      onError = { errorMessage ->
        Log.e(TAG, "LLM error: $errorMessage")
        handleError(
          context = context,
          task = task,
          model = model,
          modelManagerViewModel = currentModelManagerViewModel!!,
          errorMessage = errorMessage,
        )
        viewModelScope.launch(Dispatchers.Main) {
          _voiceUiState.update { it.copy(voiceState = VoiceChatState.IDLE) }
        }
      },
    )
  }

  // --- STT fallback: send transcribed text ---

  private fun sendToLlm(text: String) {
    val model = currentModel ?: return
    val task = currentTask ?: return

    if (model.instance == null) {
      Log.w(TAG, "Model instance is null, cannot run inference. Model may still be initializing.")
      _voiceUiState.update {
        it.copy(
          voiceState = VoiceChatState.IDLE,
          responseText = "Model is still loading. Please wait and try again.",
        )
      }
      ttsManager?.speak(text = "Model is still loading. Please wait and try again.")
      return
    }

    _voiceUiState.update {
      it.copy(
        voiceState = VoiceChatState.PROCESSING,
        finalTranscription = text,
        responseText = "",
        amplitude = 0,
      )
    }

    addMessage(model = model, message = ChatMessageText(content = text, side = ChatSide.USER))

    generateResponse(
      model = model,
      input = text,
      onDone = {
        Log.d(TAG, "LLM inference done, extracting response")
        onLlmResponseDone(model)
      },
      onError = { errorMessage ->
        Log.e(TAG, "LLM error: $errorMessage")
        handleError(
          context = context,
          task = task,
          model = model,
          modelManagerViewModel = currentModelManagerViewModel!!,
          errorMessage = errorMessage,
        )
        viewModelScope.launch(Dispatchers.Main) {
          _voiceUiState.update { it.copy(voiceState = VoiceChatState.IDLE) }
        }
      },
    )
  }

  // --- Response handling (shared by both modes) ---

  private fun onLlmResponseDone(model: Model) {
    val lastMessage =
      getLastMessageWithTypeAndSide(model, ChatMessageType.TEXT, ChatSide.AGENT) as? ChatMessageText
    val responseText = lastMessage?.content ?: ""
    Log.d(TAG, "LLM response: '${responseText.take(100)}'")

    viewModelScope.launch(Dispatchers.Main) {
      _voiceUiState.update { it.copy(responseText = responseText) }

      if (responseText.isNotEmpty()) {
        val detectedLocale = LanguageDetector.detectLanguage(responseText)
        // Update STT language toggle to match conversation language.
        if (!useNativeAudio) {
          val detectedRecognitionLang = when (detectedLocale.language) {
            "ja" -> RecognitionLanguage.JAPANESE
            else -> RecognitionLanguage.ENGLISH
          }
          _voiceUiState.update { it.copy(recognitionLanguage = detectedRecognitionLang) }
        }
        _voiceUiState.update { it.copy(voiceState = VoiceChatState.SPEAKING) }
        ttsManager?.speak(
          text = cleanTextForTts(responseText),
          locale = detectedLocale,
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
                if (useNativeAudio) {
                  startNativeAudioRecording()
                } else {
                  speechRecognizer?.startListening(createRecognizerIntent())
                }
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
  }

  // --- RecognitionListener callbacks (STT fallback only) ---

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
    stopNativeAudioRecording()
    speechRecognizer?.destroy()
    speechRecognizer = null
  }

  companion object {
    private fun convertRmsDbToAmplitude(rmsdB: Float): Int {
      val clamped = rmsdB.coerceIn(AUDIO_METER_MIN_DB, AUDIO_METER_MAX_DB)
      return ((clamped - AUDIO_METER_MIN_DB) * 65535f / (AUDIO_METER_MAX_DB - AUDIO_METER_MIN_DB))
        .toInt()
    }

    /** Strip emojis, markdown, and other non-speech artifacts from LLM output for TTS. */
    private fun cleanTextForTts(text: String): String {
      return text
        // Remove emoji characters.
        .replace(Regex("[\\p{So}\\p{Cn}\\uFE0F\\u200D]"), "")
        // Remove markdown bold/italic markers.
        .replace(Regex("\\*+"), "")
        // Remove markdown headers.
        .replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
        // Remove markdown links, keep text.
        .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
        // Remove markdown code blocks.
        .replace(Regex("```[\\s\\S]*?```"), "")
        // Remove inline code.
        .replace(Regex("`([^`]+)`"), "$1")
        // Remove bullet points.
        .replace(Regex("^\\s*[-*•]\\s+", RegexOption.MULTILINE), "")
        // Collapse multiple whitespace/newlines.
        .replace(Regex("\\s+"), " ")
        .trim()
    }
  }
}
