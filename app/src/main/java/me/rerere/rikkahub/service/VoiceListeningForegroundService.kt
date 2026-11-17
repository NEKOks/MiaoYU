package me.rerere.rikkahub.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.R
import me.rerere.rikkahub.VOICE_LISTENING_NOTIFICATION_CHANNEL_ID

private const val TAG = "VoiceListeningFgs"

/**
 * 语音模式在后台运行时用来占住麦克风的前台服务。
 *
 * Android 14+ 起，应用退到后台后如果没有 [ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE] 类型的前台服务，
 * 系统会直接掐掉录音。这个服务不参与录音本身（录音仍在 ASR 控制器里），只提供合法性与保活。
 */
class VoiceListeningForegroundService : Service() {
    companion object {
        private const val ACTION_START = "me.rerere.rikkahub.action.VOICE_LISTEN_START"
        private const val ACTION_STOP = "me.rerere.rikkahub.action.VOICE_LISTEN_STOP"

        /** 通知栏「结束语音」发出：语音页收到后按正常流程收尾（停录音、关服务） */
        const val ACTION_STOP_REQUEST = "me.rerere.rikkahub.action.VOICE_LISTEN_STOP_REQUEST"

        const val NOTIFICATION_ID = 2003

        fun start(context: Context): Boolean {
            val intent = Intent(context, VoiceListeningForegroundService::class.java).apply {
                action = ACTION_START
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.onFailure {
                Log.e(TAG, "Unable to start voice listening foreground service", it)
            }.getOrDefault(false)
        }

        /**
         * 用 [Context.stopService] 而不是再发一个 ACTION_STOP。
         *
         * Android 8+ 在后台用 startService 启服务会被直接拒绝，异常被 runCatching 吃掉之后
         * 前台通知和麦克风指示就会一直挂着。stopService 没有这个限制。
         */
        fun stop(context: Context) {
            val intent = Intent(context, VoiceListeningForegroundService::class.java)
            runCatching { context.stopService(intent) }
                .onFailure { Log.e(TAG, "Unable to stop voice listening foreground service", it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> enterForeground()
            ACTION_STOP -> {
                // 先让语音页收尾（停录音），再撤通知；只撤通知会留下后台继续录音
                sendBroadcast(Intent(ACTION_STOP_REQUEST).setPackage(packageName))
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun enterForeground() {
        val notification = buildNotification()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            },
        )
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // 通知栏直接给一个「结束语音」按钮：用户关掉麦克风不必先回到应用
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, VoiceListeningForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, VOICE_LISTENING_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cat_monochrome)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.voice_listening_notification_text))
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.voice_listening_notification_stop), stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
