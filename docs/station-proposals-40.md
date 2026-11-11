# 약속역 후보 계약

기준은 이슈 #40과 API 명세 7.14~7.16이에요. 역 ID는 기존 stations.id의 Long(bigint), 방·후보·사용자·추천 결과 ID는 UUID예요. V7의 기존 후보·투표 테이블을 사용하며 마이그레이션은 추가하지 않아요.

`POST /api/v1/rooms/{roomId}/station-proposals`는 방 멤버가 `{"stationId":112,"resultId":"01900000-0000-7000-8000-000000000001"}`로 제안해요. resultId는 선택이에요. 성공은 HTTP 201이고 data는 `{id, station:{id,name}, proposedBy:{userId,nickname}, resultId, createdAt}`예요. 직접 제안의 resultId는 null로 저장·응답해요.

`GET /api/v1/rooms/{roomId}/station-proposals`는 멤버에게 HTTP 200과 `{selectedStation,maxProposals,items}`를 반환해요. selectedStation은 기존 RoomResponses.Station의 `{id,name,isSupported,lines}` 모양이고 미확정이면 null이에요. items는 `{id,station:{id,name},proposedBy:{userId,nickname},resultId,isSelected,voteCount,votedByMe,createdAt}` 배열이에요. 없으면 빈 배열이에요. 정렬은 created_at DESC, id DESC예요. 후보 역과 확정 역 ID가 같으면 isSelected=true예요. station_proposal_votes를 LEFT JOIN하여 후보별 실제 득표 수와 요청 사용자의 투표 여부를 집계해요. 후보·역 이름·닉네임·득표는 한 쿼리로 읽고, 확정역의 호선 정보는 기존 방 조회 어댑터를 재사용해요. 후보 수와 무관한 고정 쿼리 수로 N+1을 피했어요.

기존 RoomService와 같이 GET은 REQUIRES_NEW·읽기 전용·REPEATABLE_READ예요. 호출자의 기존 트랜잭션과 독립된 동일 스냅샷에서 방 존재·멤버 권한·확정역·후보·득표를 읽어요.

`DELETE /api/v1/rooms/{roomId}/station-proposals/{proposalId}`는 제안자 본인 또는 현재 OWNER에게 허용해요. 성공은 HTTP 200과 `{"success":true,"data":null}`이에요. 투표는 V7의 FK cascade로 삭제해요. selected_station_id·confirmed_at은 변경하지 않아요.

## 검사 순서

POST는 READ_COMMITTED 쓰기 트랜잭션에서 RoomAccess.lockMember로 방을 잠그고 현재 멤버를 확인한 뒤 아래 순서로 검사해요. 잠금 전에 엔티티를 바꾸지 않아요.

1. stationId가 Long 범위 JSON 정수인지 검사해요. 생략·null·문자열·소수·boolean·배열·객체·범위 초과를 거절해요. 이어 resultId가 있다면 UUID 문자열인지 검사해요.
2. StationLookup.requireSupported로 역 존재와 지원 여부를 확인해요.
3. resultId가 있다면 recommendation_results와 recommendation_runs를 JOIN해 실행의 room_id가 이 방인지 확인해요. 없거나 다른 방이면 동일한 RESULT_NOT_FOUND예요.
4. 같은 방·같은 역 중복을 확인해요.
5. 현재 후보 수가 설정된 상한 이상인지 확인해요.
6. UUID v7을 기존 BaseUuidEntity와 같은 UuidCreator.getTimeOrderedEpoch로 생성하고 저장해요. createdAt은 DB의 created_at을 응답해요.

DELETE도 같은 쓰기 트랜잭션과 lockMember를 사용해요. 이후 방 ID와 후보 ID를 함께 조회하고, 후보가 있으면 제안자 본인 또는 findCurrentRole로 읽은 현재 OWNER인지 확인한 뒤 삭제해요. 다른 방의 후보도 PROPOSAL_NOT_FOUND로 처리해 존재 여부를 숨겨요.

JDBC INSERT는 지연 flush 없이 즉시 실행돼요. SQLState가 23505이며 오류에 정확히 인용된 제약 이름이 uq_proposal_room_station일 때만 ALREADY_PROPOSED로 변환해요. 나머지 무결성 오류는 원래 예외를 유지해요. 변환된 런타임 예외는 서비스의 트랜잭션 경계를 넘어 전체 작업을 롤백해요. 방 락이 정상 API 쓰기를 직렬화하며, UNIQUE 변환은 별도 쓰기에 대한 방어예요.

## 오류

모든 오류는 기존 `{"success":false,"error":{"code":"...","message":"...","details":{}}}` envelope를 사용해요.

