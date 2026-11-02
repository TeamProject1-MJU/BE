# 약속방 기본 API (#35)

## 작업 기준과 범위

- 작업 브랜치: `feat/35-room-api`. 시작 시 미커밋 변경 없음.
- `git fetch origin` 후 HEAD와 origin/dev 모두 `798e8fb8ab2a86dc2a2d6c2ae8ef8d7069a4d891`. 시작 시 dev 대비 차이 없음.
- 최신 GitHub #35, #38 본문을 읽기 전용으로 확인했다. 이슈 본문과 첨부 원본은 수정하지 않는다.
- 이슈의 v1.7 참조 대신 `LinkRo API 명세서 최종본 (3).docx`의 v1.12, ERD v1.13, #33 스키마, #34 도메인·RoomAccess를 기준으로 구현한다.
- 실제 전체 Flyway는 V1~V11이다. 기존 마이그레이션과 정식 테이블 정의를 변경하지 않는다. 이전 #34 문서의 V9/V10 시점 설명은 현재 체크아웃 상태와 다르다.
- 저장소와 상위 경로에서 적용되는 비어 있지 않은 AGENTS.md는 발견되지 않았다. 사용자 지침에 따라 커밋·push·PR·이슈 변경은 하지 않는다.

## API와 응답

기본 경로 `/api/v1`, 기존 `@CurrentUser UUID`와 JWT 필터를 사용한다. 모든 성공 응답은 기존 `ApiResponse`다. 엔티티는 반환하지 않는다.

| 메서드·경로 | 권한 | 성공 data |
| --- | --- | --- |
| POST /rooms | 인증된 활성 사용자 | 201, id·roomCode·name·maxMembers·status·ownerId·appointmentAt |
| GET /rooms | 인증된 활성 사용자 | 200, 참여 중인 방 배열. id·name·status·myRole·memberCount·maxMembers·appointmentAt·selectedStation. 빈 결과는 [] |
| GET /rooms/{roomId} | MEMBER/OWNER | 200, id·name·status·maxMembers·appointmentAt·ownerId·myRole·selectedStation·confirmedAt·members |
| PATCH /rooms/{roomId} | 현재 OWNER | 200, null |
| GET /rooms/{roomId}/members | MEMBER/OWNER | 200, userId·nickname·role·joinedAt·departureStation 배열 |
| GET /rooms/{roomId}/invite-code | 현재 OWNER | 200, roomCode |

상세의 members도 7.6이 명시한 동일 구조로 joinedAt을 포함한다. 출발역 미설정은 null이다. 역은 id·name·isSupported·lines, 노선은 lineId·lineName·color를 반환한다. 후보는 상세에 포함하지 않는다. selectedStation 유무로 OPEN/CONFIRMED를 계산한다. ownerId는 현재 OWNER 멤버이며 created_by는 최초 생성자 기록으로만 쓴다.

초대 코드는 생성 응답과 OWNER 전용 조회 외에는 DTO에 필드 자체가 없다. OWNER 호출도 목록·상세·멤버 JSON에 roomCode가 없어야 한다.

기존 SecurityConfig는 실제로 `/api/v1/**` 전체를 인증 허용했다. 이번에는 공개 인증·Swagger·ERROR dispatch 규칙과 기존 users/me API를 유지하고, 방 API의 위 메서드와 경로만 명시적으로 인증 허용한다. 나머지는 denyAll이다. 따라서 인증된 미등록 API도 종전 MVC 404 대신 ACCESS_DENIED(403)이며, 미인증 API는 JWT 필터의 AUTH_REQUIRED(401)를 유지한다. 기존 인증·CORS 테스트의 테스트용 컨트롤러는 허용된 경로로 옮겨 검증을 유지했다.

## 생성 트랜잭션과 코드 충돌

`RoomCreationService`는 NOT_SUPPORTED로 재시도 루프에 트랜잭션을 두지 않는다. 별도 Spring 빈 `RoomCreationAttempt.create`의 REQUIRES_NEW에서 활성 사용자 재확인 → 방 saveAndFlush → OWNER saveAndFlush → 커밋 순서로 처리한다. 정원 4, 최초 출발역 null이며 요청의 userId·ownerId·maxMembers는 사용하지 않는다.

SecureRandom으로 A-Z/0-9 6자 코드를 만든다. 사전 존재 확인 없이 실제 Hibernate ConstraintViolationException의 SQLSTATE `23505`와 제약 이름 `uq_room_code`가 모두 맞을 때만 새 코드로 재시도한다. 최대 **총 5회 시도**이며 재시도 전에 이전 프록시 트랜잭션이 완전히 롤백된다. OWNER 저장·커밋 실패도 방과 함께 롤백된다. 중간 실패 후 서비스 내부에서 계속 쓰지 않는다.

