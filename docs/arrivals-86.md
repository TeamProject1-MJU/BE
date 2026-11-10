# 실시간 열차 도착 정보 GET /stations/{stationId}/arrivals (#86)

서울 열린데이터광장 "지하철 실시간 도착정보"(`realtimeStationArrival`)를 불러, 한 역·노선(·방향)의 다음 열차를 돌려준다. 경로 응답의 `waitSec`, 환승 대기 추정은 이 이슈 범위가 아니다.

- 코드: `com.mju.linkro.subway.arrival`
  - `SeoulArrivalClient`, `ArrivalService`, `ArrivalStationRepository`, `ArrivalStationNames`
  - `SeoulSubwayIds`(외부 코드 상수), `controller`, `dto`
- 이름 예외 매핑: `src/main/resources/arrival/station-query-names.csv`

## 요청과 응답

`GET /api/v1/stations/{stationId}/arrivals` (JWT 필요)

| 쿼리 | 필수 | 설명 |
| --- | --- | --- |
| `lineId` | O | 노선 id(`GET /lines`의 id) |
| `nextStationId` | X(권장) | 같은 노선의 다음 역 id. 그 방향 열차만 |
| `directionCode` | X | UP/DOWN/INNER/OUTER/LOOP. `nextStationId`로 방향을 정하지 못할 때 쓴다 |
| `limit` | X | 기본 3, 1~10 |

| 상황 | 응답 |
| --- | --- |
| `lineId` 없음, id·limit·directionCode 형식 오류 | 400 `VALIDATION_ERROR` |
| 없는 `stationId` | 404 `STATION_NOT_FOUND` |
| 1~8호선이 아닌 노선, 그 역에 없는 노선, 미지원 역 | 200 `available=false`, `reason=UNSUPPORTED_STATION` |
| 키 없음 | 200 `available=false`, `reason=DISABLED` |
| 외부 `INFO-200` | 200 `available=false`, `reason=NO_DATA` |
| 외부 다른 코드, 네트워크 오류·타임아웃, JSON 아님 | 200 `available=false`, `reason=UPSTREAM_ERROR` |
| 외부 `INFO-000` | 200 `available=true`, `reason=null` |

`data`: `available`, `reason`, `receivedAt`, `trains[]`.
- `receivedAt`: 서버가 외부 응답을 받은 시각(ISO-8601, `+09:00`). 외부 응답이 없으면 null이다.
- `trains[]`의 각 항목:
  - `destinationName`: `bstatnNm`(종착역)
  - `directionCode`: `updnLine` 변환값(상행 UP, 하행 DOWN, 내선 INNER, 외선 OUTER, 그 밖 null)
  - `secondsUntil`: nullable
  - `message`: `arvlMsg2` 그대로
  - `lastTrain`: `lstcarAt == "1"`
- `INFO-000`인데 걸러진 열차가 0건이면 `available=true`, `trains: []`다.

## 외부 API (2026-11-10 서버 실제 호출로 확인)

- `GET http://swopenAPI.seoul.go.kr/api/subway/{KEY}/json/realtimeStationArrival/0/30/{역이름}`
  - 키와 역 이름은 URI 변수로 한 번만 인코딩한다.
  - 역 이름은 앞뒤 공백을 지운다.
  - 타임아웃은 연결·읽기 각 3초이고, 재시도는 하지 않는다.
- 결과 판정은 HTTP 상태나 `status`가 아니라 **code**로 한다.
  - 정상: `errorMessage.code = "INFO-000"`, 목록은 `realtimeArrivalList`
  - 데이터 없음·오류: 최상위 `{"status":500,"code":"INFO-200",...}`이고 `errorMessage`가 없다. `INFO-200`은 NO_DATA, 그 밖의 code는 UPSTREAM_ERROR다.
  - HTTP 500으로 와도 본문의 code로 판단한다(`exchange`로 본문을 읽음).
- 한 응답에 여러 노선이 섞인다(건대입구: 1002, 1007). `subwayId`로 거른다.
- 쓰는 필드: `subwayId`, `updnLine`, `trainLineNm`, `bstatnNm`, `statnId`, `statnFid`, `statnTid`, `barvlDt`, `arvlCd`, `arvlMsg2`, `arvlMsg3`, `recptnDt`, `lstcarAt`, `btrainSttus`, `btrainNo`
- 역 ID는 `100{노선}000{3자리}` 형식이다. 관측값: 2호선 211/212/213, 6호선 610~615·617~619, 7호선 726/727/728.
- 6호선 순환 구간(역촌·불광·독바위·연신내·구산)과 디지털미디어시티에서 `statnTid`는 방면의 다음 역과 일치했다. 순환 구간의 `updnLine`은 모두 "하행"이다.
- `arvlCd` 관측값과 `barvlDt`
  - 1: 도착, 3: 전역 출발, 5: 전역 도착, 99: 운행중
  - 99에서 `barvlDt`는 `arvlMsg2`의 분·초와 같았다(170 = "2분 50초 후").
