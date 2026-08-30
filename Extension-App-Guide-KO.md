# 확장앱 간단 사용 설명서

## 1. 저장소 주소

사용하는 앱에 맞는 저장소 주소를 추가합니다. 세 주소는 서로 다른 플랫폼용이므로 앱에 맞는 주소를 사용하세요.

| 앱 | 저장소 주소 |
|---|---|
| Mihon (Android) | `https://dc-toki.pages.dev/index.min.json` |
| Tachimanga (iPhone/iPad) | `https://dc-toki-ios.pages.dev/index.min.json` |
| Suwayomi | `https://dc-toki-suwayomi.pages.dev/index.min.json` |

테스트 버전을 안내받은 경우에만 테스트 저장소 주소를 사용합니다. 저장소 주소는 인증 서버 주소(`http://...:9870`)와 다릅니다.

## 2. Mihon (Android)

1. `More → Settings → Browse → Extension repos(또는 Extension stores)`로 이동합니다.
2. `Add`를 눌러 위의 Android 저장소 URL을 붙여 넣습니다.
3. `Browse → Extensions`에서 목록을 새로 고칩니다.
4. 필요한 확장앱을 설치합니다.
5. `Browse → Sources`에서 한국어 소스를 선택합니다.

Android에서 처음 확장앱을 설치할 때 시스템이 설치 권한을 물으면 Mihon의 설치를 허용합니다.

## 3. Tachimanga (iPhone/iPad)

### 최초 준비

Tachimanga는 국내 App Store에 없으므로 해외 App Store에서 설치합니다. 설치 후 **해외 VPN을 켠 상태로 Tachimanga를 1회 실행·접속**하면 확장앱 URL 설치 메뉴가 표시됩니다.

### 저장소와 확장앱 설치

1. `More(또는 Browser) → Extensions → Extension Repositories`로 이동합니다.
2. `+` 또는 `Add by URL`을 눌러 iOS 저장소 URL을 붙여 넣습니다.
3. 저장소를 새로 고친 뒤 필요한 확장앱을 설치합니다.
4. 반드시 `Settings → Extensions → Show NSFW Extensions and Sources`를 켭니다.
5. `Extensions`를 다시 열어 설치된 확장앱이 표시되는지 확인합니다.

`Show NSFW Extensions and Sources`가 꺼져 있으면 설치가 끝났어도 성인 소스와 확장앱이 목록에 숨겨질 수 있습니다.

## 4. Suwayomi

1. Suwayomi Web UI에서 `Settings`를 엽니다.
2. 버전에 따라 `Browse` 또는 `Extensions` 안의 `Extension repositories`를 엽니다.
3. `https://dc-toki-suwayomi.pages.dev/index.min.json`을 저장소 주소로 추가합니다.
4. 확장앱 목록을 새로 고친 뒤 필요한 확장앱을 설치합니다.

Suwayomi 전용 저장소는 Android/Mihon 저장소와 별개입니다. Suwayomi 서버가 실행되는 컨테이너에서 저장소와 인증 서버 주소에 접근할 수 있어야 합니다.

## 5. 확장앱 설정

### 도메인 수정

사이트 주소가 바뀌었을 때는 먼저 **필터 탭의 토끼 신호등**을 새로 고쳐 현재 주소를 자동으로 확인합니다. 토끼 신호등을 사용해도 접속되지 않을 때만 해당 확장앱의 `설정`에서 `도메인 번호` 또는 `사이트 주소`를 수동으로 수정합니다.

- 예: `도메인 번호 (newtoki#.org)`, `도메인 번호 (toki##.com)`처럼 표시되는 항목
- 화면에 `번호`라고 표시되면 안내된 번호만 입력하고, `https://`, 경로(`/manga/...`), 포트(`:9870`)는 입력하지 않습니다.
- 저장한 뒤 소스 목록과 회차 목록을 새로 고칩니다.
- 도메인 설정은 확장앱·소스별로 따로 저장되므로 다른 확장앱의 설정을 함께 바꾸지 않습니다.

### 외부 인증 서버

외부 인증 서버는 선택 기능이며 기본값은 **OFF**입니다.

1. 확장앱 `설정`에서 `외부 인증 서버 사용`을 **ON**으로 켭니다.
2. 서버 주소에 관리자가 제공한 주소(예: `http://서버주소:9870`)를 입력합니다.
3. 서버에서 설정한 **접속 키**를 같은 값으로 입력하고 `연결 확인`을 누릅니다.
4. 연결이 확인되면 회차 인증과 이미지 주소 확보를 외부 인증 서버가 처리합니다.

