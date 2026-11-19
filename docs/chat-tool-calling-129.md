# 채팅 @링크로 도구 호출 (#129)

`@링크로` 질문에 AI가 서버가 정한 도구(읽기 전용 8개와, 추천을 실행하는 `run_recommendation` 1개, #168)를 불러 방 정보·추천 결과·나서는 시각·경로·혼잡도를 확인하고 답해요. 근거는 이슈 #129와 API 명세 10.1, 6.1, 6.2, 8.1·8.2예요. 저장소의 명세 문서(`LinkRo API 명세서 최종본 (3).docx`)는 v1.12라서 v1.13 10.4는 없어요. 도구 이름·사유 코드·규칙 1~10은 이슈 본문을 명세로 썼어요. 마이그레이션과 새 API는 없어요.

## 흐름

1. 질문 저장과 응답은 그대로예요([chat-76.md](chat-76.md)). `ChatAiResponder`가 방 맥락을 만든 뒤 질문한 사람의 도구 묶음(`ChatTools.forAsker(roomId, 질문자)`)을 요청에 붙여 `AiClient.replyToChat`을 불러요.
2. `OpenAiClient`가 OpenAI Responses API에 도구 목록을 같이 보내요(function calling). 모델이 도구를 부르면 서버가 실행하고, 다음 라운드에 **지금까지의 입력 전체**(질문 + 모델이 낸 출력 항목 `reasoning`·`function_call` + `function_call_output`)를 다시 보내 최종 답을 받아요. 도구를 안 부르면 한 번에 끝나요.
   - 도구 호출 경로의 모든 요청에 `store:false`를 보내서 OpenAI에 응답을 저장하지 않아요. `previous_response_id`는 쓰지 않아요.
   - 저장을 끈 상태로 추론 항목을 이어 가려고 `include:["reasoning.encrypted_content"]`를 요청하고, 받은 `reasoning` 항목(암호화된 내용 포함)을 그대로 다음 라운드 입력에 넣어요.
   - 채팅 호출은 `reasoning.effort`를 보내요. 설정 `linkro.ai.openai.chat-reasoning-effort`(환경변수 `OPENAI_CHAT_REASONING_EFFORT`, 기본 `low`)이고 표지판의 `OPENAI_VISION_REASONING_EFFORT`(기본 `low`, 표지판 판독에만)와는 따로예요. 규칙은 표지판과 같아요(환경변수가 비어 있으면 기본값 `low`가 유지되고, 끄려면 `none`, 모델이 지원하는 값만 가능). 도구를 안 붙인 `replyToChat` 경로에도 같은 값을 보내요.
   - 모델이 `reasoning.effort`를 지원하지 않아 OpenAI가 400(오류 문구에 reasoning/effort)을 주면, 같은 질문을 `reasoning` 없이 한 번 더 보내요(도구 호출 경로는 그 질문의 남은 라운드도 없이). 그래서 지원하지 않는 모델이어도 @링크로 전체가 멈추지 않고, 질문마다 400 한 번과 재시도가 더 들어요(경고 로그 한 줄, 본문은 안 남김). 재시도 직전에도 25초 상한을 확인해서, 첫 요청이 이미 상한을 넘겼으면 재시도하지 않고 `AI_UNAVAILABLE`이에요. reasoning과 상관없는 400은 재시도 없이 `AI_UNAVAILABLE`이에요. 표지판 판독은 이 재시도를 하지 않아요.
3. 한 질문에 도구 호출 **라운드는 최대 3번**이에요. 3번 뒤에도 또 도구를 부르면 `AI_UNAVAILABLE`이에요. 모델 호출과 도구 실행을 합쳐 **25초**(`OpenAiClient.CHAT_TOTAL_LIMIT`)가 넘으면 다음 모델 호출도, 아직 안 돈 도구 실행도 하지 않고 `AI_UNAVAILABLE`이에요(모델 호출 직전과 도구를 하나 돌리기 직전에 확인해요).
   - 한 라운드에서 실제로 도는 도구는 **최대 4개**(`CHAT_MAX_TOOL_CALLS_PER_ROUND`)예요. 모델이 더 부르면 그 호출은 실행하지 않고 `{"ok":false,"code":"NO_DATA","message":"한 번에 부를 수 있는 도구 수를 넘어…"}`를 돌려줘요(모든 `call_id`에 결과가 있어야 해서 결과는 항상 보내요).
4. 상한 초과와 AI 실패는 기존처럼 SYSTEM 안내 메시지예요(`AI_QUOTA_EXCEEDED` 문구는 그대로, 그 밖에는 "AI가 답변을 만들지 못했어요…"). 도구 실패는 AI 실패가 아니라 사유 코드로 AI에 돌아가요.

## 도구 9개

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
| `run_recommendation` (#168, **쓰기**) | 모두 선택: `conditionText`(사용자가 말한 조건의 한국어 짧은 요약, 표시용), `maxMinutes`, `maxTransfers`, `maxFareWon`, `excludeStations`(역 이름 배열), `memberLimits`(`[{nickname, maxMinutes}]`) | `RecommendationService.create`(8.1 `POST /rooms/{roomId}/recommendations`)를 그대로 부른다. 멤버 확인·검증·계산·저장이 같고, 만든 실행은 8.2·8.3에서 [추천받기]로 만든 것과 똑같이 보인다. 아래 "추천 실행 도구" |

- 도구 설명에 언제 쓰고 언제 쓰지 않는지를 적었어요(`ChatToolSpecs`). 특히 `get_route`(두 역 사이 경로·요금), `get_departure_plan`(약속에 맞춰 몇 시에 나서는지), `compare_members_travel`(멤버 전원 비교)은 서로를 가리켜요.
- 시각은 Asia/Seoul 오프셋 ISO 문자열이에요. 시간은 올림 분, 여유는 내림 분이에요(서버가 계산하고 AI는 계산하지 않아요).
- 만들지 않은 것(규칙 3): 약속역 지정·해제, 방 수정, 출발역 변경, 후보 제안·삭제, 투표, 방 나가기·탈퇴. 실시간 도착(6.3)과 후보·투표 현황도 후속이에요.

## 추천 실행 도구 `run_recommendation` (#168)

- **용도**: 사용자가 조건을 말하며 추천을 해 달라고 할 때. 이미 받은 추천을 묻는 질문은 `get_latest_recommendation`이다. 조건이 없으면 인자 없이 부른다(filter 없는 실행).
- **`conditionText`는 표시용 요약**이다. 모델이 사용자가 말한 조건을 짧게 요약해(예: "환승 1번 이하, 40분 안쪽") 넣으면 그 실행의 `conditionText`로 저장돼 8.3(·8.2)에 그대로 보인다(8.1의 `conditionText`와 같은 길이 규칙: 앞뒤 공백 제거, 공백뿐이면 null, 500자 초과는 `VALIDATION_ERROR`로 실행 안 함, 문자열이 아니어도 `VALIDATION_ERROR`). **해석하지 않는다.** `RecommendationService.create(userId, roomId, body, displayText)`의 `displayText`로만 들어가고 `body.conditionText`는 항상 비어 있어서 `ConstraintResolver`가 조건 해석 AI(`interpretCondition`)를 부르지 않는다. 조건은 아래 구조화 인자로만 적용되므로 모델은 조건을 `maxMinutes` 같은 인자로도 넣어야 하고, 요약만 쓰면 `appliedConstraints`는 비어 있다. 요약이 없으면 `conditionText`는 null이다. OpenAI 호출 수는 그대로다(모델 호출 + 설명 1번).
- **입력은 8.1 `filter`와 같은 항목·범위**다: `maxMinutes` 1~300, `maxTransfers` 0~10, `maxFareWon` 1~100000, 제외할 역, 멤버별 시간 상한. 다만 AI는 역·사용자 id를 모르므로 `excludeStations`는 **역 이름**(다른 도구와 같은 이름 규칙, 끝의 '역' 무시), `memberLimits`는 **닉네임**으로 받고 서버가 id로 바꾼다. 범위·형식은 `RecommendationRequest.validate`로 검사하고, 틀리면 실행하지 않고 `VALIDATION_ERROR`(AI가 사용자에게 다시 묻는다). 정수가 아닌 값(`"40"`, `40.5`)도 `VALIDATION_ERROR`다.
- **한 질문에 최대 1회**: 검증을 통과해 8.1을 부르는 순간부터 그 질문의 도구 묶음에서 다시 부르면 `ALREADY_RUN`이다(8.1이 거절한 뒤에도). 입력 검증에 걸린 호출은 횟수를 쓰지 않는다.
- **출력**(성공, `ok:true`): `hasResults`, `bestStation`(시간 기준 결과의 `station`·`criterion`·`maxMinutes`·`averageMinutes`·`averageFare`·`totalTransfers`), `appliedConstraints`(적용된 `maxMinutes`·`maxTransfers`·`maxFareWon`·`excludedStations`(이름)·`memberLimits`(닉네임)), `message`(적용하지 못한 조건 안내가 있으면), 결과가 있을 때만 `runId`. 결과가 없으면(`hasResults:false`) 실행은 8.1처럼 빈 결과로 저장되지만 `runId`와 `bestStation`은 주지 않고, AI는 "조건을 조금 풀어 볼까요?"처럼 말로만 안내한다.
- **실패**(`ok:false`): 8.1의 거절이 그대로 사유가 된다. `TOO_FEW_MEMBERS`(멤버 2명 미만), `DEPARTURE_MISSING`(출발역 없는 멤버의 닉네임이 `message`에), `APPOINTMENT_PASSED`, `NO_SERVICE`(`NO_SERVICE_TIME`), `RECOMMENDATION_IN_PROGRESS`(같은 방에서 계산 중). 그 밖의 내부 오류·방 멤버 아님·시간 초과는 `NO_DATA`. 8.1이 거절하면 실행 기록은 남지 않는다.
- **시간 제한**: 추천 계산에 설명 AI 호출(최대 `OPENAI_EXPLAIN_READ_TIMEOUT` 6s)이 들어 있어 읽기 도구의 8초로는 빠듯하다. 그래서 이 도구만 `linkro.chat.ai-recommendation-tool-timeout`(기본 12s)을 쓴다. 3라운드·25초 상한은 그대로다(모델 호출 직전·도구 실행 직전 확인). 모델 호출 두 번과 이 도구가 한 질문에 다 들어가는지는 아래 "측정".
- **메시지 meta**: 이 질문이 저장한 실행에 결과가 있고, 도구가 시간 안에 끝났고, 최종 답이 완성됐을 때만 AI 답변 메시지의 `meta`가 `{"type":"RECOMMENDATION","runId":"<추천 실행 id>"}`다(`ChatAiResponder`가 `ChatTools`의 질문별 묶음에서 runId를 받아 `ChatMessageWriter.recordAiRecommendation`으로 저장). 결과 없음, 8.1 거절, 검증 오류, 도구 시간 초과·오류, 답변 실패(SYSTEM 안내)는 모두 `meta: null`이다. 시간 초과 뒤에 실행이 뒤늦게 저장돼도 meta는 붙이지 않는다(그 실행은 추천 기록에는 남는다). 형식은 조회(`GET /rooms/{roomId}/messages`)와 실시간(STOMP `MESSAGE_CREATED`)이 같다. 앱은 runId로 8.3 `GET /rooms/{roomId}/recommendations/{runId}`를 연다.
- 프롬프트(`chat.txt`)의 "도구는 읽기만 한다"는 `run_recommendation`만 예외로 고쳤다. 추천 실행은 8.1과 같은 `RecommendationCreatedEvent`를 내보내므로 방의 다른 사람도 실시간으로 본다.

### 측정 (#168 5번): 추천 실행 구간

- 방법: PostgreSQL 16(Testcontainers) + 시드 + graph·시각표 모듈, 약속 있는 방(2027-01-05 08:30), 도구 호출(`run_recommendation`: 멤버 확인 + 8.1 계산·저장 + 결과 읽기)의 벽시계 시간. 설명 AI는 목(mock)이라 **설명 호출 시간(`explain`, 실서버는 최대 6s)은 빠져 있다**. 멤버 2·4·6명 각 31회(조건 없음과 `maxMinutes 90·maxTransfers 3` 조건을 번갈아), 첫 호출은 따로.
- 결과: 첫 호출 65~336ms, 이후 30회 평균 24~87ms, 최대 247ms(2명 방의 워밍업 구간), 4·6명은 평균 약 25ms·최대 61ms. 서비스 로그의 `evaluate`는 첫 호출 때 약 230~260ms였다.
- 해석: 계산·저장 자체는 25초 상한에 거의 영향이 없다. 지배적인 시간은 설명 AI 호출(최대 6s)과 모델 호출 2번(도구 질문 2.3~3.0초, 아래 한계 참고)이고, 그래서 도구 시간 제한을 12s로 뒀다. **실제 OpenAI를 넣은 질문 전체 시간(모델 호출 2번 + 설명 호출)은 측정하지 않았다**(실키 호출은 사용자 승인 후).

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
| `VALIDATION_ERROR` / `ALREADY_RUN` | `run_recommendation`: 인자 범위·형식 오류(실행 안 함) / 이 질문에서 이미 실행함 |
| `TOO_FEW_MEMBERS` / `RECOMMENDATION_IN_PROGRESS` | `run_recommendation`: 멤버 2명 미만 / 같은 방에서 추천 계산 중 (`APPOINTMENT_PASSED`·`DEPARTURE_MISSING`·`NO_SERVICE`도 8.1 거절 그대로) |
| `NO_DATA` | 그 밖의 모든 경우: 방 멤버가 아님, 시간 초과, 알 수 없는 노선·도구, 혼잡도 데이터 없음, 입력 형식 오류, 내부 오류 |

- 역 이름은 추천의 `ConstraintResolver`와 같은 규칙으로 지원 역 id에 매핑해요(`ConstraintResolver.stationIds`, 끝의 "역" 무시, 같은 이름이 여럿이면 `STATION_AMBIGUOUS`). 지원 역에 없으면 이름이 같은 미지원 역이 있는지 보고 `STATION_UNSUPPORTED`, 아니면 `STATION_NOT_FOUND`예요.
- 닉네임은 그 방 멤버만 허용해요.
- `get_departure_plan`의 `leaveAtPassed: true`는 늦게 나서는 시각이 이미 지났다는 뜻이에요. 그 시각에 나서라고 말하지 않고(`firstTrain`·`slackMinutes`도 쓰지 않아요) `get_route`(지금 출발 기준)로 안내해요.
- `get_latest_recommendation`은 시간이 지나도 `STALE`이 되지 않아요(`isStale`의 뜻은 그대로, 약속·멤버 변경만 봐요). 그래서 현재 시각(주입된 `Clock`)으로 멤버마다 `leaveAtPassed`(나서는 시각이 지남)와 `boardAtPassed`(첫 열차 출발이 지남)를 붙이고, 하나라도 지났으면 맨 위 `departuresPassed`가 `true`예요. 시각 값은 그대로 오지만 `chat.txt`와 도구 설명이 "지금 쓸 수 있는 계획처럼 말하지 말고 `get_route`로 지금 출발 기준을 안내"하라고 해요.
- `get_route`는 시간을 셋으로 나눠 줘요. `travelMinutes`(타고 가는 시간 = `totalTimeSec` 올림 분, 기존 `totalMinutes`의 새 이름), `waitMinutes`(출발 전 첫 열차 대기 = `waitSec` 올림 분, 없으면 필드가 오지 않음), `totalWithWaitMinutes`(지금부터 도착까지 = `totalWithWaitSec` 올림 분, 없으면 필드가 오지 않음). "지금부터 얼마나"는 `totalWithWaitMinutes`, "타고 가는 데 얼마나"는 `travelMinutes`로 답하라고 적었어요. `compare_members_travel`의 `totalMinutes`는 그대로(타고 가는 시간, 대기 제외)예요.
- `get_route`의 `arrivesAfterAppointment: true`는 지금 출발해도 약속에 늦는다는 뜻이고 `appointmentPassed: true`는 약속 시각이 이미 지났다는 뜻이에요. 둘 다 약속에 맞는다고 말하지 않아요. `chat.txt`와 두 도구 설명에 이 규칙을 적었어요.
- 방 맥락 텍스트(10.1)는 그대로이고, 도구는 그 위에 필요할 때만 불러요.

## 한도·로그

- 도구마다 타임아웃 `linkro.chat.ai-tool-timeout`(기본 8s). 넘으면 취소하고 `NO_DATA`예요(`run_recommendation`만 `ai-recommendation-tool-timeout` 기본 12s). 도구는 전용 풀 `chatToolExecutor`(큐 없음, 최대 `ai-threads`×2개)에서 돌고, 풀이 가득 차면 `NO_DATA`예요.
- 도구 실행은 DB 트랜잭션을 길게 잡지 않아요. 각 서비스가 자기 짧은 트랜잭션만 열어요.
- 로그는 `chat tool {도구 이름} {결과 코드} {걸린 ms}` 한 줄뿐이에요. 질문 본문, 닉네임, 역 이름, 인자, 키는 남기지 않아요. 모르는 도구 이름은 `unknown`으로 남겨요.

## 프롬프트 규칙 (`ai/prompt/chat.txt`)

- 방·약속·추천·시각·경로·요금·혼잡도는 도구로만 확인하고, 시각·요금은 도구 값을 그대로 인용해요(직접 계산·반올림·초 단위 말하기 금지).
- 사유 코드가 오면 추측하지 않고 앱의 경로 보기로 안내해요. `NO_RECOMMENDATION`은 추천받기 버튼, `STALE`은 다시 추천받기, `STATION_AMBIGUOUS`는 되묻기예요.
- `routeSource: INTERNAL`, `fareSource: ESTIMATED`는 "예상"으로 말해요. 카카오 경로는 첫 열차·환승 대기를 반영하지 않은 예상 값이에요.
- 추천 요청은 `run_recommendation`을 한 번 부르고(조건이 있으면 인자로), 실패 코드는 이유를 말로 안내하고, `hasResults:false`면 조건을 풀어 보자고 안내하고, 실행했으면 앱의 [상세보기]를 안내해요. 이미 받은 추천을 묻는 질문은 `get_latest_recommendation`이에요.
- 도구가 없는 질문(실시간 도착, 약속역 변경, 투표 현황 등)은 지어내지 않고 앱 화면으로 안내하고, 일반 지식 질문은 도구 없이 답해요.

## 알려진 한계

- 25초 상한은 모델 호출 직전과 도구 실행 직전에 확인해요. 하지만 이미 시작한 모델 호출(`OPENAI_READ_TIMEOUT` 8s)이나 도구(`ai-tool-timeout` 8s, `run_recommendation`은 12s) 하나는 끝까지 기다려서, 최악에는 상한을 그 하나만큼 넘겨 끝날 수 있어요(약 33초). `ai-shutdown-wait`(15초)보다 오래 걸리는 답은 배포 중 끊길 수 있어요.
- 기본 추론 강도는 `low`예요(채팅이 느리게 느껴져서 `medium`에서 낮췄어요). `medium`으로 실측했을 때(`gpt-6.1-terra`) 모델 호출은 도구 질문 2.3~3.0초, 도구 없는 질문 3.1~3.2초였어요. 높이려면 `OPENAI_CHAT_REASONING_EFFORT`를 바꿔요. 질문이 길어지거나 라운드가 늘면 더 걸릴 수 있어요.
- 라운드마다 입력 전체를 다시 보내서(저장을 끈 대가) 라운드가 늘수록 요청이 커지고 토큰도 늘어요. 최대 3라운드라 한도가 있어요. 암호화된 추론 항목은 `include` 때문에 응답에 실려 와요.
- 모델이 `include: reasoning.encrypted_content`나 `reasoning.effort` 값을 받지 않으면 요청이 거절돼 `AI_UNAVAILABLE`이 돼요. 다른 모델로 바꿀 때 채팅 질문으로 한 번 확인해요.
- 채팅 답변은 계속 500자(`CHAT_MAX_LENGTH`)에서 잘려요.
- 도구 정의가 요청마다 실려 토큰과 지연이 늘어요. `chat.txt`와 도구 설명이 길어질수록 더 늘어요.
- 도구 결과는 AI가 쓰는 JSON이라 한 질문에 멤버가 여럿이면 `compare_members_travel`·`get_latest_recommendation`의 결과가 커요.

## 테스트

- 단위: `ChatToolsTest`(도구 8개: 시계만 지나도 추천의 지난 시각에 `leaveAtPassed`·`boardAtPassed`가 붙고 `isStale`은 그대로인지, 대기가 있는 경로의 `travelMinutes`·`waitMinutes`·`totalWithWaitMinutes`가 경로 API 값과 맞는지, 도구 설명·프롬프트에 `leaveAtPassed`·`arrivesAfterAppointment`·`appointmentPassed` 규칙이 있는지, 정상, 방 멤버 아님·다른 방 값 무시, 없는 역·같은 이름 여러 개·미지원 역, 사유 코드, 시간 초과, 알 수 없는 도구·깨진 인자, stale 추천에 시각·역이 없음, 로그에 닉네임·역 이름·인자가 없음), `ChatToolCallingFlowTest`(모의 AI가 도구를 부르고 그 결과로 답하는 흐름을 도구마다 정상·권한 거부·없는 역·실패로, AI 실패와 상한 초과가 `AI_UNAVAILABLE` 안내로), `OpenAiClientTest`(도구 호출 → 결과 전송 → 최종 답(요청에 `store:false`, `previous_response_id` 없음, 2라운드 입력에 1라운드 항목 포함), 채팅 호출(도구 라운드와 도구 없는 경로)에는 설정한 채팅 effort(테스트는 `medium`), 표지판 판독에는 자기 값 `low`가 가는지, 속성 기본값이 `low`이고 환경변수가 비면 `low`가 유지되는지, 채팅 설정이 비면 `reasoning`을 안 보내는지, 추론 미지원 모델의 400 재시도(도구 없는 경로·도구 호출 경로·reasoning과 상관없는 400은 재시도 안 함), 추론 400 뒤 재시도 직전 25초 상한 확인, 한 라운드 도구 4개 상한(초과분은 `NO_DATA`), 25초 상한이 도구 실행 직전에도 확인되는지, 3라운드까지 허용·4번째 도구 요청은 `AI_UNAVAILABLE`, 도구 라운드 중 AI 실패·429, 미완료 응답).
  - #168: `ChatRunRecommendationToolTest`(도구 입력 검증: 범위·형식·없는 닉네임·역, 표시용 `conditionText` 요약 전달·공백 null·500자 초과·비문자열 거절, 검증 오류는 실행 안 함·횟수 안 씀, 한 질문 1회 `ALREADY_RUN`, 8.1 거절별 사유 코드, 결과 없음에 runId 없음, 시간 초과 `NO_DATA`·runId 없음, 비멤버), `ChatRecommendationMetaTest`(결과 있음 → meta runId / 결과 없음·8.1 오류·검증 오류·도구 시간 초과·도구 오류·답변 실패·도구 없음 → meta 없음, 중복 호출, 저장 시 meta 전달과 실시간 이벤트의 메시지에 meta).
- PostgreSQL(`ChatToolsPostgresTest`, graph·시각표 모듈): 실제 방·멤버로 방 정보·멤버, 비멤버 거부, `get_departure_plan` 값이 추천이 쓰는 시각표 경로와 같음, 경로·멤버 비교, 역 검색·이름 규칙, 혼잡도 데이터 없음, 추천을 읽다가 약속 시각이 바뀌면 `STALE`(시각 없음), 모의 AI가 `get_departure_plan` 결과로 답하는 채팅 질문 전체 흐름.
  - #168 (`ChatToolsPostgresTest`, `StompPostgresTest`): 도구가 8.1과 같은 실행을 저장해 8.2(latest)·8.3(byRun)에서 보임(조건 없는 실행과 `appliedConstraints`), 결과 없음은 빈 실행이 저장되고 runId 없음, 요약이 8.3 `conditionText`로 보이고 `interpretCondition`이 불리지 않음(`RecommendationServiceTest`에도 같은 검증), 8.1 규칙(출발역·멤버 수·약속 지남·비멤버)과 검증 오류는 실행을 남기지 않음, 채팅 질문 전체 흐름에서 `GET /rooms/{roomId}/messages`의 AI 답변 `meta`(결과 있음 / 결과 없음·오류는 null), STOMP `MESSAGE_CREATED`의 `meta`.
