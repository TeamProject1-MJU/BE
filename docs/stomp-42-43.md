# STOMP 연결·구독 권한과 커밋 후 방 이벤트 (#42, #43)

API 명세 v1.12 11장 기준이에요. 메시지 전송·조회는 REST, STOMP는 이벤트를 받는 데만 써요. 마이그레이션은 추가하지 않아요. 코드는 `com.mju.linkro.realtime`에 있어요.

## 연결 (#42)

| 항목 | 값 |
| --- | --- |
| 주소 | `ws://{host}/ws` (운영은 `wss://`). 일반 WebSocket이고 SockJS는 안 켰어요(명세에서 선택 사항). |
| CONNECT 헤더 | `Authorization: Bearer {accessToken}` |
| 구독 | `/topic/rooms/{roomId}` (소문자 UUID). 방 멤버만 가능해요. |
| heartbeat | 서버 10초·10초 (`CONNECTED`의 `heart-beat:10000,10000`) |
| 허용 출처 | `websocket.allowed-origins` (`WS_ALLOWED_ORIGINS`). 비우면 REST의 `CORS_ALLOWED_ORIGINS`를 그대로 써요. `*`는 시작 때 거절해요. Origin 헤더가 없는 앱 클라이언트는 막지 않아요. |

- 토큰은 CONNECT 때 한 번만 검사해요(JWT 서명·만료, 사용자 존재, 탈퇴 아님. REST 필터와 같은 `JwtProvider`와 같은 조건). 연결이 유지되는 동안 토큰이 만료돼도 끊지 않아요. 재연결할 때는 새 토큰으로 CONNECT해요.
- 검사한 사용자는 STOMP 세션의 Principal(이름 = userId)이 돼요. 클라이언트가 보내는 `login`, `user-name` 같은 헤더는 신원으로 쓰지 않아요.
- SUBSCRIBE는 그 순간의 DB 멤버십으로 판단해요(`RoomAccess.requireMember`).
- 클라이언트의 SEND는 목적지와 상관없이 모두 거절해요. 서버에 `@MessageMapping`이 없어요.
- 핸드셰이크(`/ws`)는 Security에서 열어 두고, 인증은 STOMP CONNECT에서 해요. 나머지 `/api/v1/**` 규칙은 그대로예요.

### 거절 (ERROR 프레임)

CONNECT·SUBSCRIBE·SEND가 거절되면 서버가 ERROR 프레임을 보내고 **연결을 닫아요**. 그래서 구독 하나가 거절돼도 다시 CONNECT해야 해요. `message` 헤더가 REST와 같은 에러 코드이고, 본문은 한국어 문구(`text/plain`)예요.

| 상황 | `message` 헤더 |
| --- | --- |
| Authorization 헤더 없음·형식 틀림 | `AUTH_REQUIRED` |
| 만료된 토큰 | `TOKEN_EXPIRED` |
| 위조·깨진 토큰, 없는 사용자, 탈퇴한 사용자 | `TOKEN_INVALID` |
| CONNECT 없이 SUBSCRIBE | `AUTH_REQUIRED` |
| 방 멤버가 아님 | `NOT_ROOM_MEMBER` |
| 없는 방 | `ROOM_NOT_FOUND` |
| `/topic/rooms/{roomId}`가 아닌 목적지, UUID 형식이 틀림 | `VALIDATION_ERROR` |
| 클라이언트 SEND | `ACCESS_DENIED` |
| 그 밖의 서버 오류 | `INTERNAL_ERROR` (자세한 내용은 로그에만) |

### 세션·구독 관리

