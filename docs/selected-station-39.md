# 최종 약속역 계약

기준은 API v1.12의 1·2·7.3·7.4·7.10~7.12·13장과 ERD v1.13이다. 역 식별자는 stations.id와 같은 Long(bigint)이며 방 식별자는 UUID다.

보충 계약: API v1.12 원문에는 두 API의 구체적인 성공 응답 본문과 이미 미확정인 방의 DELETE 결과가 명시되어 있지 않다. 두 API 모두 HTTP 200과 기존 공통 응답 `{"success":true,"data":null}`을 사용한다. 이미 미확정인 방도 방 잠금과 현재 OWNER 권한 확인 후 상태를 유지하고 같은 응답으로 성공한다. 없는 방·비멤버·일반 멤버의 오류는 그대로 적용한다. 이 규칙은 v1.12 원문 규칙이 아닌 보충 계약이다.

`DELETE /api/v1/rooms/{roomId}/selected-station`은 기존 clearSelectedStation으로 selected_station_id·confirmed_at을 함께 null로 만들고 OPEN으로 계산한다. 서비스의 clear 반환값은 실제 변경이면 true, 이미 두 값이 null이면 false다. HTTP 응답은 이 반환값과 무관하게 data:null이다. 무변경 분기는 엔티티 변경 없이 끝나며 #43의 이벤트·SYSTEM 연결 지점에 도달하지 않는다. 변경 분기에만 저장·발행을 연결하여 반복 DELETE에 중복 메시지를 만들지 않는다.

`PUT /api/v1/rooms/{roomId}/selected-station`은 Bearer 인증된 현재 OWNER가 `{"stationId":112}`로 최종 약속역을 지정·변경한다. stationId는 필수 JSON 정수이며 문자열·소수·boolean·배열·객체·null·생략·Long 범위 초과를 거절한다. 정수로 표현 가능한 없는 ID는 STATION_NOT_FOUND로 처리한다. 기존 StationLookup.requireSupported를 사용하여 존재·지원 여부를 검증한다. 후보에 없거나 투표가 없는 지원 역도 지정할 수 있다.

지정·변경은 같은 역 재지정을 포함하여 매번 현재 시각으로 confirmed_at을 갱신하고 selected_station_id를 함께 설정한다. 기존 MeetingRoom.confirmStation과 selected_station_id 유무로 계산하는 getStatus를 사용하여 CONFIRMED를 반환한다. 별도 status 컬럼을 저장하지 않는다. 목록은 selectedStation·status를, 상세는 selectedStation·confirmedAt·status를 반영한다. 목록에는 원래 명세대로 confirmedAt 필드가 없다.

READ_COMMITTED 쓰기 트랜잭션에서 RoomAccess.lockOwner로 방을 잠그고 현재 멤버십·OWNER를 확인한 후 입력과 역을 검증한다. 잠금 전 엔티티를 변경하지 않으며 기존 isDirty 거절 계약을 유지한다. 방 잠금은 동일 방의 지정·해제·퇴장·방장 승계·마지막 멤버 퇴장에 따른 삭제를 직렬화한다. 대기 후 최신 방·권한을 확인하므로 이미 퇴장한 사용자는 NOT_ROOM_MEMBER, 삭제된 방은 ROOM_NOT_FOUND가 된다. 새 OWNER는 기존 생성자와 무관하게 변경할 수 있다.

오류는 ROOM_NOT_FOUND(404), NOT_ROOM_MEMBER(403), NOT_ROOM_OWNER(403), STATION_NOT_FOUND(404), STATION_UNSUPPORTED(422), ROOM_BUSY(409), VALIDATION_ERROR(400)다. 기존 인증 오류와 공통 실패 envelope를 유지한다. 파싱 가능한 요청의 stationId 값 검증은 방·권한 검증 뒤에 수행한다. 깨진 JSON·본문 누락·잘못된 UUID처럼 MVC가 요청을 구성할 수 없는 오류는 서비스 진입 전에 400이 된다.

RoomAccess의 실제 PostgreSQL 3초 lock_timeout과 설정 복원 정책을 재사용한다. 시간 초과나 다른 실패는 전체 쓰기 트랜잭션을 롤백한다. 후보·투표·추천 실행과 결과·채팅·멤버 출발역·약속 시각은 수정하거나 초기화하지 않는다.

#43은 SELECTED_STATION_CHANGED와 SYSTEM 메시지를 연결한다. 지정·해제 직후 같은 트랜잭션에서 불변 station·confirmedAt payload를 만들고, SYSTEM 저장은 변경과 함께 커밋 또는 롤백한다. WS는 최외곽 트랜잭션이 성공적으로 커밋된 뒤에만 발행한다. 해제 payload의 station·confirmedAt은 모두 null이다. 서비스 메서드 반환은 커밋 완료를 뜻하지 않으며 실패한 작업은 이벤트를 발행하지 않는다.
