# #73 AiClient 리서치

브랜치 `feat/73-ai-client` (dev 5c3b2ef 기준). 소비자: #74 추천, #75 표지판, #76 채팅.

## 1. 현재 레포 패턴
- **스택**: Spring Boot 4.1.1, Java 17. `spring-boot-starter-restclient` 이미 있음 → `RestClient` 바로 사용 가능. OpenAI 의존성 없음.
- **외부 HTTP 선례**: `auth/client/KakaoClient`
  - `RestClient.Builder` 주입, `SimpleClientHttpRequestFactory`로 connect/read 타임아웃 강제
  - 설정은 `@Value("${auth.kakao.*}")`, yaml에서 `${KAKAO_READ_TIMEOUT:5s}` 형태 env 기본값
  - `onStatus`로 상태코드 → `BusinessException(ErrorCode)` 매핑
  - 예외 원문(키·바디 포함 가능)을 버리고 클래스명만 남긴 safe cause로 감쌈 → AI도 동일하게 해야 함
  - 테스트용 package-private 생성자 `KakaoClient(RestClient, ...)`
- **mock 스위치**: `subway/mock/MockSubwayModuleConfig`
  - `@Configuration(proxyBeanMethods=false)` + `@ConditionalOnProperty(name="linkro.subway.module", havingValue="mock", matchIfMissing=true)`
  - 생성자에서 `log.warn("... is MOCK ...")` 한 줄
  - 실제 구현은 `havingValue="graph"` 별도 Config. env `LINKRO_SUBWAY_MODULE`
  - 인터페이스·record는 `subway.api`, 문서는 `docs/subway-module-interface.md`, 계약 테스트 `SubwayRouteFinderContract`
- **설정**: `application.yaml`이 `optional:file:.env[.properties]` import. `.env.example`에 변수별 주석.
- **에러**: `common/exception/ErrorCode`에 `AI_UNAVAILABLE`(503), `AI_QUOTA_EXCEEDED`(429), `IMAGE_INVALID`(400), `EXTERNAL_API_ERROR`(502) 존재. `GlobalExceptionHandler` + `ApiResponse` 래퍼.
- **테스트**: `./gradlew test`(H2/mock), `./gradlew postgresTest`(Testcontainers). 둘 다 프로퍼티 미설정 → mock이 기본으로 잡혀 실제 OpenAI 호출 없음.

## 2. OpenAI 연동 옵션 (2026-11 기준)
| 옵션 | 버전/호환 | 장점 | 단점 |
| --- | --- | --- | --- |
| A. RestClient 직접 호출 | 추가 의존성 0 | Kakao와 같은 스타일, 타임아웃·에러 매핑 완전 통제, `MockRestServiceServer`로 테스트 쉬움 | 요청/응답 DTO 직접 작성(필요한 필드만이라 소량) |
| B. 공식 `com.openai:openai-java` | v4.99.0 (2026-11-04) | 타입 안전, structured output·vision·재시도 내장 | OkHttp/Kotlin stdlib 전이 의존성, 릴리스가 매우 잦음, 테스트 시 HTTP 스텁 별도 필요 |
| C. Spring AI | 2.0.x가 Boot 4.0/4.1 지원(2.0.2 → openai-java 4.80.0). 2.1은 RC·Boot 4.2 | `ChatClient`, `.entity(Class)` 구조화 출력, 자동설정 | 자동설정이 `spring.ai.openai.api-key`를 요구 → mock 기본과 충돌(제외 설정 필요), 추상화 과함, 의존성 큼 |

→ 호출 3종(텍스트→JSON, 텍스트→문장, 이미지→JSON)뿐이라 **A 권장**. B는 차선.

## 3. 구조화 출력
- Responses API `POST /v1/responses`의 `text.format = {type:"json_schema", strict:true, schema}` 사용 (Chat Completions면 `response_format`).
- strict 스키마 규칙: 모든 필드 `required`, `additionalProperties:false`, 선택값은 `["string","null"]`.
- 스키마는 classpath JSON(`ai/schema/*.json`)로 두고 응답은 Jackson으로 record 역직렬화. 파싱 실패·`refusal`·`status != completed` → `AI_UNAVAILABLE`.
- 서버 검증 필수: #74 filter 범위·역명→stationId 매핑, 설명 문장의 수치 일치 검사는 소비자 쪽에서.

## 4. Vision (#75)
- `input_image`에 `data:image/jpeg;base64,...` data URL. 파일 저장 없음(메모리만).
- 사전 검사: content-type(jpeg/png/webp), 크기 상한(예 5MB), 디코드 가능 여부 → 실패 시 `IMAGE_INVALID` (AI 호출 전).
- `detail: "low"`로 토큰·지연 절감(5초 목표).
- 응답 스키마: `{lineName, direction(UP|DOWN|INNER|OUTER|LOOP|null), headsign, confidence}`. 판정(correct/wrong/RETAKE)은 서버가 비교, 불확실하면 RETAKE.

## 5. 타임아웃·재시도
- connect 2s, read: 텍스트 8s / vision 4s (5초 목표). 용도별 다르게 설정 가능해야 함.
- 재시도: 기본 0회. 동기 경로(#74, #75)는 폴백이 있으므로 재시도보다 빠른 실패가 낫다. #76 비동기만 1회 허용 검토.

## 6. 에러 매핑
| 상황 | ErrorCode |
| --- | --- |
| 429 `insufficient_quota` / rate limit | `AI_QUOTA_EXCEEDED` |
| 5xx, 타임아웃, I/O, 파싱 실패, refusal, 키 없음 | `AI_UNAVAILABLE` |
| 401/403, 400(우리 요청 버그) | `AI_UNAVAILABLE` (+ ERROR 로그) |
| 잘못된 이미지 | `IMAGE_INVALID` (사전 검사) |
- `EXTERNAL_API_ERROR`는 AI에 쓰지 않음(소비자가 AI 코드로 폴백 판단).
- 로그/cause에 키·프롬프트·이미지 원문 남기지 않음(Kakao 방식).

## 7. 테스트
- mock 구현: 결정적 출력 → 단위/통합 테스트 기본.
- OpenAI 구현: `MockRestServiceServer`로 성공/429/500/타임아웃/잘못된 JSON 케이스. 실제 호출 없음.
- Config 테스트: `ApplicationContextRunner`로 미설정→mock, `openai`+키→OpenAI, `openai`+빈 키→기동 실패 (`MockSubwayModuleConfigTest` 스타일).
- 선택: `@Tag("live")` + 키 있을 때만 수동 실행, CI 제외.
