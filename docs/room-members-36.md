# 초대 코드 참여·내 출발역 API (#36)

## API 계약과 기준

API 명세 v1.12(2026-10-29) 7.2·7.7, ERD v1.13 및 기존 V5 스키마를 기준으로 한다. 이슈 #36의 v1.7 표기보다 최신 명세를 우선한다. 사용자 식별은 JWT와 `@CurrentUser`를 사용한다. query/body의 userId는 대상 사용자를 선택하지 못한다.

| API | 성공 | 오류 |
| --- | --- | --- |
| POST /api/v1/rooms/join | 200, `{"success":true,"data":{"roomId":"방 UUID"}}` | ROOM_CODE_INVALID 404, ALREADY_JOINED·ROOM_FULL·ROOM_BUSY 409 |
| PATCH /api/v1/rooms/{roomId}/members/me | 200, `{"success":true,"data":null}` | ROOM_NOT_FOUND·STATION_NOT_FOUND 404, NOT_ROOM_MEMBER 403, STATION_UNSUPPORTED 422, ROOM_BUSY 409 |

공통 입력 오류는 VALIDATION_ERROR 400이며 기존 인증 오류와 SecurityConfig 정책을 유지한다. roomId는 필수 UUID 경로 파라미터다.

## 명시 사항과 구현 판단

- 명세 7.2는 roomCode 요청, roomId 응답, MEMBER 참여, 상태 무관 참여, 방 락과 정원 검사 및 참여 오류를 명시한다. 정원은 방장 포함 4명이며 새 멤버의 출발역은 null이다.
- ERD/V5는 roomCode를 대문자 영문·숫자 6자로 제한한다. 요청의 누락·null·비문자열·형식 오류 응답은 7.2에 세분화되어 있지 않다. 기존 JSON 타입 강제 변환 방지 관례와 DB 형식을 적용해 VALIDATION_ERROR로 거절한다. 소문자 대문자화나 공백 제거는 명세에 없어 하지 않는다. 형식에 맞지만 존재하지 않는 코드는 ROOM_CODE_INVALID다.
- 명세 7.7은 본인 출발역 설정·변경, 지원 역 검사와 역·권한 오류를 명시한다. 성공 상태·본문은 명시하지 않아 기존 변경 API 관례인 200/data:null을 사용한다.
- departureStationId는 JSON 정수이자 bigint/Long 범위여야 한다. 누락·null·문자열·실수·불리언·배열·객체·범위 초과는 400이다. 정수지만 존재하지 않는 ID는 STATION_NOT_FOUND다. nullable 컬럼은 참여 직후 미설정 상태를 위한 것이며 해제 요청은 허용하지 않는다.
- 알 수 없는 필드는 기존 Jackson 관례대로 무시하며 권한·대상 선택에 사용하지 않는다.

## 트랜잭션·검증 순서

참여 쓰기는 READ COMMITTED에서 RoomAccess.lockByCode → 현재 중복 참여 검사 → 인원 검사 → MEMBER saveAndFlush 순서다. 방 조회부터 저장까지 같은 트랜잭션이며 OPEN·CONFIRMED를 모두 허용한다. 잠금 대기 중 삭제되면 ROOM_CODE_INVALID다. 기존 FOR NO KEY UPDATE·약 3초 lock_timeout·Session.isDirty 검사를 유지한다.

실제 SQLSTATE 23505이면서 uq_member_room_user 제약 위반인 경우만 ALREADY_JOINED로 변환한다. 외부 RoomJoinService는 NOT_SUPPORTED이며 내부 쓰기 트랜잭션이 종료·롤백된 뒤 변환한다. 실패한 트랜잭션에서 처리를 계속하지 않고 다른 무결성 오류를 숨기지 않는다.

출발역 쓰기는 READ COMMITTED에서 RoomAccess.lockMember → 요청 ID 검증 → 기존 RoomReadRepository JDBC 역 조회 → 존재·is_supported 검사 → 본인 MeetingMember 변경 순서다. 락 안에서 OWNER/MEMBER 자격을 확인하고 실패 시 기존 값을 보존한다. 기존 읽기 경로의 REPEATABLE READ/REQUIRES_NEW 설정을 복사하지 않는다. persist/update/remove는 락 뒤에만 수행한다.

## 후속 연결과 제약

참여·출발역 변경은 방 상태·약속 시각·확정역·확정 시각과 추천 실행·결과·후보·투표를 보존한다. 자동 재추천이나 stale 저장을 하지 않으며 stale 판정은 추천 조회 담당이다.

#37 나가기·방장 승계는 같은 방 락 규약을 따라야 한다. #38 회원 탈퇴와 사용자 단위 직렬화는 후속 범위이며 현재 방 락만으로 다른 방에 걸친 사용자 생명주기를 직렬화하지 않는다. #43 MEMBER_JOINED·DEPARTURE_UPDATED 연결 위치를 성공한 서비스 변경 뒤에 주석으로 남겼다. 실제 발행과 SYSTEM 메시지는 구현하지 않으며 후속 구현은 커밋 성공에 연계해 롤백 시 외부 발행되지 않게 해야 한다.

Swagger는 기존 bearerAuth/JWT를 사용하며 userId를 숨긴다. 요청/응답은 RoomJoinRequest·RoomDepartureUpdateRequest·RoomJoinedData·RoomJoinResponse 고유 스키마를 사용한다.
