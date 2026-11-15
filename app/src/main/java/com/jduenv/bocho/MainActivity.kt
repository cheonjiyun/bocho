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
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import android.content.Intent
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

        // 깜빡이 감지와 칭찬 음성은 PraiseService가 맡는다.
        // 화면이 뒤로 가도(내비 등) 서비스는 계속 돌아서 칭찬할 수 있다.
        startForegroundService(Intent(this, PraiseService::class.java))

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
                            // 열리면 PraiseService의 음성 파일 재생 대신 tts.speak(...)로 바꾼다.
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

        enableEdgeToEdge()
        setContent {
            BochoTheme {
                // 서비스가 칭찬할 때 바꾸는 대사를 구독한다.
                val currentLine by PraiseService.currentLine.collectAsState()
                // Pleos 화면에는 Toast가 그려지지 않아서, 앱 안의 스낵바로 안내를 띄운다.
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                ) { innerPadding ->
                    PraiseScreen(
                        line = currentLine,
                        onComingSoon = {
                            scope.launch {
                                // 연달아 눌러도 안내가 쌓이지 않게 이전 안내를 닫는다.
                                snackbarHostState.currentSnackbarData?.dismiss()
                                snackbarHostState.showSnackbar("준비 중입니다.")
                            }
                        },
                        // 끄기: 서비스를 멈추고 앱 화면도 닫는다.
                        // 다시 앱을 켜면 onCreate에서 서비스가 다시 시작된다.
                        onStop = {
                            stopService(Intent(this, PraiseService::class.java))
                            finish()
                        },
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
        vehicle.release()
        if (ttsReady) tts.removeEventListener(ttsListener)
        tts.release()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "DoorTest"
        const val TTS_TAG = "TtsTest"
    }
}

// 캐릭터 목록. 지금은 스포츠 캐스터만 음성이 있고, 나머지는 준비 중이다.
private val characters = listOf("스포츠 캐스터", "경상도 아빠", "충청도 할아버지")
private const val SELECTED_CHARACTER = "스포츠 캐스터"

// 위: 캐릭터 고르기 / 가운데: 칭찬 대사 / 아래: 끄기
@Composable
fun PraiseScreen(
    line: String,
    onComingSoon: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            characters.forEach { name ->
                if (name == SELECTED_CHARACTER) {
                    // 선택된 캐릭터는 색이 채워진 버튼으로 보여준다.
                    Button(onClick = {}, contentPadding = PaddingValues(32.dp, 20.dp)) {
                        Text(text = name, fontSize = 28.sp)
                    }
                } else {
                    OutlinedButton(
                        onClick = onComingSoon,
                        contentPadding = PaddingValues(32.dp, 20.dp),
                    ) {
                        Text(text = name, fontSize = 28.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(80.dp))
        Text(
            text = line,
            fontSize = 56.sp,
            lineHeight = 72.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(80.dp))
        TextButton(onClick = onStop) {
            Text(text = "보초 끄기", fontSize = 24.sp)
        }
    }
}

@Preview(showBackground = true, widthDp = 1280, heightDp = 720)
@Composable
fun PraiseScreenPreview() {
    BochoTheme {
        PraiseScreen("깜빡이 점등!\n교과서 그대로의 신호입니다!", onComingSoon = {}, onStop = {})
    }
}