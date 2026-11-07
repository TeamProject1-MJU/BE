# 공휴일 자동 갱신 — 한국천문연구원 특일 정보 API (#83)

혼잡도 조회(`GET /congestion`, 경로 검색)는 공휴일을 `SUNDAY`로 본다(`docs/congestion-api-79.md` 규칙 B). 지금까지는 `subway-holidays.properties`에 2026~2027년 날짜를 직접 적어 두었다. 이제 한국천문연구원 특일 정보 API로 **올해와 내년**을 자동 갱신한다. 결과는 메모리에만 두고 DB에 저장하지 않는다.

- 코드: `com.mju.linkro.subway.congestion.holiday`
  - `KasiHolidayClient`: 호출과 파싱
  - `HolidayRefresher`: 갱신 일정과 실패 규칙
  - `RefreshableHolidayCalendar`: 연도 단위 교체
  - `HolidayRefreshConfig`: `@EnableScheduling`
- `HolidayCalendar`와 그 호출부(`CongestionResolver` 등)는 바꾸지 않았다.
  - `RefreshableHolidayCalendar`가 이를 상속한 `@Primary` 빈이라, 기존 주입 지점이 그대로 갱신된 목록을 쓴다.

## 호출 규격

| 항목 | 값 |
| --- | --- |
| 요청 | `GET https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService/getRestDeInfo` |
| 파라미터 | `serviceKey`, `solYear`(yyyy), `solMonth`(2자리, 예: `03`), `_type=json` |
| 키 | 공공데이터포털 **Decoding** 인증키. 서버가 URI 템플릿 변수로 한 번만 인코딩한다(`+ / =` → `%2B %2F %3D`). Encoding 키를 넣으면 이중 인코딩된다 |
| 타임아웃 | 연결 2초, 응답 3초 (`linkro.holiday.kasi.connect-timeout`, `read-timeout`) |
| 재시도 | 없음. 실패한 연도는 다음 갱신(다음 날 03:00)에 다시 시도 |

URL과 파라미터는 공공데이터포털 문서 기준이다. 실제 호출로는 아직 확인하지 않았다(아래 "실제 호출로 확인할 항목").

### 응답 파싱

- `response.header.resultCode`가 `"00"`이 아니면 실패다.
- 키 오류는 `_type=json`이어도 XML 오류 본문으로 오는 경우가 있어서, JSON으로 읽을 수 없으면 실패로 본다.
- `response.body.items`:
  - `item`이 배열이면 각각 쓴다.
  - `item`이 객체 하나면 그 하나를 쓴다.
  - 공휴일이 없는 달은 `items`가 빈 문자열(`""`)이고, 0건으로 본다.
- `isHoliday = "Y"`인 항목의 `locdate`(yyyyMMdd, 숫자 또는 문자열)만 쓴다.
- 날짜가 요청한 연·월 밖이면 실패다.
- `totalCount`가 받은 항목 수보다 크면 실패다(페이지가 잘림).

## 갱신 규칙

- **대상**: 올해와 내년(Asia/Seoul 기준 연도). 연도마다 1~12월을 12회 호출한다.
- **교체**: 12개월이 모두 성공한 연도만 통째로 교체한다. 불변 `Set`을 담은 `Map`을 원자적으로 바꿔서, 조회는 옛 목록이나 새 목록 중 하나만 본다.
- **실패**: 한 달이라도 실패하면 그 연도는 남은 달을 호출하지 않고 **이전 목록을 유지**한다.
  - 이전 목록은 직전 성공본이고, 성공한 적이 없으면 properties 기본값이다.
  - 다른 연도는 영향을 받지 않는다.
- **목록이 없는 연도**: 갱신 대상이 아닌 연도는 properties 기본값을 쓴다. properties에 없는 연도는 공휴일 0건(주말만 반영)이다.
- **시점**:
  - 기동 직후 백그라운드 스레드에서 1회. `ApplicationReadyEvent` 뒤에 별도 데몬 스레드에서 돌아서 기동을 지연시키거나 실패시키지 않는다.
  - 매일 03:00 KST(`@Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")`).
  - 두 실행이 겹치면 순서대로 돈다(`synchronized`).
- **키 없음**: 호출하지 않고 기동 때 INFO 한 줄을 남긴 뒤 properties만 쓴다.

## 로그

| 상황 | 수준 | 예 |
| --- | --- | --- |
| 키 없음(기동 시 1회) | INFO | `KASI_SPECIAL_DAY_KEY is empty: holidays come from subway-holidays.properties only.` |
| 갱신 켜짐(기동 시 1회) | INFO | `Holiday refresh is enabled: in the background now and daily at 03:00 KST.` |
| 연도 갱신 성공 | INFO | `Holiday refresh 2027: 24 holidays` |
| 실패(HTTP 상태, 결과 코드, 읽을 수 없는 본문, 타임아웃 등) | WARN | `Holiday refresh 2027: month 7 failed (HTTP 500); keeping the previous 2027 list (24 holidays)` |

