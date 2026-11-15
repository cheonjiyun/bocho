package com.jduenv.bocho

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.tts.listener.OnServerConnectionListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jduenv.bocho.ui.theme.BochoTheme
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var vehicle: Vehicle

    private val doors = listOf(
        DoorValue.ROW_1_LEFT,   // 운전석
        DoorValue.ROW_1_RIGHT,  // 조수석
        DoorValue.ROW_2_LEFT,   // 뒷좌석 왼쪽
        DoorValue.ROW_2_RIGHT,  // 뒷좌석 오른쪽
    )

    // 리스너는 어느 문인지 알려주지 않으므로 문마다 하나씩 만든다.
    // 해제할 때 같은 area·리스너 짝을 써야 해서 Map에 보관한다.
    private val doorListeners = doors.associate { value ->
        DoorArea(value) to object : DoorPositionListener {
            override fun onPositionUpdated(pos: Int?) {
                Log.d(TAG, "$value changed = $pos")
            }
            override fun onFailed(e: Exception) {
                Log.e(TAG, "$value listener failed", e)
            }
        }
    }

    // 깜빡이가 꺼짐 → 켜짐으로 바뀌는 순간에만 칭찬한다.
    // 같은 값이 중복으로 오기도 해서, 직전 상태를 기억해 두고 비교한다.
    // null = 아직 모름. 앱을 켰을 때 이미 켜져 있던 깜빡이는 칭찬하지 않는다.
    private var wasOn: Boolean? = null

    private val turnSignalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
            Log.d(TURN_TAG, "changed = $state")
            if (state == null) return
            val isOn = state != TurnSignalValue.OFF
            // 리스너는 바인더 스레드에서 불리므로, 상태 비교와 재생은 메인 스레드에서 한다.
            runOnUiThread {
                if (wasOn == false && isOn) {
                    Log.d(TURN_TAG, "turned ON ($state) -> praise")
                    playPraise()
                }
                wasOn = isOn
            }
        }
        override fun onFailed(e: Exception) {
            Log.e(TURN_TAG, "listener failed", e)
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

    // 화면에 크게 보여줄 대사. 값이 바뀌면 Compose 화면이 다시 그려진다.
    private var currentLine by mutableStateOf("깜빡이를 켜 보세요")

    private fun playPraise() {
        val (sound, line) = praises.random()
        currentLine = line
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

    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    // 재생 상태를 Logcat에서 확인하기 위한 리스너
    private val ttsListener = object : EventListener {
        override fun onReady() { Log.d(TTS_TAG, "onReady") }
        override fun onStart() { Log.d(TTS_TAG, "onStart") }
        override fun onDone() { Log.d(TTS_TAG, "onDone") }
        override fun onStop() { Log.d(TTS_TAG, "onStop") }
        override fun onError(errorType: ErrorType) { Log.e(TTS_TAG, "onError: $errorType") }
        override fun onUpdatedRms(rms: Double) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 에뮬레이터의 ON_DEVICE 엔진이 ON_DEVICE_ENGINE_ERROR로 실패해서 SERVER 모드를 쓴다.
        // SERVER 모드는 registerApp으로 앱을 등록하고, 연결된 뒤에 speak 해야 한다.
        // addEventListener는 initialize 성공 후에 불러야 한다. 먼저 부르면 SDK 내부에서 크래시가 난다.
        tts = TextToSpeech(this, Mode.SERVER)
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                Log.d(TTS_TAG, "initialize success")
                ttsReady = true
                tts.addEventListener(ttsListener)
                tts.setLocale(Locale.KOREAN)
                tts.registerApp(
                    BuildConfig.PLEOS_CLIENT_ID,
                    BuildConfig.PLEOS_CLIENT_SECRET,
                    object : OnServerConnectionListener {
                        override fun onConnected() {
                            // 서버 TTS가 Playground에서 아직 "준비 중"이라 speak는 PERMISSION_DENIED가 난다.
                            // 열리면 녹음 파일 대신 tts.speak(...)로 바꾼다.
                            Log.d(TTS_TAG, "server connected")
                        }
                        override fun onFailed(errorType: ErrorType) {
                            Log.e(TTS_TAG, "server connection failed: $errorType")
                        }
                    }
                )
            }
            override fun onFailure(errorType: ErrorType) {
                Log.e(TTS_TAG, "initialize failed: $errorType")
            }
        })

        vehicle = Vehicle(this)
        vehicle.initialize()

        val door = vehicle.getDoor()
        doorListeners.forEach { (area, listener) ->
            val value = area.values.first()
            door.getCurrentDoorPosition(
                area = area,
                onComplete = { pos: Int? -> Log.d(TAG, "$value position = $pos") },
                onFailed = { e: Exception -> Log.e(TAG, "$value failed", e) }
            )
            door.registerDoorPosition(area, listener)
        }

        val turnSignal = vehicle.getTurnSignal()
        turnSignal.getCurrentTurnSignalState(
            { state: TurnSignalValue? -> Log.d(TURN_TAG, "current = $state") },
            { e: Exception -> Log.e(TURN_TAG, "current failed", e) }
        )
        turnSignal.registerTurnSignalState(turnSignalListener)

        enableEdgeToEdge()
        setContent {
            BochoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PraiseScreen(
                        line = currentLine,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        val door = vehicle.getDoor()
        doorListeners.forEach { (area, listener) ->
            door.unregisterDoorPosition(area, listener)
        }
        vehicle.getTurnSignal().unregisterTurnSignalState(turnSignalListener)
        player?.release()
        player = null
        vehicle.release()
        if (ttsReady) tts.removeEventListener(ttsListener)
        tts.release()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "DoorTest"
        const val TTS_TAG = "TtsTest"
        const val TURN_TAG = "TurnTest"
    }
}

// 운전 중에 한눈에 읽히도록 캐릭터 이름과 대사만 크게 보여준다.
@Composable
fun PraiseScreen(line: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "🎙️ 스포츠 캐스터",
            fontSize = 32.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(32.dp))
        Text(
            text = line,
            fontSize = 56.sp,
            lineHeight = 72.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(showBackground = true, widthDp = 1280, heightDp = 720)
@Composable
fun PraiseScreenPreview() {
    BochoTheme {
        PraiseScreen("깜빡이 점등!\n교과서 그대로의 신호입니다!")
    }
}