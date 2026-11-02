# 이슈 #33: 약속방 스키마와 PostgreSQL 검증

## 기준 및 작업 시작 상태 (2026-11-02)

- 브랜치: `feat/33-room-schema`, 시작 커밋 `cc93eb6`.
- 저장소·상위 경로 및 작업 대상 하위 디렉터리에 적용할 AGENTS.md가 없었다.
- 시작 시 사용자 변경은 미추적 자료 두 파일뿐이었다. 내용·이름을 변경하지 않았다.
  - `docs/LinkRo API 명세서 최종본 (3).docx`: 본문 표기 v1.12 / 2026-10-29. 사용자 메시지의 `(2).docx` 대신 실제 첨부 파일을 확인했다.
  - `docs/erd_v1.13_20261029.dbml.txt`: ERD v1.13 / 2026-10-29. `.txt` 확장자를 유지했다.
- GitHub 최신 이슈 #33 본문과 댓글(0개), API 7장·13장·부록 A, DBML 컬럼·인덱스·Ref·주석을 대조했다. 이슈의 구버전 v1.7 표기보다 제공된 최신 자료를 우선했다.
- `git fetch origin` 후 `origin/dev`는 `30a0064`, 정식 마이그레이션은 V1·V2뿐이었다. 로컬 V3는 앞선 #33 부분 작업이다.
- 공개된 열린 PR은 없었고, fetch한 원격 브랜치 전체에서 stations/추천/방/채팅 마이그레이션이 없었다. 공개된 열린 이슈에서도 별도의 선행 스키마 작업을 발견하지 못했다. 미공개 로컬 작업·예약 번호까지 확인한 것은 아니므로 병합 전 담당자 간 번호 공유는 필요하다. 다른 담당자에게 메시지는 전송하지 않았다.

## 구현 범위와 적용 순서

| 버전 | 파일 | 내용 |
| --- | --- | --- |
| V1 | `V1__create_users.sql` | 기존 그대로 |
| V2 | `V2__create_refresh_tokens.sql` | 기존 그대로 |
| V3 | `V3__check_users_nickname.sql` | 기존 그대로, 공백 제거 길이 1~20자 CHECK |
| V4 | `V4__create_stations.sql` | 전체 정식 stations 스키마, 좌표 CHECK, 이름·좌표 인덱스 |
| V5 | `V5__create_meeting_rooms_and_members.sql` | 방·멤버 전체 컬럼, CHECK·UNIQUE·부분 유니크·FK·인덱스 |
| V6 | `V6__create_recommendations.sql` | 전체 정식 recommendation_runs·recommendation_results, JSON/enum/수치 CHECK 및 FK |
| V7 | `V7__create_station_proposals_and_votes.sql` | 후보·투표 전체 컬럼, UNIQUE·복합 FK·cascade·출처 SET NULL·인덱스 |
| V8 | `V8__create_room_messages.sql` | 전체 정식 채팅 저장 스키마, 발신자/본문/enum CHECK 및 방 삭제 cascade |

의존 순서는 `users → stations → meeting_rooms → meeting_members / recommendation_runs → recommendation_results → station_proposals → station_proposal_votes`다. room_messages는 users·meeting_rooms 뒤에 생성한다.

V4·V6은 후보 출처 FK를 완전하게 구성하기 위한 정식 선행 스키마다. stations는 다른 그래프 테이블을 참조하지 않는다. recommendation_runs는 방·사용자, recommendation_results는 실행·역만 참조하므로 subway_lines/station_lines/station_connections/congestion_stats를 먼저 만들 필요가 없다. 이 독립된 그래프·혼잡도 도메인은 이번에 생성하지 않았다. 임시 테이블, 축약 추천 결과 테이블, FK 없는 중간 상태를 만들지 않았다.

V8은 원래 #33의 방 삭제 시 채팅 기록 cascade 요구를 완성하기 위한 스키마 범위다. 채팅 API·서비스·STOMP·AI 응답은 구현하지 않는다. 방 엔티티·API·서비스·방 락 역시 #34 이후 범위다. 운영 설정과 `ddl-auto=validate`를 유지한다. 사용자 데이터 PK는 UUID이며 DB 생성 기본값이 없다(UUIDv7은 기존 애플리케이션 생성 정책). 역 PK는 bigint identity, 시각은 timestamptz다.

