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

import java.util.Locale

/**
 * Detects the dominant language of a text string using Unicode block analysis.
 * Used to set the correct TTS locale for multilingual voice chat responses.
 */
object LanguageDetector {

  fun detectLanguage(text: String): Locale {
    var japanese = 0
    var cjkIdeograph = 0
    var korean = 0
    var cyrillic = 0
    var arabic = 0
    var devanagari = 0
    var latin = 0

    for (char in text) {
      val block = Character.UnicodeBlock.of(char) ?: continue
      when (block) {
        Character.UnicodeBlock.HIRAGANA,
        Character.UnicodeBlock.KATAKANA,
        Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS -> japanese++

        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B,
        Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS -> cjkIdeograph++

        Character.UnicodeBlock.HANGUL_SYLLABLES,
        Character.UnicodeBlock.HANGUL_JAMO,
        Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO -> korean++

        Character.UnicodeBlock.CYRILLIC,
        Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY -> cyrillic++

        Character.UnicodeBlock.ARABIC,
        Character.UnicodeBlock.ARABIC_SUPPLEMENT -> arabic++

        Character.UnicodeBlock.DEVANAGARI -> devanagari++

        Character.UnicodeBlock.BASIC_LATIN,
        Character.UnicodeBlock.LATIN_1_SUPPLEMENT,
        Character.UnicodeBlock.LATIN_EXTENDED_A,
        Character.UnicodeBlock.LATIN_EXTENDED_B -> latin++
      }
    }

    // Hiragana/Katakana is a definitive signal for Japanese.
    // CJK ideographs used alongside kana are also Japanese.
    if (japanese > 0) return Locale.JAPANESE
    if (korean > 0 && korean >= cjkIdeograph) return Locale.KOREAN
    if (cjkIdeograph > 0 && cjkIdeograph > latin) return Locale.CHINESE
    if (cyrillic > 0 && cyrillic > latin) return Locale("ru")
    if (arabic > 0 && arabic > latin) return Locale("ar")
    if (devanagari > 0 && devanagari > latin) return Locale("hi")

    return Locale.US
  }
}
