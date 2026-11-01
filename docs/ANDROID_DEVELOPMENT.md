# Android 개발 안내

아마따는 Capacitor 8.6.0과 Kotlin Bluetooth Classic SPP 서비스를 사용합니다. `project/`는 앱 프로젝트 루트이며, 저장소는 [smart-tray-app](https://github.com/yjjyjjyjjyjj/smart-tray-app)입니다.

## UI를 수정할 때

1. `index.html`, `css/style.css`, `js/app.js` 등 원본 웹 파일을 수정합니다.
2. `npm run dev` 또는 기존 로컬 서버에서 화면을 확인합니다. 브라우저에서는 가상 장치를 사용합니다.
3. `npm run sync:android`로 웹 빌드와 Capacitor 동기화를 실행합니다.
4. `npm run open:android`로 Android Studio에서 실행하거나 `npm run android`를 사용합니다.

`www/`와 `android/app/src/main/assets/public/`은 생성물입니다. 이곳의 변경은 다음 동기화에서 덮어씁니다. APK에 포함된 화면을 업데이트하려면 앱을 다시 빌드·설치해야 합니다. 개발용 외부 URL/Live Reload는 기본 배포 설정에 포함하지 않습니다.

설치: `npm ci`. Node 22+, Android Studio 2025.2.1+, JDK 21, Android SDK 36이 필요합니다. `JAVA_HOME`은 JDK 21, `ANDROID_HOME`은 설치한 SDK를 가리키도록 설정하세요. 로컬 SDK 경로와 서명 키는 소스 관리에서 제외합니다.

## 시스템 바와 화면 영역

`index.html`의 `viewport-fit=contain`과 Capacitor `SystemBars.insetsHandling: native`를 함께 사용합니다. `MainActivity`는 BridgeActivity의 NoActionBar 테마 적용 후 edge-to-edge를 활성화하고 SystemBars가 상태표시줄·내비게이션 바·화면 잘림 영역·키보드 여백을 처리합니다. WebView 안의 헤더·하단 탭·모달은 시스템 영역을 제외한 화면 안에 배치됩니다. 고정 높이의 상태표시줄 여백을 CSS에 추가하지 않습니다. 앱은 밝은 배경을 사용하므로 시스템 바 아이콘도 `LIGHT` 스타일(어두운 아이콘)로 고정합니다.

공식 동작은 [Capacitor SystemBars 문서](https://capacitorjs.com/docs/apis/system-bars#configuration)를 참조하세요.

## 책임 분리

| 구성 | 책임 |
| --- | --- |
| `native/bridge.js` | Capacitor 및 App 플러그인 로딩; Android 여부 판별 |
| `js/native-transport.js` | 웹 화면 요청을 플러그인으로 전달; 서비스 스냅샷 반영; 모드 전환 |
| `SmartTrayPlugin.kt` | 권한 요청, 서비스 바인딩, JS 메서드/이벤트, 알림 탭 전달 |
| `SmartTrayBluetoothService.kt` | Classic 검색·페어링, 소켓 소유, 재연결, 네이티브 알림 호출 |
| `TrayRepository.kt` | 프로세스 상태, 설정·최신 활동 200개 영구 저장, 센서 갱신, 세션 알림함 |
| `FirmwareProtocol.kt` | 센서 도메인과 문 열림 알림 정책 |
| `ArduinoJsonProtocol.kt` | NDJSON 상태·ACK·순번·boot 검증 |
| `FirmwareChannel.kt` | 167바이트 설정/명령 큐·ACK 추적·타임아웃 |
| `FirmwareWeather.kt` | LCD 날씨·두 비 구간·유효 기한 |
| `TrayNotifications.kt` | Android 연결 유지 알림·외출 알림 채널, 대상 화면 PendingIntent |

화면 종료 시 플러그인은 서비스 바인딩과 구독만 정리합니다. 사용자 연결로 시작된 포그라운드 서비스가 BluetoothSocket을 계속 소유합니다. 앱 복귀 시 저장소 스냅샷으로 UI를 갱신하며 WebView 타이머로 센서 수신 시각을 갱신하지 않습니다.

Android 설정은 SharedPreferences가 기준이고 웹 저장소는 표시용 캐시입니다. 가상 체험 모드로 전환하면 실제 Bluetooth 연결을 해제합니다. 체험 설정과 센서값은 Android 서비스에 반영하지 않으며, 실제 연결 모드로 돌아오면 네이티브 설정을 다시 읽습니다. Android 앱을 새로 열면 실제 연결 모드에서 시작합니다.

## 통신 구현과 실물 검증 범위

Classic 검색·페어링 목록·시스템 페어링·SPP/RFCOMM 소켓·서비스 유지·저장된 연결 의도 복구를 구현했습니다. 소켓 I/O는 `bluetooth/RfcommSession.kt`, Android 소켓 어댑터는 `bluetooth/AndroidSerialSocket.kt`에 있습니다. 기존 `.ble` 패키지와 GATT 타입/콜백은 제거했습니다.

권한 차이, 연결/페어링 제한 시간, 재연결 횟수·간격, 취소와 서비스 재생성, 실제 기기 체크리스트는 **[HC-06 통신 명세](HC06_INTEGRATION_NOTES.md)** 한곳에서 관리합니다. `ArduinoJsonProtocol`은 실제 NDJSON 상태·ACK를 검증하고, `FirmwareChannel`이 상태 조회·설정·수동 시작/중지·응답 제한 시간을 처리합니다. `FirmwareWeather`는 1602 LCD용 날씨를 만듭니다. wire 규격·167바이트 제한·USB SERIAL 모드·설정 기준은 [Arduino 연동 명세](ARDUINO_HANDOVER.md)를 참조하세요. `DeviceReading`은 디코딩 후 앱 도메인 타입입니다.

기기 연결 과정에서는 Bluetooth 권한만 요청합니다. 연결이 완료되면 알림을 허용해야 남은 물건·비 예보 우산·오래 열린 문 안내를 받을 수 있음을 설명하고, 사용자가 허용 버튼을 눌렀을 때 Android 13+ 알림 권한을 요청합니다. 거절해도 연결과 화면 확인은 유지하며 홈·연결 완료·알림 설정 화면에 기능 제한과 허용 버튼을 표시합니다. 재요청 가능 상태에서는 권한 창을 다시 띄우고, 영구 거절·앱 알림 차단·‘외출 전 확인’ 채널 차단 상태에서는 명시적인 버튼으로 해당 휴대폰 알림 설정을 엽니다. 설정에서 돌아오면 상태를 갱신하고 허용된 경우 안내를 없앱니다. 거절 직후 설정을 강제로 열거나 권한 창을 반복해서 띄우지 않습니다. 알림 미리보기는 실제 로컬 시스템 알림이며 브라우저에서는 HTML 미리보기입니다. 최초/재연결 센서 스냅샷은 문 상태 기준값으로 사용하고 이후 닫힘→열림에서 평가합니다.

## 검증

```sh
npm test
npm run sync:android
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
# 일회용 QA 에뮬레이터를 연결한 경우
./gradlew :app:connectedDebugAndroidTest
```

`NativeBridgeTest`는 새 QA 에뮬레이터에서 실행하세요. 앱을 실행하고 테스트용 시스템 알림을 생성합니다. 실제 HC-06 하드웨어 통신을 검증하지 않습니다. `tests/browser-flow.cjs` 역시 새 Playwright context에서만 실행하며 기존 브라우저 설정을 삭제하지 않습니다.

최신 검증 결과는 [VALIDATION.md](VALIDATION.md)를 참조하세요.

## 우산·날씨·문 열림 유지

- 저장 구조는 트레이 2칸 + `umbrella` 1칸입니다. `UmbrellaReading`은 앱 내부 타입이며 실제 장치 전송 형식이 아닙니다. 미수신·오류는 우산 없음과 구분합니다.
- `ReminderPolicies.kt`: 비 예보·우산·문 열림 조건, 남은 오늘의 비 시간대 병합, 단조 시계 기반 N초 타이머. 우산 거치·제거 자체는 알림으로 만들지 않습니다.
- `WeatherManager.kt`: 사용자가 위치 확인을 시작하면 COARSE_LOCATION을 요청하고 대략적 위치를 약 5km 격자로 변환해 기상청 단기예보를 조회합니다. `KmaWeatherClient.kt`가 HTTP·페이지 수집을, `KmaForecast.kt`가 격자·발표 시각·강수형태 검증을 담당합니다. 키 설정은 [WEATHER_API.md](WEATHER_API.md)를 참조하세요. 위치는 앱이 보일 때만 갱신합니다. 서비스는 마지막 확인 좌표로 예보만 새로 조회하며 1시간 이내이며 자정을 넘지 않은 유효 예보를 사용합니다. 권한을 받지 않았거나 확인에 실패하면 날씨 확인 안내를 유지합니다.
- 홈 클릭(재선택 포함)·홈 진입·홈에서 앱 복귀 시 `refreshWeather({ automatic: true })`로 현재 위치와 예보를 갱신합니다. JS는 진행 중인 요청을 공유하고 Kotlin도 중복 조회를 합칩니다. 자동 갱신은 위치 권한을 새로 요청하지 않으며, 미허용 상태에는 위치 확인 안내를 표시합니다. `예보 보기`·초기 위치 설정은 사용자의 명시적 권한 요청 경로입니다. 브라우저는 외부 API 없이 가상 예보를 갱신하며 선택한 비/맑음 상태를 유지합니다.
- `SmartTrayBluetoothService`: `DoorLeftOpenPolicy`가 최신 열림 수신 시 단조 시각을 기록합니다. repository가 연결 유실로 `door=unknown`/미수신으로 바뀌어도 이 정책 상태는 보존합니다. 유효한 닫힘 수신 또는 사용자의 연결 해제/STOP에서 초기화하며, 반복 열림·재연결·설정 변경은 시작 시각을 바꾸지 않습니다. N초 뒤 최신 열림이면 열린 문 안내, 미확인 상태이면 닫힘 확인 안내를 한 번 게시합니다. 열림을 관측하지 않은 연결 끊김은 알림을 만들지 않습니다.
- 대기 중에만 최대 305초 WakeLock을 사용합니다. 알림 OFF, 닫힘, 사용자 중지, 발송 후에는 타이머와 WakeLock을 정리합니다. EOF/IOException 재시도 동안에도 타이머를 유지합니다. Bluetooth/권한 오류로 재연결을 종료할 때는 저장된 연결 의도를 지우고, 이미 시작한 문 확인이 있으면 그 기한까지 서비스를 유지한 뒤 종료합니다. OS가 권한 해제/강제 중지로 프로세스를 종료하면 보장할 수 없습니다. 열린 시각·발송 여부는 메모리 상태이므로 서비스 인스턴스가 파괴되면 복원하지 않습니다. 제품 규칙과 안내 문구는 [프로젝트 README](../README.md#우산비-예보와-현관문-열림-유지-알림)를 기준으로 합니다.
- `notifications.doorLeftOpen`, `doorOpenSeconds`(기본 30, 범위 10~300), `umbrella`를 SharedPreferences에 저장하며 기존 설정·별칭은 유지합니다.
- Android Manifest의 `screenOrientation=portrait`와 target SDK 36 대형 화면 호환 속성으로 세로 표시를 요청합니다. OS·제조사별 예외 및 target SDK 37 대형 화면 제한이 존재합니다.
- 날씨 없이 우산이 꽂혀 있다는 이유만으로 알림을 보내지 않습니다. 기상청 강수형태 PTY 1(비)·2(비/눈)·4(소나기)인 남은 시간대만 판단합니다. 눈만 오는 예보는 이 비 알림에 포함하지 않습니다.

## 처음 실행 및 위치 권한

처음 실행하면 소개 화면 없이 **연결과 위치 설정**이 열립니다. Bluetooth 장치 검색과 **위치 허용하고 날씨 확인**을 같은 화면에서 진행합니다. 위치는 COARSE_LOCATION만으로 확인하며, Android의 위치 권한 창에서 앱 사용 중 허용을 선택합니다. 시스템 위치가 꺼져 있으면 위치 설정을 열고, 권한을 거절해 다시 요청할 수 없으면 앱 상세 권한 설정을 엽니다. 설정에서 돌아오면 상태를 재확인하고 준비된 경우 날씨를 조회합니다. 사용자가 선택하기 전에 위치 권한 창을 자동으로 띄우지 않습니다.

위치 설정은 나중에 진행할 수 있습니다. **설정 마치고 시작하기** 또는 **나중에 설정하기**로 저장하면 다음 일반 앱 실행부터 홈으로 이동합니다. 시스템 알림을 눌러 시작한 경우에는 알림 대상 화면을 유지합니다. 설정 메뉴의 **연결과 위치 설정**에서 다시 관리할 수 있습니다. 브라우저 체험의 날씨는 가상 데이터이며 휴대폰 위치 권한을 요청하지 않습니다.

시스템 설정 이동은 [Android Settings API](https://developer.android.com/reference/android/provider/Settings)의 `ACTION_LOCATION_SOURCE_SETTINGS`와 `ACTION_APPLICATION_DETAILS_SETTINGS`를 사용합니다.


`LocationSetupTest`는 일회용 에뮬레이터의 위치 기능을 잠시 끄고 시스템 설정 왕복과 저장 후 홈 재실행을 확인한 뒤 원래 위치 스위치를 복원합니다. `WeatherLocationTest`는 고정 테스트 좌표와 로컬 예보 응답만 사용하며 외부 요청을 하지 않습니다. 프로세스 전역 URL handler를 사용하므로 아래처럼 별도 실행하세요.

```sh
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=kr.ac.gachon.smarttray.WeatherLocationTest
```
