# 이슈 #33: 스키마 작업 상태와 검증

## 기준과 현재 상태

기준은 사용자가 지정한 API 명세 v1.12(2026-10-29), 해당 기능 본문·13장·부록 A 및 별도 확정 DBML/DDL이다. 변경 이력에서 참조하는 ERD 버전은 v1.13이다. 이슈 본문의 v1.7을 최신 정책으로 간주하지 않는다.

작업 시작 시 `feat/33-room-schema`와 fetch한 `origin/dev`는 모두 `30a0064`였고 미커밋 변경이 없었다. 저장소와 상위 경로에서 적용할 AGENTS.md를 발견하지 못했다. 정식 마이그레이션은 V1·V2뿐이었다. 명세 v1.12 DOCX와 별도 DBML/DDL은 현재 접근 가능한 저장소에서 발견되지 않았다. 따라서 본문·13장·부록 A 대조는 아직 수행하지 못했다.

**이슈 #33은 부분 구현 상태다. 방·멤버·후보·투표 스키마가 완료된 것이 아니다.** 확정되지 않은 컬럼이나 참조 테이블을 추측하지 않고, 독립적인 users 닉네임 CHECK와 실제 PostgreSQL 검증 기반만 먼저 추가한다. 테스트에서도 임시 stations/recommendation_results를 만들지 않는다.

## 마이그레이션과 기존 데이터

1. `V1__create_users.sql`: 기존 파일 그대로 보존.
2. `V2__create_refresh_tokens.sql`: 기존 파일 그대로 보존. user FK를 재사용.
3. `V3__check_users_nickname.sql`: `ck_users_nickname_trimmed_length` CHECK 추가. `char_length(btrim(nickname)) between 1 and 20`을 기존 행과 새 INSERT/UPDATE 모두에 적용한다. UUID 기본값·새 테이블·방 API·엔티티는 추가하지 않는다.

작업 시 V3 번호 충돌은 없었다. 다른 담당자의 미공개/예정 작업은 확인되지 않았으므로 **병합 전 마이그레이션 번호 및 선행 스키마 담당 조율이 필요**하다. 배포·공유 DB에 적용된 버전은 이름/내용을 변경하지 않는다.

기존 운영 설정 `ddl-auto=validate` 및 Flyway 설정은 유지한다. UUIDv7은 기존 `BaseUuidEntity`의 애플리케이션 생성 방식, Auditing은 기존 기반을 그대로 사용한다. DB CHECK의 1~20자는 가입 정책이며, 수정 API의 2~20자 검증을 대체하지 않는다.

V1의 varchar(20)·NOT NULL만으로는 빈 문자열/공백 문자열을 막지 못한다. V3는 기존 데이터에 위반값이 있으면 실패하며 PostgreSQL 트랜잭션으로 롤백한다. 사용자 삭제·닉네임 자동 변경·NOT VALID 우회는 하지 않는다. 운영 DB는 이 작업에서 접속하거나 수정하지 않는다. 배포 담당자는 적용 전에 읽기 전용으로 다음을 확인하고 위반 데이터의 처리 방침을 정해야 한다.

```sql
select id, char_length(btrim(nickname)) as trimmed_length
from users
where char_length(btrim(nickname)) not between 1 and 20;
```

`btrim`은 PostgreSQL 기본 공백 처리다. 별도 근거 없이 정규식 기반 공백 정규화로 확장하지 않는다. 기존 가입 구현은 원본 닉네임을 20 코드 포인트까지 자르므로, 앞부분이 공백으로만 채워진 특이한 외부 입력은 CHECK에 걸릴 수 있다. 가입/수정 구현은 이번 부분 작업에서 변경하지 않는다.

## 누락된 확정 정의와 생성 순서

다음 자료가 필요하다. DOCX 설계 메모를 전체 컬럼/NULL/FK가 완비된 DBML로 대체하지 않는다.

