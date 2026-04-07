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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.litertlm.Contents
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

private const val VOICE_CHAT_SYSTEM_PROMPT =
  "You are a voice assistant. Respond in plain, conversational language. " +
    "Do NOT use emojis, markdown formatting, bullet points, numbered lists, " +
    "code blocks, or any special characters. Keep responses concise and natural, " +
    "as they will be spoken aloud."

class LlmVoiceChatTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = BuiltInTaskId.LLM_VOICE_CHAT,
      label = "Voice Chat",
      category = Category.LLM,
      icon = Icons.Outlined.RecordVoiceOver,
      models = mutableListOf(),
      description = "Have a voice conversation with on-device large language models",
      shortDescription = "Voice conversation with LLM",
      docUrl = "https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/voicechat/VoiceChatScreen.kt",
      textInputPlaceHolderRes = R.string.voicechat_tap_to_talk,
      newFeature = true,
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: (String) -> Unit,
  ) {
    model.runtimeHelper.initialize(
      context = context,
      model = model,
      supportImage = false,
      supportAudio = false,
      systemInstruction = Contents.of(VOICE_CHAT_SYSTEM_PROMPT),
      onDone = onDone,
      coroutineScope = coroutineScope,
    )
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    model.runtimeHelper.cleanUp(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskDataForBuiltinTask
    VoiceChatScreen(
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object VoiceChatTaskModule {
  @Provides
  @IntoSet
  fun provideTask(): CustomTask {
    return LlmVoiceChatTask()
  }
}