## 제약과 삭제 관계

- 방: 초대 코드 UNIQUE 및 `^[A-Z0-9]{6}$`, 이름의 `char_length(btrim(name))` 1~30, max_members 기본 4 및 `=4`, 확정 역·시각 NULL 여부 일치. status 컬럼 없음.
- 멤버: `(room_id,user_id)` UNIQUE, role은 varchar(6) + OWNER/MEMBER CHECK·기본 MEMBER, OWNER에 한정한 room_id 부분 유니크 인덱스. 출발역 NULL 허용, 입장 순 조회 인덱스 포함.
- 후보: `(room_id,station_id)` UNIQUE, `(id,room_id)` UNIQUE, 출처 FK와 인덱스. 제안자 FK는 users를 직접 참조하므로 멤버가 나가도 후보가 남는다.
- 투표: `(room_id,user_id)` UNIQUE, `(room_id,user_id) → meeting_members`, `(proposal_id,room_id) → station_proposals` 복합 FK. 비멤버·다른 방 후보 투표를 차단한다.
- 방 삭제: 멤버·추천 실행·추천 결과·후보·투표·메시지 cascade.
- 멤버 삭제: 해당 방 해당 사용자의 투표만 cascade, 후보·추천 실행·과거 메시지는 유지.
- 후보 삭제: 해당 후보의 투표 cascade, 확정 역·시각은 유지. 후보와 확정 역 사이에 FK를 만들지 않는다.
- 추천 결과 삭제: 후보의 source_result_id만 SET NULL. 추천 실행 삭제도 결과 cascade를 거쳐 같은 동작.
- 사용자·역 참조: DBML에서 삭제 동작을 지정하지 않은 Ref는 PostgreSQL 기본 NO ACTION. 임의 CASCADE/SET NULL을 추가하지 않았다. 사용자 탈퇴는 soft delete 및 서비스의 멤버 정리 절차를 따른다.
- 추천 실행 criteria는 JSON 객체, 결과 trips/nearby는 JSON 배열, metric_value는 0 이상, criterion과 reason_source는 varchar + CHECK.
- 메시지는 USER만 sender_id 필수, AI/SYSTEM은 NULL, 본문 1~4000자. meta.kind는 명세상 앱 검증이므로 새 DB CHECK를 추가하지 않는다.

별도 linkro_constraints.sql은 없다. API 부록 A와 DBML 주석으로 필요한 제약을 확정할 수 있었고, 이번 스키마에 구현을 막는 충돌·누락은 없었다. DBML의 문서용 enum은 물리 varchar 길이를 지정하지 않으므로 허용값의 최대 길이로 정했다(role 6, criterion/reason_source 8, sender_type 6). V3 닉네임 CHECK는 부록 A와 조건이 같아 이름만 다르며 중복 제약을 추가하지 않았다.

## DB와 후속 서비스의 책임

DB는 OWNER **최대** 한 명, 중복 멤버·후보·방당 투표 방지, 투표의 멤버/방 소속 및 FK 삭제 정책을 보장한다. OWNER가 항상 존재하는 것, 실제 멤버 최대 4명, 후보 최대 9개는 이 CHECK들로 보장하지 않는다.

후속 서비스·방 락에서 지원 역 검증, 후보의 추천 결과가 같은 방 소속인지 검증, 후보 삭제 권한, OWNER 승계, 마지막 멤버 퇴장 시 방 DELETE, 투표 변경·취소 금지를 구현한다. 트리거·권한 회수·새 상태 컬럼으로 서비스 규칙을 대체하지 않는다. 멤버 DELETE만으로 방이 자동 삭제되지는 않는다. 사용자 soft delete만으로 멤버가 자동 삭제되지도 않는다.

## 기존 데이터와 마이그레이션 운영

V1~V3 파일의 내용·이름을 변경하지 않는다. 배포·공유 DB에는 이 작업에서 접속하지 않는다. V4~V8은 새 테이블 생성만 수행하며 기존 users/refresh_tokens 행을 수정하지 않는다.

V3는 기존 위반 닉네임이 있으면 원자적으로 실패한다. 데이터 삭제·자동 수정·NOT VALID 우회는 하지 않는다. 운영 적용 전 배포 담당자가 다음 읽기 전용 쿼리로 점검하고 위반 데이터의 처리 방침을 정해야 한다.