`StompSessions`가 STOMP 세션 id → 사용자, 구독 id → 방을 기억해요. 구독은 멤버십 검사 전에 기록하고 검사에 실패하면 지워요. 이벤트는 **세션·구독 id·방(destination)이 모두 기록과 같을 때만** 나가요(`StompDeliveryFilter`). 연결이 끊기면 그 세션 기록이 모두 지워져요.
- **구독 id는 연결마다 하나씩만 써요.** 이미 쓰는 구독 id로 다시 SUBSCRIBE하면 `VALIDATION_ERROR` ERROR 프레임을 보내고 연결을 닫아요(STOMP는 id가 연결 안에서 유일해야 해요). UNSUBSCRIBE나 방 나가기 뒤에는 같은 id를 다시 써도 돼요.

- **방 나가기 커밋 후**: 그 사용자의 그 방 구독 기록을 모든 연결에서 지우고, **Spring 브로커의 구독 목록에서도 같은 구독을 바로 빼요**(`RoomEvents.dropSubscriptions`). 브로커에 남겨 두면, 클라이언트가 같은 구독 id(`sub-0`)로 다른 방을 구독할 때 브로커가 새 구독을 무시하고 나간 방 이벤트가 계속 오기 때문이에요. 연결은 열려 있고 다른 방은 그대로예요. 나간 사람은 자기 퇴장 이벤트(`MEMBER_LEFT`)와 퇴장 SYSTEM 메시지도 받지 않아요.
- **회원 탈퇴 커밋 후**: 그 사용자의 모든 연결을 닫아요(close status 1008, 사유 `ACCOUNT_WITHDRAWN`). 방마다 나가기와 같은 이벤트가 먼저 나가요.
- 구독 기록을 멤버십 검사보다 먼저 하므로, 검사 중에 나가기가 커밋돼도 구독이 남지 않아요.
- **CONNECT와 탈퇴가 겹칠 때**: 연결을 사용자에게 먼저 등록하고 그다음에 "탈퇴하지 않은 사용자인지"를 확인해요. 탈퇴가 확인 전에 커밋되면 확인에서 `TOKEN_INVALID`로 거절하고, 확인 뒤에 커밋되면 탈퇴 처리의 연결 닫기가 이 연결을 찾아 닫아요. (확인 뒤에 등록하면 그 사이에 탈퇴가 끝나 닫을 연결을 못 찾고 연결이 살아남았어요.)
- 로그아웃은 앱이 연결을 닫는 기존 정책 그대로예요.

## 이벤트 (#43)

모든 이벤트는 `/topic/rooms/{roomId}`로 이 모양이에요.

```json
{"type":"MEMBER_LEFT","roomId":"...","payload":{"userId":"...","newOwnerId":"..."},"at":"2026-11-11T13:36:58.374359231Z"}
```

`at`은 이벤트를 만든 시각(UTC ISO-8601)이에요. 화면 표시용 시간대 변환은 프론트가 해요.

| type | payload | 어디서 |
| --- | --- | --- |
| `MESSAGE_CREATED` | 메시지 객체(10.2와 같음, `meta` null) | `MessageCreatedEvent` (USER·AI·SYSTEM 전부) |
| `MEMBER_JOINED` | `{userId, nickname}` | `RoomMembershipService.join` |
| `MEMBER_LEFT` | `{userId, newOwnerId?}` (승계 없으면 `newOwnerId` 키가 없어요) | `RoomLeaveProcessor` (나가기·탈퇴 공통) |
| `OWNER_CHANGED` | `{previousOwnerId, newOwnerId}` | 같은 곳, 방장이 나가 자동 위임될 때 |
| `DEPARTURE_UPDATED` | `{userId, station}` (`station`은 방 상세의 `departureStation`과 같은 모양) | `RoomMembershipService.departure`, 값이 바뀐 때만 |
| `ROOM_UPDATED` | `{id, name, status, memberCount, maxMembers, appointmentAt, selectedStation}` (7.2 방 요약에서 `myRole`만 뺀 것) | `RoomService.update`, 값이 바뀐 때만 |
| `RECOMMENDATION_CREATED` | `{runId}` | `RecommendationCreatedEvent` (8.1 POST만) |
| `STATION_PROPOSAL_CREATED` | 후보 객체(7.14 응답) | `StationProposalService.create` |
| `STATION_PROPOSAL_DELETED` | `{proposalId}` | `StationProposalService.delete` |
| `STATION_PROPOSAL_VOTE_CHANGED` | `{proposalId, voteCount, userId}` | `ProposalVoteService.voteLocked`, 새 INSERT만 |
| `SELECTED_STATION_CHANGED` | `{station, confirmedAt}` (해제 시 둘 다 null) | `SelectedStationService.select/clear` |