- 응답 순서는 시간순이 아니고, `recptnDt`는 건마다 다르다(KST, 타임존 없음).
- 조회 이름은 공식 표기여야 한다. "응암", "새절", "증산"은 `INFO-200`이고 괄호 부기 이름은 `INFO-000`이었다.

## 처리 규칙

1. **노선**: `subwayId`가 그 노선 것만 남긴다. 매핑은 `SeoulSubwayIds.SUBWAY_IDS` 한 곳에 있다(`1`→`1001` … `8`→`1008`).
2. **방향**
   - **`nextStationId`가 있으면**
     - 2호선: 다음 역의 `station_code`(3자리)로 외부 ID `1002000{code}`를 만들어 `statnTid`와 비교한다.
     - 그 밖의 노선: 우리 그래프의 RIDE 간선(이 역 → 다음 역)의 방향을 구해 `updnLine`과 비교한다.
     - 다음 역이 그 노선에 없거나 이웃이 아니면 `directionCode`로 넘어간다.
   - **`directionCode`만 있으면**: `updnLine`과 비교한다(UP↔상행, DOWN↔하행, INNER↔내선, OUTER↔외선).
   - **LOOP**(파라미터든 간선이든)이면 `updnLine`으로 거르지 않는다. 순환 구간은 모두 "하행"이기 때문이다.
   - **둘 다 없으면**: 그 노선의 모든 열차를 돌려준다.
   - **응암(조회 이름 `응암순환(상선)`)**: 방향 필터를 적용하지 않는다(`SeoulSubwayIds.NO_DIRECTION_FILTER`).
3. **남은 시간**: 기준은 현재 KST − `recptnDt`(행의 나이)다.
   - `arvlCd` 0·1이면 0이다.
   - `arvlCd` 2이면 제외한다(이미 출발).
   - 그 밖(3·4·5·99와 미관측 값)이면 `max(0, barvlDt − 나이)`다.
   - `barvlDt`가 비었거나 숫자가 아니면 `secondsUntil=null`이고 `message`만 둔다.
   - 나이가 300초를 넘으면 제외한다. `recptnDt`를 읽을 수 없어도 제외한다.
4. **정렬**: `secondsUntil` 오름차순(null은 뒤, 같으면 원래 순서) 뒤 `limit`건이다.
5. **캐시**
   - 키는 (조회 이름, `subwayId`)다. 성공 응답은 15초(`linkro.arrival.cache-ttl`), `INFO-200`과 실패는 5초(`linkro.arrival.failure-cache-ttl`) 동안 둔다.
   - 같은 키의 동시 요청은 한 번만 외부를 호출한다(single-flight).
   - 남은 시간은 캐시된 행으로 요청마다 다시 계산하므로 캐시 중에도 줄어든다.
6. **역 이름**
   - 조회 이름 = 예외 매핑(노선, 우리 이름 → 공식 이름), 없으면 우리 `stations.name`(trim)이다.
   - 초기 예외 5개는 모두 실제 호출로 확인했다: 응암 → 응암순환(상선), 새절 → 새절(신사), 증산 → 증산(명지대앞), 월드컵경기장 → 월드컵경기장(성산), 어린이대공원 → 어린이대공원(세종대).
   - 목록에 없는 역이 `INFO-200`이면 NO_DATA다.

## 우리 DB 확인 결과

- **역 이름**: `응암`, `새절`, `증산`, `월드컵경기장`, `어린이대공원`으로 저장돼 있어 공식 표기가 아니다. 예외 5개가 모두 필요하다. 괄호가 있는 이름은 `총신대입구(이수)`뿐이다.
- **역 코드로 외부 ID 만들기**
  - 2호선 `station_code`는 모두 3자리이고 관측 ID(211=성수, 212=건대입구)와 같다. **가능**하다.
  - 5~8호선은 4자리(응암 2611, 건대입구 7호선 2729 등)라 관측 ID(6호선 610~, 7호선 726~728)와 맞지 않는다. **불가**라서 간선 방향 + `updnLine`으로 대신한다.
  - 1·3·4호선은 3자리지만 외부 ID와 같은지 확인하지 않아 간선 방향 방식을 쓴다.

## 설정과 보안

