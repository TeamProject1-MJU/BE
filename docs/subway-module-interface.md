# 지하철 경로 모듈 인터페이스 (#60)

중간역 추천(8.1)과 카카오 실패 시 대체 경로(6.1)가 쓰는 경로·요금·혼잡도 모듈의 인터페이스다. 지금은 mock 구현만 있다. 실제 그래프·다익스트라·혼잡도 구현(⑥)은 같은 인터페이스로 나중에 붙인다.

- 인터페이스·타입: `com.mju.linkro.subway.api`
- mock: `com.mju.linkro.subway.mock`

## 인터페이스

| 인터페이스 | 메서드 | 설명 |
| --- | --- | --- |
| `SubwayRouteFinder` | `RoutePath shortestPath(long fromStationId, long toStationId)` | 최소 시간 경로 |
| | `ShortestPathTree shortestPathsFrom(long fromStationId)` | 한 출발역에서 도달 가능한 모든 역까지의 경로 (추천용) |
| `ShortestPathTree` | `Set<Long> reachableStationIds()` | 도달 가능한 지원 역. 출발역 자신 포함 |
| | `Optional<RoutePath> pathTo(long stationId)` | `shortestPath(출발역, stationId)`와 같은 경로. 도달 불가·미지원·없는 역이면 empty, 예외 없음 |
| `FareCalculator` | `int fareOf(RoutePath path)` | 예상 요금(원) |
| `CongestionLookup` | `CongestionResult congestionOf(RoutePath path, Instant departAt)` | 경로 모든 정차역 혼잡도 중 최댓값 |

## 타입 (record, 불변)

| 타입 | 필드 |
| --- | --- |
| `RoutePath` | `List<PathStep> steps`, `List<PathStop> stops`, `int totalSeconds`, `int transferCount`, `int rideDistanceM`, `long fromStationId`, `long toStationId` |
| `PathStep` | `StepType type`, `Long lineId`, `String lineName`, `String color`, `DirectionCode direction`, `long fromStationId`, `String fromStationName`, `long toStationId`, `String toStationName`, `int stationCount`, `int durationSec`, `int distanceM` |
| `PathStop` | `long stationId`, `String stationName`, `double latitude`, `double longitude`, `long stationLineId`, `long lineId`, `DirectionCode direction`, `int offsetSec` |
| `CongestionResult` | `boolean supported`, `Double pct`, `String level`, `String levelLabel`, `CongestionSource source` |
| enum | `DirectionCode`(UP, DOWN, INNER, OUTER, LOOP), `StepType`(RIDE, TRANSFER), `CongestionSource`(PREDICTED, MOCK) |

- `PathStep`: RIDE는 `direction` 필수, TRANSFER는 `direction = null`(생성자에서 검사). TRANSFER의 `lineId`는 갈아탈 노선이다. `stationCount`는 RIDE에서 이동한 역 수(승차역 제외)이고 TRANSFER는 0이다.
- `PathStop`: 지나는 그래프 노드(역×노선)를 순서대로 담는다. 환승역은 내린 노선의 노드와 탄 노선의 노드로 두 번 나온다. `direction`은 그 정차역에서 탄 열차의 방향이다. 어떤 RIDE에도 속하지 않는 정차역(빈 경로의 유일한 정차역 등)은 null이다.
- `CongestionResult`: `supported = false`면 나머지 필드가 모두 null이고, `true`면 모두 값이 있다(생성자에서 검사). `CongestionResult.of(...)`, `CongestionResult.unsupported()`로 만든다.
- `RoutePath`의 리스트는 `List.copyOf`로 복사해 바꿀 수 없다.

## 예외 규칙

모두 `BusinessException`이고 기존 `ErrorCode`를 쓴다.

| 코드 | HTTP | 언제 |
| --- | --- | --- |
| `STATION_NOT_FOUND` | 404 | 역 id가 없음 (`shortestPath`의 양쪽 역, `shortestPathsFrom`의 출발역) |
| `STATION_UNSUPPORTED` | 422 | 역은 있지만 `is_supported = false` |
| `ROUTE_NOT_FOUND` | 404 | 두 역은 정상이지만 도달할 수 없음 |

- **출발역 = 도착역**: 예외가 아니라 **빈 경로**를 돌려준다. steps 없음, stops는 그 역 하나, 합계는 모두 0이다. 이미 후보역에 있는 참여자를 0분으로 계산하기 위해서다.
- `ShortestPathTree.pathTo`는 예외를 던지지 않고 empty를 돌려준다.

## 시간·요금·거리 정의

- `totalSeconds`: 모든 step의 `durationSec` 합이다. **환승 시간(TRANSFER)을 포함**한다.
- `rideDistanceM`: RIDE step의 `distanceM` 합이다. **TRANSFER 도보 거리는 제외**하고, 이 값이 요금 거리다.
- `transferCount`: TRANSFER step 수다.
- `fareOf`: `rideDistanceM` 기준 성인 교통카드 요금이다. 여러 호선을 타도 한 번의 이동으로 합산한다.
  - 10 km까지 1,550원
  - 10 km 초과는 5 km마다 +100원, 50 km 초과는 8 km마다 +100원
  - 구간은 올림으로 센다

| 거리 | 요금 |
| --- | --- |
| 0 m, 10,000 m | 1,550 |
| 10,001 m, 15,000 m | 1,650 |
| 15,001 m | 1,750 |
| 50,000 m | 2,350 |
| 50,001 m, 58,000 m | 2,450 |
| 58,001 m | 2,550 |

