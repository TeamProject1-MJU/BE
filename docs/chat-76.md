# 채팅 메시지 전송·조회와 @링크로 AI 답변 (#76)

API 명세 v1.12 10.1(#27), 10.2(#28), 상세설계서 3.3.4, ERD v1.13 `room_messages` 기준이에요. V8 테이블을 그대로 쓰고 마이그레이션은 추가하지 않아요. STOMP 전송은 #42, #43 몫이라 이 이슈는 이벤트 발행까지만 해요.

## API

모두 JWT 필요, 방 멤버만 쓸 수 있어요. 멤버가 아니면 `NOT_ROOM_MEMBER`(403), 방이 없으면 `ROOM_NOT_FOUND`(404)예요.

### POST /api/v1/rooms/{roomId}/messages (10.1)

요청 `{"content": "@링크로 사당역에서 갈만한 곳 알려줘"}`. 응답은 201이고 저장한 메시지를 바로 돌려줘요.

```json
{"success":true,"data":{"message":{"id":"...","senderType":"USER","senderId":"...","nickname":"민지","content":"...","createdAt":"...","meta":null},"aiPending":true}}
```

- 검사 순서: 멤버 확인 → 본문 검사. 본문이 틀리면 `VALIDATION_ERROR`(400, `details.content`)예요.
- 본문 규칙(앱 코드에서 검사, DB 제약은 4000자): 문자열이어야 하고, 공백만 있으면 안 되고, NUL 문자가 없어야 하고, 유니코드 코드포인트 1000자 이하예요. 본문은 자르거나 고치지 않고 보낸 그대로 저장해요.
- 저장은 짧은 트랜잭션 하나이고, 커밋 뒤에 응답해요. AI는 기다리지 않아요.
- `aiPending`: 본문에 한글 `@링크로`가 있으면 true예요. 뒤에 조사나 문장부호가 와도 되고("@링크로야", "@링크로,"), 앞에 글자나 숫자가 붙으면("me@링크로") 멘션이 아니에요. 영어 `@linkro`는 일반 메시지예요.
- `message.nickname`: 10.2와 같은 모양으로 맞추려고 10.1 응답에도 넣었어요(명세 10.1 예시에는 없음).

### GET /api/v1/rooms/{roomId}/messages?cursor={uuid}&size=30 (10.2)

```json
{"success":true,"data":{"items":[{"id":"...","senderType":"AI","senderId":null,"nickname":null,"content":"...","createdAt":"...","meta":null}],"nextCursor":"...","hasMore":true}}
```

- 최신 메시지부터(id 내림차순). `cursor`는 이전 페이지의 `nextCursor`(그 id보다 오래된 메시지를 줘요). 없으면 가장 최근부터예요.
- 쿼리는 `WHERE room_id = :room AND (:cursor IS NULL OR id < :cursor) ORDER BY id DESC LIMIT :n`이고, 정렬과 비교는 PostgreSQL이 해요. Java에서 UUID를 정렬하거나 비교하지 않아요. (`UUID.compareTo`는 부호 있는 long 비교라 PostgreSQL 순서와 달라요. PG 테스트에 그 경우를 넣었어요.)
- `size`는 기본 30, 1~100. `cursor`가 UUID가 아니거나 `size`가 범위 밖이면 `VALIDATION_ERROR`(400)예요.
- `hasMore`는 한 건 더 읽어서 판단해요. `hasMore`가 false이면 `nextCursor`는 null이에요.
- USER 메시지의 `nickname`은 users의 현재 닉네임이고, 나간 사용자의 과거 메시지도 닉네임이 남아요(15장의 "나간 사용자" 표시는 프론트와 확인할 일). AI·SYSTEM은 `senderId`·`nickname`이 null이에요.
- `meta`는 모든 메시지에서 null이에요(10.3 MVP 제외).

## @링크로 AI 답변

1. 질문 메시지를 먼저 커밋하고 응답해요. 그 뒤 전용 스레드풀(`chatAiExecutor`)에서 답을 만들어요. 풀이 가득 차면 요청은 실패하지 않고 SYSTEM 안내 메시지로 바뀌어요.
2. AI에게 `AiClient.replyToChat(ChatReplyRequest)`로 보내는 것: 멘션을 뺀 질문, 질문 이전의 USER·AI 메시지 최근 10개(오래된 순, AI는 "링크로"라는 이름), 방 맥락 텍스트(방 이름, 약속 시각, 확정된 약속역, 참여자와 출발역, 최근 추천의 기준별 최적역). 사실만 넣고 숫자 계산은 하지 않아요(10.1 "방 맥락(참여자·출발지·최근 추천 결과)").
3. 방 맥락을 만드는 동안과 AI를 부르는 동안에는 DB 트랜잭션을 잡지 않아요.
4. 성공하면 AI 메시지(`sender_type=AI`, `sender_id=null`)로 저장하고 4000자를 넘으면 자르고(`ck_msg_content`) 이벤트를 발행해요.
5. 실패하면 SYSTEM 안내 메시지를 저장해요. 질문 메시지는 그대로 남아요.
   - `AI_QUOTA_EXCEEDED`: "AI 사용 한도를 초과해 답변하지 못했어요. 잠시 후 다시 시도해 주세요."
   - 그 밖의 실패·빈 답변·대기열 초과: "AI가 답변을 만들지 못했어요. 잠시 후 다시 시도해 주세요."
6. AI가 답하는 사이 방이 사라졌으면(마지막 멤버 퇴장) 답은 조용히 버려요. 보낼 사람이 없어요.

## SYSTEM 메시지 (10.1)

`ChatSystemMessages`가 방을 바꾸는 트랜잭션 안에서(방 락을 잡은 뒤) 저장해요. 변경이 롤백되면 메시지도 사라지고, 이벤트는 바깥 트랜잭션 커밋 뒤에 나가요. `sender_id`는 null이에요(`ck_msg_sender`).

| 이벤트 | 위치 | 메시지 |
| --- | --- | --- |
| 입장 | `RoomMembershipService.join` | "{닉네임}님이 입장했어요." |
| 퇴장 | `RoomLeaveProcessor.leaveLocked` (나가기와 회원 탈퇴 공통) | "{닉네임}님이 나갔어요." |
| 방장 변경 | 같은 곳, 방장이 나가 자동 위임될 때 | "{닉네임}님이 새 방장이 됐어요." |
| 후보 제안 | `StationProposalService.create` | "{닉네임}님이 {역}역을 약속역 후보로 제안했어요." |
| 후보 삭제 | `StationProposalService.delete` | "{닉네임}님이 약속역 후보 {역}역을 삭제했어요." (삭제한 사람) |
| 약속역 지정 | `SelectedStationService.select` (이전 약속역 없음) | "{닉네임}님이 약속역을 {역}역으로 지정했어요." |
| 약속역 변경 | 같은 곳 (다른 역이 이미 있음) | "{닉네임}님이 약속역을 {역}역으로 변경했어요." |
| 약속역 해제 | `SelectedStationService.clear` (바뀐 경우만) | "{닉네임}님이 약속역 지정을 해제했어요." |

- 마지막 멤버가 나가 방이 삭제될 때는 메시지를 남기지 않아요(방과 함께 cascade로 사라짐).
- 이미 선택된 같은 역을 다시 지정하면(확정 시각만 갱신) SYSTEM 메시지를 남기지 않아요.
- 역 이름 끝이 "역"이면 그대로, 아니면 "역"을 붙여요("서울역"이 "서울역역"이 되지 않게).
- 문구는 명세에 정해진 게 없어서 우리가 정했어요(이벤트 목록만 명세 10.1).

## 이벤트

모든 메시지(USER, AI, SYSTEM)는 저장·커밋 뒤에 `MessageCreatedEvent(roomId, message)`를 Spring `ApplicationEventPublisher`로 발행해요. WebSocket `MESSAGE_CREATED`(11장, payload = 메시지 객체)는 #42/#43의 리스너가 이 이벤트를 받아 보내요. 이 모듈은 STOMP를 몰라요. 리스너 예외는 로그만 남기고 저장·응답에 영향을 주지 않아요. 바깥 트랜잭션 안에서 저장한 메시지는 그 트랜잭션이 커밋될 때만 발행해요.

## 설정

`linkro.chat.*`(`ChatProperties`). 1000자 제한은 명세 상수라 설정이 아니에요.

| 키 | 기본 | 뜻 |
| --- | --- | --- |
| `ai-context-messages` | 10 | 질문과 함께 AI에 보내는 이전 대화 수 |
| `ai-threads` | 4 | 동시에 답하는 스레드 수 |
| `ai-queue-capacity` | 50 | 대기 가능한 질문 수. 넘으면 SYSTEM 안내 |

AI 호출 읽기 타임아웃은 기존 `OPENAI_READ_TIMEOUT`(8s)이에요. 모델이 느려 시간이 넘으면 `AI_UNAVAILABLE`로 처리돼 SYSTEM 안내가 나가요.

## 테스트

- 단위: `ChatMessageRequestTest`(본문 검사), `LinkroMentionTest`, `ChatMessageWriterTest`(이벤트 시점·롤백·방 삭제), `ChatServiceTest`(전송·AI 성공/실패/대기열·목록), `ChatSystemMessagesTest`(문구). 방 서비스 테스트는 SYSTEM 메시지 호출을 확인하도록 고쳤어요.
- PostgreSQL(`ChatPostgresTest`): 커서 쿼리(PG 순서 vs Java 순서, 페이지 순회), 전송·조회 HTTP 흐름, 1000/1001자와 DB 4000자, @링크로 답변(mock AI)과 실패 안내, 방 이벤트별 SYSTEM 메시지, 롤백 시 메시지·이벤트 없음, 방 삭제 시 메시지 cascade.
