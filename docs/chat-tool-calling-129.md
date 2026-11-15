# 채팅 @링크로 도구 호출 (#129)

`@링크로` 질문에 AI가 서버가 정한 **읽기 전용 도구**를 불러 방 정보·추천 결과·나서는 시각·경로·혼잡도를 확인하고 답해요. 근거는 이슈 #129와 API 명세 10.1, 6.1, 6.2, 8.1·8.2예요. 저장소의 명세 문서(`LinkRo API 명세서 최종본 (3).docx`)는 v1.12라서 v1.13 10.4는 없어요. 도구 이름·사유 코드·규칙 1~10은 이슈 본문을 명세로 썼어요. 마이그레이션과 새 API는 없어요.

## 흐름

1. 질문 저장과 응답은 그대로예요([chat-76.md](chat-76.md)). `ChatAiResponder`가 방 맥락을 만든 뒤 질문한 사람의 도구 묶음(`ChatTools.forAsker(roomId, 질문자)`)을 요청에 붙여 `AiClient.replyToChat`을 불러요.
2. `OpenAiClient`가 OpenAI Responses API에 도구 목록을 같이 보내요(function calling). 모델이 도구를 부르면 서버가 실행하고, 결과를 `previous_response_id`로 이어 보내 최종 답을 받아요. 도구를 안 부르면 한 번에 끝나요.
3. 한 질문에 도구 호출 **라운드는 최대 3번**이에요. 3번 뒤에도 또 도구를 부르면 `AI_UNAVAILABLE`이에요. 모델 호출과 도구 실행을 합쳐 **25초**(`OpenAiClient.CHAT_TOTAL_LIMIT`)가 넘으면 다음 모델 호출을 하지 않고 `AI_UNAVAILABLE`이에요.
4. 상한 초과와 AI 실패는 기존처럼 SYSTEM 안내 메시지예요(`AI_QUOTA_EXCEEDED` 문구는 그대로, 그 밖에는 "AI가 답변을 만들지 못했어요…"). 도구 실패는 AI 실패가 아니라 사유 코드로 AI에 돌아가요.

## 도구 8개

모두 방과 질문자를 서버가 고정해요(`roomId`·`userId`는 인자가 아니에요). 호출마다 질문자가 아직 방 멤버인지 다시 확인하고, 아니면 `NO_DATA`예요. 인자에 다른 방 id를 넣어도 쓰지 않아요.

| 도구 | 입력 | 무엇을 쓰나 |
| --- | --- | --- |
| `get_room_info` | 없음 | `RoomAccess.requireMember`(방 이름·약속 시각·확정 약속역), `RoomReadRepository.members`(멤버 수) |
| `list_members` | 없음 | `RoomReadRepository.members`. 출발역이 없는 멤버는 `membersWithoutDeparture` |
| `get_latest_recommendation` | 없음 | `RecommendationService.latest`(8.2와 같은 값, `isStale`은 조회 때 계산). 기준별 최적역, 멤버별 `leaveAt`·`firstTrain`·`arriveAt`·`slackMinutes` |
| `get_departure_plan` | `destinationStation`, `member`(생략 시 질문자) | `TimetableRouteFinder.latestDepartures`(추천 엔진이 쓰는 호출). `leaveAt`, `firstTrain`(노선·역·방향·`boardAt`), `arriveAt`, `transferCount`, `travelMinutes`, `slackMinutes` |
| `get_route` | `originStation`(생략 시 질문자 출발역), `destinationStation` | `RouteSearchService.search`(6.1, `roomId`를 보내므로 약속이 있는 방은 시각표 경로). `routeSource`, `totalTimeSec`, `fare`, `fareSource`, `transferCount`, 단계 요약 |
| `get_congestion` | `station`, `line`, `direction`, `time`, `date`(생략 시 오늘) | `CongestionQueryService.congestion`(6.2와 같은 값) |
| `search_stations` | `query` | `StationQueryService.search`(5.1), 최대 5개 |
| `compare_members_travel` | `destinationStation` | 멤버마다 `RouteSearchService.search`. 멤버별 `totalMinutes`·`fare`·`transferCount`, `longestMember`, 출발역 없는 멤버 |

- 도구 설명에 언제 쓰고 언제 쓰지 않는지를 적었어요(`ChatToolSpecs`). 특히 `get_route`(두 역 사이 경로·요금), `get_departure_plan`(약속에 맞춰 몇 시에 나서는지), `compare_members_travel`(멤버 전원 비교)은 서로를 가리켜요.
- 시각은 Asia/Seoul 오프셋 ISO 문자열이에요. 시간은 올림 분, 여유는 내림 분이에요(서버가 계산하고 AI는 계산하지 않아요).
- 만들지 않은 것(규칙 3): 추천 실행, 약속역 지정·해제, 방 수정, 출발역 변경, 후보 제안·삭제, 투표, 방 나가기·탈퇴. 실시간 도착(6.3)과 후보·투표 현황도 후속이에요.

## 실패와 사유 코드

도구는 실패하면 `{"ok":false,"code":"...","message":"..."}`를 돌려줘요(`message`는 AI가 말을 고르는 데만 쓰는 짧은 한국어 설명이고 없을 수도 있어요).