- `congestionOf`:
  - 정차역마다 `departAt + offsetSec` 시각으로 슬롯을 찾는다. 30분 슬롯과 요일 구분(WEEKDAY/SATURDAY/SUNDAY, 공휴일은 SUNDAY)은 **Asia/Seoul 기준**이다.
  - 06:00~23:00 밖은 다음 첫차 06:00 슬롯을 쓴다(6.2).
  - 데이터가 없는 정차역은 건너뛰고, 모든 정차역에 데이터가 없으면 `unsupported()`를 돌려준다.

## offsetSec

`PathStop.offsetSec`은 출발 시점부터 그 정차역에 닿을 때까지의 초이고, 환승 시간을 포함한다. 첫 정차역은 0, 마지막 정차역은 `totalSeconds`다.

혼잡도는 역마다 **그 역을 지나는 시각**의 30분 슬롯으로 찾아야 한다(6.1·8.1: 출발 예정 시각 + 그 역까지의 누적 소요시간). `congestionOf`가 `departAt`만 받아도 이 값을 더해 정차역별 슬롯과 날짜를 계산할 수 있다. 23:50에 출발해 자정을 넘기는 경로처럼 슬롯이나 요일이 바뀌는 경우도 처리할 수 있다.

## mock 동작

`linkro.subway.module`이 없거나 `mock`이면 `MockSubwayModuleConfig`가 mock 빈 3개를 등록한다. 시작할 때 WARN 로그 한 줄(`Subway module is MOCK ...`)을 남긴다. `graph` 등 다른 값이면 mock이 등록되지 않는다.

| mock | 규칙 |
| --- | --- |
| `MockSubwayRouteFinder` | 역 1~240(V11 시드의 역 수)이 id 순서로 이어진 한 노선. 두 역 사이를 id 순서로 지나는 RIDE 1개, 구간마다 90초·1,000 m, 환승 0회. 방향은 id 증가면 DOWN, 감소면 UP. 노선은 `1호선`(lineId 1)으로 고정. 역 이름은 `목업역{id}`, stationLineId는 역 id와 같음 |
| | id ≤ 0, 241~999,999: `STATION_NOT_FOUND`. id ≥ 1,000,000: 존재하지만 어디와도 연결되지 않은 역(`ROUTE_NOT_FOUND` 재현용, 자기 자신만 도달 가능). `STATION_UNSUPPORTED`는 재현하지 않음 |
| `MockFareCalculator` | 실제 요금식 그대로(값은 상수) |
| `MockCongestionLookup` | 정차역마다 `pct = 40 + (stationId % 7) × 15`, 경로 최댓값. 단계는 6.2 기준(35/50/100/150, 하한 포함)으로 계산, source `MOCK`. 시각은 무시 |

## 실제 구현(⑥)으로 교체하는 방법

1. `com.mju.linkro.subway` 아래(예: `graph` 패키지)에 세 인터페이스의 구현을 만든다.
2. 구현 설정 클래스에 `@ConditionalOnProperty(name = "linkro.subway.module", havingValue = "graph")`를 붙인다.
3. 운영·개발 설정에 `linkro.subway.module=graph`를 넣는다. mock은 `havingValue = "mock", matchIfMissing = true`라 자동으로 빠진다.
4. 테스트에서 `SubwayRouteFinderContract`를 상속해 같은 계약 테스트를 실제 구현에도 돌린다. 출발·도착·없는 역·도달 불가 역 id만 구현마다 정해 주면 된다.
5. 실제 혼잡도는 저장된 `congestion_level`·`congestion_level_label`을 그대로 쓰고, mock처럼 다시 계산하지 않는다(6.2).

## 추천(8.1) 사용 예시

참여자별로 `shortestPathsFrom`을 한 번씩 호출하고, 모든 참여자가 도달할 수 있는 후보역마다 `pathTo` → `fareOf`/`congestionOf`를 계산한다.

```java
@Service
@RequiredArgsConstructor
class RecommendationCalculator {
    private final SubwayRouteFinder routes;
    private final FareCalculator fares;
    private final CongestionLookup congestion;

    List<CandidateScore> score(List<Long> departureStationIds, Instant appointmentAt, Instant requestedAt) {
        // 1. One tree per participant (throws STATION_NOT_FOUND / STATION_UNSUPPORTED for bad departures).
        List<ShortestPathTree> trees = departureStationIds.stream().map(routes::shortestPathsFrom).toList();

        // 2. Candidates every participant can reach.
        Set<Long> candidates = new HashSet<>(trees.get(0).reachableStationIds());
        trees.forEach(tree -> candidates.retainAll(tree.reachableStationIds()));

        List<CandidateScore> scores = new ArrayList<>();
        for (long candidate : candidates) {
            List<Trip> trips = new ArrayList<>();
            for (ShortestPathTree tree : trees) {
                RoutePath path = tree.pathTo(candidate).orElseThrow(); // present: candidate is reachable
                // Departure = appointment - travel time; request time when there is no appointment.
                Instant departAt = appointmentAt != null ? appointmentAt.minusSeconds(path.totalSeconds()) : requestedAt;
                trips.add(new Trip(path.totalSeconds(), path.transferCount(), fares.fareOf(path),
                        path.rideDistanceM(), departAt, congestion.congestionOf(path, departAt)));
            }
            scores.add(new CandidateScore(candidate, trips));
        }
        return scores;
    }
}
```

`CandidateScore`, `Trip`은 추천 쪽에서 정의할 타입의 예시다. 시간 기준은 `totalSeconds`, 요금 기준은 `fareOf`, 거리 기준은 `rideDistanceM` 합으로 비교한다.
