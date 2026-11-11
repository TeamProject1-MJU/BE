# 지하철 그래프·다익스트라·요금 구현 (#69)

#60 인터페이스의 실제 구현이다. `SubwayRouteFinder`(최소 시간 경로)와 `FareCalculator`(예상 요금)를 V10~V12 데이터로 계산한다. 혼잡도(`CongestionLookup`)는 아직 mock이다.

- 패키지: `com.mju.linkro.subway.graph`
- 활성화: `linkro.subway.module=graph`. 기본값은 mock이고, 운영 전환은 환경변수 `LINKRO_SUBWAY_MODULE=graph`로 한다.

## 그래프 모델

| 구성 | 출처 | 비고 |
| --- | --- | --- |
| 노드 | `station_lines` (역×노선) | `is_supported = false` 역은 노드를 만들지 않고, 그 노드에 닿는 간선도 버린다 |
| RIDE 간선 | `station_connections` RIDE | 방향 있는 간선. 비용 = `travel_seconds` + `dwell-seconds` |
| TRANSFER 간선 | `station_connections` TRANSFER | 비용 = `travel_seconds`(환승소요시간) + `transfer-wait-seconds` |
| 분기 환승 | 설정 `branch-transfers` | 같은 노선 분기점의 열차 갈아타기. 아래 "분기 환승" 참고 |

- **로딩**: `SubwayGraphLoader`가 `subway_lines`, `stations`, `station_lines`, `station_connections`를 JDBC로 한 번 읽는다. `SubwayGraph.of(data)`가 그 데이터로 불변 그래프를 만든다. 그래프 생성은 DB와 분리돼 있어서 손으로 만든 데이터로 단위 테스트할 수 있다.
- **시작 검증**(graph 모드):
  - 노드나 간선이 0개면 `Subway graph is empty (...)`로 시작이 실패한다.
  - 개수는 INFO 한 줄(`Subway graph loaded: 277 nodes, 536 RIDE and 78 TRANSFER edges, 240 stations in N ms`)로 남긴다.
  - RIDE가 서로 다른 노선을 잇거나, TRANSFER가 "같은 역·다른 노선"이 아니면 WARN을 남긴다.
- `travel_seconds`는 명세 14장대로 정차 시간을 더하지 않은 원본 값이다. 대신 그래프 비용에 정차·대기 보정을 더한다(기본 dwell 30초, 환승 대기 120초, 분기 환승 대기 120초). 아래 "시간 보정 기본값" 참고.

> #96 이후 추천은 이 고정 비용 대신 열차 시각표로 계산한다(`docs/recommendation-74.md`). 이 그래프 비용은 경로 API의 내부 경로와 추천 폴백(`linkro.subway.timetable.enabled=false` 등)에 쓰인다. V15가 RIDE 간선 20개의 `travel_seconds`를 시각표 중앙값으로 바꿨다.

## 탐색 규칙

- **알고리즘**: 다익스트라이고, 상태는 (노드, 직전 팔)이다. 비용은 위 간선 비용과 분기 환승 비용의 합이다.
- **출발**: 출발역의 모든 노드가 비용 0에서 시작한다. 출발역에서는 어느 노선이든 환승 비용 없이 탄다.
- **도착**: 도착역의 아무 노드에 닿으면 끝나고, 마지막에 추가 비용은 없다.
- **동점 처리**: 비용이 같으면 환승이 적은 쪽, 그다음은 노드 id(그다음 팔 이름) 순으로 고른다. 같은 입력은 항상 같은 경로를 낸다.
- **#60 계약**:
  - 같은 역은 빈 경로다.
  - 도달할 수 없으면 `shortestPath`는 `ROUTE_NOT_FOUND`를 던지고, `pathTo`는 예외 없이 `Optional.empty()`를 돌려준다.
  - `reachableStationIds`에는 출발역이 포함된다.
  - 없는 역은 `STATION_NOT_FOUND`, 미지원 역은 `STATION_UNSUPPORTED`다.
  - `pathTo(x)`는 같은 탐색 결과를 쓰므로 `shortestPath(출발, x)`와 같다.
