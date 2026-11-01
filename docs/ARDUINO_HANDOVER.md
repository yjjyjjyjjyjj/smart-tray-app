# Arduino ↔ Android 연동 명세

확인 기준: 2026-11-01. [smart-tray-arduino의 main_edit.c/main_edit.c.ino](https://github.com/yjjyjjyjjyjj/smart-tray-arduino/blob/adfd4bf60d386cdc3cab8d6778413ea9441669de/main_edit.c/main_edit.c.ino), 커밋 `adfd4bf`, 소스 헤더 v1.1, wire 버전 1을 기준으로 앱을 구현했습니다. 펌웨어 README의 v1.0 설명보다 실제 소스의 제약을 우선합니다. 실제 HC-06·센서·출력·LCD 시험은 별도 검증 대상입니다.

## 연결 준비

- 보드: UNO R3, HC-06 D12(RX)·D13(TX), UART **9600 bps / 8N1**. 앱은 표준 SPP UUID `00001101-0000-1000-8000-00805f9b34fb`로 연결합니다. UART 속도는 Android에서 설정하지 않습니다.
- 검색 이름: `HC-06` 또는 `AMATTA`. 같은 이름도 사용자가 선택한 MAC 주소로 구분합니다. PIN은 Android 시스템 페어링 창에서 입력합니다.
- 로드셀 1: D2/D3, 로드셀 2: D4/D5, 문: A0, 우산 IR: A3, LED 스트립: D8/D9/D10, 팬: D11, LCD: A4/A5 및 I²C 0x27/0x3F. 문 닫힘·우산 감지는 LOW입니다.
- USB Serial은 115200 bps의 보정·진단 채널입니다. **USB에서 문자를 입력하면 SERIAL 모드로 전환되어 Bluetooth가 꺼집니다.** 앱을 시험하려면 USB 콘솔에서 `BT ON` 또는 `EXIT`를 입력합니다. `MON`이 꺼져 있고 미완성 입력이 없으면 무입력 180초 뒤 BT로 돌아갑니다.
- 무게 영점·컵 차감·스케일·히스테리시스는 펌웨어가 담당합니다. 앱은 g 단위 수신값을 표시하며 `tare`를 노출하거나 전송하지 않습니다.

## 프레이밍과 실제 한도

| 항목 | 구현 기준 |
| --- | --- |
| 프레임 | 한 줄 UTF-8 JSON + LF, CR 무시 |
| Arduino 수신 버퍼 | `btRxBuf[168]`: JSON 본문 **167바이트 이하** |
| 요청 ID | `char[12]`: ASCII **11자 이하**, 응답의 `req`로 대응 |
| 앱 상태 수신 한도 | 본문 2048바이트. 초과·잘못된 UTF-8·깨진 JSON은 LF까지 버린 뒤 복구 |
| 상태 보고 | 변경 시 + 약 1초 heartbeat. 수신으로 `btPeerActive`가 켜져야 주기 보고 유지 |
| 상태 순서 | `boot` uint16, `seq` uint32. 중복·역순·이전 boot 거부, 두 카운터의 0 롤오버 허용 |
| LCD | 오른쪽 각 줄 ASCII 13칸, `windows` **최대 2개**, 3초 간격 순환 |

`ArduinoJsonProtocol`은 분할·병합 프레임을 처리하고 문·트레이 1/2·우산·팬을 모두 검증한 뒤 상태를 반영합니다. 잘못된 프레임이나 ACK는 센서 수신 시각을 갱신하지 않습니다. 무게 0~3000g, 센서 `ok/waiting/error`, LED `off/normal/steril`, 살균 0~30000ms, 팬 0~60000ms를 검증합니다. 문 센서 오류는 `unknown`입니다.

## Arduino → 앱

```json
{"type":"state","v":1,"boot":3,"seq":42,"door":{"state":"open","sensor":"ok"},"trays":[{"id":1,"present":true,"weight":128.5,"sensor":"ok","led":"normal","steril_ms":0},{"id":2,"present":false,"weight":0.0,"sensor":"ok","led":"off","steril_ms":0}],"umbrella":{"present":true,"sensor":"ok","led":"steril","steril_ms":8000},"fan":{"active":true,"rem_ms":15000}}
{"type":"ack","req":"a1231","status":"accepted"}
{"type":"ack","req":"a1231","status":"rejected","reason":"no_item"}
{"type":"ack","req":"a1231","status":"completed","reason":"duration_elapsed"}
{"type":"ack","req":"a1231","status":"stopped","reason":"item_removed"}
```

- 일반 LED는 `normal`일 때만 ON입니다. 점멸의 OFF 구간도 `normal`로 보고합니다. `steril_ms`와 `fan.rem_ms`는 수신 시각 기준 종료 시각으로 바꾸어 표시합니다.
- `accepted`는 요청 접수, `state`는 실제 논리 출력 상태, `completed/stopped`는 동작 종료입니다. 소켓 쓰기·설정 저장·접수만으로 출력 ON을 만들지 않습니다. 전기적 출력이나 실제 살균 효과의 측정값은 아닙니다.
- 센서 오류 때 마지막 정상 무게는 이전 값으로 보관하며 정상 물건 있음·없음으로 판정하지 않습니다.
- 최초·재연결·재부팅 상태는 문 열림 알림 기준값입니다. 이후 닫힘→열림에서 남은 트레이 알림과 비 예보 우산 알림을 판단합니다. 문 열림 유지 10~300초 감시는 첫 유효 열림부터 시작할 수 있고, 통신 유실에도 원래 기한을 유지합니다.

## 앱 → Arduino

연결 직후 `get_state`로 주기 보고를 활성화합니다. 첫 유효 상태 뒤 저장한 설정을 세 공간에 순차 적용하고 LCD 날씨를 보냅니다. 재연결·boot 변경 때도 설정과 날씨만 다시 보내고 수동 시작 명령을 재생하지 않습니다.

```json
{"type":"get_state","req":"a1231"}
{"type":"set_config","req":"a1232","space":1,"led_en":1,"led_door":1,"steril_en":0,"steril_detect":0,"steril_door":0,"steril_sec":10}
{"type":"set_config","req":"a1233","space":3,"led_en":1,"led_door":1,"steril_en":1,"steril_detect":1,"steril_door":0,"steril_sec":10,"fan_sec":30}
{"type":"cmd","req":"a1234","space":1,"action":"start_steril"}
{"type":"cmd","req":"a1235","space":3,"action":"stop_steril"}
{"type":"cmd","req":"a1236","space":3,"action":"start_fan","sec":30}
{"type":"cmd","req":"a1237","space":3,"action":"stop_fan"}
```

0/1는 펌웨어의 `extractJsonBoolP`가 명시적으로 허용하는 표현이며, 모든 설정을 167바이트 안에 넣기 위해 사용합니다. 살균 시간은 5/10/20/30초, 팬 시간은 0/15/30/60초입니다. 별칭·알림·위치·문 열림 판단 시간은 휴대폰 전용이며 보내지 않습니다.

앱의 수동 살균은 1/2번 트레이 및 3번 우산에 연결되어 있습니다. 시작 전에 최신 정상 센서·물건 있음·살균 활성화·설정 적용 ACK·중복 실행 여부를 검사하며 펌웨어도 다시 검사합니다. 팬은 우산 거치 시 펌웨어에서 자동 실행하고, 시작/중지 명령도 네이티브 브리지에서 지원합니다. 화면에는 팬 시간 설정과 수신한 실제 상태를 제공합니다.

`FirmwareChannel`은 한 번에 한 요청만 보내며 다음 전송은 완전한 `state` 수신 뒤에 시작합니다. ACK 직후 이어지는 긴 상태 송신과 SoftwareSerial 수신이 겹치는 것을 줄입니다. ACK 제한 6초, 전송 전 대기 제한 20초, 대기 큐 최대 16개입니다. **펌웨어에 요청 ID 중복 실행 방지 기능이 없으므로 명령을 자동 재전송하지 않습니다.** 응답 유실은 실패/미확인으로 남기고 사용자가 상태를 확인한 뒤 재시도합니다. 유효 상태가 15초 동안 오지 않으면 기존 요청을 취소하고 `get_state`로 다시 조회합니다. 이 경로에서도 수동 시작은 재생하지 않습니다.

휴대폰 SharedPreferences를 설정의 기준으로 삼습니다. 변경된 공간만 전송하고, 응답 대기 중 재변경은 이전 ACK 뒤 최신 값으로 합칩니다. 세 공간의 ACK가 확인되어야 `configSync.status=applied`입니다. 휴대폰 저장 성공과 장치 적용 성공을 구분하며, 적용 실패 시 재연결 안내를 표시합니다. Arduino는 `set_config`에서 EEPROM에 CRC와 함께 저장합니다. 앱이 종료되어도 Arduino가 저장된 자동 동작을 실행합니다.

## 공유 LED와 팬 동작

펌웨어는 문 열림 때 남은 물건을 백색으로 1.5초 ON/OFF 점멸시키며, 문 닫힘·수거·재거치·최대 60초에서 종료합니다. 거치 자체로 백색 점등하지 않습니다. 보라색 살균은 일반 점멸보다 우선하고, 살균 종료 뒤 일반 조건이 남으면 백색으로 복귀합니다. 우산 거치 시 설정된 팬을 실행하며 우산 제거·팬 시간 0 설정 시 중지합니다.

실제 연결 화면에는 지원되지 않는 “물건을 놓으면 켜기”와 일반 LED 수동 미리보기를 표시하지 않습니다. 기존 브라우저 체험은 유지합니다. 우산의 일반 LED는 `led_en=1/led_door=1`로 동기화하고, 별도 일반 LED 설정 UI는 없습니다. 살균 OFF·시간·팬 등은 각 공간에 독립 적용합니다.

## LCD 날씨

```json
{"type":"weather","req":"a1238","valid":true,"icon":"rain","line1":"RAIN","windows":["14:00-18:00","20:00-22:00"],"ttl_s":1800}
{"type":"weather","req":"a1239","valid":false,"icon":"none","line1":"NO DATA","windows":[""],"ttl_s":0}
```

`FirmwareWeather`는 오늘 남은 예보에서 비가 있으면 RAIN + 처음 두 비 구간, 눈만 있으면 SNOW / NO RAIN, 비·눈이 없으면 현재 시간대 SUNNY 또는 CLOUDY / NO RAIN/SNOW로 만듭니다. 눈 시간대는 전송하지 않습니다. 앱 화면에서는 모든 비 구간을 유지합니다. 자정 끝은 `24:00`으로 표시합니다.

누락된 시간대·하늘 상태·만료·미허용 위치·조회 실패는 NO DATA입니다. TTL은 최초 조회로부터 1시간, 예보 유효 기한, 다음 시간대 경계, 자정을 넘지 않으며 전송 대기 시간과 UART 전달 여유도 차감합니다. 연결·boot 변경·예보 변경 시 전송하고, 연결 중 서비스가 1분마다 만료를 확인하여 마지막 확인 위치의 예보를 갱신합니다. 백그라운드에서 새로운 위치를 추적하지 않습니다.

## 실물 검증 순서

1. 해당 커밋의 펌웨어 업로드, 용기 보정, USB `BT ON`, 앱에서 HC-06/AMATTA 선택·페어링.
2. 두 트레이 무게·물건, 우산, 문 개폐가 앱에 반영되는지 확인. 연결과 첫 상태 수신 구분.
3. LED·살균·팬 시간 변경 후 장치 설정 적용 상태 확인. 거치·수거·문 열림·시간 종료와 실제 출력 비교.
4. 수동 살균 접수/거절/완료/중단, 설정 OFF·우산 제거 시 종료 확인. ACK 유실 시 자동 재실행 없음 확인.
5. 문 열림 미수거·우산 알림, 10/30/300초 문 확인, 화면 꺼짐·거리 이탈·재연결·보드 재부팅·센서 정지/오류 확인.
6. LCD의 비·눈·맑음·구름·두 비 구간 순환·NO DATA·만료를 실제 화면과 비교.

자동 검증과 실물 시험 결과는 [VALIDATION.md](VALIDATION.md)에 구분해 기록합니다. 연결·권한·재연결 세부 규칙은 [HC06_INTEGRATION_NOTES.md](HC06_INTEGRATION_NOTES.md), 알림 문구는 [푸시 메시지 리스트](푸시메시지_리스트.md)를 참조하세요.
