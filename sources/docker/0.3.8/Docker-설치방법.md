# Rabbit 인증 서버 0.3.8 — Docker/NAS 설치 방법

이 서버는 확장앱에서 **외부 인증 서버 사용**을 켰을 때만 사용하는 선택 기능입니다. 사이트를 실제 브라우저로 열어 회차 인증을 처리하고 이미지 주소·순서·개수를 확장앱에 전달합니다.

이미지 파일 자체는 저장하거나 중계하지 않습니다. 실제 이미지 다운로드는 Mihon, Tachimanga 또는 Suwayomi가 직접 처리합니다.

## 1. 준비 사항

- Docker를 실행할 수 있는 x86-64 NAS 또는 Linux PC
- 권장 여유 메모리 2GB 이상
- 같은 Wi-Fi/LAN 또는 Tailscale로 서버에 접속할 기기
- 기본 포트: `9870`

ARM NAS는 현재 검증 대상이 아닙니다.

## 2. 설치 파일 압축 해제

ZIP을 NAS의 원하는 폴더에 압축 해제합니다. 반드시 Docker 전용 폴더 아래에 둘 필요는 없습니다.

예시:

```text
/volume1/docker/rabbit-auth-server/
/volume1/apps/rabbit-auth-server/
/volume2/my-containers/toki-server/
```

다음 파일과 폴더를 같은 위치에 구조 그대로 둡니다.

```text
docker-compose.yml
docker-compose.build.yml
Dockerfile
entrypoint.sh
package.json
package-lock.json
src/
public/
```

일반 설치와 업데이트에는 `docker-compose.yml`을 사용합니다. 이 파일은 GHCR의 공개 안정 이미지를 내려받습니다. `docker-compose.build.yml`과 소스는 GHCR를 사용할 수 없을 때를 위한 로컬 빌드 예비 구성입니다.

## 3. docker-compose.yml 수정

별도의 `.env` 파일은 필요하지 않습니다. `docker-compose.yml`을 직접 편집합니다.

### 접속 키

```yaml
SERVER_KEY: "replace-with-your-key"
```

예제 문구를 본인이 사용할 4자 이상의 키로 바꿉니다.

```yaml
SERVER_KEY: "1234"
```

- 숫자, 영문, 특수기호를 사용할 수 있습니다.
- 공백은 사용하지 않는 것을 권장합니다.
- `0123`처럼 0으로 시작하는 키는 따옴표를 유지합니다.
- NAS 로그인 비밀번호나 사이트 계정 비밀번호는 사용하지 마세요.

### 접속 포트

```yaml
ports:
  - "9870:9870"
```

기본 포트를 그대로 사용하거나, 충돌하면 왼쪽 숫자만 바꿉니다.

```yaml
ports:
  - "9871:9870"
```

이 경우 접속 주소는 `http://서버주소:9871`입니다. 오른쪽 컨테이너 포트 `9870`은 변경하지 않습니다.

### 접속 주소 허용

```yaml
ALLOWED_HOSTS: "*"
```

기본값 그대로 둡니다. 사용자마다 다른 NAS 이름, PC 이름, LAN IP 및 Tailscale IP를 미리 등록할 필요가 없습니다.

`*`는 주소 제한만 해제하는 설정이며 접속 키 검증은 계속 적용됩니다. DNS, Tailscale, 방화벽 또는 네트워크 경로를 자동으로 설정하는 기능은 아닙니다.

### 브라우저 샌드박스

```yaml
BROWSER_SANDBOX: "false"
```

검증한 Synology NAS에서 `true`로 설정하면 브라우저가 시작되지 않았으므로 기본값 `false`를 사용합니다. 이 설정은 브라우저 격리를 줄이므로 서버를 인터넷에 직접 공개하지 마세요.

## 4. Synology Container Manager 설치

1. Container Manager에서 **프로젝트 → 생성**을 선택합니다.
2. 압축을 푼 폴더의 `docker-compose.yml`을 선택합니다.
3. 프로젝트를 빌드하고 시작합니다. `pull_policy: always`에 따라 최신 안정 이미지를 확인합니다.
4. 최초 설치는 브라우저가 포함된 이미지 다운로드 때문에 시간이 걸릴 수 있습니다.
5. 컨테이너 상태가 `healthy`가 될 때까지 기다립니다.

SSH에서 설치한다면 압축을 푼 폴더로 이동한 뒤 실행합니다.

```sh
docker compose -f docker-compose.yml pull
docker compose -f docker-compose.yml up -d
docker compose -f docker-compose.yml logs --tail=100
```

Synology에서 `docker: command not found`가 나오면 다음 Docker 경로를 사용해야 할 수 있습니다.

```text
/var/packages/ContainerManager/target/usr/bin/docker
```

## 5. 서버 접속 확인

브라우저에서 다음 주소를 엽니다.

```text
http://NAS주소:9870
```

로그인 창이 표시되면 다음 정보를 입력합니다.

```text
사용자 이름: lab
비밀번호: docker-compose.yml에 설정한 SERVER_KEY
```

주소 예시:

```text
http://192.168.0.7:9870
http://100.x.x.x:9870
http://nas:9870
```

이름 주소는 접속하는 기기에서 해당 이름을 DNS로 찾을 수 있을 때만 사용할 수 있습니다.

## 6. 확장앱 설정

각 확장앱의 설정에서 다음과 같이 입력합니다.

1. **외부 인증 서버 사용** → ON
2. **서버 주소** → `http://서버주소:9870`
3. **접속 키** → `SERVER_KEY`와 같은 값
4. **연결 확인** 실행

서버 주소 끝에 `/health` 또는 `/v1` 같은 API 경로를 붙이지 않습니다.

### Android 및 iPhone/iPad

휴대폰에서 실제로 접속할 수 있는 LAN IP, Tailscale IP 또는 MagicDNS 이름을 사용합니다.

```text
http://192.168.0.7:9870
http://100.x.x.x:9870
http://nas:9870
```

### Suwayomi Docker

Suwayomi에는 **NAS의 LAN IP** 사용을 권장합니다.

```text
http://192.168.0.7:9870
```

PC나 휴대폰에서 `http://nas:9870`이 열려도 Suwayomi 컨테이너에서는  Tailscale MagicDNS 이름을 찾지 못할 수 있습니다. Docker 컨테이너는 호스트의 Tailscale DNS 환경을 자동으로 물려받지 않기 때문입니다.

사용자 지정 이름을 반드시 사용하려면 Suwayomi의 `docker-compose.yml`에 사용자 환경에 맞는 매핑을 별도로 추가할 수 있습니다.

```yaml
extra_hosts:
  - "nas:192.168.0.7"
```

이 이름과 IP는 사용자마다 다르므로 배포 파일에는 고정되어 있지 않습니다.

## 7. 오류 확인

### 401 또는 접속 키 오류

- 확장앱과 `docker-compose.yml`의 접속 키가 같은지 확인합니다.
- 키 앞뒤에 공백이 들어가지 않았는지 확인합니다.

### 서버에 연결할 수 없음 또는 시간 초과

- 입력한 주소가 앱 또는 Suwayomi 컨테이너에서 실제로 열리는지 확인합니다.
- NAS 방화벽과 포트 설정을 확인합니다.
- Suwayomi에서는 MagicDNS 이름 대신 NAS LAN IP로 시험합니다.

### `untrusted_host`

예전 서버 이미지가 실행 중일 수 있습니다. 0.3.8의 `docker-compose.yml`로 프로젝트를 다시 빌드·재생성하고 다음 설정이 적용됐는지 확인합니다.

```yaml
ALLOWED_HOSTS: "*"
```

단순 재시작만으로는 새로운 서버 이미지가 반영되지 않습니다.

### `browser_not_ready`

컨테이너 로그에서 메모리 부족 또는 브라우저 시작 오류를 확인합니다. Synology에서는 `BROWSER_SANDBOX: "false"`인지 확인합니다.

## 8. 업데이트와 삭제

- 기존 프로젝트 이름과 데이터 볼륨을 유지하면 브라우저 쿠키 상태를 계속 사용할 수 있습니다.
- 0.3.5부터 기본 이미지는 `ghcr.io/wankyo83/rabbit-auth-server:stable`입니다.
- Container Manager에서 기존 프로젝트를 **빌드/재생성**하면 `pull_policy: always`에 따라 최신 안정 이미지를 확인합니다. 단순 재시작만으로는 새 이미지를 확인하지 않을 수 있습니다.
- SSH에서는 `docker compose -f docker-compose.yml pull` 후 `docker compose -f docker-compose.yml up -d`를 실행합니다.
- 특정 버전으로 되돌릴 때는 이미지 태그 `stable`을 원하는 버전(예: `0.3.8`)으로 바꾸고 다시 `pull`과 `up -d`를 실행합니다.
- GHCR를 사용할 수 없으면 `docker-compose.build.yml`에 기존 설정을 옮긴 뒤 `docker compose -f docker-compose.build.yml up -d --build`로 로컬 빌드할 수 있습니다.
- 일반적인 중지는 `docker compose stop`을 사용합니다.
- `docker compose down -v`는 브라우저 데이터 볼륨까지 삭제하므로 주의하세요.
- 압축을 푼 폴더 위치나 프로젝트 이름을 바꾸면 Docker가 별도 프로젝트와 별도 볼륨으로 인식할 수 있습니다.

## 9. 보안 안내

- 기본 구성은 신뢰하는 LAN 또는 Tailscale 안에서 사용하는 용도입니다.
- 인터넷에 직접 포트포워딩하지 마세요.
- HTTP에서는 접속 키가 암호화되지 않습니다.
- 서버 화면에 사이트 계정 비밀번호, 결제 정보 또는 개인정보를 입력하지 마세요.
- 외부 인증 서버 사용을 OFF로 바꾸면 확장앱은 기존 방식으로 동작합니다.
- 서버 설정은 라이브러리, 소스 ID, 읽음 상태 또는 기존 다운로드를 변경하지 않습니다.