| 대상 | 필요한 정의 |
| --- | --- |
| v1.12 DOCX | 접근 가능한 파일과 7장·13장·부록 A의 현행 본문 |
| meeting_rooms | 전체 컬럼·타입·길이·NULL·기본값·인덱스, created_by 사용자 FK 삭제 정책, selected_station_id 역 FK 정책 |
| meeting_members | PK/전체 컬럼, departure_station_id NULL 허용, 사용자/역 FK 삭제 정책, 역할 varchar 길이, 시각 정의 |
| station_proposals | 제안자 컬럼/사용자 FK 삭제 정책, 전체 컬럼과 시각, source_result_id 타입·FK 대상, 복합 UNIQUE |
| station_proposal_votes | PK/전체 컬럼·시각·기본값, 복합 FK와 필요한 인덱스 |
| stations | PK bigint를 포함한 전체 정식 DDL, 유니크 제약·다른 테이블 의존성·삭제 정책 |
| recommendation_results | 전체 정식 DDL, PK 타입·room FK·그 밖의 선행 FK/테이블, 삭제 정책 |
| 추천·채팅의 방 연결 | 정식 테이블 정의 및 room FK cascade 적용 담당/마이그레이션 순서 |
| 담당자 조율 | 선행 도메인 구현 브랜치·예정 마이그레이션 번호·확정 DDL 버전 |

확정 자료를 받은 후의 의존 순서는 `users` → 정식 역 선행 스키마 → `meeting_rooms` → `meeting_members` 및 정식 추천 결과 스키마 → `station_proposals` → `station_proposal_votes`다. 실제 추천 결과의 추가 의존성은 확정 DDL로 확인해야 한다. 추천/채팅의 room FK는 해당 정식 테이블 생성 시 연결한다. 이미 정식 마이그레이션이 있으면 재사용하며, 없으면 전체 확정 DDL과 담당 범위를 확인한 후 별도 선행 버전으로 구성한다.

`selected_station_id`는 후보 삭제와 독립적인 역 참조여야 하므로 후보 삭제로 확정 약속역이 지워지는 구조를 만들지 않는다. 제안자 관계는 멤버 행 삭제에 CASCADE로 연결하지 않는다. 추천 결과 삭제는 후보의 source_result_id만 SET NULL로 처리해야 한다. 이 관계들은 아직 구현·검증하지 않았다.

## DB와 서비스의 책임 (방 스키마 후속 구현 기준)

DB는 중복 참여, 방당 OWNER 최대 1명, 동일 역 중복 후보, 방당 1인 1표, 멤버 자격 및 후보의 같은 방 소속, 명세의 CHECK/FK/cascade를 보장해야 한다. OWNER 부분 유니크 인덱스는 두 명의 OWNER만 막으며 **OWNER가 항상 한 명 존재하는 것은 보장하지 않는다.**

후속 서비스와 방 락은 실제 참여 인원 최대 4명, 후보 최대 9개, 지원 가능한 역 검증, 후보 삭제 권한, OWNER 승계, 마지막 멤버 퇴장 시 방 삭제, 투표 변경·취소 금지를 보장한다. 이번 작업에서 트리거나 새 상태 체계는 추가하지 않는다.

## 혼잡도와 범위

혼잡도 구현은 #33 범위가 아니다. 공용 선행 DDL을 받으면 현행 부록 A와 대조한다. source는 PREDICTED/MOCK만 허용하고 ACTUAL은 제외한다. 슬롯은 06:00~23:00 시작의 30분 간격 35개다. congestion_level은 varchar(12) NOT NULL, congestion_level_label은 varchar(10) NOT NULL이다. PREDICTED 단계/라벨은 AI 파일 값을 저장하며 조회 시 재계산하지 않고, MOCK은 적재 시 같은 기준으로 생성한다. pct·단계 일치 검증/CHECK의 정확한 수치는 부록 A 확인 전 추측하지 않는다. 04:00 이전 전날 운행일 정책은 폐기하며 firstTrain/slotDate는 근거 없이 DB 컬럼으로 만들지 않는다.

