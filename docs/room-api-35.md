# 약속방 기본 API (#35)

## 기준과 API 계약

API 명세 v1.12의 7.1·7.3~7.6·7.9, ERD v1.13, 정식 Flyway V1~V11과 #34 공통 방 도메인을 따른다. 기존 마이그레이션과 첨부 명세 원본은 변경하지 않는다.

기본 경로는 `/api/v1`이며 기존 JWT 필터와 `@CurrentUser UUID`를 사용한다. 요청 userId·ownerId로 사용자를 결정하지 않는다. 엔티티 대신 DTO를 공통 ApiResponse에 담아 반환한다.

| 메서드·경로 | 권한 | 성공 응답의 data |
| --- | --- | --- |
| POST /rooms | 인증된 활성 사용자 | 201, id·roomCode·name·maxMembers·status·ownerId·appointmentAt |
| GET /rooms | 인증된 활성 사용자 | 200, 참여 방 배열. id·name·status·myRole·memberCount·maxMembers·appointmentAt·selectedStation. 빈 결과는 [] |
| GET /rooms/{roomId} | MEMBER/OWNER | 200, id·name·status·maxMembers·appointmentAt·ownerId·myRole·selectedStation·confirmedAt·members |
| PATCH /rooms/{roomId} | 현재 OWNER | 200, null |
| GET /rooms/{roomId}/members | MEMBER/OWNER | 200, userId·nickname·role·joinedAt·departureStation 배열 |
| GET /rooms/{roomId}/invite-code | 현재 OWNER | 200, roomCode |

ownerId는 현재 OWNER 멤버 기준이다. created_by는 최초 생성자 기록이다. selectedStation 유무로 OPEN/CONFIRMED를 계산한다. 후보는 상세에 포함하지 않는다. 상세의 members도 명세 7.6과 동일하게 joinedAt을 포함한다. 미설정 출발역·미확정 약속역·확정 시각은 null이며, nullable 필드도 응답 키는 생략하지 않는다.

역은 id·name·isSupported·lines, 노선은 lineId·lineName·color다. 초대 코드는 생성 응답과 OWNER 전용 조회에만 있다. OWNER가 호출해도 목록·상세·멤버 응답에는 roomCode가 없다.

## 보안 경계

#32의 정책을 유지한다. 정확히 일치하는 POST kakao·refresh·logout, Swagger 경로, ERROR dispatch는 기존대로 공개하며, 그 밖의 `/api/v1/**`는 인증이 필요하고 외부 경로는 denyAll이다. 방별 참여자·OWNER 권한은 RoomAccess가 검증한다. 미구현 API를 공개 API로 등록하지 않으며, 인증된 미등록 API는 기존대로 MVC의 404/405를 반환한다. 공개 인증 API도 본문의 카카오 토큰·Refresh Token 검증은 수행한다.

토큰 누락은 AUTH_REQUIRED(401), 만료는 TOKEN_EXPIRED(401), 잘못된 토큰·미존재/탈퇴 사용자는 TOKEN_INVALID(401)다. 방 없음은 ROOM_NOT_FOUND(404), 비참여자는 NOT_ROOM_MEMBER(403), OWNER가 아닌 멤버는 NOT_ROOM_OWNER(403)다.

## 생성과 초대 코드 충돌

정원은 4명 고정, 생성자는 created_by와 OWNER 멤버로 기록하고 최초 출발역은 null이다. `RoomCreationService`의 NOT_SUPPORTED 루프가 별도 빈 `RoomCreationAttempt.create`의 REQUIRES_NEW를 호출한다. 각 시도에서 활성 사용자 확인 → 방 saveAndFlush → OWNER saveAndFlush → 커밋을 하나의 트랜잭션으로 처리한다.

SecureRandom으로 A-Z/0-9 6자 코드를 만든다. 사전 존재 확인 대신 실제 SQLSTATE 23505와 uq_room_code 제약 이름이 모두 일치할 때만 새 코드로 재시도한다. 최대 총 5회 시도하며, 실패한 트랜잭션은 완전히 롤백한 뒤 다음 시도를 연다. OWNER 저장·커밋 실패도 방과 함께 롤백한다. 충돌 소진은 기존 INTERNAL_ERROR(500)다. 다른 무결성/연결 오류를 재시도하거나 일괄 400/409/404로 변환하지 않는다.

## 이름 규칙과 PATCH

RoomName의 공통 규칙을 요청 DTO와 MeetingRoom 생성자·rename이 함께 사용한다. 도메인은 잘못된 이름을 IllegalArgumentException으로 거절하고, DTO는 해당 입력 오류만 기존 VALIDATION_ERROR(400)로 변환한다. 예외를 전역적으로 400으로 바꾸지 않는다.

