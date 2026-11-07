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

응답은 명세 6.1 형식이다: `routeSource`, `routes[]`.
- `routes[]`의 각 경로: `routeId`, `routeType`, `summary`, `steps[]`
- `steps[]`의 각 단계: `type`, `guidance`, `durationSec`, `distanceM`, `line`, `direction`, `directionCode`, `stops`, `path`, `stations`

- `routeSource`: `KAKAO` 또는 `INTERNAL`
- `summary.fareSource`: 카카오 `fare.value`면 `KAKAO`, 우리 RIDE 거리로 계산했으면 `ESTIMATED`(화면에 "예상 요금")
- `routeType`: `FASTEST`, `FEWER_TRANSFERS`, `CHEAPEST` 중 하나 또는 null
  - 각 라벨은 그 기준에서 가장 앞선 경로에 하나씩 준다.
  - 그 경로가 이미 라벨을 가졌으면 그 라벨은 주지 않는다.
  - INTERNAL은 경로 1개이고 `FASTEST`다.
- `direction`(방면 문자열): 카카오가 방면을 주지 않아 항상 null이다. `directionCode`는 간선으로 계산한다.
- `stations[]`: SUBWAY 단계의 정차역별 슬롯과 혼잡도, `stops`와 같은 순서. WALKING 단계는 빈 배열이다.
- `summary.arrivalAt`: 출발 예정 시각 + `totalTimeSec`, Asia/Seoul 오프셋
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
4. 출발역과 도착역이 같으면 카카오를 호출하지 않고 INTERNAL(빈 경로)이다.
5. 카카오 호출 → 우리 역·노선·방향으로 변환 → 쓸 수 있는 경로가 없으면 INTERNAL
   - INTERNAL로도 경로가 없으면 404 `ROUTE_NOT_FOUND`다.
6. 경로마다 출발 예정 시각을 정하고 정차역 혼잡도를 붙인다.

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

설정으로 바꿀 수 있다(기본값 표시): `linkro.route.kakao.base-url=https://dapi.kakao.com`, `call-timeout=3s`, `total-timeout=5s`, `retry-delay=200ms`.

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

요금: `fare.value`를 쓴다(`KAKAO`). 지하철 경로에 값이 없으면 우리 RIDE 간선 거리 합으로 계산한다(`ESTIMATED`). #58 표본에서는 없는 경우가 없었다.

INTERNAL 경로는 다음과 같이 만든다.
- `SubwayRouteFinder.shortestPath`(그래프 모드면 다익스트라)의 RIDE 단계는 SUBWAY 단계, TRANSFER 단계는 WALKING 단계로 바꾼다.
- `stops`와 `path`는 우리 역 좌표로 채운다.
- 요금은 `FareCalculator`로 계산하고 `ESTIMATED`로 표시한다.

## 혼잡도 규칙

- 정차역마다 DB(`congestion_stats`)에서 조회한다(`DbCongestionLookup`). `linkro.subway.congestion` 스위치와 관계없다. `GET /congestion`과 같은 방식이다.
- 정차역 통과 시각 = 출발 예정 시각 + 그 정차역까지의 누적 시간이다. 슬롯·영업일·첫차·출처 우선순위 규칙은 `docs/congestion-api-79.md`의 A~E를 그대로 따른다.
- `pct`는 `CongestionResolver.display`의 값 그대로다(정수 HALF_UP, 단계 상한을 넘으면 상한 바로 아래 정수). 여기서 다시 반올림하지 않는다.
- `level`·`levelLabel`은 DB 값 그대로이고, 출처는 `kind` 필드(명세 6.1 예시 이름)에 담는다.
- `summary.congestion`은 데이터가 있는 정차역 중 원값이 가장 큰 정차역의 값이다. 모두 없으면 `supported: false`다.
- 출발 예정 시각:
  - `roomId`가 있고 방에 약속 시각이 있으면 `약속 시각 − totalTimeSec`이다. 경로마다 계산한다.
  - 계산한 시각이 이미 지났으면 요청 시각을 쓴다.
  - 약속 시각이 없거나 `roomId`가 없으면 `departAt`, 그것도 없으면 요청 시각이다.

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
  - 4xx·429(재시도 없음)
  - 5xx(재시도 1회)
  - 느린 응답(호출 타임아웃·전체 상한)
  - 깨진 본문
  - 로그에 키·본문·좌표가 없음
- `KakaoRouteMapperTest`
  - 정차역·방향·통과 시각, 환승
  - 버스 섞임, 9호선, 없는 역, 인접하지 않은 역, 출발역 불일치 → 제외
  - 빈 결과, 요금 없음 → ESTIMATED
- `RouteSearchPostgresTest`(V1~V13 시드, graph)
  - 카카오 정상: 혼잡도 93/34, 회색 정차역
  - 타임아웃·429·빈 결과·키 없음 → INTERNAL
  - 방 멤버(약속 시각 기준 슬롯), 비멤버 403, 없는 방 404
  - 역 검증(404, 422)이 카카오보다 먼저
  - 인증 없음 401, OpenAPI
