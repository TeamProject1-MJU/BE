# 노선·역 조회 API와 StationLookup (#65)

API 명세 v1.12의 5.1~5.3을 구현한다. 데이터는 V10 스키마와 V11 시드(노선 8, 역 240, 역×노선 277)를 읽기만 한다. 새 JPA 엔티티나 마이그레이션은 없다.

- 패키지: `com.mju.linkro.subway.station`(controller, service, repository, dto), `com.mju.linkro.subway.api`(`StationLookup`, `StationInfo`)
- 조회: `StationReadRepository`가 `NamedParameterJdbcTemplate`로 읽는다(`RoomReadRepository`와 같은 방식)

## API 계약

모두 인증이 필요하다. 토큰이 없으면 401 `AUTH_REQUIRED`이고, 응답은 공통 `ApiResponse`로 감싼다.

| 메서드·경로 | 성공 data | 오류 |
| --- | --- | --- |
| GET /api/v1/lines | `[{ id, lineCode, name, color }]`, sort_order·id 순 | 401 |
| GET /api/v1/stations | `{ items: [역], page, size, totalElements }` | 400 `VALIDATION_ERROR`, 401 |
| GET /api/v1/stations/{id} | 역 | 404 `STATION_NOT_FOUND`, 400(id 형식 오류, 전역 처리), 401 |

역 항목: `{ id, name, latitude, longitude, isSupported, lines: [{ lineId, lineName, color, stationCode }] }`
- 한 역이 여러 노선에 속하면 한 건이고, `lines`에 모든 노선을 sort_order·id 순으로 담는다.
- `stationCode`는 `station_lines.station_code`다.
- `isSupported = false`인 역도 그대로 내려준다.

Swagger 스키마 이름:
- 데이터: `SubwayLineData`, `StationLineData`, `StationData`, `StationPageData`
- 응답: `SubwayLineListResponse`, `StationPageResponse`, `StationDetailResponse`

## GET /stations 파라미터 검증

값을 보정(clamp)하지 않고, 규칙을 어기면 400 `VALIDATION_ERROR`를 돌려준다. `details`에는 문제가 된 파라미터 이름을 키로 넣는다.

| 파라미터 | 규칙 |
| --- | --- |
| keyword | 앞뒤 공백을 지운다. 빈 문자열이면 없는 것으로 본다. 50자(코드 포인트) 초과면 400 |
| bbox | `minLng,minLat,maxLng,maxLat` 숫자 4개(각 값 앞뒤 공백 허용). 경도 -180~180, 위도 -90~90, min < max. NaN·Infinity·개수 오류는 400. 빈 문자열이면 없는 것으로 본다 |
| keyword·bbox | 둘 다 없으면 400 (`details.keyword`). 함께 쓸 수 있다 |
| lineId | 그 노선에 속한 역만 반환한다. 없는 노선이면 오류가 아니라 빈 결과 |
| page | 0부터, 기본 0. 음수면 400 |
| size | 기본 20. 1 미만이면 400. 상한은 bbox가 있으면 500, keyword만 있으면 50이고 초과하면 400 |

숫자 파라미터(lineId, page, size)의 형식 오류(예: `size=abc`)는 전역 예외 처리에 따라 400 `VALIDATION_ERROR`(details 없음)다.

## 검색·정렬 규칙

- **keyword**: 역명 부분 일치이고 대소문자를 구분하지 않는다(`ilike`). LIKE 특수문자 `%`, `_`, `\`는 이스케이프해서 문자 그대로 찾는다.
- **"역" 접미사**: keyword가 "역"으로 끝나면 끝의 "역"을 뗀 이름(stem)으로도 찾는다. 조건은 "원문 keyword 일치 OR stem 일치"다.
  - `강남역` → `강남`도 찾는다.
  - `서울역` → 원문으로 `서울역`을 찾는다. stem `서울`로도 찾으므로 `서울대입구` 같은 역도 뒤에 나온다.
  - `역` 한 글자는 stem이 없다.
  - 옛 이름 별칭(당고개 등)은 지원하지 않는다.
- **정렬**:
  - keyword가 있으면: 이름이 원문 또는 stem과 정확히 같은 역 → 원문 또는 stem으로 시작하는 역 → 이름순 → id순
  - bbox만 있으면: id순
- **bbox**: `longitude BETWEEN minLng AND maxLng AND latitude BETWEEN minLat AND maxLat`. 경계값을 포함하고 PostGIS는 쓰지 않는다.
- **페이지**: `totalElements`는 같은 조건의 전체 건수다. 건수와 페이지는 한 읽기 전용 트랜잭션에서 조회한다.

## StationLookup

다른 도메인(출발역 설정, 후보 제안 등)이 역 존재·지원 여부를 확인할 때 쓴다. 조건부 빈이 아니어서 항상 실제 구현(`JdbcStationLookup`)이 등록된다.

| 메서드 | 결과 |
| --- | --- |
| `StationInfo require(long stationId)` | 없으면 `STATION_NOT_FOUND`(404) |
| `StationInfo requireSupported(long stationId)` | 없으면 `STATION_NOT_FOUND`(404), `is_supported = false`면 `STATION_UNSUPPORTED`(422) |

- `StationInfo(long id, String name, double latitude, double longitude, boolean supported)`
- **stations 한 테이블만** 읽고 노선을 JOIN하지 않는다.
- `@Transactional`이 없다. 트랜잭션을 새로 열거나 격리 수준을 바꾸지 않고, 호출한 쪽 트랜잭션의 커넥션을 그대로 쓴다. 그래서 방 락을 잡은 쓰기 트랜잭션 안에서 호출해도 된다.

방 도메인 호출 예시 (호출부 교체는 방 담당 작업):

```java
@Service
@RequiredArgsConstructor
class DepartureStationExample {
    private final RoomAccess access;
    private final StationLookup stations;

    @Transactional
    public void setDeparture(UUID roomId, UUID userId, long stationId) {
        access.lockMember(roomId, userId);                      // 방 락
        StationInfo station = stations.requireSupported(stationId); // 404 STATION_NOT_FOUND / 422 STATION_UNSUPPORTED
        // ... meeting_members.departure_station_id = station.id()
    }
}
```

## 테스트

- **단위** `StationSearchConditionTest`(25개): keyword 정규화·50자 상한·stem, LIKE 이스케이프, bbox 파싱·범위·순서, keyword·bbox 필수, size 상한(50/500)·page/size 하한, offset 오버플로
- **PostgreSQL** `StationApiPostgresTest`(12개, V1~V11 적용):
  - `/lines` 8개 순서
  - 강남 한 건(2호선, 222), 잠실 2·8호선
  - `서울역`·`강남역` 검색, LIKE 특수문자
  - lineId 필터(2호선 51역, 각 항목에 모든 노선), 없는 lineId
  - bbox 단독·keyword 동시·페이지
  - 400 검증, 상세 = 검색 항목, 404, 401
  - StationLookup 404·422
  - Swagger 경로·스키마
- **미지원 역**: V11 시드에는 `is_supported = false` 역이 없어서 목록 포함 여부는 검증하지 않았다. `requireSupported`의 422만 롤백되는 트랜잭션 안에 임시 역을 넣어 확인했다. 같은 트랜잭션에서 커밋 전 행이 보이므로 호출한 쪽 트랜잭션에 참여한다는 것도 함께 확인된다.
- 대소문자 무시는 SQL `ilike`로 처리한다. 시드 역명이 모두 한글이라 대소문자 사례는 테스트하지 않았다.
