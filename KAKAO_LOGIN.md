# Issue #12 카카오 로그인

`POST /api/v1/auth/kakao`를 구현했습니다. SDK에서 받은 카카오 토큰의 `/v1/user/access_token_info` 응답에서 App ID·만료 여부를 검증한 후 `/v2/user/me`로 회원 ID·닉네임을 조회하고 LinkRo JWT를 발급합니다. 두 API의 회원 ID가 다르면 인증에 실패합니다. 기존 PostgreSQL 설정과 `ddl-auto: update`를 유지합니다. Refresh Token과 인증 필터는 포함하지 않습니다.

## 변경 파일과 목적

Java 파일 경로는 `src/main/java/com/mju/linkro/` 기준입니다.

| 파일 | 목적 |
| --- | --- |
| `auth/controller/AuthController.java` | 로그인 POST 요청 및 입력 검증 |
| `auth/service/AuthService.java` | 외부 조회 → DB 처리 → JWT 발급, 동시 가입 충돌 재조회 |
| `auth/service/UserLoginService.java` | 별도 트랜잭션에서 가입·복구, 신규 닉네임 제한·fallback |
| `auth/client/KakaoClient.java` | RestClient 외부 조회, 타임아웃, 인증 실패·외부 오류 분류 |
| `auth/dto/KakaoLoginRequest.java` | 토큰 요청 DTO, 토큰을 출력하는 toString 생성 방지 |
| `auth/dto/KakaoLoginResponse.java` | accessToken, user, isNewUser 응답 |
| `auth/dto/KakaoUserInfo.java` | 서버가 카카오에서 조회한 ID·닉네임 전달 |
| `auth/jwt/JwtProvider.java` | 설정 기반 HS256 JWT 생성, sub·iat·exp |
| `user/domain/User.java` | users 매핑, #16 BaseUuidTimeEntity 상속, 도메인 soft delete·복구 |
| `user/repository/UserRepository.java` | 삭제된 회원도 포함하는 kakaoId 조회 |
| `build.gradle` | 필요한 라이브러리 추가 |
| `src/main/resources/application.yaml` | JWT 환경 설정, 기존 DB 설정 유지 |
| `.env.example` | JWT 변수 안내, 기존 로컬 변경 보존 |
| `docker-compose.yml` | app 컨테이너로 JWT 변수 전달 |
| `src/test/resources/application-test.yaml` | 명시적인 test profile의 H2 DB 및 테스트 전용 인증 설정 |
| `src/test/java/com/mju/linkro/auth/AuthServiceTest.java` | 실제 JPA 가입·복구·동시 로그인·JWT 검증, KakaoClient mock |
| `src/test/java/com/mju/linkro/auth/client/KakaoEndpointTest.java` | 외부 HTTP mock과 MVC를 통한 401·502 및 최신 프로필 필드 검증 |
| `KAKAO_LOGIN.md` | 실행·Postman·설정·검증 안내 |

응답과 예외 처리는 dev의 #14 공통 모듈을 그대로 사용합니다. UUIDv7 생성·Persistable·Auditing·동등성은 #16 BaseUuidTimeEntity/BaseUuidEntity를 상속해 사용하며, User에는 kakaoId·nickname·deletedAt과 삭제·복구 로직만 둡니다. 공통 클래스와 JpaAuditingConfig는 재구현하거나 변경하지 않습니다.

## 추가 의존성

- `spring-boot-starter-restclient`: 현재 Spring Boot 스택의 동기 HTTP 클라이언트. WebFlux 추가 없음.
- `jjwt-api:0.13.0`: JWT 생성 API.
- `jjwt-impl:0.13.0`: JWT 런타임 구현.
- `jjwt-gson:0.13.0`: JWT JSON 직렬화. MVC의 Jackson 3 구성과 분리.

uuid-creator와 테스트 전용 H2는 dev 공통 기반에 이미 존재하는 의존성을 그대로 사용합니다.

## 환경변수

| 변수 | 의미 / 기본값 |
| --- | --- |
| `JWT_SECRET` | 필수. 최소 32바이트 무작위 키를 Base64 인코딩한 값 |
| `JWT_ACCESS_TOKEN_TTL` | 필수. Spring Duration 형식 (`15m`, `1h` 등). 실제 정책은 미확정 |
| `KAKAO_APP_ID` | 필수. Kakao Developers의 숫자 App ID. Native App Key / REST API Key와 다른 값 |
| `KAKAO_BASE_URL` | `https://kapi.kakao.com` |
| `KAKAO_CONNECT_TIMEOUT` / `KAKAO_READ_TIMEOUT` | `3s` / `5s`. Boot 기본 HTTP client를 명시적인 request factory로 대체 |
| `DB_URL` | `jdbc:postgresql://localhost:5432/linkro` |
| `DB_USERNAME` | `linkro` |
| `DB_PASSWORD` | `linkro` |
| `POSTGRES_DB` | Compose DB 이름, 기본 `linkro` |
| `POSTGRES_USER` | Compose 사용자, 기본 `linkro` |
| `POSTGRES_PASSWORD` | Compose 비밀번호, 기본 `linkro` |
| `POSTGRES_PORT` | Compose 호스트 포트, 기본 `5432` |

