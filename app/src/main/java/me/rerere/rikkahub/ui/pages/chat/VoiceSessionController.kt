package me.rerere.rikkahub.ui.pages.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.asr.ASRController
import me.rerere.asr.ASRStatus
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.MessageQueuePausedException
import me.rerere.rikkahub.service.VoiceActivity

/** 用户一直不说话多久之后，让模型主动找个话题 */
private const val IDLE_NUDGE_TIMEOUT_MS = 25_000L

/** 沉默催促时发给模型的提示。用括号包起来，聊天记录里能看出这不是用户说的话 */
private const val IDLE_NUDGE_MESSAGE = "（安静了一会儿，你可以主动找个话题继续聊，一句话即可）"

/** 说出这些词就退出语音模式，回到打字 */
private val EXIT_PHRASES = listOf(
    "下次再聊",
    "下次聊",
    "打字聊",
    "打字吧",
    "退出语音",
    "关掉语音",
    "关闭语音",
    "先不聊了",
)

enum class VoicePhase { Off, Connecting, Listening, Transcribing, Waiting, Speaking, Error }

data class VoiceSessionState(
    val phase: VoicePhase = VoicePhase.Off,
    val transcript: String = "",
    val error: String? = null,
    val pendingReplies: Int = 0,
) {
    val isActive: Boolean get() = phase != VoicePhase.Off && phase != VoicePhase.Error
}

