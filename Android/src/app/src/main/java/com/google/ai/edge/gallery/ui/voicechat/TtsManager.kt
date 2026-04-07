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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

private const val TAG = "AGTtsManager"

/**
 * Wraps Android's TextToSpeech API with lifecycle callbacks for use in voice chat.
 * Supports dynamic language switching and selects the highest-quality available voice.
 */
class TtsManager(context: Context) {
  private var tts: TextToSpeech? = null
  private var isReady = false
  private var currentLocale: Locale = Locale.US
  private var onSpeakingDone: (() -> Unit)? = null
  private var onSpeakingStart: (() -> Unit)? = null
  private var onSpeakingError: (() -> Unit)? = null

  init {
    tts =
      TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS) {
          isReady = applyLanguage(Locale.US)
          if (!isReady) {
            Log.e(TAG, "TTS language not supported")
          }

          tts?.setSpeechRate(0.95f)
          tts?.setPitch(1.0f)

          tts?.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
              override fun onStart(utteranceId: String?) {
                onSpeakingStart?.invoke()
              }

              override fun onDone(utteranceId: String?) {
                onSpeakingDone?.invoke()
              }

              @Deprecated("Deprecated in Java")
              override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS error for utterance: $utteranceId")
                onSpeakingError?.invoke()
              }

              override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS error for utterance: $utteranceId, code: $errorCode")
                onSpeakingError?.invoke()
              }
            }
          )
        } else {
          Log.e(TAG, "TTS initialization failed with status: $status")
        }
      }
  }

  /**
   * Sets the TTS language and selects the best available voice for that locale.
   * Returns true if the language is supported.
   */
  private fun applyLanguage(locale: Locale): Boolean {
    val result = tts?.setLanguage(locale)
    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
      Log.w(TAG, "TTS language not supported: ${locale.toLanguageTag()}")
      return false
    }
    currentLocale = locale
    selectBestVoice(locale)
    return true
  }

  /**
   * Selects the highest-quality voice available for the given locale.
   * Prefers offline voices at equal quality to avoid network latency.
   */
  private fun selectBestVoice(locale: Locale) {
    val voices = tts?.voices ?: return

    val matchingVoices = voices.filter { voice ->
      voice.locale.language == locale.language && !voice.isNetworkConnectionRequired
    }

    // Fall back to network voices if no offline voices match.
    val candidates = matchingVoices.ifEmpty {
      voices.filter { it.locale.language == locale.language }
    }

    if (candidates.isEmpty()) return

    val best = candidates.maxByOrNull { it.quality }
    if (best != null) {
      Log.d(TAG, "Selected voice: ${best.name} (quality=${best.quality}, locale=${best.locale})")
      tts?.voice = best
    }
  }

  fun speak(
    text: String,
    locale: Locale? = null,
    onStart: (() -> Unit)? = null,
    onDone: (() -> Unit)? = null,
    onError: (() -> Unit)? = null,
  ) {
    if (!isReady) {
      Log.w(TAG, "TTS not ready, skipping speak")
      onError?.invoke()
      return
    }

    // Switch language if needed.
    if (locale != null && locale.language != currentLocale.language) {
      if (!applyLanguage(locale)) {
        Log.w(TAG, "Cannot speak in ${locale.toLanguageTag()}, language not available")
        onError?.invoke()
        return
      }
    }

    onSpeakingStart = onStart
    onSpeakingDone = onDone
    onSpeakingError = onError

    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice_chat_utterance")
  }

  fun stop() {
    tts?.stop()
  }

  fun isSpeaking(): Boolean {
    return tts?.isSpeaking == true
  }

  fun shutdown() {
    tts?.stop()
    tts?.shutdown()
    tts = null
    isReady = false
  }
}
