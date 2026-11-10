# 중간역 추천 API (#74)

API 명세 v1.12 8장(8.1~8.3), 상세설계서 3.5 기준. `POST /api/v1/rooms/{roomId}/recommendations`, `GET .../latest`, `GET .../{runId}`.

## 흐름

1. `RoomAccess.requireMember`로 방 멤버 확인(ROOM_NOT_FOUND 404 / NOT_ROOM_MEMBER 403).
2. 요청 검사(`RecommendationRequest.validate`): 범위·`memberLimits.userId`가 방 멤버인지. 틀리면 VALIDATION_ERROR 400.
3. 사전 검사(계산 전, run을 남기지 않음): 멤버 2명 미만 `TOO_FEW_MEMBERS`(422) → 출발역 없는 멤버가 있으면 `DEPARTURE_MISSING`(422, `error.details.missingUserIds`).
4. `ConstraintResolver`: `conditionText`가 있으면 `AiClient.interpretCondition` 1회. 서버가 범위를 검증하고 역 이름→stationId(`stations.name`, 끝의 "역" 무시, 같은 이름의 역이 여럿이면 버림), 닉네임→userId(방 멤버, 중복 닉네임이면 버림)로 매핑. 매핑 못 한 항목은 버리고 POST `message`에 알린다. 같은 항목이 `filter`에도 있으면 filter 값이 우선(항목 단위). AI 실패(어떤 예외든)는 filter만 적용하고 `conditionParseFailed=true`.
5. `RecommendationEngine`: 참여자당 `shortestPathsFrom` 1회, 전원이 도달 가능한 역 = 후보. 방 멤버의 출발역은 후보에서 뺀다(어느 기준으로도 추천하지 않고, nearbyAlternatives에도 넣지 않는다). 이 출발역 제외는 서버 규칙이라 `appliedConstraints.excludeStationIds`에는 넣지 않는다. 그 필드는 사용자가 조건으로 뺀 역만 담는다(명세 v1.12 §8.1). 제약(참여자 **한 명당** 최대 시간·환승·요금, 개인별 한도, 제외 역)을 모두 통과한 역만 남긴다. 요금은 `FareCalculator.fareOf`(경로 API와 같은 `GraphFareCalculator`)로 계산한다.
6. 기준별 최적역(TIME / FARE / DISTANCE, 명세 8.1 표). 허용 폭은 설정 `linkro.recommendation.fare-tolerance-won`(100), `distance-tolerance-m`(1000).
7. 결과마다 `participantTimes`(혼잡도는 `CongestionLookup`, 출발 예정 시각 = 약속 시각 − travelMinutes, 약속이 없으면 요청 시각), `nearbyAlternatives`(앞·뒤 한 정거장, 후보 집합에 있는 역만), 설명 문장.
8. 설명: `AiClient.explainRecommendation`을 추천 1건당 **1회**만 호출한다(결과가 없으면 호출 없음). 요청에 모든 기준(`ExplanationRequest.items`: criterion, stationName, facts)을 담고, 응답(`ExplanationResult.texts`)은 기준별 문장이다. 기준마다 문장 안 숫자를 그 기준의 facts와 대조해서 다르거나 비었거나 빠졌으면 그 기준만 템플릿 + `explanationSource: TEMPLATE`, 호출 전체가 실패하면 모든 기준이 템플릿이다.
9. `RecommendationRepository.save`가 run과 결과 행을 짧은 트랜잭션 하나로 저장. 계산·AI 호출은 트랜잭션 밖.
10. 저장 뒤 `RecommendationCreatedEvent`를 Spring `ApplicationEventPublisher`로 발행(결과가 없는 run도). WebSocket `RECOMMENDATION_CREATED`는 이 이벤트를 듣는 쪽(11장)이 보낸다. 리스너 예외는 로그만 남기고 응답에 영향을 주지 않는다.

## 저장 (V6 그대로, 마이그레이션 없음)

- `recommendation_runs.criteria` = `{conditionText, filter, appliedConstraints, conditionParseFailed, appointmentAt, participants:[{userId, departureStationId}]}`.
- `recommendation_results`: 기준당 1행, run당 최대 3행. `metric_value` = TIME 분(가장 오래 걸리는 사람) / FARE 원(총요금) / DISTANCE m(이동거리 합). `trips` = participantTimes 스냅샷(userId·nickname·**departureStationId(ID만)**·travelMinutes·…·departureAt·congestion·segments, ERD v1.13). 응답의 `departureStation{id,name}`은 조회 때 `departureStationId`로 이름을 채운다(응답 모양은 그대로). 이전 형식(`departureStation` 객체로 저장된 행)도 읽는다. `congestion.pct`는 정수이고, `93.0`처럼 소수로 저장된 이전 행도 읽는다, `nearby` = nearbyAlternatives, `reason`/`reason_source` = explanation/explanationSource.
- `metrics`(averageMinutes, maxDifferenceMinutes, maxMinutes, averageFare, totalTransfers)는 저장하지 않고 `trips`에서 계산한다.

## 조회

- `latest`: `ORDER BY created_at DESC, id DESC LIMIT 1`. 없으면 `data: null`.
- `isStale`/`staleReasons`는 조회할 때 계산(`StaleCheck`): `appointmentAt` Instant 비교(null=null 같음, null↔값 다름) → `APPOINTMENT_CHANGED`, 현재 멤버 `(userId, departureStationId)` 집합과 `criteria.participants` 비교(순서 무시, null 출발역은 null과만 같음) → `MEMBERS_CHANGED`. 최근 run이 아니면 둘 다 `null`(8.3).
- `message`는 저장하지 않는다. 결과 행이 없으면 "조건에 맞는 중간역이 없습니다. 조건을 완화해 보세요.", `conditionParseFailed`이면 "자연어 조건을 해석하지 못해 적용하지 못했습니다"(명세대로 끝에 마침표 없음)를 조회 때 다시 만든다(둘 다면 앞 문장 뒤에 마침표를 붙여 이어 붙임). 매핑하지 못한 항목 안내는 POST 응답에만 있다.
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
