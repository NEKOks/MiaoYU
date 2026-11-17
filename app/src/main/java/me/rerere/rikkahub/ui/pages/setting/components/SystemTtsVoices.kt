package me.rerere.rikkahub.ui.pages.setting.components

import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 列出系统 TTS 引擎提供的发音人名称。
 *
 * 名字来自 [TextToSpeech.Voice.name]，选中后由 SystemTTSProvider 用 setVoice 应用。
 * 中文发音人排在前面，其余按名称排序。
 */
@Composable
fun rememberSystemVoiceNames(): List<String> {
    val context = LocalContext.current
    var voices by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        voices = loadSystemVoices(context)
    }
    return voices
}

private suspend fun loadSystemVoices(context: android.content.Context): List<String> =
    withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            var tts: TextToSpeech? = null
            tts = TextToSpeech(context) { status ->
                val names = if (status == TextToSpeech.SUCCESS) {
                    runCatching {
                        tts?.voices
                            ?.filter { it.locale.language == Locale.CHINESE.language }
                            ?.map { it.name }
                            ?.distinct()
                            ?.sorted()
                            .orEmpty()
                    }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                runCatching { tts?.shutdown() }
                if (continuation.isActive) continuation.resume(names)
            }
        }
    }