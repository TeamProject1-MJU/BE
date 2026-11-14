# 표지판 방향 확인 (#75, 입력 계약 보완 #132)

## 기준

API 명세 v1.12 9.1(`POST /api/v1/ai/direction-check`, multipart), 상세설계서 3.2, `AiClient.readSign`(#73, `docs/ai-client-interface.md`). 인증 필요(명세 1장), 방 멤버 확인은 없다(명세에 roomId 없음). 저장하는 테이블이 없고 이미지는 메모리에서만 쓴다(multipart 임시 파일도 만들지 않는다: `spring.servlet.multipart.file-size-threshold=6MB`, 요청 상한 6MB보다 크게 둠).

## 요청·응답

- 파트 `image`: JPG/PNG, 5MB 이하. 파일의 첫 바이트(JPEG `FF D8 FF`, PNG 시그니처)로 종류를 정하고 클라이언트가 보낸 Content-Type은 믿지 않는다. 그다음 AI를 부르기 전에 이미지를 메모리에서 실제로 디코딩해 보고(ImageIO, 디스크 캐시 없음) 열리지 않거나 일부만 있는 파일, 가로×세로 5천만 픽셀 초과는 IMAGE_INVALID로 막는다.
- 파트 `route`: JSON 문자열 `{"stationId":105,"lineId":2,"direction":"INNER"}`. `stationId`, `lineId`는 양의 정수로 항상 필수. `headsign`이 있으면 기존처럼 비교하며 `direction` 생략 가능. `headsign`을 생략하거나 null·공백으로 보내면 `direction` 필수. 둘 다 없으면 VALIDATION_ERROR.
- FE는 SUBWAY 단계의 `stationId`, `lineId`, `directionCode`를 사용하고 `directionCode`를 `direction`에 그대로 넣는다. 허용값은 대문자 UP/DOWN/INNER/OUTER/LOOP. 전달한 코드가 잘못되면 headsign 유무와 관계없이 VALIDATION_ERROR. 방면명·행선지를 임의 생성하지 않는다.
- 응답 `data`: `result`(CORRECT/WRONG/RETAKE), `reason`(CORRECT면 null), `recognized`(AI가 읽은 `stationName`·`lineName`·`headsign`, 못 읽은 값은 null), `expected`(요청 route를 DB 이름으로 바꾼 값), `message`.
- `expected.headsign`은 요청에 알려진 headsign이 없으면 **필드를 생략하지 않고 null**로 반환한다. 방향 코드로 행선지를 만들지 않는다. `recognized.headsign`은 사진에서 읽은 방면 또는 명시적 방향 표시다.
- headsign 없는 요청은 DB의 지원 역 및 실제 RIDE 연결로 역·노선·방향 조합을 확인한다. 방향 판정은 사진에 명시된 상행/하행, 내선(순환)/외선(순환), 응암순환을 각각 UP/DOWN, INNER/OUTER, LOOP와 비교한다. AI가 CORRECT로 응답해도 명시적 방향 근거가 없으면 RETAKE/MISSING_INFO로 보정한다. 반대 방향은 UP↔DOWN, INNER↔OUTER만 확정하며 LOOP를 UP/DOWN의 반대로 간주하지 않는다.
- 사진에서 다른 노선임이 확인되면 WRONG/LINE_NOT_ON_ROUTE, 반대 방향이 명확하면 WRONG/DIRECTION_MISMATCH. 표지판 아님은 RETAKE/NOT_A_SIGN, 판독 불가는 RETAKE/UNREADABLE, 비교 근거 부족은 RETAKE/MISSING_INFO. RETAKE는 유효한 요청의 판독 결과(HTTP 200)이며 잘못된 요청(HTTP 400)과 구분한다.
- headsign 없는 일반 노선·지선 사진에 행선지명만 있으면 방향을 추정하지 않고 RETAKE/MISSING_INFO로 처리한다. 이때 재촬영만으로 해결되지 않을 수 있다. 사진에 명시된 방향 표시 또는 경로에서 확보한 정확한 headsign이 필요하다.

## 시간표 조사 결과와 계약 결정 (#132)

`TrainTimetable.TrainKey`는 운행일 종류·노선·열차 코드·출발역·종착역을 보존한다. 로더는 원본 direction 열로 행선지를 매핑하지 않고 정차 순서로 RIDE 연결을 구성한다. 조회는 연결(edge 또는 다음 역), 운행일, 시각 등으로 열차를 선택한다. `TimetableRouter`가 선택한 열차의 terminal이 `TimedLeg.terminal`에 담긴다. 현재 입력 세 개에는 선택 열차·다음 역·운행일·시각이 없어 이를 해당 경로의 행선지로 확정할 수 없다. 이번 변경은 시간표에서 임의의 열차를 골라 채우지 않는다.

| 구간 | 시간표 terminal | stationId + lineId + direction만으로 행선지 확정 |
| --- | --- | --- |
| 일반 노선 | 선택 열차별 조회 가능 | 보장 불가: 같은 방향의 단축 운행·복수 종착역 |
| 2호선 내선·외선 | 선택 열차별 조회 가능 | 보장 불가: 순환 방향은 종착역과 다름 |
| 지선 | 선택 열차별 조회 가능 | 보장 불가: 분기에서 방향 코드만으로 이용 열차·지선 미확정 |
| 순환 구간 | 열차별 terminal 존재 | 보장 불가: LOOP는 진행 구간 표시이며 행선지가 아님 |

따라서 하위 호환 headsign 비교와 명시적 direction 비교를 허용하되, 미상 행선지는 null로 유지하고 불확실한 판정은 MISSING_INFO로 처리한다.

## 오류

| 상황 | 코드 |
| --- | --- |
| image 없음·빈 파일·JPG/PNG 아님·5MB 초과·디코딩 안 되는 손상 파일 | IMAGE_INVALID (400) |
| route 없음·JSON 아님·필드 오류, 존재하지 않는 lineId, headsign과 direction 모두 없음 | VALIDATION_ERROR (400) |
| headsign 없는 요청의 미존재/미지원 역, 잘못된 역·노선 조합, 해당 역·노선에 없는 승차 방향 | VALIDATION_ERROR (400) |
| 기존 headsign 기반 요청의 존재하지 않는 stationId (하위 호환) | STATION_NOT_FOUND (404) |
| AI 호출 429 / 그 밖의 실패 | AI_QUOTA_EXCEEDED (429) / AI_UNAVAILABLE (503) |

이미지·route 검사는 AI 호출 전에 한다. 업로드 크기 제한은 `spring.servlet.multipart.max-file-size=5MB`(Spring 기본 1MB)이고, 초과하면 `GlobalExceptionHandler`가 IMAGE_INVALID로 바꾼다.

## FE 요청·응답 예시 (headsign 없음)

아래는 `route` 파트 JSON이다. 모든 요청에 별도의 `image` 파트(JPG/PNG)와 인증 헤더가 필요하다. ID 105/2는 기존 예시 값이며 실제 전송 시 경로 검색 결과의 승차 역·노선 ID를 사용한다.

### 정상: 명시적 내선순환 표시 판독 (200)

요청:
```json
{"stationId":105,"lineId":2,"direction":"INNER"}
```

응답:
```json
{"success":true,"data":{"result":"CORRECT","reason":null,"recognized":{"stationName":"강남","lineName":"2호선","headsign":"내선순환"},"expected":{"stationName":"강남","lineName":"2호선","headsign":null},"message":"올바른 방향입니다."}}
```

### RETAKE: 행선지만 보이고 방향 근거 부족 (200)

요청:
```json
{"stationId":105,"lineId":2,"direction":"INNER"}
```

응답:
```json
{"success":true,"data":{"result":"RETAKE","reason":"MISSING_INFO","recognized":{"stationName":"강남","lineName":"2호선","headsign":"성수 방면"},"expected":{"stationName":"강남","lineName":"2호선","headsign":null},"message":"경로와 표지판의 방향을 비교할 정보가 부족합니다. 방향 표시가 보이게 다시 촬영해 주세요."}}
```

### VALIDATION_ERROR: headsign과 direction 모두 누락 (400)

요청:
```json
{"stationId":105,"lineId":2}
```

응답:
```json
{"success":false,"error":{"code":"VALIDATION_ERROR","message":"요청 값이 올바르지 않습니다.","details":{"direction":"headsign이 없으면 direction은 필수입니다."}}}
```

## API 명세 9.1 반영 문구

> route.stationId와 route.lineId는 필수 양의 정수이다. route.headsign은 선택이며, 생략/null/공백이면 route.direction이 필수이다. direction에는 경로 검색 SUBWAY 단계의 directionCode(UP/DOWN/INNER/OUTER/LOOP)를 그대로 전달한다. headsign이 있으면 기존 행선지 비교를 유지하고 direction 생략을 허용한다. headsign과 direction이 모두 없거나 방향 코드가 잘못되면 VALIDATION_ERROR(400)이다. headsign 없는 요청은 실제 역·노선의 승차 방향 조합을 검증하며, 잘못된 조합은 VALIDATION_ERROR(400)이다. BE와 FE는 방향 코드로 행선지를 임의 생성하지 않는다. 알 수 없는 expected.headsign은 명시적 null로 반환한다. 명시적 방향 근거가 부족하거나 비교가 불확실하면 CORRECT로 추측하지 않고 RETAKE/MISSING_INFO(200)를 반환한다. RETAKE는 사진 판독 결과이고 VALIDATION_ERROR는 요청 오류이다. 기존 이미지 검증과 사진 미저장 정책은 유지한다.

## 검증 기록 (#132)

- 자동 테스트: `./gradlew.bat test --no-daemon --tests '*sign*' --tests '*OpenAiClientTest*'` — 2026-11-14, 60개 성공, 실패/오류/스킵 0.
- 검증 범위: 기존 headsign 요청, 누락/null/공백 headsign, 필수 입력·코드 오류, 역·노선·방향의 실제 연결 검증(H2 JDBC), 올바른 방향/반대 방향/다른 노선/정보 부족, 일반 방향·내선/외선·지선·단방향 순환, multipart 요청·응답·오류 계약, 생성된 Swagger 응답·오류 예시, OpenAI 요청 프롬프트, 이미지 손상·크기·메모리 업로드 회귀.
- 실제 사진 검증: **미실시**. 테스트는 생성된 JPEG/PNG와 mock AI/HTTP 응답을 사용하며 현장 표지판 인식 정확도를 입증하지 않는다. 실제 사진이나 외부 AI를 호출하지 않았고 API 키를 읽거나 기록하지 않았다.
- 실제 운영 시간표/DB를 이용한 종단 검증: 미실시. 시간표 분석은 소스 구조 조사이며 연결 검증은 테스트 DB의 분기·순환 픽스처를 사용한다.

## 응답 시간

AI 호출 시간은 로그 `Sign check: result=… ai=…ms`로 본다. 표지판 판독 설정(표지판 경로에만 적용):

- `OPENAI_VISION_READ_TIMEOUT` 기본 10s (예전 4s). 실제 모델 호출이 4s를 넘겨 `AI_UNAVAILABLE`이 났기 때문이다. 이 값은 응답을 읽는 시간의 상한일 뿐 전체 응답 시간의 상한이 아니다(연결 시간 `OPENAI_CONNECT_TIMEOUT`, 업로드·이미지 디코딩·DB 조회 등은 따로 걸린다). 그래서 앱(프론트)의 요청 타임아웃은 read 값이 아니라 연결+읽기 등을 합친 전체 응답 시간을 기준으로 잡는다. 응답 목표 5초는 이 값보다 짧으며, 실측 지연은 모델·추론 강도에 따라 다르다.
- `OPENAI_VISION_REASONING_EFFORT` 기본 `low`. 추론 강도를 낮춰 지연을 줄인다. 비어 있으면 `low`, 추론을 끄려면 `none`. 정확도는 낮은 강도에서 RETAKE가 늘 수 있다(정답 사진을 다시 찍으라고 하는 쪽으로 틀린다).