충돌 소진은 기존 INTERNAL_ERROR(500)로 처리한다. 새 외부 코드는 만들지 않는다. 다른 UNIQUE/CHECK/FK·연결 오류 등은 재시도하거나 일괄 400/409로 바꾸지 않고 기존 전역 500 정책에 맡긴다.

## PATCH 해석과 입력 검증

명세 7.5는 모든 필드 선택이라고 명시하지만 null 요청과 성공 응답 예시를 정의하지 않는다. 다음은 기존 모델·응답 관례를 근거로 한 구현 판단이다.

- setter 기반 DTO에서 Java null은 생략, JSON NullNode는 명시적 null로 보존한다. 숫자·불리언을 문자열로 자동 변환하지 않는다.
- name 생략은 유지, null은 VALIDATION_ERROR. PostgreSQL btrim과 동일하게 앞뒤 U+0020만 제거해 저장하고 코드포인트 1~30자를 검증한다. 탭·줄바꿈·유니코드 공백은 임의로 정규화하지 않는다. DB text에 넣을 수 없는 NUL은 요청 오류다. 이모지의 UTF-16 길이 대신 codePointCount를 사용한다.
- appointmentAt 생략은 유지, 명시적 null은 해제한다. 근거는 ERD와 V5의 nullable appointment_at, 기존 MeetingRoom.schedule(null), 명세 8.2의 약속 시각 null 비교 규칙이다. **7.5 자체의 명시적 해제 문구는 없으며 위 근거에 따른 판단**이다.
- 시각은 시간대가 있는 ISO 8601 값을 Instant로 처리한다. 미래 시각 제한 등 명세에 없는 제약은 추가하지 않는다.
- PATCH 성공은 기존 무본문 변경/탈퇴의 ApiResponse.success() 관례에 따라 `200 {"success":true,"data":null}`이다. 변경된 방 상세나 초대 코드를 임의로 반환하지 않는다.
- 알 수 없는 요청 필드는 기존 Jackson 정책대로 무시하지만 변경 대상으로 사용하지 않는다.

쓰기 트랜잭션의 첫 도메인 호출은 `RoomAccess.lockOwner`다. 방 락과 현재 역할 확인이 끝난 뒤 전체 요청을 검증하고 rename/schedule만 실행한다. 락 전에 persist/remove/dirty 변경은 없다. RoomAccess의 MANDATORY·READ COMMITTED·전체 세션 dirty 거절·3초 timeout 정책을 그대로 사용하며 ROOM_BUSY(409)를 기존 handler로 반환한다.

약속 시각 수정은 기존 추천 실행/결과·후보·투표·확정역·confirmedAt을 보존한다. 자동 재추천과 stale 판정을 수행하지 않고 isStale 컬럼도 추가하지 않는다.

## 조회와 후속 연동

OSIV=false를 유지한다. RoomService의 읽기 트랜잭션 안에서 참여 방을 JPA로 조회하고, 전체 대상 방의 멤버+닉네임을 한 번, 필요한 역+노선을 한 번 일괄 조회해 DTO로 변환한다. 목록은 데이터 수에 비례하는 반복 쿼리 없이 최대 3회이며 역이 없으면 역 쿼리는 생략된다. 명세에 없는 페이지 래퍼는 추가하지 않는다. 목록 순서는 안정적으로 created_at DESC, id DESC를 사용한다(명세가 순서를 규정하지 않아 내부 기본 순서로만 사용).

현재 dev에는 stations/subway_lines/station_lines 정식 테이블과 V11 시드는 있지만 Java 역 조회 서비스·Repository는 없다. `RoomReadRepository`는 정식 테이블의 읽기 전용 배치 어댑터이며 다른 담당자의 역 검색·그래프 도메인을 구현하지 않는다. 역 담당자의 공통 조회 기능이 합쳐지면 이 어댑터를 같은 DTO 계약으로 교체할 수 있다.

### #38 회원 탈퇴

JWT 필터와 생성 트랜잭션에서 미존재/탈퇴 사용자를 차단한다. 그러나 현재 UserService.withdraw는 활성 사용자 조회 → soft delete → 토큰 폐기만 하고 사용자 행 잠금이나 방 정리를 하지 않는다. #34 RoomAccess는 기존 방을 직렬화하며 신규 방 생성과 사용자 탈퇴를 직렬화하지 않는다.

