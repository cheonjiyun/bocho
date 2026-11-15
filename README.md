# 보초운전 (Bocho)

> 초보운전, 칭찬으로 보조합니다. **보초운전**

깜빡이를 켜는 순간을 차량 데이터로 알아채고, 캐릭터(스포츠 캐스터) 목소리로 칭찬해 주는 초보운전자용 Pleos Connect 앱입니다.

## 주요 기능

- **깜빡이 칭찬:** 깜빡이가 꺼짐 → 켜짐(왼쪽/오른쪽)으로 바뀌는 순간, 칭찬 멘트 3개 중 하나를 무작위로 재생하고 같은 대사를 화면에 크게 보여줍니다.
- **중복 방지:** 같은 상태가 연달아 들어오거나, 앱을 켤 때 이미 켜져 있던 깜빡이, 켜진 상태에서 방향만 바꾼 경우에는 칭찬하지 않습니다.
- **주행 중 사용:** 기어 D 상태에서도 화면이 가려지지 않도록 운전 방해 최소화 앱(`distractionOptimized`)으로 선언했습니다.

## APK 파일 위치

```
apk/보초_보초운전_release.apk
```

## 실행 방법

### APK로 실행 (권장)

1. Pleos Connect 에뮬레이터를 실행합니다.
2. APK를 설치합니다.
   ```bash
   adb install -r apk/보초_보초운전_release.apk
   ```
3. 하단 앱 목록에서 **Bocho**를 실행합니다.

### 소스에서 실행

1. Android Studio에서 이 저장소를 엽니다.
2. Pleos Connect 에뮬레이터를 실행한 뒤 **Run 'app'** 을 누릅니다.

Pleos SDK는 공개 Maven 저장소(`https://nexus-playground.pleos.ai/repository/maven-releases/`)에서 받아오므로, 따로 다운로드할 필요가 없습니다.

## 테스트 방법

깜빡이는 Pleos Connect **Virtual Console**에서 조작합니다.

1. 에뮬레이터가 켜진 상태에서 포트를 연결합니다.
   ```bash
   adb forward tcp:8088 tcp:8088
   ```
2. 브라우저에서 http://127.0.0.1:8088/ 을 엽니다.
3. **Emulation Controls** → 검색창에 `turn signal` 입력 → **Turn Signal State**
4. `Off` → `Blink left` (또는 `Blink right`)를 누르면 캐스터 칭찬 음성이 나오고 화면에 대사가 표시됩니다.

| 조작 | 기대 결과 |
|---|---|
| Off → Blink left / Blink right | 칭찬 음성 + 대사 표시 |
| Blink left → Blink right (끄지 않고 방향만 변경) | 칭찬 없음 |
| 깜빡이를 켠 채로 앱 실행 | 칭찬 없음 (다음에 새로 켤 때부터 칭찬) |

Logcat에서 `tag:TurnTest`로 필터하면 상태 변화와 칭찬 시점을 확인할 수 있습니다.

> 깜빡이를 켤 때 화면 위쪽에 뜨는 카메라 영상은 차량 시스템의 후측방 카메라 기능이며, 이 앱이 띄우는 화면이 아닙니다.

## 테스트 환경

| 항목 | 내용 |
|---|---|
| 에뮬레이터 | Pleos Connect v2.1.0 (`Pleos_Connect_v2.1.0`, Android 14 / API 34) |
| 화면 규격 | 2560 × 1440, 160 dpi |
| 빌드 환경 | Android Studio, AGP 9.3.3, Kotlin 2.2.10, Jetpack Compose |
| minSdk / targetSdk | 34 / 37 |

## 필요한 설정

- **권한:** `AndroidManifest.xml`에 선언되어 있으며, 별도로 허용할 필요가 없습니다.
  - `pleos.car.permission.CAR_LIGHTS`: 깜빡이 상태 읽기
  - `pleos.car.permission.CAR_DOORS`: 문 상태 읽기 (SDK 동작 확인용)
  - `pleos.car.permission.TTS_SERVICE`: Pleos TTS
- **(선택) Pleos TTS 키:** 핵심 기능(깜빡이 칭찬)에는 필요 없습니다. Pleos TTS 서버 연결을 시험하려면 `local.properties`에 아래 값을 넣습니다. 이 파일은 Git에 올라가지 않습니다.
  ```
  PLEOS_CLIENT_ID=발급받은_값
  PLEOS_CLIENT_SECRET=발급받은_값
  ```

## 사용한 Pleos SDK

| SDK | 버전 | 사용 내용 |
|---|---|---|
| Vehicle | 2.3.2 | `TurnSignal`: 깜빡이 상태 구독 (`registerTurnSignalState`) |
| TextToSpeech | 3.3.1 | 서버 모드 연결까지 구현 (아래 참고) |

### 캐릭터 음성에 대해

Pleos TextToSpeech의 서버 모드는 현재 Playground 콘솔에서 **"준비 중"** 상태라 `PERMISSION_DENIED`가 반환되고, 에뮬레이터의 온디바이스 엔진은 `ON_DEVICE_ENGINE_ERROR`로 동작하지 않았습니다. 그래서 칭찬 멘트는 미리 만든 음성 파일(`app/src/main/res/raw/caster_1~3.mp3`)로 재생합니다. Pleos TTS가 열리면 음성 파일 재생 부분을 `tts.speak(...)`로 바꿔 연결할 수 있도록 서버 연결 코드를 남겨 두었습니다.