- 이름은 앞뒤 U+0020만 제거한 뒤 유니코드 코드포인트 1~30자다. NUL은 DB text에 저장할 수 없어 거절한다. 탭·줄바꿈·유니코드 공백을 임의로 정규화하지 않는다.
- PostgreSQL V5 CHECK의 char_length(btrim(name)) 1~30 및 varchar(30)과 맞추기 위해 정규화한 값을 저장한다. UTF-16 length 대신 codePointCount를 사용한다.
- 명세 4.5는 닉네임 2~20자를 명시하지만 제거할 공백의 종류는 정하지 않는다. 기존 닉네임 수정은 Java strip 후 코드포인트 2~20자이고, 닉네임 DB CHECK는 btrim 후 1~20자다. 기본 btrim은 U+0020만 제거하므로 닉네임 서비스는 DB보다 좁은 길이와 넓은 공백 제거를 적용하는 기존 계약이다. 이 차이를 임의로 없애지 않으며 규칙 통합은 별도 합의가 필요하다. 닉네임 서비스와 기존 마이그레이션은 변경하지 않는다.

PATCH 요청은 JSON 객체가 필수이며 각 필드는 선택이다. DTO는 Java null로 생략을, NullNode로 명시적 null을 구분한다. 숫자·불리언을 이름 문자열로 강제 변환하지 않는다.

| 입력 | name | appointmentAt |
| --- | --- | --- |
| 생략 | 유지 | 유지 |
| 명시적 null | VALIDATION_ERROR(400) | 시각 해제 |
| 값 | 공통 이름 규칙 적용 | 시간대가 포함된 ISO 8601 시각 |

빈 객체 {}는 허용한다. 알 수 없는 요청 필드는 기존 Jackson 정책대로 무시하지만 변경에 사용하지 않는다. 성공은 `200 {"success":true,"data":null}`이다. 명세 7.5는 null 처리와 성공 본문을 명시하지 않는다. 시각 해제는 ERD/V5의 nullable appointment_at·기존 schedule(null)·명세 8.2의 null 비교에 근거한 해석이며, 성공 본문은 기존 무본문 변경 API의 공통 응답 관례를 따른다. 두 항목은 명세 보완 시 명시할 사항이다.

쓰기 트랜잭션은 READ COMMITTED다. 첫 도메인 호출인 RoomAccess.lockOwner에서 방 락과 현재 권한을 확인한 뒤 요청을 검증하고 rename/schedule만 실행한다. 락 전에 dirty 변경·persist/remove를 하지 않는다. 공통 락의 MANDATORY·전체 세션 dirty 거절·3초 timeout 계약을 유지한다. 락 대기 초과만 ROOM_BUSY(409)로 반환한다.

시각 변경 후에도 기존 추천 실행/결과·후보·투표·확정역·confirmedAt을 유지한다. 자동 재추천하지 않고 isStale 컬럼이나 stale 판정도 추가하지 않는다.

## 읽기 일관성과 조회 비용

목록·상세·멤버·초대 코드 조회는 readOnly + REPEATABLE_READ + REQUIRES_NEW로 실행한다. 호출자 트랜잭션의 낮은 격리 수준이나 미리 로딩한 엔티티를 이어받지 않고 독립된 스냅샷을 사용한다. RoomAccess.requireMember/requireOwner와 JPA/JDBC 배치 조회는 이 동일 트랜잭션/연결에 참여한다. 변경용 lock*는 이 격리 수준을 사용하지 않는다.

READ COMMITTED에서는 방 목록 조회 직후 참여자가 삭제되어 role/group 매핑이 실패하거나, 상세 권한 확인 뒤 OWNER가 삭제/승계되어 소유자 매핑이 실패할 수 있다. 초대 코드 조회도 방과 권한이 서로 다른 시점을 볼 수 있다. REPEATABLE READ는 최초 SQL의 스냅샷으로 권한·방·멤버·역을 함께 읽는다. 조회 도중 탈퇴·방 삭제·승계가 커밋되어도 시작 시점의 일관된 응답을 반환하며 다음 요청은 새 상태를 본다. 이는 조회 종료 순간까지 권한이 실시간 유지된다는 보장은 아니다.

이미 커밋된 잘못된 데이터까지 정상으로 간주하지 않는다. 예를 들어 OWNER가 없는 방의 상세는 기존 INTERNAL_ERROR로 남기며 무조건 404로 숨기지 않는다.

OSIV=false를 유지하고 서비스 트랜잭션 안에서 DTO 변환까지 완료한다. 목록은 방 JPA 조회, 모든 대상 방의 멤버+닉네임 배치, 필요한 역+노선 배치로 최대 3회이며 역이 없으면 역 쿼리를 생략한다. 페이지 래퍼는 추가하지 않는다. 목록 기본 순서는 created_at DESC, id DESC다. 정식 역 조회 서비스와 통합할 때 RoomReadRepository의 작은 읽기 어댑터를 같은 계약으로 대체할 수 있으며, 별도 그래프 도메인을 구현하지 않는다.