따라서 **활성 확인 직후 탈퇴가 커밋되면 탈퇴자의 방/OWNER가 남는 경합은 아직 보장하지 않는다**. 생성 쪽만 임의로 락을 추가해 탈퇴와 직렬화됐다고 주장하지 않는다. #38 본문도 생성·참여·탈퇴에 동일한 사용자 상태 검증·잠금 규칙을 함께 적용하도록 명시한다. #38에서 사용자 잠금 → 활성 상태 확인 → 방 ID DB 오름차순 잠금·나가기 정리 → soft delete·토큰 폐기를 연결하고 생성/참여 서비스도 동일 규칙으로 조정해야 한다. 재시도마다 사용자 상태도 다시 확인해야 한다.

### #43 이벤트와 제외 범위

`RoomService.update`의 허용 필드 변경 직후에 ROOM_UPDATED 연동 지점을 남겼다. #43에서 트랜잭션 성공과 커밋 후 발행 정책을 연결한다. 현재 이벤트 발행·STOMP·SYSTEM 메시지는 구현하지 않는다.

방 참여·출발역 변경, 나가기·승계·마지막 멤버 방 삭제, 회원 탈퇴 전체 정리, 약속역 지정·해제, 후보·투표 API, WS, 추천 계산·stale 판정은 후속 이슈 범위다. 테스트의 현재 OWNER 변경은 SQL fixture이며 승계 API 구현이 아니다.

## 검증

2026-11-02 최종 실행:

```powershell
.\gradlew.bat test postgresTest --no-daemon
git diff --check
```

| 검증 | 실행 | 통과 | 실패 | 오류 | 스킵 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 일반 test 전체 | 328 | 328 | 0 | 0 | 0 |
| postgresTest 전체 | 162 | 162 | 0 | 0 | 0 |
| 위 PostgreSQL 중 RoomApiPostgresTest | 23 | 23 | 0 | 0 | 0 |

Gradle BUILD SUCCESSFUL, `git diff --check` 통과. 최종 집계는 `build/test-results/test/TEST-*.xml`, `build/test-results/postgresTest/TEST-*.xml`의 tests/failures/errors/skipped 합계로 확인했다. 일반 테스트에는 새 요청 검증 25건과 락→변경 순서 검증 1건이 포함된다. 초기 일반 테스트에서 명시적 허용 목록으로 인해 인증·CORS 테스트용 경로 7건이 실패했고, 테스트용 컨트롤러를 허용된 경로로 이동한 후 최종 전체 재검증했다. 다운로드가 차단된 샌드박스 실행 1회는 검증 성공으로 집계하지 않으며, 권한이 허용된 Gradle 실행으로 최종 확인했다.

PostgreSQL 테스트는 운영/개발 DB 대신 postgres:16 Testcontainers에 전체 Flyway V1~V11을 적용하고 JPA validate와 OSIV=false로 기동한다. 테스트 전용 AFTER INSERT 트리거는 중간 실패를 주입한 후 제거하며 마이그레이션이나 제품 스키마 정의를 수정하지 않는다.

검증 범위는 생성 201 계약·인증 주입·OWNER 원자성, 실제 UNIQUE 충돌 후 독립 트랜잭션 재시도·5회 소진·무관한 무결성 오류의 재시도 금지, 중간 실패의 전체 롤백, 이름 경계·Unicode·입력 형식, PATCH 생략/null/offset 시각, 내 방 필터·빈 배열, 현재 OWNER와 최초 생성자 구분, 권한별 403/404, OWNER 호출을 포함한 JSON 초대 코드 비노출, 정식 역/노선·출발역 null, 미존재/탈퇴 사용자 차단과 후속 경로 거절, 3초 락 대기 ROOM_BUSY·변경 롤백, 추천·후보·투표·확정역 보존이다. 기존 RoomDomainPostgresTest 20건도 전체 실행에 포함되어 실제 락 SQL·dirty 세션 거절·락 후 변경·timeout 정책을 함께 검증했다.

탈퇴와 생성의 동시 직렬화 보장, 실제 OWNER 승계 API, 이벤트 발행, 추천 stale 판정은 이 검증으로 완료됐다고 간주하지 않는다.

## Swagger 후속 점검

대상은 방 6개, 카카오 로그인·refresh·logout 3개, 내 정보 GET·PATCH·DELETE 3개다. 기존 미커밋 변경을 유지하면서 문서 설정과 검증만 보완한다. SecurityConfig·JWT 필터·CurrentUserArgumentResolver·서비스의 인증/인가 동작은 이 후속 작업에서 변경하지 않는다.

