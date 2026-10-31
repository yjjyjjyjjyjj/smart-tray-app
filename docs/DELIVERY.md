# 아마따 제작 결과

## 프로젝트 이해

현관의 트레이 2칸과 접이식 우산 공간 1칸에서 소지품·우산 유무를 감지하고, 문이 열리면 남은 물건을 Android 앱의 로컬 알림으로 안내하는 IoT 프로젝트입니다. 하드웨어·Arduino 펌웨어는 다른 팀원이 담당합니다. 현재 결과물은 공통 웹 UI를 포함한 Capacitor Android 앱과 브라우저 목업입니다. Kotlin Bluetooth Classic 서비스·권한·시스템 알림 기반을 구현했으며 실제 센서 디코더·장치 제어는 펌웨어 분석 후 연결합니다. 자체 서버는 사용하지 않으며 날씨 조회는 기상청 단기예보조회(getVilageFcst)를 사용합니다. 메이커톤 예산 내에서 자외선(UV) 모듈을 확보하지 못해, 시제품에서는 보라색 LED로 살균 동작을 시연합니다.

## 생성 파일과 경로

| 파일 | 저장소 파일 |
| --- | --- |
| 전체 프로젝트 README | [README.md](../README.md) |
| 앱 진입점 | [index.html](../index.html) |
| 디자인 시스템·반응형 UI | [css/style.css](../css/style.css) |
| 화면·사용자 입력 | [js/app.js](../js/app.js) |
| 데이터·설정·알림 조건 | [js/mock-data.js](../js/mock-data.js) |
| 가상 통신·센서 | [js/mock-transport.js](../js/mock-transport.js) |
| 브랜드 아이콘 | [assets/icons/favicon.svg](../assets/icons/favicon.svg) |
| 트레이 일러스트 | [assets/images/smart-tray.svg](../assets/images/smart-tray.svg) |
| 화면 명세 | [docs/SCREEN_SPEC.md](../docs/SCREEN_SPEC.md) |
| 앱 구조 | [docs/APP_ARCHITECTURE.md](../docs/APP_ARCHITECTURE.md) |
| 펌웨어 분석 체크리스트 | [docs/HC06_INTEGRATION_NOTES.md](../docs/HC06_INTEGRATION_NOTES.md) |
| 검증 결과 | [docs/VALIDATION.md](../docs/VALIDATION.md) |
| 이 제작 결과 보고서 | [docs/DELIVERY.md](../docs/DELIVERY.md) |
| 도메인 시나리오 테스트 | [tests/scenarios.test.cjs](../tests/scenarios.test.cjs) |
| UI 흐름 검증 함수 | [tests/browser-flow.cjs](../tests/browser-flow.cjs) |
| 개발 산출물 제외 설정 | [.gitignore](../.gitignore) |

검증 도구의 캡처·로그는 Git 제외 대상인 `.playwright-mcp/`에 있습니다. 앱 실행에는 필요하지 않습니다.

## README 작성 결과

[프로젝트 루트의 README.md](../README.md)에 한국어로 정리했습니다. 전체 프로젝트 배경·목적·기능·하드웨어·역할 분리·트레이 2칸·우산 1칸 구조·HC-06 통신 구현·프로토콜 미확정·로컬 알림·살균 시제품 구성·화면·목업 범위·디렉터리·실행·Android 개발 계획·미확정 기술 사항을 포함합니다. 2026년 11월 1일 상위 폴더에서 저장소 루트로 이동하여 소스와 함께 관리합니다.

## 앱 화면

Bluetooth 연결·위치 설정, 홈, 트레이 관리, 트레이 상세·LED·살균, 알림 설정, 활동 기록, 장치 관리를 구현했습니다. 하단 탭은 홈·트레이·기록·설정입니다.

추가한 알림함은 사라진 배너를 다시 확인하기 위한 화면입니다. 앱 정보는 목업 범위와 미확정 상태를 확인하기 위해 추가했습니다. 별칭 편집 모달은 현재 맥락을 유지하며 이름을 바꾸도록 돕습니다. 개발자 드로어는 일반 사용 흐름과 테스트 조작을 분리합니다. 연결 실패·권한 안내·연결 끊김·센서 오류는 관련 화면 안에 포함합니다.

## 실행과 체험

빌드·패키지 설치 없이 `index.html`을 일반 브라우저로 열거나, 권장되는 로컬 HTTP 방식으로 실행합니다.

```sh
# README.md가 있는 저장소 루트에서 실행
python3 -m http.server 4173 --bind 127.0.0.1
```

<http://127.0.0.1:4173>에 접속하면 처음에는 연결·위치 설정이 열립니다. 가상 장치 검색과 가상 날씨를 확인하고 설정을 마치거나, 목업 체험을 선택합니다. 저장된 초기 설정이 있으면 홈으로 바로 이동합니다. 설정은 localStorage에 저장합니다. 저장을 차단한 브라우저에서는 세션에만 적용합니다.

## 시뮬레이션

모바일 설정 또는 데스크톱 사이드바에서 개발자 시뮬레이션을 엽니다. 키보드 D로도 열 수 있습니다. 문 열림·닫힘, 트레이별 물건·가상 무게·센서 오류·복구·수신 대기, 가상 연결·해제, 수신 중단·재개, LED·RGB 살균 시작, 가상 알림을 지원합니다. A–D 버튼으로 요구된 네 시나리오를 준비·실행합니다.

## 테스트 결과

초기 목업 검증에서는 도메인 테스트 11개, UI 확인 21개를 통과했습니다. Capacitor 전환 이후 최신 검증 결과는 `VALIDATION.md`를 참조하세요. 여섯 화면 폭의 열 개 화면, **60개 조합에서 가로 넘침 없음**을 확인했습니다. 센서 입력·공백 이름·HTML 이스케이프·16자 이름도 추가 확인했습니다. 오류 수정 후 센서 패널 반복 조작에서 브라우저 예외가 없었습니다. 정확한 검증 범위와 파일 직접 열기 자동화 제한은 `VALIDATION.md`를 참조하세요.

## Android 앱 빌드 및 다음 작업

`npm ci` → `npm run sync:android` → `npm run open:android` 순서로 실행합니다. 기존 HTML/CSS/JS를 수정하며 Kotlin 서비스와 분리된 UI를 유지합니다. 자세한 절차는 [ANDROID_DEVELOPMENT.md](ANDROID_DEVELOPMENT.md)를 참조하세요.

실제 펌웨어 확보·분석 → 수신 UUID·디코더·쓰기 명령 연결 → 실제 장치에서 화면 꺼짐·재연결·오류·미수거 알림 검증이 남아 있습니다.