| HTTP | 코드 | 조건 |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | 입력 형식 오류, 잘못된 경로 UUID·JSON·본문 |
| 401 | AUTH_REQUIRED / TOKEN_INVALID / TOKEN_EXPIRED | 기존 인증 실패 |
| 403 | NOT_ROOM_MEMBER | 현재 방 멤버가 아님 |
| 403 | NOT_PROPOSAL_OWNER | 제안자 본인도 현재 OWNER도 아님 |
| 404 | ROOM_NOT_FOUND | 방 없음 |
| 404 | STATION_NOT_FOUND | 역 없음 |
| 404 | RESULT_NOT_FOUND | 추천 결과가 없거나 다른 방 소속 |
| 404 | PROPOSAL_NOT_FOUND | 후보가 없거나 다른 방 소속 |
| 409 | ALREADY_PROPOSED | 같은 방·역 중복 |
| 409 | ROOM_BUSY | 기존 RoomAccess의 3초 잠금 대기 초과 |
| 422 | STATION_UNSUPPORTED | 미지원 역 |
| 422 | PROPOSAL_LIMIT_EXCEEDED | 후보 수가 상한 이상 |
| 500 | INTERNAL_ERROR | 기타 내부 오류 |

파싱 가능한 본문의 필드 검사는 방 잠금·권한 확인 후 진행해요. 깨진 JSON·없는 본문·잘못된 경로 UUID는 MVC 단계에서 먼저 400을 반환해요.

## 설정값과 보충 계약

`linkro.room.max-station-proposals`의 기본값은 9예요. RoomProperties의 @ConfigurationProperties와 @Validated·@Min(1)로 바인딩하며, 0 이하 설정은 시작 시 거절해요. POST와 GET maxProposals가 같은 빈의 값을 사용해요.

명세에 없는 중복·상한 검사의 우선순위는 중복 → 상한으로 정했어요. resultId 생략 또는 명시적 null은 직접 제안으로 취급해요. UUID는 표준 하이픈 포함 문자열을 받고 대소문자는 허용해요. 추천 결과는 방 소속만 검증하고, 후보 역이 결과의 최적역과 같아야 한다는 조건은 추가하지 않아요. 주변 대안역에도 동일한 resultId를 사용할 수 있어요.

제안자는 meeting_members가 아닌 users를 참조하므로 퇴장 후에도 후보를 유지해요. UserService.withdraw는 멤버를 정리한 뒤 User.softDelete로 deleted_at만 설정하며 nickname은 지우지 않아요. 기존 방 멤버 조회와 같이 users.nickname을 그대로 읽으므로 탈퇴한 제안자의 저장된 닉네임도 그대로 응답해요. 마지막 멤버 퇴장으로 방 자체가 삭제되면 후보·투표도 cascade 삭제돼요.

## #43 연결 지점

생성 INSERT 성공 직후와 DELETE 실행 직후 서비스에 `// #43:` 주석을 남겼어요. 이후 STATION_PROPOSAL_CREATED/DELETED와 SYSTEM 메시지는 이 지점의 불변 payload를 사용하고, SYSTEM은 같은 트랜잭션에 저장하며 WS는 최외곽 커밋 성공 후에만 발행해야 해요. 현재 이벤트·SYSTEM 메시지·투표 API는 구현하지 않아요.

## 검증

StationProposalServiceTest는 검사 순서·입력 형식·설정 상한·삭제 권한·정확한 UNIQUE 오류 변환을 확인해요. StationProposalHttpTest는 실제 MVC·JWT·서비스를 통해 성공/오류 envelope, 요청 검증, 설정값과 Swagger를 확인해요. RoomPropertiesTest는 기본값·재설정·0 이하 거절을 확인해요.

StationProposalPostgresTest는 PostgreSQL 16에서 서로 다른 지원 역 12개의 동시 제안(9건 성공·3건 상한 초과), 같은 역의 동시 제안(1건 성공·11건 중복), 다른 방의 추천 결과·후보 차단, 실제 표 집계·정렬·확정역 표시, 삭제 권한·cascade·확정역 유지, 퇴장·탈퇴 후 보존과 닉네임을 검사해요. 실제 UNIQUE INSERT 실패의 변환과 롤백, 조회 도중 다른 트랜잭션이 변경해도 유지되는 독립된 조회 스냅샷, 별도 방 잠금 시간 초과 검사도 포함해요.

변이 검사에서는 서비스의 상한 비교를 잠시 `>=`에서 `>`로 바꾸었어요. resultThenDuplicateThenConfiguredLimit 테스트가 상한과 같은 개수에서 예외가 발생하지 않는 결함을 잡아 실패하는 것을 확인한 뒤 `>=`로 복원했어요.

최종 검증은 Java 17에서 `./gradlew.bat test postgresTest --no-daemon`으로 실행했어요. 단위·HTTP 762개와 Docker PostgreSQL 16 테스트 360개가 모두 통과했고, 실패·오류·건너뛰기는 없어요. 새 PostgreSQL 검사 10개도 포함돼요.