OFF로 되돌리면 확장앱은 기존 직접 접속 방식으로 동작합니다. 이 ON/OFF 설정은 소스 ID, 서재, 다운로드 항목을 변경하지 않습니다. 외부 인증 서버는 이미지를 저장하거나 대신 다운로드하지 않고, 인증 후 이미지 주소를 앱 또는 Suwayomi에 전달합니다.

## 6. 필터 탭과 토끼 신호등

### 필터 탭의 커스텀 기능

소스 화면의 `필터` 탭에서 장르·상태·정렬 등 원하는 조건을 조합해 목록을 좁힐 수 있습니다.

- `커스텀` 항목에서 필요한 조건을 선택하고 적용합니다.
- 조건을 해제하려면 `초기화` 또는 선택 해제를 누른 뒤 다시 검색합니다.
- 지원하는 필터 항목은 소스마다 다를 수 있습니다.

### 토끼 신호등

`토끼 신호등`은 사이트 주소가 바뀌었는지 확인하는 보조 확장앱입니다.

1. `필터` 탭에서 `토끼 신호등`의 확인/새로 고침 기능을 실행합니다.
2. 지원되는 각 확장앱의 현재 목록 주소를 검사합니다.
3. 새 주소가 확인되면 토끼 신호등 설정과 해당 확장앱 설정에 주소를 저장합니다.
4. 대상 소스의 목록 또는 회차 화면을 다시 열어 변경된 주소를 적용합니다.

사이트 주소를 찾을 수 없다는 오류가 나오면 먼저 토끼 신호등을 새로 고친 뒤, 필요한 경우 해당 확장앱의 도메인 설정을 직접 확인합니다. 토끼 신호등은 작품을 읽는 소스가 아니라 주소 확인용 보조 확장앱입니다.

## 7. 외부 인증 서버 설치

사용하는 환경에 맞는 서버 파일을 다운로드한 뒤 해당 설치 설명서를 따라 설정합니다.

작품 목록과 회차 목록은 정상적으로 표시되지만 뷰어에서 이미지를 불러오지 못하거나 오류가 발생한다면 사이트 인증 문제인 경우가 많습니다. 이런 상황에서는 확장앱 설정에서 `외부 인증 서버 사용`을 켜면 도움이 됩니다.

같은 내부 네트워크에서만 사용할 때는 포트포워딩이 필요하지 않습니다. 집 밖이나 다른 네트워크에서 서버에 직접 접속하려면 일반적으로 공유기에서 인증 서버 포트(기본 `9870`)를 포트포워딩해야 합니다. 포트포워딩 설정이 어렵거나 인터넷에 포트를 직접 공개하고 싶지 않다면 **Tailscale 사용을 권장합니다.** Tailscale을 사용하는 경우 앱과 인증 서버 장치가 같은 Tailscale 네트워크에 연결되어 있어야 합니다.

### Docker/NAS 0.3.4

- [Docker/NAS ZIP 다운로드](https://github.com/wankyo83/rabbit-auth-server-releases/releases/download/v0.3.4/rabbit-auth-server-docker-0.3.4.zip)
- [Docker/NAS 설치 설명서](https://dc-toki.pages.dev/tools/rabbit-auth-server/Docker-Install-KO.md)

### Windows 0.4.4

- [Windows ZIP 다운로드](https://github.com/wankyo83/rabbit-auth-server-releases/releases/download/v0.4.4/RabbitAuthServer-Windows-x64-0.4.4.zip)
- [Windows 설치 설명서](https://dc-toki.pages.dev/tools/rabbit-auth-server/Windows-Install-KO.md)

## 주의

확장앱과 저장소는 Mihon·Tachimanga·Suwayomi 본체와 별도로 관리되는 비공식 구성입니다. 신뢰할 수 있는 저장소만 추가하고, 이용 지역의 법률과 사이트 이용 약관을 확인하세요.

## 참고

- [Mihon 공식 시작 안내](https://mihon.app/docs/guides/getting-started)
- [Tachimanga 공식 저장소 안내](https://tachimanga.app/help/guides/repositories.html)
- [Tachimanga 공식 확장앱 안내](https://tachimanga.app/help/guides/extensions.html)