로컬 기본값으로 실행하려면 `.env`의 PostgreSQL 값도 linkro로 맞추세요. 파일의 실제 비밀키를 저장소에 커밋하지 마세요. Spring Boot는 `.env` 파일을 자동으로 로드하지 않으므로 bootRun에는 셸 환경변수 또는 IntelliJ Run Configuration을 사용합니다. Compose는 `.env`를 읽습니다.

`docker compose up -d postgres`는 JWT/Kakao 변수 없이 실행할 수 있습니다. app의 JWT/Kakao App ID는 Compose 필수 보간을 사용하지 않으며, app 실행 시 Spring Boot가 필수 설정을 검증합니다. 배포 workflow는 컨테이너 교체 전에 서버 `~/BE/.env`의 JWT_SECRET·JWT_ACCESS_TOKEN_TTL·KAKAO_APP_ID가 비어 있지 않은지 검사하고, 값은 출력하지 않습니다. 머지 전에 서버에 세 값을 수동 설정해야 합니다.

## 실행 (PowerShell)

로컬 테스트용 무작위 키 생성과 수명 설정:

```powershell
$jwtKeyBytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Fill($jwtKeyBytes)
$env:JWT_SECRET = [Convert]::ToBase64String($jwtKeyBytes)
$env:JWT_ACCESS_TOKEN_TTL = '15m'
$env:KAKAO_APP_ID = '본인 앱의 숫자 App ID'
docker compose up -d postgres
.\gradlew.bat bootRun
```

`15m`는 로컬 실행 예시이며 확정 정책이 아닙니다. Java 17을 사용합니다. 기본 서버 포트는 8080입니다.

## Postman

1. Kakao Developers 앱에 카카오 로그인 및 닉네임 동의 항목을 설정하고 프론트 SDK로 로그인합니다.
2. SDK에서 획득한 실제 **Access Token**을 아래 JSON의 `kakaoAccessToken` 값에 넣습니다. 카카오 앱 REST API 키나 ID Token을 넣지 않습니다.
3. POST `http://localhost:8080/api/v1/auth/kakao`, Header `Content-Type: application/json`, Body → raw → JSON:

```json
{"kakaoAccessToken":"여기에 실제 카카오 Access Token"}
```

4. 최초 요청은 200, `success=true`, `data.isNewUser=true`, UUIDv7 ID와 LinkRo `data.accessToken`을 확인합니다.
5. 같은 회원으로 다시 요청하면 ID가 같고 `isNewUser=false`입니다. soft delete 복구도 같은 ID·기존 LinkRo 닉네임을 유지합니다.
6. 잘못되거나 만료된 토큰은 401 / `KAKAO_AUTH_FAILED`, 카카오 장애·네트워크 오류는 502 / `EXTERNAL_API_ERROR`입니다. `details`는 빈 객체입니다.

userId·nickname을 요청에 보내도 회원 정보를 결정하는 데 사용하지 않습니다. 백엔드는 카카오 토큰을 DB에 저장하지 않고 외부 요청의 Authorization 헤더에서만 사용합니다. 반환된 JWT가 LinkRo 서비스 토큰입니다.

## 테스트 및 남은 사항

```powershell
.\gradlew.bat test
```

테스트는 실제 카카오 API를 호출하지 않습니다. 신규·기존·복구, UUIDv7와 Persistable 상태, Auditing, 긴 닉네임(유니코드 포함), fallback, 동시 첫 로그인, JWT 서명·sub·iat·exp, HTTP 401·502 및 네트워크 실패를 검증합니다.

- Access Token의 운영 수명은 명세 확정 후 환경변수로 결정해야 합니다.
- 실제 SDK 토큰을 이용한 종단 간 검증과 PostgreSQL 16에서의 동시 가입·DDL 검증은 별도 필요합니다. 자동 테스트는 H2 PostgreSQL 모드입니다.
- H2의 varchar 길이 계산은 PostgreSQL과 다르므로 이모지 닉네임의 code point 절단은 별도 단위 테스트로 검증합니다. H2 테스트가 실제 PostgreSQL UUID·시간·문자열 호환성을 모두 보장하지는 않습니다.
- 애플리케이션 context 테스트는 `@ActiveProfiles("test")`로 H2·테스트 인증 설정을 선택합니다. #16 기반 테스트는 자체 H2 properties를 유지합니다. CI에는 미사용 PostgreSQL 서비스를 두지 않습니다.
- DB CHECK 제약 및 Flyway 전환은 별도 작업입니다. 이번에는 기존 ddl-auto 설정을 유지합니다.
- 추후 JWT 인증 필터를 JwtProvider 기반으로 연결할 수 있습니다. Refresh Token은 이번 구현에 없습니다.

참고: [카카오 사용자 정보 API](https://developers.kakao.com/docs/ko/kakaologin/rest-api), [UUID Creator](https://github.com/f4b6a3/uuid-creator), [JJWT](https://github.com/jwtk/jjwt).