/** Capture and generation run independently. Only TTS owns an exclusive microphone pause. */
class VoiceSessionController(
    private val scope: CoroutineScope,
    private val getString: (Int) -> String,
    private val enqueueMessage: (String) -> Deferred<String?>,
) {
    private val mutableState = MutableStateFlow(VoiceSessionState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    private sealed interface Event {
        data class Utterance(val text: String) : Event
        data class Reply(val text: String?) : Event
        data class Failed(val error: Exception) : Event

        /** 用户沉默超时，这次采集没听到任何话 */
        data object Idle : Event
    }

    fun start(
        createAsr: () -> ASRController,
        speak: (suspend (String) -> Unit)?,
        stopSpeaking: () -> Unit,
        listenWhileReplying: Boolean = true,
        onExit: () -> Unit = {},
    ) {
        if (job?.isCompleted == false) return
        VoiceActivity.setActive(true)
        mutableState.value = VoiceSessionState(VoicePhase.Connecting)
        job = scope.launch {
            try {
                stopSpeaking()
                delay(200)
                runSession(createAsr, speak, listenWhileReplying, onExit)
            } catch (e: Exception) {
                if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                mutableState.update {
                    it.copy(
                        phase = VoicePhase.Error,
                        error = when (e) {
                            is TimeoutCancellationException -> "Speech recognition timed out. Restart voice mode."
                            is MessageQueuePausedException -> getString(R.string.chat_page_voice_queue_paused)
                            else -> e.message ?: getString(R.string.chat_page_voice_failed)
                        },
                    )
                }
            } finally {
                stopSpeaking()
                VoiceActivity.setActive(false)
            }
        }
    }

    private suspend fun runSession(
        createAsr: () -> ASRController,
        speak: (suspend (String) -> Unit)?,
        listenWhileReplying: Boolean,
        onExit: () -> Unit,
    ) = coroutineScope {
        val events = Channel<Event>(Channel.UNLIMITED)
        val submitted = Channel<Deferred<String?>>(Channel.UNLIMITED)
        val replies = ArrayDeque<String>()
        var asr: ASRController? = null
        var capture: Job? = null

        // Await replies in submission order, without blocking capture. Queue removal returns null.
        launch {
            try {
                for (reply in submitted) events.send(Event.Reply(reply.await()))
            } catch (e: Exception) {
                if (e is CancellationException && !isActive) throw e
                events.send(Event.Failed(e))
            }
        }

        try {
            while (isActive) {
                // Finish any sentence already in progress before giving TTS the microphone pause.
                if (speak != null && replies.isNotEmpty() && asr?.state?.value?.voiceTurn?.itemId == null) {
                    capture?.cancelAndJoin()
                    capture = null
                    asr = null
                    mutableState.update { it.copy(phase = VoicePhase.Speaking) }
                    speak(replies.removeFirst())
                    delay(300) // Let the loudspeaker's tail decay before opening the microphone.
                    continue
                }
                // Turn-based mode keeps the microphone closed until every reply has arrived and been spoken.
                if (capture == null && !listenWhileReplying && state.value.pendingReplies > 0) {
                    mutableState.update { it.copy(phase = VoicePhase.Waiting) }
                } else if (capture == null) {
                    mutableState.update { it.copy(phase = VoicePhase.Connecting, transcript = "") }
                    val recorder = createAsr()
                    asr = recorder
                    capture = launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            val heard = listen(recorder)
                            if (heard == null) {
                                events.send(Event.Idle)
                            } else {
                                events.send(Event.Utterance(heard))
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException && !isActive) throw e
                            events.send(Event.Failed(e))
                        }
                    }
                }
                when (val event = events.receive()) {
                    is Event.Utterance -> {
                        capture?.join()
                        capture = null
                        asr = null
                        if (event.text.isNotBlank()) {
                            // 用户说了「下次再聊」这类话：退出语音，回到打字
                            if (isExitPhrase(event.text)) {
                                mutableState.update { it.copy(transcript = event.text) }
                                onExit()
                                // 由控制器自己收尾：调用方只需要弹个提示，不用再 stop()（那会取消它自己）
                                mutableState.value = VoiceSessionState()
                                return@coroutineScope
                            }
                            val reply = enqueueMessage(event.text)
                            mutableState.update {
                                it.copy(transcript = event.text, pendingReplies =
                                    it.pendingReplies + 1)
                            }
                            submitted.send(reply)
                        }
                    }

                    // 用户一直没开口：让模型主动开个话题，把对话接下去
                    is Event.Idle -> {
                        capture?.join()
                        capture = null
                        asr = null
                        val reply = enqueueMessage(IDLE_NUDGE_MESSAGE)
                        mutableState.update {
                            it.copy(phase = VoicePhase.Waiting, pendingReplies = it.pendingReplies + 1)
                        }
                        submitted.send(reply)
                    }

                    is Event.Reply -> {
                        mutableState.update { it.copy(pendingReplies = (it.pendingReplies - 1).coerceAtLeast(0)) }
                        event.text?.takeIf { speak != null && it.isNotBlank() }?.let { replies.addLast(it) }
                    }

                    is Event.Failed -> throw event.error
                }
            }
        } finally {
            capture?.cancel()
            // Submitted messages belong to the chat queue; leaving voice mode only detaches observers.
            submitted.close()
        }
    }

    /** 采集一轮用户说话。返回 null 表示等到超时也没人说话。 */
    private suspend fun listen(asr: ASRController): String? {
        try {
            asr.start {}
            withTimeout(15_000) {
                asr.state.first {
                    check(it.errorMessage == null) { it.errorMessage.orEmpty() }
                    it.status != ASRStatus.Connecting
                }.also {
                    check(
                        it.status == ASRStatus.Listening || it.voiceTurn.isComplete
                    ) { "Unable to start speech recognition" }
                }
            }
            val ended = withTimeoutOrNull(IDLE_NUDGE_TIMEOUT_MS) {
                asr.state.onEach {
                    check(it.errorMessage == null) { it.errorMessage.orEmpty() }
                    check(
                        it.status == ASRStatus.Listening || it.voiceTurn.isComplete
                    ) { "Speech recognition disconnected" }
                    mutableState.update { current ->
                        current.copy(
                            phase = VoicePhase.Listening,
                            transcript = it.transcript
                        )
                    }
                }.first { it.voiceTurn.speechEnded }
            } ?: run {
                // 沉默超时：先关掉采集，把控制权交回循环（它会催模型开话题）
                asr.pauseCapture()
                return null
            }
            asr.pauseCapture()
            mutableState.update { it.copy(phase = VoicePhase.Transcribing, transcript = ended.transcript) }
            return withTimeout(15_000) {
                asr.state.first {
                    check(it.errorMessage == null) { it.errorMessage.orEmpty() }
                    check(it.voiceTurn.isComplete || it.status == ASRStatus.Listening) {
                        "Speech recognition disconnected before the final transcript was received"
                    }
                    it.voiceTurn.isComplete
                }.voiceTurn.finalText.orEmpty()
            }
        } finally {
            asr.dispose()
        }
    }

    fun stop() {
        job?.cancel()
        VoiceActivity.setActive(false)
        mutableState.value = VoiceSessionState()
    }
}

/** 用户是不是在说「结束语音」 */
internal fun isExitPhrase(text: String): Boolean {
    val normalized = text.trim()
    if (normalized.isEmpty()) return false
    return EXIT_PHRASES.any { normalized.contains(it) }
}