```sql
select id, char_length(btrim(nickname)) as trimmed_length
from users
where char_length(btrim(nickname)) not between 1 and 20;
```

## 검증 방법

```powershell
# JDK 17 환경에서 실행
.\gradlew.bat test postgresTest --no-daemon
git diff --check
git diff --exit-code HEAD -- src/main/resources/db/migration/V1__create_users.sql src/main/resources/db/migration/V2__create_refresh_tokens.sql src/main/resources/db/migration/V3__check_users_nickname.sql
```

Linux/CI: `./gradlew test postgresTest --no-daemon`. 독립된 검증용 컨테이너를 사용한다.

Testcontainers가 postgres:16 임시 컨테이너를 만들고 호스트 임의 포트를 쓴다. 매 테스트마다 새 DB를 생성한다. 테스트는 애플리케이션 .env와 개발·운영 DB 접속값을 읽지 않으며, Compose·기존 컨테이너·볼륨을 변경하지 않는다. Docker가 없으면 실패하고 스킵하지 않는다. 기존 필수 CI/Gradle check 연결을 유지한다.

- 기존 UserNicknameMigrationPostgresTest는 V3 회귀 범위를 명시적으로 고정했다. 새 파일 추가 때문에 과거 V3 검증이 바뀌지 않는다.
- RoomSchemaMigrationPostgresTest는 신규 DB의 V1~V8 적용·validate·재실행, V1/V2/V3 데이터·적용 체크섬 보존, 기본값·NULL·유니코드 경계, UNIQUE·부분 인덱스·CHECK·FK 위반, 멤버·후보·추천·방 삭제와 다른 방/공유 데이터 보존을 검증한다.
- 기존 H2 BaseEntityJpaTest는 테스트 전용 엔티티를 create-drop으로 검증하므로 Flyway를 명시적으로 비활성화했다. PostgreSQL SQL을 H2에 적용하지 않으며, 실제 SQL 검증은 필수 postgresTest에서 수행한다.
- 결과 위치: build/reports/tests/test, build/reports/tests/postgresTest 및 build/test-results 각 태스크 디렉터리.

## 실제 실행 결과 (2026-11-02)

- JDK 17에서 `.\gradlew.bat test postgresTest --no-daemon`: BUILD SUCCESSFUL.
- 기존 전체 테스트: 302개, 실패 0 / 오류 0 / 스킵 0.
- PostgreSQL 테스트: 54개, 실패 0 / 오류 0 / 스킵 0. 새 방 스키마 41개 + 기존 닉네임 회귀 13개.
- PostgreSQL 16.14 임시 컨테이너에서 정식 V1~V8 적용·validate·재실행, V1/V2/V3 업그레이드와 데이터·체크섬 보존, CHECK/UNIQUE/부분 인덱스/일반·복합 FK, 삭제 관계 검증 통과.
- `git diff --check` 통과. 새 SQL/Java 파일 6개도 별도 `git diff --no-index --check`로 점검해 통과했다.
- `git diff --exit-code HEAD -- V1경로 V2경로 V3경로`: 변경 없음.
- 첫 실행에서 H2 BaseEntityJpaTest가 PostgreSQL Flyway SQL을 적용해 8개가 실패했다. 해당 테스트에만 Flyway 비활성화를 명시한 뒤 전체 태스크를 재실행하여 위 결과를 얻었다.
- 개발·운영 DB와 기존 볼륨은 변경하지 않았다. 검증을 위해 Docker Desktop을 시작했다.
- GitHub Actions 자체 실행과 운영 배포 검증은 수행하지 않았다. 로컬에서 CI의 두 테스트 태스크를 실행했다. 아래 테스트 결과는 로컬 실행 결과다.

현재 구현을 막는 정의 충돌·누락은 없다. 남은 조율은 병합 전 미공개 선행 스키마 작업·마이그레이션 번호 중복 확인이다. 후속 API·서비스·방 락·실제 정원/후보 수/OWNER 승계 검증은 이번 스키마 작업 완료에 포함하지 않는다.

최종 검토 후 #33 관련 구현·테스트·문서만 커밋·push한다. 사용자 제공 원본 DOCX·DBML은 미추적 상태로 보존한다. PR 생성·머지·이슈 종료는 수행하지 않는다.