## OpenAPI 계약

OpenApiConfig의 HTTP bearer/JWT 스키마를 사용하고 방/내 정보 API에 SecurityRequirement를 적용한다. 인증 POST 3개는 security:[]다. @CurrentUser는 숨기되 실제 JWT 주입은 유지하며 roomId는 필수 UUID 경로 파라미터다.

방 응답 데이터 스키마는 RoomCreatedData·RoomSummaryData·RoomDetailData·RoomMemberData·RoomInviteCodeData·RoomStationData·RoomLineData다. 응답 envelope는 RoomCreateResponse·RoomListResponse·RoomDetailResponse·RoomMembersResponse·RoomInviteCodeResponse이며, 공통 null 성공은 ApiEmptySuccessResponse다. auth/user 문서 모델에도 도메인 이름을 사용한다. 문자열로 모델을 찾는 nullable 역 customizer는 RoomResponses의 스키마 이름 상수를 사용한다.

모든 방 응답 필드는 required이며, 필수와 null 허용은 구분한다. appointmentAt·confirmedAt은 string/date-time 또는 null, 역은 `anyOf: [RoomStationData 참조, {type:null}]`이다. 생성/수정의 문서 전용 요청 모델은 JsonNode 내부 구조를 숨기면서 생성 name 필수·PATCH 모두 선택을 표현한다. sealed 공통 응답의 generic data 손실을 막기 위한 문서 전용 envelope는 실제 DTO를 data로 참조한다. 반복 오류 설명은 Controller 공통 400/401/500과 각 operation의 권한/미존재/락 오류로 구분한다.

## 후속 연동

- #38: JWT 필터와 생성 시 활성 사용자 확인은 있지만, 현재 withdraw는 사용자 잠금/방 정리 없이 soft delete·토큰 폐기만 한다. 활성 확인 직후 탈퇴가 커밋되는 생성 경합은 아직 직렬화하지 못한다. 생성·참여·탈퇴에 동일한 사용자 잠금과 상태 재확인 규칙을 적용하고, 사용자 잠금 → 방 ID DB 오름차순 잠금·나가기 정리 → soft delete·토큰 폐기를 함께 연결해야 한다.
- #43: RoomService.update의 허용 필드 변경 뒤가 ROOM_UPDATED 연결 지점이다. 커밋 후 발행 정책을 함께 구현한다. 현재 이벤트·STOMP·SYSTEM 메시지는 구현하지 않는다.
- 참여·출발역 변경·나가기·실제 OWNER 승계·마지막 멤버 방 삭제·탈퇴 전체 정리·약속역 지정/해제·후보/투표 API·추천 계산/stale 판정은 별도 범위다. 테스트의 승계는 SQL fixture다.

## 검증 범위

자동 검증은 `.\gradlew.bat test postgresTest --no-daemon`과 `git diff --check`로 수행한다. PostgreSQL은 운영 DB가 아닌 postgres:16 Testcontainers에 정식 Flyway 전체와 JPA validate·OSIV=false를 적용한다. 생성 원자성·실제 UNIQUE 재시도·중간 실패 롤백·권한·입력 경계·PATCH 생략/null·초대 코드 비노출·역/노선·락/보존 계약을 검증한다.

동시성 검증은 읽기 최초 SQL 후 별도 스레드/연결의 READ COMMITTED 쓰기 트랜잭션이 탈퇴·방 삭제·승계를 커밋하도록 한다. 읽기 SQL의 REPEATABLE READ와 서로 다른 backend PID, 최초 응답과 다음 요청의 상태를 검증한다. 잘못 커밋된 OWNER 누락을 404로 숨기지 않는 것도 확인한다. OpenAPI 검증은 모든 로컬 참조 해소와 실제 DTO 직렬화의 필드·타입·required·null 및 JWT 사용자 주입/덮어쓰기 방지를 포함한다.

사용자가 Swagger의 OWNER 계정으로 직접 확인한 범위:

| 수동 확인 항목 | 응답 |
| --- | --- |
| 방 생성 | 201 |
| 목록·상세·멤버·초대 코드 조회 4개 | 각각 200 |
| 이름만 수정·약속 시각 null 해제·빈 PATCH | 각각 200 |
| name:null·공백 이름 PATCH | 각각 400 VALIDATION_ERROR |
| 마지막 상세 조회 | 최종 저장 상태 확인 |

이름만 수정한 직후 약속 시각이 유지되는지와 다른 계정의 권한은 수동 확인 범위에 포함하지 않는다. 해당 항목은 자동 테스트 범위와 구분한다.
