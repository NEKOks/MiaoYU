package me.rerere.rikkahub.service

/**
 * 语音会话是否正在进行。
 *
 * 悬浮窗用它决定"操作完手机要不要自动弹开面板"：语音对话时弹开会挡住她正在操作的界面，
 * 也会打断"你一句我一句"的节奏，所以这时保持浮窗原样（用户手动点开依然有效）。
 */
object VoiceActivity {
    @Volatile
    var isActive: Boolean = false
        private set

    fun setActive(active: Boolean) {
        isActive = active
    }
}