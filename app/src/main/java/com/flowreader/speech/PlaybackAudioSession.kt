package com.flowreader.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.flowreader.MainActivity

/** All resources are acquired only for active speech and released on every terminal path. */
internal class PlaybackAudioSession(private val service: Service, private val interrupted: (String) -> Unit) {
    val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val audio = service.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null
    private var receiver: BroadcastReceiver? = null
    private var foreground = false

    val held: Boolean get() = request != null

    fun acquire(): Boolean {
        if (held) return true
        try {
            val notifications = service.getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel("reading_audio", "正文朗读", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(service, 0, Intent(service, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(service, "reading_audio")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("FlowReader 正在朗读")
                .setContentText("点击返回 FlowReader；可在小窗停止朗读").setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_TRANSPORT).build()
            if (Build.VERSION.SDK_INT >= 29)
                service.startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else service.startForeground(1001, notification)
            foreground = true
            lateinit var current: AudioFocusRequest
            current = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener({ change ->
                    if (request === current && change < 0) interrupted("音频被其他应用打断，已停止")
                }, Handler(Looper.getMainLooper())).build()
            request = current
            if (audio.requestAudioFocus(current) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                release(); return false
            }
            val noisy = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (request === current && intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                        interrupted("耳机或音频输出断开，已停止")
                }
            }
            if (Build.VERSION.SDK_INT >= 33) service.registerReceiver(noisy,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
            else service.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            receiver = noisy
            return true
        } catch (_: RuntimeException) {
            release(); return false
        }
    }

    fun release() {
        val old = request
        request = null // Queued callbacks from an older request cannot stop a newer session.
        receiver?.let { try { service.unregisterReceiver(it) } catch (_: RuntimeException) { } }
        receiver = null
        if (old != null) try { audio.abandonAudioFocusRequest(old) } catch (_: RuntimeException) { }
        if (foreground) try { service.stopForeground(Service.STOP_FOREGROUND_REMOVE) } catch (_: RuntimeException) { }
        foreground = false
    }
}