## 검증 실행과 한계

```powershell
.\gradlew.bat test postgresTest
git diff --check
```

Linux/CI에서는 `./gradlew test postgresTest --no-daemon`을 사용한다. 기존 H2 기반 `test`는 유지하고, 별도 source set의 `postgresTest`는 Testcontainers로 Docker Compose와 같은 메이저 버전인 `postgres:16`의 임시 컨테이너를 생성한다. 호스트 임의 포트를 사용하며 기존 DB/컨테이너/볼륨에 접근하지 않는다. 테스트마다 빈 데이터베이스를 만들고 컨테이너 수명 종료 시 제거한다. 애플리케이션 .env의 DB 접속값을 읽지 않는다.

Docker가 없으면 `postgresTest`는 실패하며 조용히 스킵하지 않는다. Gradle `check`에 연결하고 CI에도 필수 실행 단계를 추가했다. 결과는 `build/reports/tests/postgresTest` 및 `build/test-results/postgresTest`에서 확인한다. H2 결과만으로 PostgreSQL 검증 완료를 주장할 수 없다.

현재 PostgreSQL 테스트 범위: 빈 DB V1~V3 적용·validate·재실행, V1·V2 사용자/토큰 전체 행 및 기록된 체크섬 보존, 기존 위반 데이터가 있을 때 V3의 원자적 실패/복구, 가입 닉네임 1자·20자·유니코드 경계, 빈 값/NULL/21자/UPDATE 위반의 SQLSTATE와 제약 확인이다. 방·멤버·후보·투표·추천·채팅 관계 테스트는 **미구현**이며 정식 스키마 확보 후 추가해야 한다.

### 실제 실행 결과 (2026-10-31)

- `.\gradlew.bat test postgresTest`: 기존 테스트 302개, PostgreSQL 테스트 13개, 각각 실패/오류/스킵 0개. 실제 PostgreSQL 16.14에서 검증했다. Testcontainers는 Spring Boot 의존성 관리 버전(2.0.5)을 사용한다.
- `git diff --check`: 통과.
- `git diff --exit-code origin/dev -- src/main/resources/db/migration/V1__create_users.sql src/main/resources/db/migration/V2__create_refresh_tokens.sql`: 차이 없음. 테스트에서도 적용 전후 V1·V2의 Flyway 체크섬을 비교했다.
- 기존 개발/운영 DB, 볼륨, 운영 설정은 변경하지 않았다. GitHub CI 자체 실행 결과는 아직 없으며 로컬에서 CI와 같은 테스트 태스크를 실행했다.

## 현 단계 PR 본문 초안

> #33의 독립 선행 작업으로 users 닉네임 길이 CHECK를 V3에 추가하고, 기존 V1·V2를 보존했습니다. PostgreSQL 16 Testcontainers 전용 테스트 태스크와 필수 CI 실행 단계를 추가했습니다. 신규 DB 및 사용자·토큰이 있는 V1·V2 DB의 적용/validate/재실행, 데이터·체크섬 보존, 위반 데이터의 원자적 실패를 검증했습니다. 기존 테스트 302개와 PostgreSQL 테스트 13개가 실패·오류·스킵 없이 통과했습니다.
>
> 방·멤버·후보·투표 테이블 및 관계 검증은 미완료입니다. v1.12 DOCX와 별도 확정 DBML/DDL, stations·recommendation_results의 전체 선행 스키마, 추천/채팅 room FK 담당 및 마이그레이션 순서 확정이 필요합니다. 임시 참조 테이블이나 FK 생략으로 우회하지 않았습니다. 이 PR만으로 #33을 닫지 않습니다.

커밋 메시지 제안: `feat: 사용자 닉네임 CHECK 및 PostgreSQL 스키마 검증 기반 추가 (#33)`