- **노드가 없는 역**: 지원 역인데 노드가 하나도 없으면(시드에는 없음) 어디로도 갈 수 없고 같은 역 경로도 `ROUTE_NOT_FOUND`다.

## 분기 환승 (#56)

분기점 역은 노드가 하나(MAIN)다(#48). 그래서 같은 노선이라도 열차를 갈아타야 하는 경로에는 분기 환승을 더하고 `transferCount`를 1 늘린다.
- **분기점**: MAIN 노드 중 같은 노선의 다른 branch 노드와 RIDE로 이어진 노드다.
- **팔(arm)**: 분기점에서 이웃 노드가 있는 쪽이다. 이웃의 branch 코드(SEONGSU, SINJEONG, MACHEON, EUNGAM_LOOP)이거나, 본선이면 seq가 분기점보다 작은 쪽이 `MAIN-`, 큰 쪽이 `MAIN+`다.
- **판정**: 들어온 팔과 나가는 팔의 조합이 `BranchRules`의 "한 열차로 통과 가능한 조합" 표에 없으면 환승이다.

| 분기점 | 통과 가능(환승 아님) | 환승 |
| --- | --- | --- |
| 2호선 성수 | MAIN- ↔ MAIN+ (순환 본선 통과) | 본선 ↔ SEONGSU |
| 2호선 신도림 | MAIN- ↔ MAIN+ | 본선 ↔ SINJEONG |
| 5호선 강동 | MAIN-(방화 쪽) ↔ MAIN+(하남검단산 쪽), MAIN- ↔ MACHEON | MACHEON ↔ MAIN+ |
| 6호선 응암 | MAIN+ ↔ EUNGAM_LOOP, EUNGAM_LOOP ↔ EUNGAM_LOOP | 없음(순환과 본선이 한 열차) |

- **팔이 없는 경우**: 출발역이 분기점이면 직전 팔이 없어서 환승이 아니다(예: 성수→용답, 신도림→까치산). 일반 TRANSFER 간선으로 분기점에 들어와도 새로 타는 것이라 직전 팔이 없다.
- **표에 없는 분기점**: 데이터에 있으면 시작할 때 WARN(`Subway junction ... is not in the branch rule table`)을 남기고 통과(환승 아님)로 처리한다.
- **비용**: 분기 환승 비용 = 환승 시간 + `branch-transfer-wait-seconds`. 환승 시간과 거리는 `branch-transfers` 설정을 쓴다.
  - 기본값은 서울교통공사 환승역거리 소요시간 정보(공공데이터포털 15044419, 2026-08-31)의 같은 노선 환승 행이다.
  - 이 행들은 V11에서 자기 자신 간선 금지(ck_conn_not_self) 때문에 빠졌다. 마이그레이션은 추가하지 않았다.

| 분기점 | 노선 | 초 | 미터 |
| --- | --- | --- | --- |
| 성수 | 2 | 19 | 23 |
| 신도림 | 2 | 68 | 81 |
| 강동 | 5 | 16 | 19 |

- **설정에 없는 분기점**: 표에서 환승이 생길 수 있는 분기점이 `branch-transfers`에 없으면 `default-branch-transfer-seconds`(기본 120초, 거리 0)를 쓰고 시작할 때 WARN을 남긴다. 응암은 서로 다른 팔 사이에 환승이 없어서 WARN 대상이 아니다.
- **한계**: 공공데이터에는 걷는 시간만 있고 지선 열차 대기 시간은 없다. 기본값 `branch-transfer-wait-seconds=120`으로 일괄 보정만 한다.

## 설정 프로퍼티 (graph 모드)

기본값은 `src/main/resources/subway-graph-defaults.properties`에 있고, 애플리케이션 설정이나 환경변수로 덮어쓴다.

| 프로퍼티 | 기본값 | 의미 |
| --- | --- | --- |
| `linkro.subway.module` | (없음 = mock) | `graph`면 이 구현을 쓴다 |
| `linkro.subway.congestion` | (없음 = mock) | 혼잡도 mock 사용 여부. 실제 구현이 붙으면 바꾼다 |
| `linkro.subway.graph.dwell-seconds` | 30 | RIDE 간선마다 더하는 정차 시간 |
| `linkro.subway.graph.transfer-wait-seconds` | 120 | 일반 환승마다 더하는 열차 대기 |
| `linkro.subway.graph.branch-transfer-wait-seconds` | 120 | 분기 환승마다 더하는 열차 대기 |
| `linkro.subway.graph.default-branch-transfer-seconds` | 120 | 설정에 없는 분기점의 환승 시간(거리 0) |
| `linkro.subway.graph.branch-transfers[i].station/line/seconds/meters` | 위 표 3건 | 분기점별 환승 시간·거리 |
| `linkro.subway.fare.base-fare` | 1550 | 기본 요금(원) |
| `linkro.subway.fare.base-distance-m` | 10000 | 기본 거리 |
| `linkro.subway.fare.mid-limit-m` | 50000 | 5 km 단위 구간의 끝 |
| `linkro.subway.fare.mid-step-m` | 5000 | 10~50 km 구간 단위 |
| `linkro.subway.fare.long-step-m` | 8000 | 50 km 초과 구간 단위 |
| `linkro.subway.fare.step-fare` | 100 | 구간당 추가 요금 |

- graph 값은 음수면 시작이 실패한다.
- fare 값은 모두 양수여야 하고 `mid-limit-m ≥ base-distance-m`이어야 하며, 어기면 시작이 실패한다.
- 요금 경계값은 mock과 같다: 0/10,000 m → 1,550, 10,001/15,000 m → 1,650, 15,001 m → 1,750, 50,000 m → 2,350, 50,001/58,000 m → 2,450, 58,001 m → 2,550.

## 경로 조립

- **RIDE step**: 연속된 RIDE 간선 중 같은 노선·같은 방향은 한 step으로 묶는다. `stationCount`는 간선 수이고, `durationSec`·`distanceM`은 합이다(정차 시간 설정 포함). 방향이 바뀌면(응암 LOOP → 본선 DOWN) 같은 열차라도 step을 나눈다.
- **일반 환승**: TRANSFER step이다. `lineId`·`lineName`·`color`는 갈아탈 노선, `durationSec`은 환승 시간에 대기를 더한 값, `distanceM`은 환승거리다.
- **분기 환승**: TRANSFER step이고 from = to = 분기점 역이다. `lineId`는 그 노선, `durationSec`은 분기 환승 비용, `distanceM`은 설정의 환승거리다.
- **요금 거리**: TRANSFER step의 거리는 `rideDistanceM`(요금 거리)에 넣지 않는다.
- **합계**: `totalSeconds`는 모든 step의 `durationSec` 합(환승 포함), `transferCount`는 TRANSFER step 수(분기 환승 포함), `rideDistanceM`은 RIDE 거리 합이다.
- **PathStop**: 지나는 노드를 순서대로 담는다.
  - 환승(일반·분기)이 있으면 내린 노드와 탄 노드가 따로 나온다. 분기 환승은 같은 노드가 두 번 나온다.
  - `direction`은 그 정차역에서 탄(떠나는) 열차의 방향이다. RIDE의 마지막 정차역(내리는 역, 도착역)은 직전 구간의 방향이다.
  - 같은 열차로 방향만 바뀌는 정차역(응암)은 떠나는 방향이다.
  - `offsetSec`은 출발부터 그 노드까지의 누적 초다(환승 포함, 첫 정차역 0, 마지막 = `totalSeconds`).
  - 좌표는 `stations`의 값이다.

## 검증 결과 (postgresTest, V1~V12 시드)

- 그래프: 노드 277, RIDE 536, TRANSFER 78, 역 240
- **전 역 쌍 비교**: 분기 환승과 대기를 0으로 둔 설정에서 240×240 = 57,600쌍의 `totalSeconds`가 테스트 안의 Floyd–Warshall(노드 단위 후 역별 최솟값)과 모두 같았다.
- **연결성**: 모든 출발역에서 240개 역 전체에 도달한다. 끊긴 역은 없다.
- **대표 경로**: 기본값과 무관하게 보정 0(dwell 0, 대기 0, 분기 대기 0)을 명시한 설정에서 확인했다. 기대값은 V11 간선 합이고, 기본값 기준 결과는 아래 "시간 보정 기본값"에 있다.

| 구간 | 시간(초) | 환승 | 근거 |
| --- | --- | --- | --- |
| 강남 → 삼성 | 240 | 0 | 2호선 60+90+90 |
| 강남 → 고속터미널 | 273 | 1 | 2호선 90 + 교대 환승 63 + 3호선 120 |
| 시청 → 충정로 / 충정로 → 시청 | 90 / 90 | 0 | 2호선 순환 닫힘 OUTER / INNER |
| 연신내 → 응암 | 200 | 0 | LOOP 80+120 |
| 응암 → 구산 | 470 | 0 | 한 방향 순환을 돌아서 감: 100+80+80+130+80 |
| 성수 → 용답 / 용답 → 성수 | 180 / 180 | 0 | 분기점에서 출발·도착하면 환승 아님 |
| 신도림 → 까치산 | 570 | 0 | 90+150+180+150 |
| 건대입구 → 용답 | 289 | 1 | 90 + 성수 분기 환승 19 + 180 |
| 대림 → 도림천 | 308 | 1 | 150 + 신도림 분기 환승 68 + 90 |
| 마천 → 하남검단산 | 1,686 | 1 | 610 + 강동 분기 환승 16 + 1,060 |
| 둔촌동 → 길동 | 216 | 1 | 110 + 16 + 90 |
| 천호 → 마천 | 680 | 0 | 방화 쪽 ↔ 마천 직통 |

- **응암 → 연신내**(보정 0): 순환으로만 가면 390초(100+80+80+130)다. 하지만 불광에서 3호선으로 갈아타는 353초 경로(100+80+환승 83+90)가 더 빨라서, 그래프는 이 경로를 고른다. 기본값에서는 순환 510초가 환승 경로 563초보다 빨라 순환을 고른다.

## 시간 보정 기본값

원본 간선 시간에는 정차·배차 대기가 없어서 카카오보다 크게 짧았다(강남 → 홍대입구 1,717초 대 2,340초). 또 환승 비용이 걷는 시간뿐이라 환승이 많은 우회 경로가 직행보다 빨라지는 문제가 있었다. 그래서 그래프 비용에 다음 기본값을 넣었다.
- `dwell-seconds=30`: RIDE 간선마다 정차 30초
- `transfer-wait-seconds=120`: 일반 환승마다 열차 대기 120초
- `branch-transfer-wait-seconds=120`: 분기 환승마다 열차 대기 120초

`default-branch-transfer-seconds=120`과 `branch-transfers`(19/68/16초)는 그대로다. 보정값은 `totalSeconds`·`offsetSec`·step의 `durationSec`에 그대로 들어간다(요금 거리에는 영향 없음).

측정(V1~V12 시드, `SubwayGraphPostgresTest.representativeRoutesWithDefaults`):

| 구간 | 보정 0 | 기본값(dwell 30, 대기 120, 분기 대기 120) | 카카오 |
| --- | --- | --- | --- |
| 강남 → 홍대입구 | 1,717초, 환승 2 (2→3→2호선) | **2,250초, 환승 0 (2호선 직행)** = 1,740 + 17구간 × 30 | 2,340초, 환승 0 |
| 둔촌동 → 길동 | 216초, 환승 1 | **396초, 환승 1** = (110+30) + (16+120) + (90+30) | 450초 |

설정 조합별로 보면 강남 → 홍대입구는 dwell 0이면 환승 대기 120초부터, dwell 30이면 대기 0부터 2호선 직행이 선택된다.

기본값에서 다른 대표 경로의 결과(같은 테스트에서 확인):

| 구간 | 시간(초) | 환승 |
| --- | --- | --- |
| 강남 → 삼성 | 330 | 0 |
| 강남 → 고속터미널 | 453 | 1 |
| 시청 → 충정로 / 충정로 → 시청 | 120 / 120 | 0 |
| 연신내 → 응암 / 응암 → 구산 | 260 / 620 | 0 |
| 응암 → 연신내 | 510 | 0 (순환) |
| 건대입구 → 용답 | 469 | 1 |
| 대림 → 도림천 | 488 | 1 |
| 마천 → 하남검단산 | 2,316 | 1 |
| 천호 → 마천 | 920 | 0 |

**잠정값**: 카카오 표본이 2쌍뿐이라 위 기본값은 잠정값이다. ⑨(카카오 경로 연동) 이후 더 많은 역 쌍의 카카오 소요시간과 비교해 다시 보정한다.

## 성능 (로컬, Testcontainers PostgreSQL 16)

- 그래프 로드(JDBC 4개 쿼리와 그래프 생성): 두 번 실행에서 8.5~16.2 ms였다. 앱 시작 로그로는 20~30 ms(첫 쿼리 포함)였다.
- `shortestPathsFrom` 한 번: 출발역 240곳 각각 실행해서 평균 0.079~0.106 ms, 최대 0.105~0.226 ms(워밍업 후)였다.
- 측정값은 테스트 출력(`SUBWAY_GRAPH_PERF`)에 남기고, 통과 조건에는 넣지 않았다.

## 카카오 소요시간과 비교

`docs/kakao-route-analysis.md`에 남아 있는 카카오 수치는 아래 두 쌍뿐이다. 응답 원본은 저장하지 않았다.

| 구간 | 카카오(지하철 경로) | 내부 그래프 | 비고 |
| --- | --- | --- | --- |
| 강남 → 홍대입구 | 2,340초, 환승 0 (2호선 직행) | 기본값 2,250초, 환승 0 (2호선 직행). 보정 0이면 1,717초, 환승 2 | 차이 −90초 |
| 둔촌동 → 길동 | 450초 (강동에서 환승) | 기본값 396초, 환승 1. 보정 0이면 216초 | 차이 −54초 |

카카오 값에는 배차·대기·정차가 들어가서 원본 간선 시간보다 길다. 기본 보정값으로 차이를 줄였지만 잠정값이고, ⑨ 이후 다시 보정한다.

## 운영 전환

1. 서버 `~/BE/.env`에 `LINKRO_SUBWAY_MODULE=graph`를 적고 재배포한다.
   - `docker-compose.yml`의 `app.environment`에 `LINKRO_SUBWAY_MODULE: ${LINKRO_SUBWAY_MODULE:-mock}`을 추가했다(#79). 그래서 이 값이 컨테이너로 전달된다.
   - Spring relaxed binding으로 `linkro.subway.module=graph`가 된다.
   - `.env`에 값이 없거나 비어 있으면 mock이다. compose의 `:-mock` 기본값과 `MockSwitchCondition`이 둘 다 빈 값을 mock으로 처리한다.
2. 시작 로그에서 `Subway graph loaded: 277 nodes ...`(V12 기준)를 확인하고, `Subway route module is MOCK` WARN이 없는지 확인한다.
3. 혼잡도는 별도 스위치 `LINKRO_SUBWAY_CONGESTION`이다(`docs/congestion-api-79.md`). mock이면 `Subway congestion is MOCK` WARN이 남는다.

## 알려진 한계

- 실제 시간표·배차 간격·급행·막차를 반영하지 않는다. 역간 소요시간과 환승 걷기 시간만 쓴다.
- 정차·환승 대기는 실제 배차가 아니라 일괄 상수(기본 30초/120초/120초, 잠정값)다.
- 같은 비용의 경로가 여럿이면 환승 수 → 노드 id 순으로 하나만 고른다.
- 분기 규칙 표는 역 이름과 노선 코드로 찾는다. 분기점 역 이름이 바뀌면 표와 `branch-transfers`도 함께 고쳐야 한다(안 고치면 WARN과 함께 통과로 처리된다).
