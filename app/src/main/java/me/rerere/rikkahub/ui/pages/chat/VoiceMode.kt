package me.rerere.rikkahub.ui.pages.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.providers.DashScopeASRController
import me.rerere.asr.providers.OpenAIRealtimeASRController
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import me.rerere.asr.providers.DeepSeekASRController
import me.rerere.asr.providers.LocalVadAsrController
import me.rerere.asr.providers.MiMoASRController
import me.rerere.asr.providers.StepASRController
import me.rerere.asr.providers.VolcengineASRController
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.VoiceListeningForegroundService
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.rikkahub.data.datastore.getSelectedTTSProvider
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalASRState
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.utils.extractQuotedContentAsText
import me.rerere.rikkahub.utils.removeAsteriskContent
import me.rerere.rikkahub.utils.removeBracketedContent
import me.rerere.rikkahub.utils.stripMarkdown
import okhttp3.OkHttpClient
import org.koin.compose.koinInject

/** 在打字框里说这些词就直接切进语音模式 */
private val VOICE_START_PHRASES = listOf(
    "语音聊天",
    "语音模式",
    "语音对话",
    "打语音",
    "开语音",
)
/** Lives above adaptive drawer branches, so resizing does not recreate the voice session. */
@Composable
fun rememberVoiceModeStarter(vm: ChatVM, settings: Settings): () -> Unit {
    val context = LocalContext.current.applicationContext
    val client = koinInject<OkHttpClient>()
    val asr = LocalASRState.current
    val tts = LocalTTSState.current
    val toaster = LocalToaster.current
    val permission = rememberPermissionState(PermissionRecordAudio)
    PermissionManager(permission)
    val voice = vm.voiceSession
    val scope = rememberCoroutineScope()
    val state by voice.state.collectAsStateWithLifecycle()
    val provider = settings.getSelectedASRProvider()
    val lifecycleOwner = LocalLifecycleOwner.current

    // 语音会话不再跟随 Activity 生命周期：切后台/锁屏时继续聆听，由前台服务保活；只有页面销毁才收尾
    DisposableEffect(voice, provider, settings.getSelectedTTSProvider()) {
        onDispose {
            VoiceListeningForegroundService.stop(context)
            voice.stop()
        }
    }

    // 通知栏上的「结束语音」按钮：前台服务收到后发广播，这里走和页面销毁一样的收尾，
    // 免得只关掉保活通知、录音还在后台继续。
    DisposableEffect(voice, context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                VoiceListeningForegroundService.stop(context)
                voice.stop()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(VoiceListeningForegroundService.ACTION_STOP_REQUEST),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val start: () -> Unit = {
        val blocked = when {
            provider == null -> context.getString(R.string.chat_page_voice_configure_asr)
            !provider.supportsVoiceMode -> context.getString(R.string.chat_page_voice_unsupported_asr)
            vm.currentChatModel.value == null -> context.getString(R.string.chat_page_voice_select_model)
            asr.state.value.isRecording -> context.getString(R.string.chat_page_voice_finish_dictation)
            vm.messageQueue.value.paused && vm.messageQueue.value.messages.isNotEmpty() ->
                context.getString(R.string.chat_page_voice_resume_queue)
            vm.conversation.value.currentMessages.any { message ->
                message.parts.any { it is UIMessagePart.Tool && it.isPending }
            } -> context.getString(R.string.chat_page_voice_pending_tools)
            else -> null
        }
        when {
            blocked != null -> toaster.show(message = blocked)
            !permission.allRequiredPermissionsGranted -> permission.requestPermissions()
            else -> {
                VoiceListeningForegroundService.start(context)
                voice.start(
                createAsr = { createVoiceAsr(context, client, checkNotNull(provider), scope) },
                speak = if (tts.isAvailable.value) {
                    { reply ->
                        var text = reply
                        // 角色扮演：只念台词（引号内），旁白、*动作*、（心理）都不念
                        if (vm.assistant.value.roleplay.isActive) {
                            text = text.extractQuotedContentAsText()
                                ?: text.removeAsteriskContent()?.removeBracketedContent()
                                ?: ""
                        }
                        if (settings.displaySetting.ttsOnlyReadQuoted) {
                            text = text.extractQuotedContentAsText() ?: text
                        }
                        if (settings.displaySetting.ttsOnlyReadOutsideBrackets) {
                            text = text.removeBracketedContent() ?: text
                        }
                        // tts.speak() strips markdown itself; stripping twice would turn decoded
                        // entities like &lt;tag&gt; into real tags and drop them.
                        if (text.stripMarkdown().isNotBlank()) {
                            tts.speak(text)
                            // playbackState.Ended is also emitted between chunks. isSpeaking
                            // remains true until the entire synthesis/playback queue finishes.
                            combine(tts.isSpeaking, tts.error) { speaking, error ->
                                check(error == null) { error.orEmpty() }
                                !speaking
                            }.first { it }
                        }
                    }
                } else {
                    null
                },
                stopSpeaking = tts::stop,
                listenWhileReplying = settings.displaySetting.voiceModeListenWhileReplying,
                onExit = {
                    VoiceListeningForegroundService.stop(context)
                    toaster.show(message = "已退出语音模式，继续打字聊吧")
                },
            )
            }
        }
    }

    // 在打字框里说「语音聊天」这类话，就直接切进语音模式，不用去点按钮
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val lastUserText = conversation.currentMessages
        .lastOrNull { it.role == MessageRole.USER }
        ?.parts
        ?.filterIsInstance<UIMessagePart.Text>()
        ?.joinToString("") { it.text }
        ?.trim()
        .orEmpty()
    LaunchedEffect(lastUserText) {
        if (lastUserText.isBlank()) return@LaunchedEffect
        if (state.isActive) return@LaunchedEffect
        if (VOICE_START_PHRASES.any { lastUserText.contains(it) }) start()
    }

    if (state.isActive) {
        val view = LocalView.current
        DisposableEffect(view) {
            val previous = view.keepScreenOn
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = previous }
        }
    }
    return start
}

