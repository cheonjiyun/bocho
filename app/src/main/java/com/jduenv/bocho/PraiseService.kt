package com.jduenv.bocho

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 깜빡이 감지와 칭찬 음성 재생을 맡는 포그라운드 서비스.
 *
 * 화면(Activity)에 두면 내비 등으로 앱이 뒤로 가고 몇 초 뒤 안드로이드가 앱을 얼려서
 * 깜빡이 신호를 받지 못한다. 포그라운드 서비스는 얼리지 않으므로 백그라운드에서도 칭찬할 수 있다.
 */
class PraiseService : Service() {

    private lateinit var vehicle: Vehicle
    private val mainHandler = Handler(Looper.getMainLooper())

    // 깜빡이가 꺼짐 → 켜짐으로 바뀌는 순간에만 칭찬한다.
    // 같은 값이 중복으로 오기도 해서, 직전 상태를 기억해 두고 비교한다.
    // null = 아직 모름. 서비스가 시작될 때 이미 켜져 있던 깜빡이는 칭찬하지 않는다.
    private var wasOn: Boolean? = null

    private val turnSignalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
            Log.d(TAG, "changed = $state")
            if (state == null) return
            val isOn = state != TurnSignalValue.OFF
            // 리스너는 바인더 스레드에서 불리므로, 상태 비교와 재생은 메인 스레드에서 한다.
            mainHandler.post {
                if (wasOn == false && isOn) {
                    Log.d(TAG, "turned ON ($state) -> praise")
                    playPraise()
                }
                wasOn = isOn
            }
        }

        override fun onFailed(e: Exception) {
            Log.e(TAG, "listener failed", e)
        }
    }

    // 스포츠 캐스터 칭찬: 음성 파일(res/raw)과 화면에 띄울 대사를 짝지어 둔다.
    // 켤 때마다 하나를 무작위로 골라 재생하고 화면에도 보여준다.
    private val praises = listOf(
        R.raw.caster_1 to "깜빡이 점등!\n교과서 그대로의 신호입니다!",
        R.raw.caster_2 to "아~ 지금 깜빡이!\n뒤차에게 완벽한 예고를 보냅니다!\n이제 사이드미러 체크!",
        R.raw.caster_3 to "신호 확인!\n이 선수, 오늘 컨디션 최고입니다!",
    )
    private var player: MediaPlayer? = null

    private fun playPraise() {
        val (sound, line) = praises.random()
        _currentLine.value = line
        // 앞 멘트가 아직 나오고 있으면 끊고 새 멘트를 튼다.
        player?.release()
        player = MediaPlayer.create(this, sound)?.apply {
            setOnCompletionListener { mp ->
                mp.release()
                if (player === mp) player = null
            }
            start()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 포그라운드 서비스는 시작하자마자 알림을 띄워야 한다.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )

        vehicle = Vehicle(this)
        vehicle.initialize()
        val turnSignal = vehicle.getTurnSignal()
        turnSignal.getCurrentTurnSignalState(
            { state: TurnSignalValue? -> Log.d(TAG, "current = $state") },
            { e: Exception -> Log.e(TAG, "current failed", e) }
        )
        turnSignal.registerTurnSignalState(turnSignalListener)
        Log.d(TAG, "service started")
    }

    // 시스템이 서비스를 정리했다가 여유가 생기면 다시 살린다.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        vehicle.getTurnSignal().unregisterTurnSignalState(turnSignalListener)
        vehicle.release()
        player?.release()
        player = null
        Log.d(TAG, "service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // 알림을 누르면 앱 화면으로 돌아간다.
    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "보초", NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("보초")
            .setContentText("깜빡이를 켜면 칭찬해 드릴게요")
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "TurnTest"
        private const val CHANNEL_ID = "praise"
        private const val NOTIFICATION_ID = 1

        // 화면에 보여줄 현재 대사. 서비스가 바꾸고, 화면은 구독만 한다.
        private val _currentLine = MutableStateFlow("깜빡이를 켜 보세요")
        val currentLine: StateFlow<String> = _currentLine.asStateFlow()
    }
}