| code | 언제 |
| --- | --- |
| `NO_APPOINTMENT` / `APPOINTMENT_PASSED` | `get_departure_plan`: 약속 시각이 없음 / 이미 지남(추천의 `APPOINTMENT_PASSED`와 같은 초 단위 기준) |
| `NO_ROUTE` | 경로 없음(`ROUTE_NOT_FOUND`), 출발역과 도착역이 같음 |
| `NO_SERVICE` | 약속 시각까지 도착하는 열차가 없음, 운행 시간이 아님 |
| `STATION_NOT_FOUND` / `STATION_AMBIGUOUS` / `STATION_UNSUPPORTED` | 역 이름 해석(아래). 없음 / 같은 이름이 여럿 / 미지원 역 |
| `MEMBER_NOT_FOUND` | 그 방 멤버의 닉네임이 아님(같은 닉네임이 여럿이어도) |
| `DEPARTURE_MISSING` | 대상 멤버(또는 질문자)의 출발역이 없음. `compare`는 전원이 없을 때만 |
| `NO_RECOMMENDATION` | 최근 추천이 없거나 결과가 비었음 |
| `STALE` | 최근 추천 이후 약속·멤버가 바뀜. **시각도 역도 돌려주지 않고** `staleReasons`만 |
| `NO_DATA` | 그 밖의 모든 경우: 방 멤버가 아님, 시간 초과, 알 수 없는 노선·도구, 혼잡도 데이터 없음, 입력 형식 오류, 내부 오류 |

- 역 이름은 추천의 `ConstraintResolver`와 같은 규칙으로 지원 역 id에 매핑해요(`ConstraintResolver.stationIds`, 끝의 "역" 무시, 같은 이름이 여럿이면 `STATION_AMBIGUOUS`). 지원 역에 없으면 이름이 같은 미지원 역이 있는지 보고 `STATION_UNSUPPORTED`, 아니면 `STATION_NOT_FOUND`예요.
- 닉네임은 그 방 멤버만 허용해요.
- `get_departure_plan`의 `leaveAtPassed: true`는 늦게 나서는 시각이 이미 지났다는 뜻이고, 이때는 `get_route`(지금 출발 기준)로 안내해요.
- 방 맥락 텍스트(10.1)는 그대로이고, 도구는 그 위에 필요할 때만 불러요.

## 한도·로그

- 도구마다 타임아웃 `linkro.chat.ai-tool-timeout`(기본 8s). 넘으면 취소하고 `NO_DATA`예요. 도구는 전용 풀 `chatToolExecutor`(큐 없음, 최대 `ai-threads`×2개)에서 돌고, 풀이 가득 차면 `NO_DATA`예요.
- 도구 실행은 DB 트랜잭션을 길게 잡지 않아요. 각 서비스가 자기 짧은 트랜잭션만 열어요.
- 로그는 `chat tool {도구 이름} {결과 코드} {걸린 ms}` 한 줄뿐이에요. 질문 본문, 닉네임, 역 이름, 인자, 키는 남기지 않아요. 모르는 도구 이름은 `unknown`으로 남겨요.

## 프롬프트 규칙 (`ai/prompt/chat.txt`)

- 방·약속·추천·시각·경로·요금·혼잡도는 도구로만 확인하고, 시각·요금은 도구 값을 그대로 인용해요(직접 계산·반올림·초 단위 말하기 금지).
- 사유 코드가 오면 추측하지 않고 앱의 경로 보기로 안내해요. `NO_RECOMMENDATION`은 추천받기 버튼, `STALE`은 다시 추천받기, `STATION_AMBIGUOUS`는 되묻기예요.
- `routeSource: INTERNAL`, `fareSource: ESTIMATED`는 "예상"으로 말해요. 카카오 경로는 첫 열차·환승 대기를 반영하지 않은 예상 값이에요.
- 도구가 없는 질문(실시간 도착, 약속역 변경, 투표 현황 등)은 지어내지 않고 앱 화면으로 안내하고, 일반 지식 질문은 도구 없이 답해요.

## 알려진 한계

- 25초 상한은 모델 호출 사이에 확인해요. 한 번의 모델 호출은 `OPENAI_READ_TIMEOUT`(8s)까지 갈 수 있어서 최악에는 상한을 한 번 넘겨 끝날 수 있어요.
- `previous_response_id`로 이어 가므로 OpenAI 쪽에 응답이 저장되는 기본 설정(`store`)을 그대로 써요. 저장을 끄는 조직 설정이면 도구 호출 답변이 `AI_UNAVAILABLE`이 돼요.
- 도구 정의가 요청마다 실려 토큰과 지연이 늘어요. `chat.txt`와 도구 설명이 길어질수록 더 늘어요.
- 도구 결과는 AI가 쓰는 JSON이라 한 질문에 멤버가 여럿이면 `compare_members_travel`·`get_latest_recommendation`의 결과가 커요.

## 테스트

- 단위: `ChatToolsTest`(도구 8개: 정상, 방 멤버 아님·다른 방 값 무시, 없는 역·같은 이름 여러 개·미지원 역, 사유 코드, 시간 초과, 알 수 없는 도구·깨진 인자, stale 추천에 시각·역이 없음, 로그에 닉네임·역 이름·인자가 없음), `ChatToolCallingFlowTest`(모의 AI가 도구를 부르고 그 결과로 답하는 흐름을 도구마다 정상·권한 거부·없는 역·실패로, AI 실패와 상한 초과가 `AI_UNAVAILABLE` 안내로), `OpenAiClientTest`(도구 호출 → 결과 전송 → 최종 답, 3라운드까지 허용·4번째 도구 요청은 `AI_UNAVAILABLE`, 도구 라운드 중 AI 실패·429, 미완료 응답).
- PostgreSQL(`ChatToolsPostgresTest`, graph·시각표 모듈): 실제 방·멤버로 방 정보·멤버, 비멤버 거부, `get_departure_plan` 값이 추천이 쓰는 시각표 경로와 같음, 경로·멤버 비교, 역 검색·이름 규칙, 혼잡도 데이터 없음, 추천을 읽다가 약속 시각이 바뀌면 `STALE`(시각 없음), 모의 AI가 `get_departure_plan` 결과로 답하는 채팅 질문 전체 흐름.