private fun createVoiceAsr(context: Context, client: OkHttpClient, provider: ASRProviderSetting, scope: CoroutineScope): ASRController {
    val delegate = when (provider) {
        is ASRProviderSetting.OpenAIRealtime -> {
            check(provider.apiKey.isNotBlank()) { context.getString(R.string.chat_page_voice_configure_key) }
            OpenAIRealtimeASRController(context, client, provider)
        }
        is ASRProviderSetting.DashScope -> {
            check(provider.apiKey.isNotBlank()) { context.getString(R.string.chat_page_voice_configure_key) }
            DashScopeASRController(context, client, provider)
        }
        is ASRProviderSetting.Volcengine -> {
            check(provider.apiKey.isNotBlank()) { context.getString(R.string.chat_page_voice_configure_key) }
            VolcengineASRController(context, client, provider)
        }
        is ASRProviderSetting.DeepSeek -> LocalVadAsrController(
            delegate = DeepSeekASRController(context, client, provider),
            scope = scope,
        )
        is ASRProviderSetting.MiMo -> LocalVadAsrController(
            delegate = MiMoASRController(context, client, provider),
            scope = scope,
        )
        is ASRProviderSetting.Step -> LocalVadAsrController(
            delegate = StepASRController(context, client, provider),
            scope = scope,
        )
    }
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .build()
    return object : ASRController by delegate {
        override fun start(onTranscriptChange: (String) -> Unit) {
            check(audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                "Unable to acquire audio focus for the microphone. Try again later."
            }
            delegate.start(onTranscriptChange)
        }

        override fun dispose() {
            try {
                delegate.dispose()
            } finally {
                audioManager.abandonAudioFocusRequest(focus)
            }
        }
    }
}
