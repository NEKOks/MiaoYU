package me.rerere.asr.providers

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.asr.ASRController
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.ASRVoiceTurn

/**
 * 给「整段上传」式 ASR 补一个**客户端断句**。
 *
 * DeepSeek / MiMo / Step 这些接口没有服务端 VAD：录音一直累积，等到固定时长或 stop() 才上传，
 * 所以语音模式等不到 `voiceTurn.speechEnded`，进来就会卡住。
 *
 * 这里监听音量：检测到说话之后连续静音 [silenceMs]，就 stop() 让底层把这一段传上去，
 * 拿到文字后合成一个 `speechEnded + finalText` 的语音回合，语音模式的循环就能继续了。
 *
 * 代价是一句话的延迟 = 静音判定 + 上传 + 识别，比流式的 DashScope 慢 1 秒左右。
 */
class LocalVadAsrController(
    private val delegate: ASRController,
    private val scope: CoroutineScope,
    /** 说完之后要静音多久才认为这句结束 */
    private val silenceMs: Long = 900L,
    /** 太短的声音（咳嗽、click）不算一句 */
    private val minSpeechMs: Long = 300L,
    /** 音量阈值：0=静音，1=满幅；低于它算静音。环境吵时会按底噪自动抬高 */
    private val amplitudeThreshold: Float = 0.18f,
    /** 一句话最长多久强制断句：风扇、马路这类持续噪声不会自己安静下来 */
    private val maxUtteranceMs: Long = 30_000L,
    /** 等底层把最后一段上传完的最长时间，超过就当作没识别到 */
    private val uploadTimeoutMs: Long = 12_000L,
) : ASRController {

    private val _state = MutableStateFlow(delegate.state.value)
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var mirrorJob: Job? = null
    private var vadJob: Job? = null
    private var finishJob: Job? = null

    @Volatile
    private var finished = false

    /**
     * 环境底噪估计。
     *
     * 固定阈值在安静房间好用，但空调、风扇、马路上底噪本身就高于阈值时，
     * 「一直有人在说话」永不结束。所以底噪只跟着更低的声音往下走、按固定速度慢慢回升，
     * 阈值取 [amplitudeThreshold] 和「底噪的两倍」中较大的那个。
     */
    private var noiseFloor = amplitudeThreshold * 0.5f

    override fun start(onTranscriptChange: (String) -> Unit) {
        finished = false
        // 必须先进入 Connecting：VoiceSessionController 启动时会等"非 Connecting 且为 Listening"，
        // 如果这里还停在 Idle，它第一眼就会判定「无法启动语音识别」
        _state.value = ASRState(status = ASRStatus.Connecting, isAvailable = true)
        delegate.start(onTranscriptChange)
        // 底层 start() 里通常已同步切到 Listening，这里立刻同步一次，避免竞态
        _state.value = delegate.state.value
        mirrorJob = scope.launch {
            delegate.state.collect { s ->
                // 收尾期间底层会经历 Stopping / Idle，而上层只接受 Listening 或「已完整」，
                // 透传过去会被判定成「语音识别断开」。所以这里保持 Listening，
                // 由 finishTurn() 一次性发出「说完 + 最终文本」。
                if (finished) return@collect
                _state.value = s
            }
        }
        vadJob = scope.launch { watchForSilence() }
    }

    /** 当前生效的阈值：环境越吵越高 */
    private fun threshold(): Float = maxOf(amplitudeThreshold, noiseFloor * 2f)

    /**
     * 底噪只跟着更低的声音走：说话和偶发的响声都不会把它抬高，
     * 但持续的环境噪声会让它按 [NOISE_RISE_PER_SAMPLE] 慢慢升上来，把阈值一起顶上去。
     */
    private fun updateNoiseFloor(amplitude: Float) {
        noiseFloor = minOf(amplitude, noiseFloor + NOISE_RISE_PER_SAMPLE)
    }

    private suspend fun watchForSilence() {
        var speechStartedAt = 0L
        var silentSince = 0L
        delegate.state.collect { s ->
            if (finished) return@collect
            val amplitude = s.amplitudes.lastOrNull() ?: return@collect
            val now = SystemClock.elapsedRealtime()
            updateNoiseFloor(amplitude)
            if (amplitude >= threshold()) {
                if (speechStartedAt == 0L) speechStartedAt = now
                silentSince = 0L
                // 一直不停顿（或底噪一直压着阈值）时说满 [maxUtteranceMs] 就强制断句，
                // 否则录音会无限累积，语音模式永远等不到这一回合结束
                if (now - speechStartedAt >= maxUtteranceMs) finishTurn()
            } else if (speechStartedAt != 0L) {
                if (now - speechStartedAt < minSpeechMs) return@collect
                if (silentSince == 0L) {
                    silentSince = now
                } else if (now - silentSince >= silenceMs) {
                    finishTurn()
                }
            }
        }
    }

    private fun finishTurn() {
        if (finished) return
        finished = true
        finishJob = scope.launch {
            delegate.pauseCapture()
            delegate.stop()
            // 等底层把最后一段传完（DeepSeek 会在这里发请求）。
            // 网络挂住时不能一直等：超时就按当前文本收尾，让语音循环还能继续下一回合。
            val last = withTimeoutOrNull(uploadTimeoutMs) {
                delegate.state.first { it.status == ASRStatus.Idle || it.status == ASRStatus.Error }
            } ?: delegate.state.value
            val text = last.transcript.trim()
            _state.value = last.copy(
                status = ASRStatus.Idle,
                voiceTurn = ASRVoiceTurn(itemId = "local-vad", speechEnded = true, finalText = text),
            )
        }
    }

    override fun stop() {
        if (finished) delegate.stop() else finishTurn()
    }

    override fun pauseCapture() {
        delegate.pauseCapture()
    }

    override fun dispose() {
        mirrorJob?.cancel()
        vadJob?.cancel()
        finishJob?.cancel()
        delegate.dispose()
    }

    private companion object {
        /** 每个音量采样允许底噪上升的幅度；25 次/秒的采样率下约 2 秒追上环境噪声 */
        const val NOISE_RISE_PER_SAMPLE = 0.002f
    }
}
