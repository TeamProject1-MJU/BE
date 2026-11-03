# 혼잡도 PREDICTED 적재 (#71)

AI 파트가 전달한 혼잡도 예측(모델 `HGB_20261103_39T`)을 `congestion_stats`에 `source = PREDICTED`로 넣는 도구와, 이를 받기 위한 V13 마이그레이션이다. 혼잡도 조회 API와 `congestionOf`는 ⑦에서 구현한다.

## V13 (`V13__widen_congestion_pct_and_slots.sql`)

| 변경 | 전(V10) | 후(V13) | 이유 |
| --- | --- | --- | --- |
| `congestion_pct` | `numeric(5,1)` | `numeric` (자릿수 지정 없음) | AI 원값은 반올림하지 않은 값이다. 소수 1자리로 반올림하면 전체 60,914행 중 112행이 `ck_cong_level`(단계 경계)과 충돌한다. 원값 그대로면 0행이다 |
| `ck_cong_slot` | 분 0/30, 06:00~23:00 (35칸) | 분 0/30, 06:00~23:30 또는 00:00, 00:30 (38칸) | AI 파일이 23:30, 00:00, 00:30 슬롯을 준다 |

- 제약 이름(`ck_cong_slot`, `ck_cong_level`, `ck_cong_pct`, `ck_cong_model`, `uq_cong_slot_source`)은 그대로다. `ck_cong_level`은 자릿수 없는 numeric에서도 그대로 동작한다(34.999999999999 → COMFORTABLE, 35 → RELAXED).
- V1~V12는 바꾸지 않았다. ERD v1.13의 `numeric(5,1)`과 "06:00~23:00" 주석은 이제 V13과 다르다(아래 "⑦에서 고칠 목록").

## 실행 방법

정식 앱 시작 경로와 분리된 독립 도구다(`com.mju.linkro.subway.congestion.load.CongestionLoadTool`). Spring 컨텍스트와 Flyway를 쓰지 않는다. 대상 DB는 먼저 V13까지 마이그레이션돼 있어야 한다(앱을 한 번 띄우면 된다).

```
DB_URL=jdbc:postgresql://localhost:5432/linkro DB_USERNAME=linkro DB_PASSWORD=... \
  ./gradlew loadCongestion --args="--csv='/path/predictions_for_import.csv' --manifest='/path/manifest.json'"
```

- DB 접속 정보는 환경변수 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`로만 받는다. 앱과 달리 `.env`를 읽지 않는다.
- `--args` 안의 경로에 공백이 있으면 작은따옴표로 감싼다(예: `팀프로젝트 1`).
- 결과: 성공하면 종료 코드 0과 리포트, 실패하면 종료 코드 1이고 아무것도 쓰지 않는다.
- AI 전달 파일은 레포에 넣지 않는다(용량). 경로를 인자로 받는다.

## 적재 규칙

1. **파일 검증**: 먼저 CSV의 SHA-256을 `manifest.json`의 `output_sha256[<CSV 파일명>]`과 비교한다. 다르면 DB에 접속하기 전에 중단한다. 모든 행의 `model_version`이 manifest의 `model_version`과 같아야 하고, `source`는 `PREDICTED`만 받는다.
2. **값**:
   - `congestion_pct`는 CSV 문자열을 그대로 `BigDecimal`로 만들어 넣는다(double·float를 쓰지 않음).
   - `congestion_level`·`congestion_level_label`은 AI 값을 그대로 쓰고 다시 계산하지 않는다.
   - `model_version`은 파일 값을 쓴다.
3. **슬롯**: 허용 슬롯은 06:00~23:30(30분 단위 36칸)과 00:00, 00:30, 모두 38칸이다.
   - 05:30 행은 적재하지 않고 건수만 센다.
   - 그 밖의 슬롯도 따로 세고 적재하지 않는다(이번 파일에는 없음).
4. **노드 매칭**: (호선, 정규화한 역명)으로 `station_lines`를 찾는다. `station_code`는 쓰지 않는다.
   - 정규화는 AI 이름과 `stations.name` 양쪽에 똑같이 한다: 앞뒤 공백 제거 → 괄호와 그 안 제거 → 끝의 "역" 제거. 예: `서울역`→`서울`, `신촌(지하)`→`신촌`, `자양(뚝섬한강공원)`→`자양`, `강동(하남검단산)`→`강동`.
   - 별칭: `이수` → `총신대입구(이수)` (7호선). 4호선 `총신대입구`는 정규화만으로 맞는다.
   - 매칭되지 않은 행은 적재하지 않고 역명별로 센다(리포트 상위 20개).
   - 정규화 후 같은 키(노드, 방향, 요일, 슬롯)가 두 행 이상이면 중단한다.
5. **방향**: AI 방향이 그 노드의 시드 방향 집합(그 노드에 닿는 RIDE 간선의 방향)에 있으면 그대로 넣는다. 없으면 적재하지 않고 (호선, 방향)별로 센다. 임의로 변환하지 않는다.
   - 2호선 성수지선·신정지선 노드는 시드가 UP/DOWN뿐이라 INNER/OUTER 행이 빠진다.
   - 6호선 응암순환 노드는 LOOP뿐이라 UP/DOWN 행이 빠진다.
   - 방향 매핑은 AI 답변 후 후속 이슈에서 정한다.
6. **요일**: `day_type`은 AI 값을 그대로 저장한다. 00:00·00:30 행도 바꾸지 않는다. 이 값은 그 영업일(전날 밤에 시작한 운행일)의 요일 유형이다.
7. **저장**:
   - 한 트랜잭션에서 임시 테이블에 모은 뒤 `uq_cong_slot_source` 기준으로 upsert한다(`on conflict ... do update`).
   - 같은 파일을 다시 실행해도 행 수는 그대로이고 모두 "갱신"으로 센다.
   - 어떤 행이든 DB 제약(예: `ck_cong_level`)에 걸리면 전체를 롤백한다.

## 실제 파일 적재 결과 (임시 PostgreSQL 16, V1~V13)

`predictions_for_import.csv` (SHA-256 `69af1955…3245`, manifest와 일치)

```
model_version: HGB_20261103_39T
read rows: 60914
excluded 05:30 slot: 1588
excluded other slots: 0
node match failures: 0
direction mismatches: 2090
  2호선 INNER: 770
  2호선 OUTER: 770
  6호선 DOWN: 550
