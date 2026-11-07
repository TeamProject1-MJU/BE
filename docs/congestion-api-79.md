# 혼잡도 조회 API와 CongestionLookup DB 구현 (#79)

`congestion_stats`(#71에서 적재)를 읽는 두 경로를 만든다.

- `GET /api/v1/congestion`: 한 역·노선·방향의 30분 슬롯 혼잡도(명세 6.2)
- `CongestionLookup.congestionOf`의 DB 구현 `DbCongestionLookup`: 경로의 정차역별 혼잡도와 경로 최댓값(6.1·8.1에서 사용)

마이그레이션과 로더는 바꾸지 않았다(V1~V13 그대로).

## 규칙 (모두 Asia/Seoul)

슬롯은 39칸이다: 05:30, 06:00, …, 23:30, 00:00, 00:30. **영업일**은 05:30에 시작해 다음 날 00:30 슬롯까지다. "이후 슬롯"은 이 영업일 순서를 따른다.

### A. 슬롯 결정 (통과 시각 t, 달력 날짜 d)

| t | 영업일 D | 슬롯 | firstTrain |
| --- | --- | --- | --- |
| 05:30 ≤ t ≤ 23:59 | d | t가 속한 30분 칸 | false |
| 00:00 ≤ t < 01:00 | d − 1 | 00:00 또는 00:30 | false |
| 01:00 ≤ t < 05:30 | d | 05:30 (첫차) | true |

예: 토요일 00:10 → 금요일 영업일의 00:00 슬롯. 토요일 03:00 → 토요일 05:30.

### B. dayType

영업일 D 기준이다. 일요일과 공휴일은 `SUNDAY`, 토요일은 `SATURDAY`, 나머지는 `WEEKDAY`다. 토요일인 공휴일도 `SUNDAY`다.

- 공휴일 목록은 `src/main/resources/subway-holidays.properties`의 `linkro.subway.holidays`다. 2026~2027년 공휴일(대체·임시 공휴일 포함)이 들어 있다.
  - 2026년 지방선거일(06-03)과 2026년에 새로 공휴일이 된 노동절(05-01)·제헌절(07-17)도 포함한다.
- 다른 설정처럼 환경변수 등으로 덮어쓸 수 있다.
- 매년 목록을 갱신해야 한다. 명세의 특일 정보 API 자동 갱신은 구현하지 않았다.

예:
- 공휴일 전날 밤(12-24 목요일 23:30, 12-25 00:10)은 12-24 영업일이라 `WEEKDAY`다.
- 공휴일 당일(12-25 08:00)과 그날 밤(12-26 00:10)은 12-25 영업일이라 `SUNDAY`다.

### C. 행이 없을 때

조회 키는 (노드, 방향, dayType, 슬롯)이다.

1. 행이 있으면 그 값을 쓴다.
2. 행이 없고, **같은 영업일에 그 슬롯 이후 슬롯 행이 하나도 없으면**(그 노드·방향의 막차 이후) **다음 영업일 D+1의 05:30 슬롯**으로 대체한다.
   - 대체하면 `firstTrain: true`이고, `slotDate`는 D+1, `dayType`은 D+1의 요일 유형이다.
   - D+1의 05:30 행도 없으면 회색이다.
3. 같은 영업일에 뒤 슬롯이 있는데 그 슬롯만 없으면(낮 시간 중간의 빈 슬롯 등) 대체하지 않고 회색이다.

회색(`supported: false`)일 때 `slotStart`·`slotDate`·`dayType`·`firstTrain`은 대체한 값이 아니라 A·B로 정한 원래 슬롯이다.

예(금요일 23:30이 막차 이후인 노드): 토요일 05:30 `SATURDAY`, `firstTrain: true`, `slotDate`는 토요일이다.

### D. 출처 우선순위

같은 슬롯에 source가 여러 행이면 **PREDICTED > ESTIMATED > MOCK** 순으로 하나만 쓴다(`CongestionSource` 선언 순서).

### E. 값 표시

- `pct`는 소수 1자리 **내림**(`BigDecimal`, `RoundingMode.DOWN`)이다. 예: 34.95326485813263 → 34.9, 99.96055952795814 → 99.9.
  - 내림이라 표시값이 다음 단계 경계를 넘지 않는다. 반올림하면 34.96 → 35.0인데 COMFORTABLE이 된다.
- `level`, `levelLabel`은 DB 값 그대로이고 다시 계산하지 않는다.
- 경로 최댓값 비교는 내리기 전 원값으로 한다.

### F. ESTIMATED

`CongestionSource`에 `ESTIMATED`(2호선 성수 외선, 앞뒤 역 평균 추정값)를 추가했다. OpenAPI 스키마(`CongestionData.source`)와 예시에도 반영했다.

## GET /api/v1/congestion

인증은 다른 API와 같다(JWT Bearer). `linkro.subway.congestion` 설정과 관계없이 항상 DB를 읽는다.

| 쿼리 | 필수 | 설명 |
| --- | --- | --- |
| `stationId` | O | 역 id (1 이상 정수) |
| `lineId` | O | 노선 id (1 이상 정수) |
| `direction` | O | UP, DOWN, INNER, OUTER, LOOP |
| `dayType` | date가 없으면 O | WEEKDAY, SATURDAY, SUNDAY. date가 있으면 **무시**한다(값 형식만 검사) |
| `time` | O | 통과 시각 `HH:mm` (00:00~23:59) |
| `date` | X | 통과 시각의 달력 날짜 `yyyy-MM-dd` |

- **date가 있으면**: A·B·C를 모두 적용한다. `slotDate`는 실제 조회한 영업일이다. 00:00~00:59는 전날, 막차 이후 첫차면 다음 날이다.
- **date가 없으면**: `dayType`을 영업일의 요일 유형으로 본다.
  - 05:30~23:59와 00:00~00:59는 그 dayType으로 슬롯을 조회한다.
  - 01:00~05:29는 같은 dayType의 05:30 슬롯이고 `firstTrain: true`다.
  - 다음 영업일이 필요한 대체(막차 이후)는 날짜를 모르므로 회색이다.
  - `slotDate`는 null이다.

### 응답

```json
{ "success": true, "data": { "stationId": 31, "lineId": 2, "direction": "INNER", "dayType": "WEEKDAY",
  "slotStart": "18:30", "slotDate": null, "firstTrain": false, "supported": true,
  "pct": 92.5, "level": "NORMAL", "levelLabel": "보통", "source": "PREDICTED" } }
```

막차 이후 첫차(date=2026-11-06 금요일, time=23:30):

```json
{ "success": true, "data": { "stationId": 35, "lineId": 2, "direction": "INNER", "dayType": "SATURDAY",
  "slotStart": "05:30", "slotDate": "2026-11-07", "firstTrain": true, "supported": true,
  "pct": 12.3, "level": "COMFORTABLE", "levelLabel": "쾌적", "source": "PREDICTED" } }
```

데이터 없음(회색):

```json
{ "success": true, "data": { "stationId": 31, "lineId": 2, "direction": "INNER", "dayType": "WEEKDAY",
  "slotStart": "13:00", "slotDate": null, "firstTrain": false, "supported": false,
  "pct": null, "level": null, "levelLabel": null, "source": null } }
```

- 명세 6.2의 필드에 `supported`를 더했다. 데이터 없음 형식(`supported: false`, 값 null)을 그대로 따른다.
- 시각 필드는 `"HH:mm"`, 날짜는 `"yyyy-MM-dd"` 문자열이다.

### 오류

| 상황 | HTTP | code |
| --- | --- | --- |
| 필수값 없음, id가 1 이상의 정수가 아님, direction·dayType 값 오류, time이 `HH:mm`이 아님, date가 `yyyy-MM-dd`가 아님, date 없이 dayType 없음 | 400 | `VALIDATION_ERROR` (`details`에 필드별 사유, 한 번에 모두) |
| 인증 없음·토큰 오류 | 401 | `AUTH_REQUIRED` / `TOKEN_INVALID` / `TOKEN_EXPIRED` |
| 역 없음 | 404 | `STATION_NOT_FOUND` |
| 지원 역(`is_supported = true`)인데 그 노선에 속하지 않음 | 404 | `NOT_FOUND` (`details.lineId`) |

- 미지원 역(`is_supported = false`)이 그 노선에 없으면 오류 대신 회색(200)이다. 명세 6.2의 "제공 범위 밖 역은 supported: false"를 따른다.
- 그 노드에 없는 방향(예: 1호선 노드의 INNER)은 행이 없으므로 회색이다.

## congestionOf (DbCongestionLookup)

- 정차역마다 `departAt + PathStop.offsetSec`를 Asia/Seoul로 바꾸고 A~E를 적용한다. 날짜를 알기 때문에 C의 다음 영업일 대체도 쓴다.
- 조회 키는 `PathStop.stationLineId`와 `PathStop.direction`이다.
- 환승역처럼 같은 역이 두 노드로 나오면 각각 조회한다.
- `direction`이 null인 정차역(빈 경로의 유일한 정차역)은 회색이다.
- 결과:
  - **요약**(`supported`, `pct`, `level`, `levelLabel`, `source`): 회색이 아닌 정차역 중 원값이 가장 큰 정차역의 값이다. 회색이 아닌 정차역이 없으면 `supported: false`다.
  - **`stops`**: 정차역별 `StopCongestion`(slotDate, slotStart, firstTrain, 값)이고 `RoutePath.stops` 순서다. `CongestionResult`에 덧붙인 필드라 기존 5개 필드의 의미는 그대로다. mock은 `stops`를 비워 둔다.
- **쿼리 수**: 경로의 (노드, 방향, dayType) 조합을 모아 `where (station_line_id, direction, day_type) in (...)` **한 번**으로 그 조합의 모든 슬롯을 읽고, 규칙은 메모리에서 적용한다. 다음 영업일 대체용 dayType도 같은 조회에 포함한다.

## 스위치

| `linkro.subway.congestion` | `CongestionLookup` 빈 | 기동 로그 |
| --- | --- | --- |
| 없음 또는 `mock` (기본) | `MockCongestionLookup` (`MockCongestionConfig`) | WARN `Subway congestion is MOCK: ...` |
| `db` | `DbCongestionLookup` (`DbCongestionConfig`) | INFO `Subway congestion is DB: ...` |

- 두 설정은 조건이 겹치지 않아 동시에 등록되지 않는다.
- 운영 전환 순서:
  1. 운영 DB에 혼잡도를 적재한다(`docs/congestion-load-71.md`의 `loadCongestion`).
  2. 환경변수 `LINKRO_SUBWAY_CONGESTION=db`를 설정하고 재시작한다.
- 적재 전에 켜면 모든 정차역이 회색이 된다.
- 기본값은 mock 그대로다.

## 알려진 제한

- **공휴일 목록**: 2027년까지만 있다. 2028년 목록을 넣기 전에는 그해 공휴일이 평일·토요일로 처리된다.
- **date 없는 GET**: 막차 이후 대체를 하지 않는다(날짜를 모름).
- **회색 응답의 슬롯 필드**: 대체를 시도했더라도 원래 요청 슬롯이다.