- 마지막 멤버가 나가 방이 삭제될 때는 `MEMBER_LEFT`를 보내지 않아요. 받을 사람이 없어요.
- 같은 트랜잭션의 SYSTEM 메시지(#76)와 이벤트는 모두 커밋 뒤에 나가요. **한 트랜잭션 안에서는 보낸 순서 그대로 받아요.** SYSTEM 메시지의 `MESSAGE_CREATED`가 먼저이고 상태 이벤트가 뒤예요(입장: 메시지 → `MEMBER_JOINED`, 후보 제안: 메시지 → `STATION_PROPOSAL_CREATED`, 방장이 나갈 때: 나감 메시지 → 새 방장 메시지 → `MEMBER_LEFT` → `OWNER_CHANGED`).
  - 이유: 서비스가 `systemMessages`를 먼저 부르고 `events.publish`를 뒤에 부르면 커밋 뒤 콜백이 등록된 순서(메시지 → 상태)로 같은 스레드에서 차례로 `send()`돼요. 메시지 쪽은 `MessageCreatedEvent`를 동기 리스너(`RoomEventListeners`)가 받아 `send()`하므로 같은 스레드·같은 순서예요. 그리고 `RealtimeConfig`가 `setPreservePublishOrder(true)`를 켜서 브로커·outbound 스레드가 순서를 바꾸지 못해요.
  - 새 이벤트를 붙일 때도 **SYSTEM 메시지를 먼저, `events.publish`를 나중에** 부르세요. 순서는 이 호출 순서가 정해요.
  - 보장하지 않는 것: 서로 다른 트랜잭션·요청 사이의 순서(동시에 두 사람이 보낸 채팅과 방 이벤트 등)와 AI 답변이 도착하는 시점. 그건 커밋 순서·스레드 타이밍에 따라 달라요.
- `RECOMMENDATION_CREATED`가 오면 8.2를 다시 조회하고, 멤버·출발역·방 수정 이벤트를 받으면 최근 추천의 `isStale`을 다시 조회하는 건 명세대로 프론트 몫이에요.

### 발행 규칙 (`RoomEvents`)

```java
events.publish(roomId, RoomEventType.ROOM_UPDATED, payload);   // 커밋 뒤에만 나가요
events.dropSubscriptions(roomId, userId);                      // 커밋 뒤, 그 사용자의 방 구독 해제
events.disconnect(userId);                                     // 커밋 뒤, 그 사용자의 연결 종료
```

- 쓰는 트랜잭션 안에서 부르면 그 트랜잭션이 커밋될 때만 나가고, 롤백되면 아무것도 안 나가요. 트랜잭션 밖에서 부르면 바로 나가요.
- 전송이 실패해도(직렬화 오류, 브로커 오류) 로그만 남기고 호출한 쪽에는 예외가 가지 않아요. 이미 커밋된 DB 변경은 되돌아가지 않아요.
- payload는 커밋 전에 값으로 만들어 넘겨요(불변 record). 채팅·추천은 기존 Spring 이벤트(`MessageCreatedEvent`, `RecommendationCreatedEvent`)를 `RoomEventListeners`가 받아 그대로 STOMP로 보내요. SYSTEM 메시지 저장은 #76의 `ChatSystemMessages`가 그대로 해요.
- payload 모양은 `RoomEventPayloads`에 있어요(`ProposalVoteChanged`, `SelectedStationChanged`도 미리 만들어 뒀어요).

### 약속역·투표 발행 조건 (#125)

- **SELECTED_STATION_CHANGED**: 같은 역 재지정에도 항상 발행해요. SYSTEM 메시지는 실제 역이 바뀔 때만 저장하며 상태 이벤트보다 먼저 나가요. `station`은 `RoomReadRepository.stations(...)`의 `RoomResponses.Station`이며, `confirmedAt`은 `room.confirmStation`에 넘긴 값이에요. 해제는 실제 변경된 경우만 null/null을 발행하고 이미 열린 방에서는 발행하지 않아요.
- **STATION_PROPOSAL_VOTE_CHANGED**: 새 INSERT 뒤 `voteState` 응답과 같은 `voteCount`로 발행해요. 같은 후보 재투표와 `ALREADY_VOTED`에서는 발행하지 않아요. 외부 트랜잭션 유무와 관계없이 `REQUIRES_NEW` 안에서 동기화를 등록하여 해당 트랜잭션 커밋 뒤 발행해요. 유니크 위반으로 롤백된 시도는 발행하지 않고 재시도에서 INSERT가 성공한 경우만 발행해요.

## 설정

| 키 | 기본 | 뜻 |
| --- | --- | --- |
| `websocket.allowed-origins` (`WS_ALLOWED_ORIGINS`) | `CORS_ALLOWED_ORIGINS` 값 | WebSocket 핸드셰이크 허용 출처, 쉼표 구분 |

heartbeat 10초와 메시지 크기 제한(16KB)은 코드 상수예요. heartbeat용 스케줄러는 Spring이 만드는 `messageBrokerTaskScheduler`예요.

## 테스트

- 단위(`StompSessionsTest`, `StompDeliveryFilterTest`): 구독 기록·해제, 같은 구독 id 중복 기록 거절, 방까지 같아야 구독으로 인정, 방 구독만 지우기, 연결 종료와 닫기 실패, 다른 방으로 재사용된 구독 id의 프레임 차단, payload JSON 모양. 기존 서비스 테스트는 `RoomEvents` mock을 받도록 고쳤고, `/ws`가 Security에서 열린 것에 맞춰 `JwtAuthenticationFilterTest`의 비공개 경로 목록에서 `/ws`만 뺐어요.
- PostgreSQL(`StompPostgresTest`): 내장 서버와 Spring `WebSocketStompClient`로 실제 연결해요. 토큰 없음·형식 틀림·만료·위조·없는 사용자·탈퇴 사용자 CONNECT 거절, heartbeat, 허용·비허용 Origin, 멤버 구독과 채팅 POST 뒤 `MESSAGE_CREATED` 수신, 비멤버·없는 방·잘못된 목적지 거절, 신원 헤더 위조 무시, 클라이언트 SEND 차단, 나가기 뒤 기존 구독 수신 중단(연결은 유지), 방장 퇴장 시 `MEMBER_LEFT`+`OWNER_CHANGED`, 탈퇴 시 두 연결 모두 닫힘, 입장·출발역·방 수정(바뀐 때만)·후보 제안/삭제 이벤트, 추천 이벤트 전달, 다른 방 이벤트 차단, 롤백 시 이벤트 없음, 커밋 전에는 안 나감, 전송 실패해도 DB 변경 유지. 리뷰 반영 테스트: 구독 id 재사용(A `sub-0` 구독 → A 나가기 → B `sub-0` 구독 → A에 발행해도 안 옴, 브로커 목록에서도 빠짐), 브로커에 옛 구독이 남은 상태에서도 전달 차단, 구독 id 중복 거절, CONNECT 확인과 등록 사이·등록 직후 탈퇴 경합, 순서(입장·후보 제안·나가기·방장 위임을 도착한 순서 그대로 비교, 한 트랜잭션 300개 이벤트 순서).
