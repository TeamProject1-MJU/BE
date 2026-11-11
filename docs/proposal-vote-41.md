# 약속역 후보 참고용 투표 계약

기준은 API 명세 7.17이에요. V7의 기존 station_proposal_votes를 사용해요.

`PUT /api/v1/rooms/{roomId}/station-proposals/{proposalId}/vote`는 현재 방 멤버에게 허용하고 요청 본문은 없어요. 성공은 HTTP 200이며 `{"success":true,"data":{"proposalId":"UUID","voteCount":1,"votedByMe":true}}`로 응답해요. voteCount는 해당 후보의 현재 실제 표 수예요.

## 검사 순서

1. READ_COMMITTED 쓰기 트랜잭션에서 RoomAccess.lockMember로 방을 잠그고 현재 멤버를 확인해요. 잠그기 전 엔티티 변경은 없어요.
2. roomId와 proposalId를 함께 조회해 후보 소속을 확인해요. 없거나 다른 방 소속이면 동일한 PROPOSAL_NOT_FOUND로 존재 여부를 숨겨요.
3. 방 전체에서 호출자의 기존 표를 조회해요.
4. 표가 없으면 UUID v7 ID로 JDBC INSERT해요. 같은 후보면 저장 없이 현재 상태를 반환하고, 다른 후보면 ALREADY_VOTED로 거절해 기존 표를 보존해요.
5. 해당 후보의 실제 표 수와 호출자의 투표 여부를 집계해 응답해요.

## 멱등과 동시성

방 락으로 같은 방의 요청을 직렬화해요. 같은 사람이 같은 후보에 동시에 요청하면 한 표만 저장하고 모두 200이에요. 서로 다른 후보면 최초 표만 저장하고 이후 요청은 409예요. 서로 다른 사람은 각각 한 표를 행사해요.

SQLState=23505와 정확히 인용된 제약 이름 uq_vote_room_user가 모두 일치하는 UNIQUE 위반만 방어적으로 식별해요. 실패한 트랜잭션을 완전히 롤백한 뒤 새 트랜잭션에서 방 락·멤버·후보 소속·기존 표를 다시 확인해요. 같은 후보는 200, 다른 후보는 409예요. 복구는 한 번으로 제한하고, 다른 제약 오류나 재시도의 DB 오류는 숨기지 않아요. 실패한 PostgreSQL 트랜잭션 안에서는 재조회하지 않아요.

## 오류

모든 오류는 기존 `{"success":false,"error":{"code":"...","message":"...","details":{}}}` envelope를 사용해요.

| HTTP | 코드 | 조건 |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | 잘못된 경로 UUID |
| 401 | AUTH_REQUIRED / TOKEN_INVALID / TOKEN_EXPIRED | 인증 실패 |
| 403 | NOT_ROOM_MEMBER | 현재 방 멤버가 아님 |
| 404 | ROOM_NOT_FOUND | 방 없음 |
| 404 | PROPOSAL_NOT_FOUND | 후보 없음 또는 다른 방 소속 |
| 409 | ALREADY_VOTED | 방 내 다른 후보에 이미 투표함 |
| 409 | ROOM_BUSY | 기존 3초 방 잠금 대기 초과 |
| 500 | INTERNAL_ERROR | 그 밖의 내부 오류 |

## cascade 규칙

V7의 fk_vote_proposal은 후보 삭제 시, fk_vote_member는 멤버 행 삭제 시, fk_vote_room은 방 삭제 시 표를 cascade 삭제해요. 후보를 삭제하면 해당 표의 사용자는 다른 후보에 다시 투표할 수 있어요. RoomLeaveProcessor는 meeting_members 행을 실제 삭제하고 flush해요. 회원 탈퇴도 방 나가기 처리를 거쳐 표가 삭제돼요. 제안자 나가기와 달리 투표는 남지 않아요.

## 보충 계약

OWNER와 MEMBER 모두 투표할 수 있고 본인 제안 후보에도 투표할 수 있어요. 약속역 확정 전후 모두 허용해요. selected_station_id와 confirmed_at은 수정하지 않아요. 투표 변경·취소 API는 제공하지 않아요.

각 투표 시도는 REQUIRES_NEW 트랜잭션으로 실행해요. 호출자의 외부 트랜잭션과 독립적으로 커밋하므로 외부 롤백이 이미 성공한 표를 취소하지 않아요. 현재 HTTP 진입점에는 외부 쓰기 트랜잭션이 없어요. 방 락을 가진 외부 트랜잭션에서는 호출하지 않아야 해요.

GET station-proposals는 같은 투표 테이블의 count와 bool_or를 사용해요. PUT 결과와 GET의 voteCount·votedByMe는 같은 저장 상태를 반영해요. 응답 이후 다른 멤버 투표나 cascade 삭제가 일어나면 값이 달라질 수 있어요.

## #43 연결 지점

새 표 INSERT 성공 분기에만 `// #43:` 연결 지점을 둬요. 멱등 재요청에는 이벤트가 없어요. 후속 STATION_PROPOSAL_VOTE_CHANGED payload는 불변 값으로 만들고 WS는 커밋 성공 후 발행해야 해요. 현재 WS 이벤트와 SYSTEM 메시지는 구현하지 않아요.