loaded rows: 57236 (new 57236, updated 0)
```

- 적재된 노드는 277개 중 264개이고, 슬롯 범위는 00:00~23:30이다.
- 다시 실행하면 `new 0, updated 57236`이고 행 수는 그대로였다. 실행 시간은 각 약 4초였다.
- 값 왕복을 확인했다: 서울역 1호선 평일 06:00 UP = `21.423461136679226`, DOWN = `11.453633590479066`(파일 값과 같음).
- SHA가 틀린 manifest로 실행하면 중단되고 행 수는 그대로다.

## 자정 이후 조회 규칙 (⑦에서 구현)

- **00:00~00:59**: 전날 날짜의 요일 유형(공휴일이면 SUNDAY)으로 00:00 또는 00:30 슬롯을 조회한다. 예: 토요일 00:10은 금요일 영업일이라 WEEKDAY, 00:00 슬롯이다.
- **01:00~05:59**: 해당 슬롯이 없어서 데이터가 없다.
- 명세 6.2의 현재 규칙("23:30 이후는 다음 날 06:00 첫차 슬롯")은 이번 슬롯 확장과 맞지 않는다. ⑦에서 명세와 함께 정리한다.

## API 표시

- DB에는 원값을 저장한다. API의 `pct`는 소수 1자리로 **내림**해서 보여준다(⑦에서 구현).
- 반올림하면 단계와 어긋날 수 있다(예: 34.96 → 35.0인데 COMFORTABLE). 그래서 내림을 쓴다.
- `level`·`levelLabel`은 저장된 AI 값을 그대로 내려준다.

## 알려진 제한

- **방향 불일치로 빠지는 2,090행**: 2호선 지선의 INNER/OUTER와 6호선 응암순환의 DOWN이다.
- **데이터가 없는 노드 13개**: 모두 위 방향 불일치 때문이다. AI 파일에는 이 역들의 행이 있지만 방향이 시드와 맞지 않는다.
  - 2호선 성수지선 4개: 용답, 신답, 용두, 신설동
  - 2호선 신정지선 4개: 도림천, 양천구청, 신정네거리, 까치산
  - 6호선 응암순환 5개: 역촌, 불광, 독바위, 연신내, 구산
  - 방향 매핑이 정해질 때까지 이 노드의 혼잡도는 없다(⑦에서 `supported: false`). 미지원 행(`unsupported_conditions.csv`)은 적재하지 않는다.
- **`service_minute`, `service_day_offset`**: DB에 저장하지 않는다. 자정 이후 행은 `slot_start`(00:00/00:30)와 원래 `day_type`으로 구분한다.
- **ck_cong_slot의 초 단위**: 분만 검사해서 초가 있는 값(예: 06:00:15)은 막지 못한다. 로더는 `HH:MM`만 넣는다.
- **MOCK과 같은 슬롯**: 같은 슬롯에 MOCK 행이 있으면 조회 우선순위(PREDICTED > MOCK)로 처리한다(⑦).

## ⑦에서 고칠 목록 (35칸·23:00 가정)

| 파일 | 줄 | 내용 |
| --- | --- | --- |
| `src/main/java/com/mju/linkro/subway/api/CongestionLookup.java` | 13 | Javadoc "Times outside 06:00~23:00 use the next first-train 06:00 slot (6.2)" |
| `docs/subway-module-interface.md` | 69 | "06:00~23:00 밖은 다음 첫차 06:00 슬롯을 쓴다(6.2)" |
| `docs/subway-schema-48.md` | 32 | `ck_cong_slot` "분이 0 또는 30이고 06:00~23:00 (35개 슬롯)" |
| `docs/erd_v1.13_20261029.dbml.txt` | 145, 146 | `slot_start` "06:00~23:00 시작 슬롯만", `congestion_pct numeric(5,1)` |
| API 명세(docx) | 변경 이력 v1.8 | "06:00~23:00(35슬롯)으로 제한" |
| API 명세(docx) | 6.2 쿼리 설명 | "이 슬롯이 06:00~23:00 밖이면 … 첫차 규칙" |
| API 명세(docx) | 6.2 운행일과 dayType | "06:00~23:00에 시작하는 30분 슬롯(35개)만 제공 … 23:30 이후 … 06:00 슬롯" |
| API 명세(docx) | 13장 congestion_stats | "slot_start는 06:00~23:00 시작 슬롯(35개)만" |
| API 명세(docx) | 15장 확인 항목 | "06:00~23:00 시작 슬롯 35개가 모두 있는지 확인" |
| API 명세(docx) | 부록 A | `ck_cong_slot ... BETWEEN TIME '06:00' AND TIME '23:00'` |

V10을 대상으로 한 기존 테스트(`SubwaySchemaMigrationPostgresTest`)는 V10까지만 적용해서 V10의 23:30 거부를 검증하므로 그대로 둔다. `MockCongestionLookup`은 시각을 쓰지 않아 슬롯 가정이 없다.
