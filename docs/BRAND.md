# 아마따 브랜드 아이콘

## 이름과 의미

**아마따**는 소지품을 두고 나온 뒤 하는 “아, 맞다! 놓고 왔지”를 소리 나는 대로 부른 이름입니다. 말풍선은 그 한마디를, 둥근 느낌표는 기억이 떠오른 순간을, 노란 반짝임은 작은 깨달음을 뜻합니다. 기존 UI의 초록색을 이어가며 상자·보관함 모양은 사용하지 않습니다.

## 자산과 적용

| 파일/위치 | 용도 |
| --- | --- |
| `assets/brand/amatta-symbol-source.png` | 이미지 생성 도구가 만든 투명 원본(1254×1254), 수정 없이 보관 |
| `assets/brand/amatta-symbol.png` | 웹 헤더·사이드바·앱 정보에 사용하는 256px PNG |
| `assets/brand/amatta-symbol-mono.svg` | 작은 크기·단색 표시용으로 단순화한 말풍선과 느낌표 |
| `assets/icons/favicon.svg`, `favicon.png` | SVG 우선 파비콘, PNG 대체 자산 |
| `assets/icons/apple-touch-icon.png` | 홈 화면 바로가기용 180px PNG |
| Android `drawable-nodpi/amatta_symbol.png` | 동일 원본을 512px로 내보낸 실행 아이콘 그림 |
| Android `mipmap-anydpi/ic_amatta.xml` | Android 7.x용 투명 비트맵 아이콘 |
| Android `mipmap-anydpi-v26/ic_amatta.xml` | 밝은 배경과 분리된 그림을 사용하는 adaptive 아이콘 |
| Android `mipmap-anydpi-v33/ic_amatta.xml` | 테마 아이콘용 monochrome 레이어 포함 |
| Android `drawable/ic_amatta_foreground.xml` | 원본의 투명 여백에 17% 추가 여백을 두는 레이어. 시작 화면에도 사용 |
| Android `drawable/ic_tray_notification.xml` | 흰색 실루엣과 투명한 느낌표 구멍으로 구성된 시스템 알림 아이콘 |

생성한 PNG를 그대로 사용하며 내보내기에서 해상도만 변경합니다. 작은 파비콘과 Android 단색 아이콘은 동일한 의미를 유지한 단순한 벡터 형태입니다. 브라우저의 시스템 알림 미리보기에도 이 단색 모양을 사용합니다. 앱 실행 중 이미지 생성 API나 외부 이미지 서버를 호출하지 않습니다. Kotlin 클래스·앱 ID·설정 저장 키는 변경하지 않습니다.

Android의 원형·둥근 사각형 마스크 및 단색 표시를 고려합니다. [Android adaptive icon 문서](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive), [Drawable 리소스 문서](https://developer.android.com/guide/topics/resources/drawable-resource)를 기준으로 레이어를 구성합니다. 실제 휴대폰 런처 및 시스템 시작 화면의 최종 모습은 설치 후 확인합니다.

## 생성 기록

내장 `image_gen`을 사용했습니다. CLI 또는 별도 API 키는 사용하지 않았습니다. 다음 프롬프트로 투명 배경의 심볼 한 개를 생성했습니다.

```text
Use case: logo-brand. Create one polished app brand symbol for the Korean app '아마따' (Amatta). The name sounds like the spoken '아, 맞다!' ('Oh, right! I forgot my things!'), a friendly sudden moment of remembering belongings before leaving home. Deliver ONE isolated finished flat icon, not a presentation sheet or a mockup. Subject: a friendly softly inflated rounded dark emerald-green speech bubble, leaning into an expressive short tail toward the lower left, containing one very bold cream rounded exclamation mark. A small warm butter-yellow four-point sparkle nestles at the upper-right of the speech bubble, conveying a pleasant aha moment. The speech bubble silhouette must feel soft and cute, with distinctive generously rounded corners, but sophisticated and simple for a useful everyday Android app. No face, no eyes, no hands, no box, no tray, no bulb, no words, no letters, no extra symbols. Palette: deep emerald #087D70 / #0A695C, warm ivory #FFFDF3, butter yellow #E6C86A only. Visual style: perfectly clean flat vector-style solid fills, high quality geometric brand design, minimal smooth curves, no gradients, no 3D, no shadow, no texture, no outline strokes. Make the exclamation mark a thick short rounded capsule plus a clear round dot with comfortable space around both, upright not tilted, readable at 24px. Composition: square canvas with transparent background; centered coherent compact mark, speech bubble and sparkle altogether occupy approximately 82 percent of canvas width and height; keep balanced transparent margins and no colored square behind the mark. The sparkle must remain near the bubble so the overall silhouette stays compact and not off-center. Real alpha transparency.
```
