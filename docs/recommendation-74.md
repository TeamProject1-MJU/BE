# 중간역 추천 API (#74)

API 명세 v1.12 8장(8.1~8.3), 상세설계서 3.5 기준. `POST /api/v1/rooms/{roomId}/recommendations`, `GET .../latest`, `GET .../{runId}`.

## 흐름

1. `RoomAccess.requireMember`로 방 멤버 확인(ROOM_NOT_FOUND 404 / NOT_ROOM_MEMBER 403).
2. 요청 검사(`RecommendationRequest.validate`): 범위·`memberLimits.userId`가 방 멤버인지. 틀리면 VALIDATION_ERROR 400.
3. 사전 검사(계산 전, run을 남기지 않음): 멤버 2명 미만 `TOO_FEW_MEMBERS`(422) → 출발역 없는 멤버가 있으면 `DEPARTURE_MISSING`(422, `error.details.missingUserIds`) → 약속 시각 직전 60분(설정값) 안에 도착하는 열차가 전 노선에 하나도 없으면 `NO_SERVICE_TIME`(422, #96, 아래 "운행하지 않는 시간대").
4. **방 단위 동시 요청 보호(#98)**: 위 검사를 모두 통과한 뒤, 계산을 시작하기 직전에 `RecommendationInFlightGuard.tryAcquire(roomId)`를 건다. 아래 "동시 요청 보호" 참고.
5. `ConstraintResolver`: `conditionText`가 있으면 `AiClient.interpretCondition` 1회. 서버가 범위를 검증하고 역 이름→stationId(`stations.name`, 끝의 "역" 무시, 같은 이름의 역이 여럿이면 버림), 닉네임→userId(방 멤버, 중복 닉네임이면 버림)로 매핑. 매핑 못 한 항목은 버리고 POST `message`에 알린다. 같은 항목이 `filter`에도 있으면 filter 값이 우선(항목 단위). AI 실패(어떤 예외든)는 filter만 적용하고 `conditionParseFailed=true`.
6. `RecommendationEngine`: 이동 시간은 열차 시각표로 계산한다(#96, 아래 "열차 시각표 기반 이동 시간"). 시각표가 없을 때만 참여자당 `shortestPathsFrom` 1회(고정 소요시간)이고, 전원이 도달 가능한 역 = 후보. 방 멤버의 출발역은 후보에서 뺀다(어느 기준으로도 추천하지 않고, nearbyAlternatives에도 넣지 않는다). 이 출발역 제외는 서버 규칙이라 `appliedConstraints.excludeStationIds`에는 넣지 않는다. 그 필드는 사용자가 조건으로 뺀 역만 담는다(명세 v1.12 §8.1). 제약(참여자 **한 명당** 최대 시간·환승·요금, 개인별 한도, 제외 역)을 모두 통과한 역만 남긴다. 요금은 `FareCalculator.fareOf`(경로 API와 같은 `GraphFareCalculator`)로 계산한다.
7. 기준별 최적역(TIME / FARE / DISTANCE, 명세 8.1 표). 허용 폭은 설정 `linkro.recommendation.fare-tolerance-won`(100), `distance-tolerance-m`(1000).
8. 결과마다 `participantTimes`(혼잡도는 `CongestionLookup`, 출발 예정 시각은 TIMETABLE이면 첫 열차 출발 − 역 접근 시간, ESTIMATE면 약속 시각 − travelMinutes(약속이 없으면 요청 시각)), `nearbyAlternatives`(앞·뒤 한 정거장, 후보 집합에 있는 역만), 설명 문장.
9. 설명: `AiClient.explainRecommendation`을 추천 1건당 **1회**만 호출한다(결과가 없으면 호출 없음). 요청에 모든 기준(`ExplanationRequest.items`: criterion, stationName, facts)을 담고, 응답(`ExplanationResult.texts`)은 기준별 문장이다. 기준마다 문장 안 숫자를 그 기준의 facts와 대조해서 다르거나 비었거나 빠졌으면 그 기준만 템플릿 + `explanationSource: TEMPLATE`, 호출 전체가 실패하면 모든 기준이 템플릿이다.
10. `RecommendationRepository.save`가 run과 결과 행을 짧은 트랜잭션 하나로 저장. 계산·AI 호출은 트랜잭션 밖.
11. 저장 뒤 `RecommendationCreatedEvent`를 Spring `ApplicationEventPublisher`로 발행(결과가 없는 run도). WebSocket `RECOMMENDATION_CREATED`는 이 이벤트를 듣는 쪽(11장)이 보낸다. 리스너 예외는 로그만 남기고 응답에 영향을 주지 않는다.

## 동시 요청 보호 (#98)

- **목적**: 같은 방에서 추천 생성이 진행 중이면 새 요청은 계산·AI 호출·저장 없이 거절한다. 연타나 여러 멤버가 동시에 눌러도 AI 호출과 run이 한 번만 생긴다.
- **응답**: 409 `RECOMMENDATION_IN_PROGRESS`, "추천을 계산하는 중입니다. 잠시 후 다시 시도해 주세요."
- **거는 위치**
  - 방 멤버 확인, 요청 검사(400), `TOO_FEW_MEMBERS`, `DEPARTURE_MISSING`, `NO_SERVICE_TIME` 뒤에 건다.
  - 조건 해석 AI·계산·설명 AI·저장·응답 조립 전체를 감싼다.
  - 검사 실패는 가드를 점유하지 않는다.
- **해제**
  - `finally`에서 해제한다(성공, 비즈니스 예외, 런타임 예외 모두).
  - 이벤트 발행 실패는 기존대로 `publish`가 로그만 남기므로 해제에 영향이 없다.
- **최대 점유 시간**: 설정 `linkro.recommendation.in-progress-max-seconds`(기본 60, 양수)가 지나면 점유가 만료되고, 다음 요청이 가져간다(WARN 로그).
  - 점유마다 고유 토큰이 있어서, 만료된 이전 요청이 늦게 해제해도 새 점유는 풀리지 않는다.
- **조회 API**(`GET latest`, `GET {runId}`)는 가드와 관계없이 동작한다.
- **한계**: 메모리(`ConcurrentHashMap`) 기반이라 서버 1대일 때만 보호된다. 서버가 여러 대가 되면 DB나 외부 락이 필요하다.

## AI 지연 대응 (#99)

- **구간별 소요 시간 로그**: 추천 생성이 끝나면 INFO 한 줄을 남긴다.
  - 예: `Recommendation timing: parse=3006ms evaluate=1ms explain=3001ms save=0ms total=6041ms (members 2, results 3)`
  - parse = 조건 해석(AI), evaluate = 후보 계산, explain = 설명(AI), save = 저장이다. total은 요청 시작부터 응답 조립까지다.
  - 조건 문구, 닉네임, 키는 넣지 않는다.
  - 실패한 요청은 이 줄을 남기지 않는다.
- **조건 해석 호출 조건**: `conditionText`가 없거나 공백이면 `RecommendationRequest.text()`가 null이고, `ConstraintResolver`는 `interpretCondition`을 부르지 않는다(parse≈0ms).
- **설명 AI 읽기 타임아웃**: `OPENAI_EXPLAIN_READ_TIMEOUT`(`linkro.ai.openai.explain-read-timeout`, 기본 6s)은 `explainRecommendation`에만 적용한다. 실제 응답 시간은 타이밍 로그의 explain 값을 보고 조정한다.
  - 넘으면 `AI_UNAVAILABLE` → 기준마다 템플릿 문장(`explanationSource: TEMPLATE`)이고, 응답은 정상(200)이다.
  - 조건 해석·채팅은 `OPENAI_READ_TIMEOUT`(8s), 표지판은 `OPENAI_VISION_READ_TIMEOUT`(4s) 그대로다.
- **응답 뒤 비동기 설명 갱신은 검토했으나 제외했다.** 설명이 늦게 채워지면 결과 행 갱신(스키마·상태 필드)과 클라이언트 알림(WebSocket 이벤트)이 새로 필요하다.

## 저장 (V6 그대로, 마이그레이션 없음)

- `recommendation_runs.criteria` = `{conditionText, filter, appliedConstraints, conditionParseFailed, appointmentAt, participants:[{userId, departureStationId}], unreachableByTrain}`. `unreachableByTrain`(#96)은 열차로 약속 시각까지 도착할 수 없어 뺀 역 수이고, 이전 run에는 없다(null).
- `recommendation_results`: 기준당 1행, run당 최대 3행. `metric_value` = TIME 분(가장 오래 걸리는 사람) / FARE 원(총요금) / DISTANCE m(이동거리 합). `trips` = participantTimes 스냅샷(userId·nickname·**departureStationId(ID만)**·travelMinutes·…·departureAt·congestion·segments, ERD v1.13, #96부터 boardingTrain·legs·source 추가. 이전 행은 null·[]·null로 읽는다). 응답의 `departureStation{id,name}`은 조회 때 `departureStationId`로 이름을 채운다(응답 모양은 그대로). 이전 형식(`departureStation` 객체로 저장된 행)도 읽는다. `congestion.pct`는 정수이고, `93.0`처럼 소수로 저장된 이전 행도 읽는다, `nearby` = nearbyAlternatives, `reason`/`reason_source` = explanation/explanationSource.
- `metrics`(averageMinutes, maxDifferenceMinutes, maxMinutes, averageFare, totalTransfers)는 저장하지 않고 `trips`에서 계산한다.

## 조회

- `latest`: `ORDER BY created_at DESC, id DESC LIMIT 1`. 없으면 `data: null`.
- `isStale`/`staleReasons`는 조회할 때 계산(`StaleCheck`): `appointmentAt` Instant 비교(null=null 같음, null↔값 다름) → `APPOINTMENT_CHANGED`, 현재 멤버 `(userId, departureStationId)` 집합과 `criteria.participants` 비교(순서 무시, null 출발역은 null과만 같음) → `MEMBERS_CHANGED`. 최근 run이 아니면 둘 다 `null`(8.3).
- `message`는 저장하지 않는다. 결과 행이 없으면 "조건에 맞는 중간역이 없습니다. 조건을 완화해 보세요."(열차로 도착할 수 없어 뺀 역이 있으면 그 앞에 "약속 시각까지 열차로 도착할 수 없는 역 N곳을 제외했습니다."), `conditionParseFailed`이면 "자연어 조건을 해석하지 못해 적용하지 못했습니다"(명세대로 끝에 마침표 없음)를 조회 때 다시 만든다(둘 다면 앞 문장 뒤에 마침표를 붙여 이어 붙임). 매핑하지 못한 항목 안내는 POST 응답에만 있다.
- `GET .../{runId}`: 다른 방의 run이나 없는 run은 `RUN_NOT_FOUND`(404).

## 명세가 정하지 않아 이 구현이 고른 것

| 항목 | 선택 |
| --- | --- |
| 멤버 출발역 | 방 멤버의 출발역은 모든 기준의 추천 후보와 최적역 옆 nearbyAlternatives에서 뺀다. 출발역 제외는 `appliedConstraints.excludeStationIds`에 넣지 않는다(그 필드는 사용자 조건만, 명세 v1.12 §8.1) |
| 검사 순서 | 요청 값 검사(400) → `TOO_FEW_MEMBERS` → `DEPARTURE_MISSING` |
| 소요 분 | `totalSeconds`를 분으로 올림(40분 한도가 40분 59초를 통과시키지 않게) |
| 제약 단위 | 한도(maxMinutes/maxTransfers/maxFareWon)는 1인당 기준이다. 참여자 한 명 한 명이 각자 한도를 넘지 않아야 후보가 된다(명세 예시: 한도 2000원, 대안역 총요금 4650원) |
| 요청 범위 | maxMinutes 1~300, maxTransfers 0~10, maxFareWon 1~100000, conditionText 500자 이하. filter가 범위 밖이면 400, AI 값이 범위 밖이면 버림 |
| `appliedConstraints` | 항상 5개 필드(maxMinutes, maxTransfers, maxFareWon, excludeStationIds, memberLimits)를 내린다. 없으면 null / [] |
| 같은 항목 우선순위 | 항목(필드) 단위: filter에 값이 있으면 AI 값은 쓰지 않는다(`memberLimits`도 목록 단위) |
| PREV / NEXT | 같은 노선에서 이웃 노드의 seq가 낮으면 PREV, 높으면 NEXT. 2호선 순환의 이음매는 두 이웃이 같은 쪽이라 먼 쪽을 반대로 본다. 분기점은 이웃을 모두 남긴다 |
| 혼잡도 | `CongestionLookup` 빈(`linkro.subway.congestion`)을 쓴다. 데이터가 없으면 `congestion: null`, 있으면 `{pct, level, levelLabel}`. `pct`는 경로 API `RouteCongestionData.pct`와 같은 정수(`RouteSearchService`처럼 `CongestionResult.pct().intValue()`; 조회 값은 이미 표시용 정수) |
| 설명 호출 수 | 명세 8.1대로 추천 1건당 1회. 한 번에 최대 3개 기준의 문장을 받는다(`AiClient` 시그니처를 이에 맞게 바꿈) |
| 설명 숫자 검사 | 문장의 모든 숫자가 facts 값과 같아야 한다. 역 이름과 `N호선`은 먼저 지운다 |
| 이벤트 | 클래스 `RecommendationCreatedEvent(roomId, runId, requestedBy, createdAt)`. 이름은 팀 문서(WS 메시지 `RECOMMENDATION_CREATED`)를 따랐다 |

## 열차 시각표 기반 이동 시간 (#96)

이동 시간·출발 시각·대기 시간은 #94의 열차 시각표(`TrainTimetable`)로 계산한다. 고정값(정차 30초, 환승 대기 120초, `travel_seconds`)은 시각표를 쓸 수 없을 때만 쓴다.

- 코드
  - `TimetableRouter`(`com.mju.linkro.subway.graph`, `TimetableRouteFinder` 구현)
  - `RecommendationEngine`
- 활성화: `linkro.subway.module=graph`이고 `linkro.subway.timetable.enabled`가 false가 아니면(기본 true) 시각표를 쓴다.

### 계산

- **약속 시각 T가 있을 때**: 후보역 c마다 "T까지 c에 도착"을 조건으로 거꾸로(latest-departure) 시간 의존 다익스트라를 한 번 돌려, 각 멤버 출발역의 최늦 출발 시각을 구한다.
  - 그 출발 시각에 나서서 앞으로(earliest-arrival) 다시 탐색해 가장 일찍 도착하는 경로를 쓴다. 단, 거꾸로 경로보다 환승이 많지 않을 때만 쓴다. 1분 빠르려고 두 번 갈아타지 않게 하기 위해서다(강남→홍대입구 22시는 2호선 직행).
    - 예: 장암→모란 22시는 같은 7호선 열차로 군자에서 더 이른 5호선·8호선에 이어져 21:41에 도착한다.
  - **이동 시간 = 실제 도착 − (첫 열차 출발 − 역 접근 시간)**, 분 단위 올림. T 전에 도착해서 기다리는 시간은 이동 시간이 아니라 `slackSeconds`다.
  - 후보 점수·`maxMinutes` 필터·정렬은 이 이동 시간을 쓴다.
  - 후보 탐색은 서로 독립이라 병렬로 돌리고, 결과는 역 id 순으로 모은다.
- **약속 시각이 없을 때**: 멤버마다 요청 시각에 출발하는 앞으로(earliest-arrival) 탐색을 한 번 돌린다.
  - 이동 시간 = 도착 − (첫 열차 출발 − 역 접근 시간). 첫 승강장 대기는 빼고, 그 열차를 타는 시점부터 센다. `slackSeconds`는 null이다.
  - 그 운행일의 막차가 지났으면 다음 운행일 첫차부터 탄다.
- **승차 간선**: 실제 열차 한 대를 탄다.
  - 거꾸로는 다음 정차역에 제때 닿는 가장 늦은 열차(`lastDepartureArriving`), 앞으로는 다음 열차(`nextDeparture`)다.
  - 같은 열차를 계속 타면 대기가 없다. 열차가 바뀌면 다음 열차 출발까지가 대기다.
  - 정차 시간은 시각표의 도착·출발 차이가 그대로 들어간다(dwell 30초를 더하지 않는다).
- **환승 간선**: 환승 도보 시간(`station_connections.travel_seconds`, 기존 값) 뒤에 갈아탈 노선의 다음 열차를 기다린다. 고정 120초나 평균 대기는 쓰지 않는다.
- **분기점(#56)**: `BranchRules`가 열차를 바꿔야 한다고 보는 팔 조합(예: 강동 MACHEON ↔ MAIN+)이면 `branch-transfers`의 도보 시간(성수 19, 신도림 68, 강동 16초)을 더하고 환승 1회로 센다.
  - 같은 승강장에서 같은 노선의 다른 열차로 바꾸는 것(2호선 성수행 열차가 끝나 다음 열차로, 단축 운행)은 대기만 생기고 환승으로 세지 않는다.
  - 이때 대기는 legs에 보이고, RoutePath에서는 다음 RIDE step 시간에 들어간다.
- **되돌아가기 금지**: A→B를 탄 뒤 바로 B→A를 타는 경로는 만들지 않는다. 기다리는 동안 반대 방향 열차로 왕복해서 분기점 환승을 피하는 경로를 막기 위해서다.
- **같은 열차 유지**: 탐색이 같은 출발 시각을 주는 다른 열차를 고른 경우가 있다(마천→여의도: 마천 열차가 방화까지 가는데 강동에서 다음 열차로 갈아탐). 그래서 경로를 만들 때, 타고 있던 열차가 다음 구간을 달리고 더 늦지 않게 도착하면 그 열차를 그대로 탄다.
- **운행일**: 03:00 경계(`TimetableClock`, #94)다.
  - 약속 시각 00:00~02:59는 전날 운행일이다. 예: 01:30 약속은 전날 막차(평일 01:14까지)로 도착해서 기다리는 경로로 계산되고, 기다림은 `slackSeconds`다.
  - 03:00 이후 첫차(05:11:30) 전 약속은 그날 열차로 도착할 수 없다. 직전 60분 안에 도착하는 열차가 없으면 `NO_SERVICE_TIME`이고, 그 밖에는 모든 역이 도착 불가로 빠진다.
- **RoutePath**: 시각표 경로도 같은 `RoutePath`로 만든다. 요금·거리·환승 수·segments·혼잡도는 기존과 같은 방식으로 계산된다.
  - TRANSFER step은 하차부터 다음 승차까지(도보 + 대기)다.
  - step 합 = 첫 승차부터 마지막 하차까지 = `totalSeconds`(불변식 유지)다.
  - 정차역 `offsetSec`은 첫 열차 출발 기준이고, 혼잡도는 첫 열차 출발 시각으로 조회한다.

### 운행하지 않는 시간대 (`NO_SERVICE_TIME`)

- **조건**: 약속 시각 T 직전 창 [T − 창, T] 안에 도착하는 열차가 전 노선에 하나도 없으면 계산하지 않고 거절한다.
  - 창은 설정 `linkro.subway.timetable.no-service-window-minutes`(기본 60분)다.
  - 열차 도착은 그 운행일의 첫 도착부터 마지막 도착까지로 본다. T의 운행일과 그 전날 운행일을 모두 본다.
- **응답**: 422 `NO_SERVICE_TIME`, "해당 시간대에는 지하철이 운행하지 않습니다. 약속 시간을 바꿔 주세요."
  - run을 남기지 않고 AI도 호출하지 않는다.
  - 시각표가 없을 때(mock, `enabled=false`)는 판단할 수 없어 거절하지 않는다.
- **시각표 경계**: 첫 출발은 모든 요일 05:11:30이고 첫 도착은 그 직후다. 마지막 도착은 평일 25:14(2호선 도림천 5907), 토·일·공휴일 24:18:30(1호선 청량리 K212)이다. 기본 60분이면 다음과 같다.

| 약속 | 결과 |
| --- | --- |
| 평일 밤 다음 날 01:30 | 정상(00:30~01:30에 막차 도착) |
| 평일 밤 다음 날 02:30, 04:00 | `NO_SERVICE_TIME` |
| 05:30 | 정상(첫차 도착) |
| 토요일 밤 다음 날(일요일) 01:00 | 정상(00:18:30 막차 도착) |
| 일요일 04:00 | `NO_SERVICE_TIME` |

- 평일 밤은 02:14 이후, 토·일·공휴일 밤은 01:18:30 이후부터 첫차 도착 전까지 거절한다.

### 도착할 수 없을 때와 폴백

- **도착 불가**: 시각표가 있는데 T까지 도착하는 열차가 없는 멤버가 한 명이라도 있으면 그 후보역은 **도착 불가로 제외**한다. 폴백하지 않는다.
  - 모든 후보가 빠지면 기존 실패 응답(결과 없음)이다.
  - `message` = "약속 시각까지 열차로 도착할 수 없는 역 N곳을 제외했습니다. 조건에 맞는 중간역이 없습니다. 조건을 완화해 보세요."
- **폴백(고정 소요시간, `source = ESTIMATE`)은 두 경우뿐이다.**
  1. 시각표가 없다: `linkro.subway.module=mock`이거나 `linkro.subway.timetable.enabled=false`
  2. 약속 시각(약속이 없으면 요청 시각)의 운행일 요일 유형에 시각표 열차가 하나도 없다. 지금 파일은 세 요일 유형 모두 있어서 실제로는 생기지 않는다.
- **폴백 로그**: 폴백하면 요청당 WARN `Recommendation used fixed-cost estimates for N member(s): <이유>`를 남긴다.
- **소요 시간 로그**: 매 요청 INFO `Recommendation evaluated (TIMETABLE|ESTIMATE): M members, C candidates, U unreachable by train in T ms`.

### 응답 필드 (participantTimes, 추가만)

| 필드 | TIMETABLE | ESTIMATE / 이전 실행 |
| --- | --- | --- |
| `departureAt` | 첫 열차 출발 − 역 접근 시간 | 약속 시각 − travelMinutes(없으면 요청 시각) |
| `arriveAt` | 마지막 열차 도착(travelMinutes = arriveAt − departureAt, 분 올림) | departureAt + 그래프 경로 초 |
| `slackSeconds` | 약속 시각 − arriveAt. 약속이 없으면 null | 약속 시각 − arriveAt(0~59초). 약속이 없으면 null |
| `boardingTrain` | `{lineId, lineName, stationId, stationName, direction, departureAt, terminal, trainCode}` | null |
| `legs[]` | 열차별 `{lineId, lineName, trainCode, terminal, direction, fromStationName, toStationName, boardAt, alightAt, stationCount, walkSec, transferWaitSec}` | [] |
| `source` | `TIMETABLE` | `ESTIMATE`(이전 실행은 null) |

- 이전 실행에는 `arriveAt`·`slackSeconds`가 없다(null).

- `direction`은 `segments.direction`과 같은 UP/DOWN/INNER/OUTER/LOOP다.
- `terminal`·`trainCode`는 시각표 표기다.
- `transferWaitSec` = 승차 − (이전 하차 + `walkSec`), 첫 열차는 0이다.

### 설정

| 프로퍼티 | 기본값 | 의미 |
| --- | --- | --- |
| `linkro.recommendation.in-progress-max-seconds` | 60 | 방 단위 추천 생성 가드의 최대 점유 시간(#98) |
| `linkro.subway.timetable.enabled` | true | false면 시각표를 적재하지 않고 기존 고정 소요시간으로 계산한다(시연 직전 비상 복귀용) |
| `linkro.subway.timetable.station-access-seconds` | 0 | 출발해서 첫 승강장에 서기까지. 역별 자료가 없어 0이다 |
| `linkro.subway.timetable.no-service-window-minutes` | 60 | 약속 직전 이 시간 안에 도착하는 열차가 없으면 `NO_SERVICE_TIME`(양수여야 하며, 아니면 기동 실패) |

### V15

- 시각표 중앙값과 30초 이상 차이 나는 RIDE 간선 20개(방향별)의 `travel_seconds`를 시각표 중앙값으로 바꿨다.
  - 대상은 `docs/timetable-94.md`의 리포트 기준이다.
  - 고정 소요시간(폴백과 경로 API의 내부 경로)만 이 값을 읽는다. 정차 30초는 그대로다.
- 마이그레이션은 대상마다 V11 값을 가진 간선이 정확히 하나인지 확인하고, 갱신 행 수가 20이 아니면 중단한다.

### 검증 (postgresTest, V1~V15)

- `TimetableRecommendationPostgresTest`
  - 평일 08·13·22시와 토요일 14시에 네 그룹(2~3명)으로 추천을 만든다.
  - 모든 멤버가 TIMETABLE이고 폴백 0인지 확인한다.
  - 모든 열차 구간이 시각표 파일의 같은 열차 번호·출발·도착 시각과 일치하는지 확인한다.
  - 이동 시간 = 도착 − 출발, 여유 = 약속 − 도착인지 확인한다.
  - 01:30, `NO_SERVICE_TIME`(위 표의 여섯 경우), 저장 후 다시 읽기, OpenAPI도 확인한다.
- `TimetableDisabledRecommendationPostgresTest`: `enabled=false`이면 시각표 빈이 없고, travelMinutes = 최단경로 초 올림, departureAt = 약속 − 분, ESTIMATE다.
- `RecommendationTimetableReportPostgresTest`: 10개 구간 × 4개 시간대 리포트와 소요 시간이다(`build/reports/recommendation-96.md`).
- **소요 시간**(엔진만, 후보 234~238곳, 멤버 2~6명, 로컬 측정이라 실행마다 다름)
  - 시각표 14~24 ms(앞으로 다시 탐색 포함), 고정 소요시간 2~7 ms
  - `create` 전체(mock AI) 17~35 ms
  - 추천은 카카오를 호출하지 않는다.

