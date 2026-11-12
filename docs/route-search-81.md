# 지하철 경로 검색 POST /routes/search (#81)

방 멤버나 혼자 이동하는 사용자가 출발역·도착역을 보내면, 카카오 대중교통 길찾기의 **지하철 경로**에 우리 DB 혼잡도를 붙여 돌려준다. 카카오를 쓸 수 없으면 우리 그래프의 다익스트라 경로(INTERNAL)로 대체한다.

- 코드: `com.mju.linkro.route` (`controller`, `service`, `dto`, `kakao`)
- 근거: API 명세 6.1, `docs/kakao-route-analysis.md`(#58 실제 응답 분석), `docs/congestion-api-79.md`(혼잡도 규칙)

## 요청과 응답

`POST /api/v1/routes/search` (JWT 필요)

| 필드 | 필수 | 설명 |
| --- | --- | --- |
| `originStationId` | O | 출발역 ID (`is_supported = true`) |
| `destinationStationId` | O | 도착역 ID (`is_supported = true`) |
| `departAt` | X | 혼잡도 기준 시각, 오프셋 포함 ISO-8601. 생략하면 요청 시각 |
| `roomId` | X | 방 화면에서 호출할 때. 보내면 방 멤버를 확인하고 약속 시각 기준으로 출발 시각을 계산 |

응답은 명세 6.1 형식이다: `routeSource`, `routes[]`. #120에서 `appointmentPassed`, `notice`와 `summary.departureAt`, `summary.noService`, `summary.serviceNotice`, `summary.arrivesAfterAppointment`를 추가했다(필드 추가만, 기존 필드·구조·에러 코드는 그대로).
- `routes[]`의 각 경로: `routeId`, `routeType`, `summary`, `steps[]`
- `steps[]`의 각 단계: `type`, `guidance`, `durationSec`, `distanceM`, `line`, `direction`, `directionCode`, `stops`, `path`, `stations`

- `routeSource`: `KAKAO` 또는 `INTERNAL`
- `summary.fareSource`: 카카오 `fare.value`면 `KAKAO`, 우리가 정했으면 `ESTIMATED`(화면에 "예상 요금"). 우리가 정하는 요금은 운임표의 출발역→도착역 요금이고, 없으면 우리 RIDE 거리로 계산한다(#109)
- `routeType`: `FASTEST`, `FEWER_TRANSFERS`, `CHEAPEST` 중 하나 또는 null
  - 각 라벨은 그 기준에서 가장 앞선 경로에 하나씩 준다.
  - 그 경로가 이미 라벨을 가졌으면 그 라벨은 주지 않는다.
  - INTERNAL은 경로 1개이고 `FASTEST`다.
- `direction`(방면 문자열): 카카오가 방면을 주지 않아 항상 null이다. `directionCode`는 간선으로 계산한다.
- `stations[]`: SUBWAY 단계의 정차역별 슬롯과 혼잡도, `stops`와 같은 순서. WALKING 단계는 빈 배열이다.
- `summary.departureAt`: 출발 예정 시각, Asia/Seoul 오프셋. 비운행이면 첫차 기준으로 바꾼 시각이다(아래 "비운행 시각과 첫차").
- `summary.arrivalAt`: `departureAt` + `totalTimeSec`, Asia/Seoul 오프셋
- `summary.noService`, `summary.serviceNotice`: 비운행이라 출발을 첫차 기준으로 바꿨으면 `true`와 안내 문구, 아니면 `false`·`null`
- `appointmentPassed`, `notice`: 방 약속 시각 자체가 지났으면 `true`와 안내 문구, 아니면 `false`·`null`
- `summary.arrivesAfterAppointment`: 방 약속 시각이 있고 `appointmentPassed`가 `false`인데 최종 `arrivalAt`이 약속보다 늦으면 `true`. 방·약속이 없으면 항상 `false`(아래 "방 약속이 지난 경우")
- `walkingMinutes`: 도보 단계 시간 합을 분으로 반올림

## 처리 순서

1. 요청 검증: 400 `VALIDATION_ERROR`. 필드별 사유를 한 번에 돌려준다.
2. `roomId`가 있으면 `RoomAccess.requireMember`로 확인한다. 방 API와 같다.
   - 방 없음: 404 `ROOM_NOT_FOUND`
   - 방은 있는데 멤버가 아님: 403 `NOT_ROOM_MEMBER`
3. 출발·도착역 확인: `StationLookup.requireSupported`
   - 없는 역: 404 `STATION_NOT_FOUND`
   - 미지원 역: 422 `STATION_UNSUPPORTED`
   - 카카오를 호출하기 **전에** 확인한다. 대체 경로가 우리 데이터 범위만 다루기 때문이다.
4. **약속방 모드(#122)**: `roomId`의 방에 약속 시각이 있고 지나지 않았으며 열차 시각표 라우터가 있으면, 추천과 같은 시각표 경로를 쓰고 카카오를 호출하지 않는다. 시각표에 약속까지 도착하는 경로가 없으면 5 이후의 흐름으로 간다(아래 "약속방 모드").
5. 출발역과 도착역이 같으면 카카오를 호출하지 않고 INTERNAL(빈 경로)이다.
6. 카카오 호출 → 우리 역·노선·방향으로 변환 → 쓸 수 있는 경로가 없으면 INTERNAL
   - INTERNAL로도 경로가 없으면 404 `ROUTE_NOT_FOUND`다.
7. 경로마다 출발 예정 시각을 정하고, 열차 시각표가 있으면 그 시각에 경로를 운행하는 열차가 있는지 대조해 없으면 첫차 기준으로 바꾼 뒤(#120) 정차역 혼잡도를 붙인다.

카카오 호출 중에는 DB 트랜잭션을 잡지 않는다.

## 카카오 호출 규격

| 항목 | 값 |
| --- | --- |
| 요청 | `GET https://dapi.kakao.com/v2/routing/publictraffic` |
| 인증 | `Authorization: KakaoAK ${KAKAO_REST_API_KEY}` |
| 파라미터 | `start_x`, `start_y`, `end_x`, `end_y`(우리 `stations` 좌표, x=경도 y=위도), `input_coord=WGS84`, `output_coord=WGS84` |
| 1회 타임아웃 | 3초(연결·응답) |
| 전체 상한 | 5초(재시도 포함) |
| 재시도 | 타임아웃·I/O 오류·5xx일 때 200ms 쉬고 1회. 남은 시간 안에서만 하고, 그 호출의 대기 시간은 남은 시간으로 줄인다 |
| 재시도 안 함 | 4xx(429 쿼터 초과 포함), 읽을 수 없는 본문 |
| 키가 비어 있음 | 호출하지 않고 바로 INTERNAL |
| 호출 스레드 | `ThreadPoolExecutor` core 4, max 16, 대기열 없음(`SynchronousQueue`), 데몬 스레드 `kakao-route-N`, 종료 시 `shutdownNow` |
| 풀이 가득 참 | 16개가 모두 호출 중이면 카카오를 호출하지 않고 즉시 INTERNAL(`BUSY`) |

설정으로 바꿀 수 있다(기본값 표시):
- `linkro.route.kakao.base-url=https://dapi.kakao.com`
- `call-timeout=3s`, `total-timeout=5s`, `retry-delay=200ms`
- `auth-cooldown=5m`, `quota-cooldown=30s`
- 키: `linkro.route.kakao.rest-api-key`, 없으면 `KAKAO_REST_API_KEY`. 테스트 설정은 이 프로퍼티를 빈 값으로 고정해, `.env`나 셸에 키가 있어도 실제 카카오를 호출하지 않는다

### 호출 보류(쿨다운)

| 응답 | 보류 시간 | 로그 |
| --- | --- | --- |
| 401, 403 (키 오류) | 5분 | ERROR, 쿨다운당 1회(`Kakao rejected the key; INTERNAL only for 300 s. Check KAKAO_REST_API_KEY.`) |
| 429 (쿼터 초과) | 30초 | WARN, 쿨다운당 1회 |

- 보류 중에는 카카오를 호출하지 않고(호출 0회) 바로 INTERNAL이다(`routeSource: INTERNAL`, 대체 이유 `COOLDOWN`).
- 보류가 끝나면 다음 요청이 다시 시도한다. 다시 401·403·429면 새 보류가 시작된다.
- 상태는 앱 메모리에만 있다(DB·파일에 저장하지 않음). 재시작하면 초기화되고, 서버가 여러 대면 서버마다 따로 판단한다.
- 시간은 주입한 `Clock`으로 계산해 테스트에서 앞당길 수 있다.

### 로그 수준

| 상황 | 수준 |
| --- | --- |
| 카카오 성공 | INFO |
| 카카오 타임아웃·I/O 오류·5xx·그 밖의 4xx·읽을 수 없는 본문·풀 포화, 그로 인한 INTERNAL 대체 | WARN |
| 429 쿨다운 시작 | WARN(1회) |
| 401·403 쿨다운 시작 | ERROR(1회) |
| 키 없음·쿨다운 중·출발역=도착역·쓸 수 있는 지하철 경로 없음(빈 결과)으로 INTERNAL | INFO |

어느 수준이든 키·좌표·응답 본문·예외 메시지는 출력하지 않는다.

### 약관과 로그

- 카카오 응답 본문은 그 요청을 처리하는 동안 메모리에만 있다. DB·캐시·파일·로그에 남기지 않는다.
- 로그에는 다음만 남긴다. 키, 좌표, 응답 본문, 예외 메시지는 남기지 않는다.
  - 요청마다 만든 8자리 식별자, 시도 번호, HTTP 상태 코드(또는 `timeout`/`I/O error`), 걸린 시간: `Kakao route 1a2b3c4d attempt 1: 200 in 512 ms`
  - INTERNAL로 대체할 때의 이유: `Route search 31 -> 35 uses INTERNAL: TIMEOUT`

## 변환 규칙 (카카오 → 우리 데이터)

경로는 아래를 **모두** 만족할 때만 쓴다. 하나라도 어기면 그 경로는 버리고, 남는 경로가 없으면 INTERNAL로 대체한다.

1. 경로 `properties.type = SUBWAY`이고, 단계가 `SUBWAY`와 `WALKING`뿐이다(BUS가 섞이면 제외).
2. SUBWAY 단계의 `vehicles[].name`이 모두 같은 `N호선`(1~8)이다. 9호선·경의중앙선 등은 제외한다.
3. 정차역 이름이 모두 그 노선의 우리 노드와 맞는다.
   - 양쪽 이름에 같은 정규화를 적용한다: 앞뒤 공백 제거 → 괄호와 그 안 제거 → 끝의 "역" 제거, 그리고 `이수` → `총신대입구(이수)` 별칭.
   - 적재 도구(#71)와 같은 `StationNames.normalize`를 쓴다.
4. 연속한 정차역 쌍 A→B마다 같은 노선의 RIDE 간선이 있다. 그 간선 방향이 A의 방향이고, 하차역은 직전 방향을 쓴다.
5. 첫 SUBWAY 정차역이 출발역이고 마지막이 도착역이다.

정차역 통과 시각: 카카오는 단계 시간만 주므로 단계 안의 정차역에 시간을 고르게 나눈다. 예: 600초, 5개 역이면 0, 150, 300, 450, 600초.

요금: `fare.value`를 쓴다(`KAKAO`). 지하철 경로에 값이 없으면 운임표의 출발역→도착역 요금(#109), 운임표에 없으면 우리 RIDE 간선 거리 합으로 계산한다(`ESTIMATED`). #58 표본에서는 없는 경우가 없었다.

INTERNAL 경로는 다음과 같이 만든다.
- `SubwayRouteFinder.shortestPath`(그래프 모드면 다익스트라)의 RIDE 단계는 SUBWAY 단계, TRANSFER 단계는 WALKING 단계로 바꾼다.
- `stops`와 `path`는 우리 역 좌표로 채운다.
- 요금은 운임표의 출발역→도착역 요금(#109, 추천과 같은 `StationPairFare`)이고, 운임표가 없거나 쌍이 없으면 `FareCalculator`로 계산한다. `ESTIMATED`로 표시한다.

## 혼잡도 규칙

- 정차역마다 DB(`congestion_stats`)에서 조회한다(`DbCongestionLookup`). `linkro.subway.congestion` 스위치와 관계없다. `GET /congestion`과 같은 방식이다.
- 정차역 통과 시각 = 출발 예정 시각 + 그 정차역까지의 누적 시간이다. 슬롯·영업일·첫차·출처 우선순위 규칙은 `docs/congestion-api-79.md`의 A~E를 그대로 따른다.
- `pct`는 `CongestionResolver.display`의 값 그대로다(정수 HALF_UP, 단계 상한을 넘으면 상한 바로 아래 정수). 여기서 다시 반올림하지 않는다.
- `level`·`levelLabel`은 DB 값 그대로이고, 출처는 `kind` 필드(명세 6.1 예시 이름)에 담는다.
- `summary.congestion`은 데이터가 있는 정차역 중 원값이 가장 큰 정차역의 값이다. 모두 없으면 `supported: false`다.
- 출발 예정 시각:
  - 약속방 모드(#122)이면 시각표 경로의 출발 시각(`leaveAt`)이고, 정차역 통과 시각은 첫 열차 출발(`boardAt`) + 누적 시간이다(추천과 같다).
  - `roomId`가 있고 방에 약속 시각이 있으면(약속방 모드가 아닐 때) `약속 시각 − totalTimeSec`이다. 경로마다 계산한다.
  - 계산한 시각이 이미 지났으면 요청 시각을 쓴다.
  - 약속 시각이 없거나 `roomId`가 없으면 `departAt`, 그것도 없으면 요청 시각이다.

## 약속방 모드: 추천과 같은 시각표 경로 (#122)

약속방의 추천 결과 카드는 "실제 경로는 '경로 자세히 보기'에서 확인해요"로 이 API를 부른다. 추천은 열차 시각표(`TimetableRouter.latestDepartures`)로 계산하는데, 경로 검색은 카카오나 고정 비용으로 `약속 − totalTimeSec`에 출발시켜 두 값이 어긋났다.

- **왜 바꿨나 (측정, #122 0단계)**: 지금 방식(INTERNAL, `약속 − totalTimeSec` 출발)으로 안내받은 출발 시각에 나가 보여 준 경로를 실제 열차로 타면, 방 8개 × 멤버 3명 × 약속 4개 시각(평일 08:30·14:00·19:00·22:30) 96개 중 **83개(86%)가 약속보다 늦었다**(중앙값 +2.5분, 최대 +15.5분). 환승은 실제 도보(`station_connections`, 고정 대기 120초 제외) + 다음 열차 시각표 대기로 태웠다. 환승 없는 경로도 24개 중 20개가 늦었다. 주원인은 첫 열차 대기를 고려하지 않는 것이다.
  - 한계: 측정 날짜 2026-11-17(화) 하루, 시각표 2026-09-01 기준, 역 접근 시간 0초, 로컬 DB(V15) 기준. 카카오 경로(KAKAO)는 측정하지 않았다. API를 띄워 부른 값이 아니라 같은 클래스(Dijkstra, `TrainTimetable`, `TimetableRouter`)를 직접 부른 값이다.
- **조건** (`RouteSearchService.roomAppointmentRoute` 한 곳): `roomId`가 있고, 방에 약속 시각이 있고, 약속이 지나지 않았고(`appointmentPassed: false`), 출발·도착역이 다르고, `TimetableRouteFinder` bean이 있을 때(`linkro.subway.module=graph`, `linkro.subway.timetable.enabled`가 false가 아님).
- **계산**: `latestDepartures(도착역, 약속 시각, {출발역}, 0).get(출발역)`. 추천 엔진이 쓰는 같은 bean이라 역 접근 시간(`station-access-seconds`) 등 설정도 같다.
  - `departureAt` = `leaveAt`(첫 열차 출발 − 역 접근 시간), `arrivalAt` = `arriveAt`(마지막 열차 도착), `totalTimeSec` = `arriveAt − leaveAt`. 그래서 `arrivalAt = departureAt + totalTimeSec`은 그대로이고, 추천 `travelMinutes`는 이 초를 분 단위로 올린 값이다.
  - 정차역 혼잡도는 첫 열차 출발(`boardAt`)부터 센다(추천의 `participantTimes`와 같다).
  - 요금은 `InternalRouteBuilder`의 `StationPairFare`(출발역→도착역 운임표, 없으면 거리)다. 추천 엔진과 같은 클래스·같은 입력이다.
  - 응답 변환은 `InternalRouteBuilder.toDraft(RoutePath, 출발, 도착)`를 고정 비용 경로와 같이 쓴다. RIDE는 SUBWAY 단계, TRANSFER는 WALKING 단계다.
  - 시각표 경로의 WALKING 단계 `durationSec`은 하차부터 다음 승차까지(도보 + 승강장 대기)다. SUBWAY 단계에는 같은 열차의 정차와 같은 노선 다른 열차 대기가 들어 있다. `walkingMinutes`는 대기를 빼고 열차별 환승 도보(`TimedLeg.walkSec`) 합으로 계산한다.
  - `routeSource`는 `INTERNAL`, 경로는 1개(`routeType: FASTEST`), `noService: false`, `serviceNotice: null`, `arrivesAfterAppointment: false`(약속까지 도착하는 경로만 고른다), `appointmentPassed: false`, `notice: null`.
- **카카오는 호출하지 않는다.** 이 모드의 경로 좌표(`path`)는 우리 역 좌표를 잇는 선이다.
- **넘어가는 경우 (지금 흐름 그대로)**: 약속까지 도착하는 열차가 약속 시각의 운행일에 없으면(예: 새벽 05:40 약속, 첫차 도착 05:45) 카카오/INTERNAL + `FirstTrainTimer` + `arrivesAfterAppointment` 흐름이다. 약속이 지났거나, 방·약속이 없거나, 시각표 라우터가 없으면(mock 모드, 시각표 끔) 이전과 완전히 같다. 방 없는 혼자 길 찾기는 바뀌지 않는다.
- **추천과 값이 같아지는 조건**: 출발역이 그 멤버의 출발역, 도착역이 추천역이고, 약속 시각·시각표·휴일 달력·역 접근 시간·코드가 추천 실행 때와 같을 때. 출발역 하나로 검색해도 멤버 전체로 검색한 추천과 같은 경로가 나온다(측정 96/96, `RouteSearchPostgresTest.roomRouteIsTheRecommendationTrip`).
- **같지 않은 경우**
  - 추천 실행 뒤 약속·멤버가 바뀐 경우(추천 `isStale: true`). 경로 검색은 지금 약속으로 계산한다.
  - 추천역이 아닌 역(투표·선택한 다른 역)으로 검색한 경우: 비교할 추천 값이 없다.
  - 휴일 달력이 갱신된 경우(KASI 갱신으로 그 날의 요일 유형이 바뀜).
  - 시각표에 약속까지 도착하는 경로가 없어 지금 흐름으로 넘어간 경우.
  - 추천이 고정 비용(ESTIMATE, 시각표 끔)으로 계산된 경우.
  - 혼잡도 값: 기준 시각은 같지만, 추천은 `linkro.subway.congestion` 스위치의 조회를, 경로 검색은 항상 DB를 쓴다.
- **혼자 길 찾기(방 없음)의 한계 (바꾸지 않음)**: KAKAO 경로의 `totalTimeSec`은 카카오 `totalTime`이고, INTERNAL은 고정 비용이다. 둘 다 첫 열차·환승 열차 대기를 시각표로 계산한 값이 아니라 예상 값이다. 카카오 `totalTime`은 같은 경로면 호출 시각이 달라도 같은 값이었다(#122 1.5단계 측정, 27/27): 열차 대기를 포함하지 않는다.

## 비운행 시각과 첫차 (#120)

카카오 길찾기는 출발 시각을 받지 않고 시각 정보도 주지 않는다. 그래서 전에는 열차가 없는 시각(예: 평일 03:00)에도 `입력 시각 + 낮 소요시간`이 도착 시각으로 나왔다. 이제 경로마다 열차 시각표(`train-timetable.csv.gz`, #94)로 판별한다. 고정 시각은 쓰지 않는다.

- **적용 조건**: 열차 시각표가 올라온 경우만이다(`linkro.subway.module=graph`이고 `linkro.subway.timetable.enabled`가 false가 아님). mock 모드나 시각표를 끈 경우에는 전과 같고 `noService`는 항상 `false`다.
- **고정 경로 대조** (`FirstTrainTimer`): 보여줄 경로(KAKAO는 매핑된 정차역, INTERNAL은 다익스트라 경로)는 그대로 둔다. 그 경로의 SUBWAY 단계 정차역 쌍(RIDE 간선)을 순서대로 실제 열차에 태워 본다.
  - 첫 승차는 출발 시각 + 첫 정차역까지 시간(카카오의 승차 전 도보) 이후의 다음 열차다.
  - 같은 단계 안에서는 그 열차가 계속 가면 그대로 타고(`rideOf`), 아니면 다음 열차를 탄다.
  - 다음 SUBWAY 단계는 사이 도보 시간 뒤의 다음 열차를 탄다.
  - 그날 운행일 열차로 끝까지 못 가면(어느 간선이든 막차가 지났으면) 다음 운행일 첫 열차부터 다시 대조한다. 그래서 **막차 이후 입력은 다음 운행일 첫차**다. 운행일은 03:00에 바뀐다(`TimetableClock`). 그래서 토요일 01:30은 금요일(평일) 운행일의 막차 이후이고, 토요일 첫차로 안내한다.
- **비운행 판정**: 다음 운행일로 넘어갔거나, 처음 탄 열차가 그 간선의 그날 첫차이고 출발 시각보다 늦게 떠나면 비운행이다. 이때만 바꾼다.
  - `departureAt` = 첫차 출발 − 첫 정차역까지 시간
  - `arrivalAt` = `departureAt` + `totalTimeSec`
  - `serviceNotice` = `요청한 시각에는 이 경로를 운행하는 열차가 없어 첫차(11월 12일 05:36 출발) 기준으로 안내합니다.`
- **소요시간은 기존 계산 그대로**다. `totalTimeSec`, 요금, 환승 수, 단계, 정차역 `offsetSec`는 바꾸지 않는다. 시각표의 실제 도착 시각(첫차 대기·환승 대기 포함)은 쓰지 않는다.
- **판정하지 않는 경우**: 정차역 쌍이 시각표 간선이 아니거나 두 운행일 모두 대조가 안 되면 아무것도 바꾸지 않는다(`noService: false`). 시각표 리포트(`docs/timetable-94.md`) 기준으로 RIDE 간선 536개 모두에 요일별 열차가 있다.
- **정상 운행 시각**: 바뀌는 것이 없다. 새 필드만 `departureAt`(요청한 출발 그대로), `noService: false`, `serviceNotice: null`로 붙는다.
- **첫차 시각은 역·방향마다 다르다**: 평일 첫 출발은 역·방향별로 05:11~06:05, 막차는 00:09~01:14다. 그래서 노선 전체 기준(추천의 `noTrainArrivesBefore`)이 아니라 경로 기준으로 판정한다. 예: 강남→사당(2호선) 첫차는 평일 05:36:00, 토요일 05:35:30이다. 막차는 평일 00:46, 토·휴일 23:45다(같은 외선 서울대입구행 열차가 주말 시각표에서 약 1시간 이르다). 그래서 토요일 23:50 강남→사당은 비운행으로 다음 운행일(일요일) 첫차 05:35:30을 안내한다(`RouteSearchPostgresTest`).
- **혼잡도와 `firstTrain`**: 혼잡도는 바꾼 출발 시각 기준으로 조회한다. 그래서 비운행이면 정차역 `stations[].slotStart`·`slotDate`·`firstTrain`이 바뀐다. 예: 평일 03:00 조회는 전에는 05:30 슬롯 `firstTrain: true`였다. 이제 05:36 출발 기준 05:30 슬롯 `firstTrain: false`다. 막차 이후(예: 00:50) 조회는 전에는 그날 00:30 슬롯이었고, 이제 다음 날 첫차 시간대 슬롯이다. 비운행 표시는 `summary.noService`로 한다.
- **#115 `earliestArrivalsOfServiceDay`는 재사용하지 않았다**: 출발역에서 아무 경로로나 가장 빨리 닿는 도착 시각이라 보여주는 경로와 다를 수 있다. 승차 시각도 주지 않는다.

## 방 약속이 지난 경우 (#120)

- 방 약속 시각 자체가 요청 시각보다 앞서면(추천의 `APPOINTMENT_PASSED`와 같은 초 단위 비교, 같은 초는 지나지 않음) `appointmentPassed: true`, `notice: "약속 시각이 이미 지나 지금 출발 기준으로 안내합니다."`다. 출발은 요청 시각이고 `departAt`은 무시한다(전과 같은 동작).
- 방에 약속 시각이 있으면 `departAt`은 약속이 지나지 않았어도 무시한다(기존 동작).
- 약속방 모드(#122)로 시각표 경로를 쓴 응답은 약속까지 도착하는 경로라 아래 판정과 관계없이 `arrivesAfterAppointment: false`다. 아래는 그 모드가 아닐 때(시각표 경로 없음, 시각표 라우터 없음)의 규칙이다.
- **약속보다 늦게 도착 (`summary.arrivesAfterAppointment`)**: 경로마다 최종 `arrivalAt`(비운행으로 첫차로 밀린 값 포함)과 방 약속 시각을 둘 다 초 단위로 자른 뒤 비교해, 도착이 늦으면 `true`다.
  - 정상 케이스(`약속 − totalTimeSec`에 출발해 약속 시각에 도착)는 같은 시각이라 `false`다.
  - 약속 시각 기준 출발이 비운행이면(예: 약속 06:00, 40분 경로 → 05:20 출발 → 첫차 05:36) 출발이 첫차로 밀려 `arrivalAt`(06:16)이 약속보다 늦어진다. 이때 `noService`와 함께 `true`다.
  - 약속은 아직 남았지만 `약속 시각 − totalTimeSec`가 이미 지나 출발이 요청 시각(지금)으로 바뀐 경우(이전 알려진 한계, 지금 출발해도 늦는 경우)도 같은 필드로 `true`가 된다. 별도의 `TOO_LATE_NOW` 값은 두지 않았다.
  - `appointmentPassed: true`(약속 자체가 지남)이면 비교하지 않고 `false`다. 방이 없거나(`roomId` 없음) 약속 시각이 없으면 항상 `false`다.

## 설정과 실행

### 운영 서버

- 서버 `~/BE/.env`에 `KAKAO_REST_API_KEY=...`를 적고 재배포한다.
- `docker-compose.yml`의 `app.environment`에 `KAKAO_REST_API_KEY: ${KAKAO_REST_API_KEY:-}`를 추가했다. 그래서 값이 컨테이너로 전달된다.
- 선택값이라 `deploy.yml`의 필수 설정 검사에는 넣지 않았다. 비어 있으면 INTERNAL만 쓴다.

### 로컬 실행 (IntelliJ)

1. Run → Edit Configurations… → `LinkroApplication` 선택
2. Modify options → **Environment variables**를 켠다.
3. 환경 변수 칸에 `KAKAO_REST_API_KEY=<REST API 키>`를 추가한다. 이미 쓰는 `DB_URL` 등과 세미콜론으로 구분한다.
   - 실행 구성은 `.idea/workspace.xml`에 저장되므로 그 파일을 커밋하지 않는다.
   - 또는 셸 `~/.zshrc`에 `export KAKAO_REST_API_KEY=...`를 두고 IntelliJ를 터미널에서 실행해도 된다.
4. 실행하면 기동 로그에 INFO 한 줄이 남는다. 키 값은 어느 경우에도 출력하지 않는다.
   - 키 있음: `Kakao route client is enabled: POST /routes/search tries Kakao first (call 3000 ms, total 5000 ms, 1 retry).`
   - 키 없음·빈 값: `KAKAO_REST_API_KEY is empty: POST /routes/search uses INTERNAL routes only.`

- 레포 루트의 `.env`(로컬용)는 `.gitignore`에 들어 있다. 키는 코드·커밋·PR·로그에 넣지 않는다.
- 로컬에서 INTERNAL 경로가 실제 역으로 나오려면 `LINKRO_SUBWAY_MODULE=graph`도 켠다. mock이면 가짜 경로다.

## 테스트

카카오 호출은 모두 `MockRestServiceServer` 스텁이다. 실제 키로 외부를 호출하지 않는다. 스텁 본문은 `docs/kakao-route-analysis.md`의 필드 구조로 직접 쓴 가짜 JSON이다.

- `KakaoRouteClientTest`
  - 정상(키·좌표 파라미터)
  - 키 없음(호출 0회)
  - 400·401·403·429(재시도 없음)
  - 5xx(재시도 1회, WARN)
  - 느린 응답(호출 타임아웃·전체 상한)
  - 깨진 본문
  - 풀 포화(즉시 `BUSY`, 추가 호출 없음)
  - 401 쿨다운(5분 동안 호출 0회, ERROR 1회, 이후 재시도)
  - 429 쿨다운(30초)
  - 로그에 키·본문·좌표가 없음
- `RouteSearchFallbackLogTest`: 실패 사유마다 즉시 INTERNAL, 로그 수준(INFO/WARN)
- `FirstTrainTimerTest`(직접 만든 시각표, #120)
  - 평일 03:00·05:00 → 첫차, 토요일 01:30 → 토요일 첫차, 막차 이후 → 다음 운행일 첫차
  - 14:00·23:30·막차 전·첫차 정각 → 그대로
  - 환승 노선 막차가 지났으면 경로 전체를 다음 날 첫차로
  - 시각표에 없는 간선, 빈 경로 → 판정 안 함
  - 카카오 fixture(호출 없음)를 `KakaoRouteMapper`로 매핑한 정차역으로 대조. 승차 전 도보만큼 일찍 출발
- `RouteSearchRoomTimetableTest`(#122, `FirstTrainTimerTest`의 시각표로 만든 `TimetableRouter`)
  - 약속방 모드: 엔진처럼 멤버 전체로 구한 `latestDepartures`와 출발·도착·`totalTimeSec`·역 순서가 같고, 카카오 호출 0회, 혼잡도 기준 = 첫 열차 출발
  - 약속 정각 도착(14:07)은 그 열차, 1초 앞(14:06:59)은 앞 열차(05:36 → 05:40)
  - 새벽 약속(05:39:59, 첫차 도착 05:40)은 시각표 경로가 없어 시각표 라우터 없는 응답과 같음(`noService`, `arrivesAfterAppointment: true`)
  - 약속 지남·방 없음·약속 없는 방·시각표 라우터 없음은 이전과 같은 응답
- `RouteSearchServiceTest`
  - 14:00·23:30 응답이 시각표 없이 만든 응답과 같음(새 필드 `false`/`null`)
  - 03:00이면 출발·도착만 바뀌고 소요시간·요금·환승·정차역은 그대로
  - `appointmentPassed`: 약속 시각 자체가 지났을 때만, 초 단위
  - `arrivesAfterAppointment`: 새벽 약속(06:00, 40분)이 첫차로 밀림 → `true`, 내일 14:00 약속(도착 = 약속) → `false`, `약속 − 소요`가 지나 지금 출발 → `true`, 지난 약속 → `false`, 방 없음 → `false`, 초 단위 비교
- `KakaoRouteMapperTest`
  - 정차역·방향·통과 시각, 환승
  - 버스 섞임, 9호선, 없는 역, 인접하지 않은 역, 출발역 불일치 → 제외
  - 빈 결과, 요금 없음 → ESTIMATED
- `RouteSearchPostgresTest`(V1~V13 시드, graph)
  - 카카오 정상: 혼잡도 93/34, 회색 정차역
  - 타임아웃·429·빈 결과·키 없음 → INTERNAL
  - 401 뒤 쿨다운 중 요청은 카카오 호출 0회, INTERNAL
  - 방 멤버(약속방 모드: 시각표 경로, 카카오 호출 0회, 약속 이전 도착, 08:00 슬롯), 비멤버 403, 없는 방 404
  - #122 `roomRouteIsTheRecommendationTrip`: 같은 방(멤버 강남·노원, 화요일 19:00 약속, 종로3가)에서 추천 엔진 `evaluate`의 trip과 경로 검색이 `leaveAt`·`arriveAt`(초)·역 순서·환승 수·요금·`travelMinutes`까지 같고 카카오 호출 0회
  - 역 검증(404, 422)이 카카오보다 먼저
  - 인증 없음 401, OpenAPI
  - #120 실제 시각표: 강남→사당 KAKAO fixture·INTERNAL 모두 평일 03:00·05:00 → 05:36:00, 토요일 01:30 → 05:35:30. 14:00·23:30 → 그대로. 토요일 23:50 → 일요일 첫차 05:35:30(이 경로 막차 23:45 이후). 지난 약속 → `appointmentPassed`. 화요일 05:40 약속 → 첫차 05:36:00 출발, 05:46 도착, `arrivesAfterAppointment: true`. 약속 시각에 도착하는 방 조회 → `false`