- 기존 OpenApiConfig의 HTTP bearer/JWT 스키마와 Controller의 SecurityRequirement를 재사용한다. 루트 전역 requirement는 없고 방/내 정보는 인증 필요, 인증 POST 3개는 security:[]다. 공개 API가 Bearer 인증을 요구하지 않는다는 의미이며 refreshToken·카카오 토큰의 본문 검증은 그대로다.
- `@CurrentUser`에 `@Parameter(hidden=true)`를 추가한다. userId query를 없애지만 roomId의 required/path/uuid는 유지한다.
- 기존 sealed ApiResponse의 자동 추론 결과는 성공/실패 oneOf와 무타입 data였다. `ApiDocumentation`의 문서 전용 모델로 성공 data의 DTO·배열과 오류 envelope를 명시한다. 런타임 응답이나 DTO의 직렬화를 변경하지 않는다.
- RoomWriteRequest의 JsonNode 내부 구조가 문서에 노출되지 않도록 생성/수정 요청을 문서 전용 스키마로 분리한다. 생성 name만 필수이며 PATCH 필드는 모두 선택이다. 코드포인트·U+0020 처리와 null 의미를 설명하고 실제 예시를 제공한다. 실제 JsonNode setter와 생략/null 검증은 유지한다.
- 생성 성공은 201, 나머지 성공은 200으로 명시한다. PATCH·logout·회원 탈퇴는 data:null, 목록은 배열이다. 방 권한 오류 403·미존재 404·수정 락 timeout 409, 공통 요청/인증/서버 오류, 카카오 외부 통신 실패 502 등도 실제 handler의 envelope로 문서화한다.
- appointmentAt·confirmedAt 및 미설정/미확정 역의 null을 OpenAPI 3.1 스키마로 표현한다. springdoc 3.1.1은 null-only 클래스를 annotation으로 참조했을 때 누락된 참조를 생성했으므로, 역 필드는 OpenApiCustomizer에서 `anyOf: [Station 참조, {type:null}]`로 작성한다. `$ref`를 union의 형제에 두어 null을 거절하는 잘못된 구조를 사용하지 않는다.
- 내 닉네임 PATCH는 방 PATCH와 달리 nickname 필수이며 생략/null 오류라는 기존 동작을 문서에 명시한다.

검증은 실제 Spring 컨텍스트의 `/v3/api-docs`를 검사한다. bearer 스키마·12개 operation의 공개/보호 구분, 숨김 userId·유지된 roomId, 상태 코드·구체적인 응답 data·요청 필수성·PATCH 설명/타입, null union과 모든 로컬 schema 참조의 해소를 검증한다. 생성된 문서는 테스트 산출물 `build/openapi-test.json`에 저장한다.

기존 `validTokenInjectsSubjectAsCurrentUser`, 미인증/탈퇴/미존재 사용자 차단, 실제 UserController 테스트를 유지한다. `queryUserIdCannotOverrideAuthenticatedSubject`는 다른 userId query를 보내도 JWT subject가 주입되고, query만 보내는 무인증 요청은 거절되는지 확인한다. 실제 PostgreSQL의 방 생성 테스트에도 다른 query/body userId·ownerId를 넣어 인증된 사용자가 OWNER/created_by가 되는지 확인한다.

브라우저 연결을 시도했으나 사용 가능한 브라우저 목록이 비어 있었다. 따라서 **로컬 Swagger의 Authorize 버튼 표시·토큰 입력·Try it out 클릭은 수동 검증하지 않았다**. 실제 카카오 외부 계정 로그인도 수행하지 않았다. 서버 재시작과 브라우저 새로고침 후 README의 수동 절차로 확인해야 한다.

후속 점검 최종 실행: `.\gradlew.bat test postgresTest --no-daemon` BUILD SUCCESSFUL. springdoc 3.1.1에서 컴파일과 전체 일반 테스트 331건·전체 PostgreSQL 테스트 162건이 실패/오류/스킵 없이 통과했다. 일반 테스트에는 OpenAPI/Swagger 통합 5건·JWT 필터/주입 49건·실제 내 정보 Controller 10건·refresh Controller 7건이 포함되며, PostgreSQL에는 실제 방 API 23건이 포함된다. `git diff --check` 통과. 집계는 최종 JUnit XML 기준이다. 중간 검증에서 발견한 null-only 모델 참조 실패 1건은 문서 customizer 수정 후 전체 재검증으로 해결했다. 운영 서버 재시작·실제 사용자 토큰 등록은 수행하지 않았다.