- 키: 환경변수 `SEOUL_ARRIVAL_API_KEY`(프로퍼티 `linkro.arrival.seoul.api-key`가 있으면 그것). 비어 있으면 외부를 호출하지 않고 `DISABLED`다.
  - 운영: 서버 `~/BE/.env`에 넣고 재배포한다. `docker-compose.yml`에 `SEOUL_ARRIVAL_API_KEY: ${SEOUL_ARRIVAL_API_KEY:-}`가 있다. `deploy.yml` 필수 검사에는 넣지 않았다(선택값).
  - 로컬: 레포 루트 `.env`에 `SEOUL_ARRIVAL_API_KEY=키`(따옴표 없이)를 넣는다. `application.yaml`이 `.env`를 읽는다.
- 키가 URL 경로에 들어간다.
  - 호출 URL, 요청 객체, 외부 예외 메시지는 로그·응답·예외 메시지에 남기지 않는다.
  - 실패는 `ArrivalUpstreamException("Seoul arrival request failed: <code 또는 예외 이름>")`으로 감싼다. 원인 예외를 붙이지 않고 스택 위치만 옮긴다.
- 로그
  - INFO: 역 이름, `subwayId`, 결과 코드, 건수, 걸린 시간
  - WARN: 실패 사유
- 테스트는 실제 API를 부르지 않는다.
  - 테스트 설정(`application-test.yaml`, postgresTest `application.properties`)에서 키를 빈 값으로 고정했다. `.env`나 셸에 키가 있어도 앱 컨텍스트는 `DISABLED`다.
  - 스텁 테스트는 가짜 키로 클라이언트를 직접 만든다.

## 미확인 항목

| 항목 | 현재 처리 |
| --- | --- |
| 응암 역(`응암순환(상선)`) 방향 매칭: 상행 Fid/Tid 611/610, 하행 봉화산행 610/611로 이웃 역 규칙과 맞지 않음 | 방향 필터 없이 노선만 거름 |
| `lstcarAt = 1`의 의미(막차 여부, 심야 확인 필요) | `lastTrain = (lstcarAt == "1")` |
| 급행 `btrainSttus` 값 | 읽기만 하고 응답에 쓰지 않음 |
| `arvlCd` 0·2·4(미관측) | 0 → 0초, 2 → 제외, 4 → `barvlDt` 사용(이슈 규칙) |
| 1~8호선 `subwayId`가 100N 패턴인지(관측: 1002, 1006, 1007) | `SeoulSubwayIds.SUBWAY_IDS`에 100N으로 둠 |
| 1·3·4호선 역 ID가 우리 3자리 코드와 같은지 | 같다고 보지 않고 간선 방향 방식 사용 |
| 2호선 지선(성수지선·신정지선) 역 ID와 `updnLine` | 2호선 규칙(ID 비교) 그대로 적용 |
| 전체 역의 조회 이름 일치(괄호 부기 역이 더 있을 수 있음, `총신대입구(이수)`·7호선 `이수` 포함) | 매핑에 없으면 DB 이름. `INFO-200`이면 NO_DATA. 전체 점검은 별도 작업 |
| 일일 호출 한도(15초 캐시로 한 역·노선을 하루 종일 부르면 약 5,760회) | 확인 필요(발급 페이지) |
| `updnLine` UP/DOWN이 우리 그래프 UP/DOWN과 모든 노선·지선에서 같은지 | 같다고 보고 비교 |

## 테스트

외부 호출은 모두 `MockRestServiceServer` 스텁이고, 응답은 위 필드로 직접 만든 픽스처(`src/test/resources/arrival/`)다.
- 픽스처 목록
  - 건대입구 2·7호선 혼합
  - 6호선 순환 구간 역촌
  - 응암순환(상선) 4건
  - `INFO-200` 최상위 응답
  - 다른 오류 code

- `SeoulArrivalClientTest`
  - 키·이름 1회 인코딩
  - `INFO-000` 파싱
  - HTTP 500 + `INFO-200` → NO_DATA
  - 다른 code·JSON 아님·타임아웃 → 안전한 예외(메시지·문자열에 키·URL 없음)
- `ArrivalServiceTest`
  - 노선 필터, 정렬, `secondsUntil` 보정, `arvlCd`별 처리, 오래된 건 제외, 막차
  - 방향: 2호선 ID 비교, 7호선 간선 방향 대체, `directionCode`, LOOP, 응암 예외
  - 이름 변환·trim·노선별 매핑
  - NO_DATA·UPSTREAM_ERROR·키 없음·미지원 노선·없는 역
  - 캐시 히트·만료·실패 5초, 캐시 중 남은 시간 감소, 동시 요청 1회 호출
  - 로그에 키·URL 없음
- `ArrivalApiPostgresTest`(V1~V13 시드)
  - 시드 사실(이름, 2호선 3자리·5~8호선 4자리 코드, 7호선 건대입구→어린이대공원 = UP)
  - 정상 응답·필드
  - 미지원 노선 200
  - 400·404·401
  - OpenAPI