- 키, 요청 URL(키 포함), 응답 본문은 출력하지 않는다.
- 공휴일 이름도 출력하지 않는다.
- 실패 사유는 HTTP 상태 코드, 결과 코드(`result code 30` 등), 예외 클래스 이름만 남긴다.

## 운영 설정

- 서버 `~/BE/.env`에 `KASI_SPECIAL_DAY_KEY=<Decoding 키>`를 적고 재배포한다.
- `docker-compose.yml`의 `app.environment`에 `KASI_SPECIAL_DAY_KEY: ${KASI_SPECIAL_DAY_KEY:-}`가 있어 컨테이너로 전달된다.
- 선택값이라 `deploy.yml` 필수 설정 검사에는 넣지 않았다. 비어 있으면 properties만 쓴다.
- 프로퍼티 이름은 `linkro.holiday.kasi.service-key`다. 설정하지 않으면 `KASI_SPECIAL_DAY_KEY`를 읽는다.
- 기동 로그에서 `Holiday refresh is enabled`와 연도별 `Holiday refresh 2026: N holidays`를 확인한다.

## 로컬 실행

1. data.go.kr에서 "한국천문연구원_특일 정보"를 활용신청하고, 마이페이지 → 개발계정에서 **Decoding** 인증키를 복사한다.
   - 각자 본인 계정으로 발급한다. 개발계정은 보통 자동 승인되고, 키가 반영되기까지 시간이 걸릴 수 있다.
2. 레포 루트 `.env`에 한 줄을 넣는다.
   - `application.yaml`이 `.env`를 설정값으로 읽는다(`spring.config.import: optional:file:.env[.properties]`).
   - **따옴표 없이** 쓴다. properties 형식이라 따옴표가 값에 들어간다.
   ```
   KASI_SPECIAL_DAY_KEY=Decoding키값
   ```
3. IntelliJ에서 `LinkroApplication`을 실행한다(작업 디렉터리 = 프로젝트 루트). IntelliJ 실행 구성의 Environment variables에 넣어도 된다.
4. 키 없이 실행하면 기존 properties 목록만 쓴다. 다른 기능에는 영향이 없다.

- `.env`는 `.gitignore`에 들어 있다. 키를 코드·커밋·PR·단톡방에 올리지 않는다.
- 테스트는 실제 API를 부르지 않는다. `src/test`의 `application-test.yaml`과 `src/postgresTest`의 `application.properties`에 빈 키를 넣어, `.env`나 셸에 키가 있어도 호출하지 않는다.

## 실제 호출로 확인할 항목

1. 엔드포인트 `https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService/getRestDeInfo`와 파라미터 이름(`serviceKey`, `solYear`, `solMonth`, `_type`)
2. Decoding 키를 서버가 한 번 인코딩해 보내는 방식이 인증을 통과하는지
3. JSON 응답 구조(`response.header.resultCode`, `response.body.items.item`, `totalCount`)와 `locdate` 타입(숫자/문자열)
4. 공휴일이 없는 달에 `items`가 `""`로 오는지
5. 한 달 공휴일 수가 기본 `numOfRows`(10)를 넘지 않는지. 넘으면 `totalCount` 검사로 실패 처리되므로, 그때 `numOfRows`를 추가한다.
6. 키 오류·트래픽 초과 때의 응답 형식(HTTP 상태, XML 여부, 결과 코드)
7. 대체공휴일·임시공휴일·선거일이 `isHoliday = Y`로 오는지, 노동절·제헌절 포함 여부
8. 개발계정 일일 트래픽 한도. 하루 24회(기동 시 24회 추가)가 한도 안인지

## 테스트

호출은 모두 `MockRestServiceServer` 스텁이고, 응답은 위 구조로 직접 쓴 가짜 JSON이다.

- `KasiHolidayClientTest`
  - 키 1회 인코딩·파라미터
  - 배열·단일 객체·빈 `items`·문자열 `locdate`
  - 결과 코드 오류·XML 본문·페이지 잘림·월 밖 날짜·형식 오류·HTTP 500
  - 키 없음(호출 0회)
- `HolidayRefresherTest`
  - 두 연도 교체
  - 일부 월 실패 시 그 연도만 기본값 유지(WARN)
  - 직전 성공본 유지
  - 키 없음(INFO 1회, 호출 0회)
  - 기동 비차단(200ms 안에 반환)
  - 03:00 KST 스케줄 등록
  - 로그에 키·URL·공휴일 이름 없음
- `RefreshableHolidayCalendarTest`: 연도 단위 교체, 다른 연도는 기본값, 연도 밖 날짜 거부
