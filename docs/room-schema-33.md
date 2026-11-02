# 약속방 스키마 설계 및 배포 점검 (#33)

## 기준과 범위

기준은 API 명세 v1.12(2026-10-29)의 7장·13장·부록 A와 ERD v1.13이다. 별도 제약 SQL 대신 API 부록 A와 DBML 주석에 정의된 제약을 적용한다.

PR 전체 변경 기준으로 기존 V1·V2를 보존하고, V3 닉네임 CHECK와 V4~V8 스키마를 신규 추가한다. 정식 V1~V8 SQL의 내용·이름은 보존한다. 배포·공유 DB에 적용된 마이그레이션은 후속 버전으로 변경을 관리한다.

방·멤버·후보·투표와 그 FK에 필요한 정식 역·추천 스키마, 방 삭제 시 정리할 메시지 스키마를 포함한다. 엔티티·API·서비스·방 락·추천 계산·채팅 처리는 후속 범위다. 그래프·혼잡도 스키마는 독립된 도메인으로 별도 관리한다.

## 마이그레이션과 의존 순서

| 버전 | 파일 | PR 기준 변경 및 역할 |
| --- | --- | --- |
| V1 | `V1__create_users.sql` | 기존 파일 보존 |
| V2 | `V2__create_refresh_tokens.sql` | 기존 파일 보존 |
| V3 | `V3__check_users_nickname.sql` | 신규: 닉네임의 앞뒤 기본 공백 제외 길이 1~20자 CHECK |
| V4 | `V4__create_stations.sql` | 신규: 정식 stations 스키마, 좌표 CHECK, 이름·좌표 인덱스 |
| V5 | `V5__create_meeting_rooms_and_members.sql` | 신규: 방·멤버, CHECK·UNIQUE·OWNER 부분 유니크·FK·인덱스 |
| V6 | `V6__create_recommendations.sql` | 신규: recommendation_runs·recommendation_results, JSON/enum/수치 CHECK·FK |
| V7 | `V7__create_station_proposals_and_votes.sql` | 신규: 후보·투표, UNIQUE·복합 FK·CASCADE·출처 SET NULL·인덱스 |
| V8 | `V8__create_room_messages.sql` | 신규: 메시지, 발신자/본문/enum CHECK·방 삭제 CASCADE |

참조 대상은 다음 순서로 생성한다.

1. 기존 users에 V3 닉네임 CHECK를 적용한다.
2. stations를 생성한다.
3. users·stations를 참조하는 meeting_rooms를 생성한다.
4. meeting_members와 recommendation_runs를 생성한다.
5. recommendation_runs·stations를 참조하는 recommendation_results를 생성한다.
6. meeting_rooms·stations·users·recommendation_results를 참조하는 station_proposals를 생성한다.
7. meeting_rooms·meeting_members·station_proposals를 참조하는 station_proposal_votes를 생성한다.
8. users·meeting_rooms를 참조하는 room_messages를 생성한다.

stations는 다른 그래프 테이블을 참조하지 않는다. 추천 실행·결과의 선행 대상은 방·사용자·역이므로 subway_lines·station_lines·station_connections·congestion_stats 생성에 의존하지 않는다. 역·추천 테이블은 축약된 참조용 테이블이 아닌 DBML 전체 컬럼·인덱스·FK를 갖춘 정식 스키마다. room_messages는 방 삭제 시 채팅 기록도 정리하는 요구를 충족한다. 병합 전 다른 도메인의 마이그레이션 번호와 중복 생성 여부를 확인한다.

사용자 데이터 PK는 애플리케이션에서 생성하는 UUIDv7이며 DB 생성 기본값은 없다. 역 PK는 bigint identity, 시각은 timestamptz다. 문서용 enum은 varchar + CHECK이며 허용값 최대 길이를 사용한다(role 6, criterion/reason_source 8, sender_type 6).

## 제약과 삭제 정책

- 방: room_code UNIQUE와 `^[A-Z0-9]{6}$`, 이름의 `char_length(btrim(name))` 1~30, max_members 기본값 4 및 `= 4`, selected_station_id·confirmed_at의 NULL 여부 일치. status 컬럼 없이 확정 역 유무로 상태를 계산한다.
- 멤버: `(room_id, user_id)` UNIQUE, role은 OWNER/MEMBER CHECK와 기본 MEMBER, OWNER 행에 한정한 room_id 부분 유니크 인덱스. 출발역 NULL 허용, `(room_id, joined_at, id)` 인덱스로 승계 대상 조회를 지원한다.
- 후보: `(room_id, station_id)`와 `(id, room_id)` UNIQUE, source_result_id FK·인덱스. proposed_by는 멤버 행이 아닌 users를 참조한다.
- 투표: `(room_id, user_id)` UNIQUE, 같은 컬럼의 meeting_members 복합 FK, `(proposal_id, room_id)`의 station_proposals 복합 FK로 멤버 자격과 후보의 같은 방 소속을 보장한다.
- 추천: criteria는 JSON 객체, trips·nearby는 JSON 배열, metric_value는 0 이상. criterion·reason_source는 varchar + CHECK, `(run_id, criterion)` UNIQUE.
- 메시지: USER만 sender_id 필수이며 AI/SYSTEM은 NULL, 본문 길이 1~4000자. meta.kind는 명세상 앱 검증 대상이다.

| 삭제 대상 | DB 동작 | 보존되는 데이터 |
| --- | --- | --- |
| 방 | 멤버·추천 실행/결과·후보·투표·메시지 CASCADE | 다른 방, 공유 사용자·역 |
| 멤버 | 해당 방 해당 사용자의 투표 CASCADE | 제안 후보, 추천 실행, 과거 메시지 |
| 후보 | 해당 후보 투표 CASCADE | 확정 약속역·confirmed_at |
| 추천 결과 | 후보 source_result_id만 SET NULL | 후보와 투표 |
| 추천 실행 | 결과 CASCADE 후 후보 source_result_id SET NULL | 후보와 투표 |

DBML에서 삭제 동작을 지정하지 않은 사용자·역 Ref는 PostgreSQL 기본 NO ACTION이다. 확정 역은 stations를 직접 참조하며 후보 삭제와 독립적이다. 사용자 탈퇴는 soft delete와 서비스의 멤버 정리 절차를 따른다. 기존 FK·삭제 정책은 유지한다.

## DB와 서비스의 책임

DB는 OWNER **최대** 한 명, 중복 멤버·동일 역 후보·방당 1인 1표 방지, 투표의 멤버/방 소속, 명세의 CHECK·FK·삭제 정책을 보장한다. max_members는 4로 고정하지만 실제 멤버 수를 세는 CHECK는 없다. OWNER가 항상 한 명 존재하는 것도 부분 유니크 인덱스만으로 보장되지 않는다.

서비스는 같은 방 락 안에서 실제 멤버 최대 4명·후보 최대 9개, 지원 가능한 역, 후보 삭제 권한, OWNER 승계, 마지막 멤버 퇴장 시 방 삭제를 보장한다. 투표 변경·취소 금지와 같은 후보 재투표의 멱등성도 서비스 책임이다. 멤버 삭제만으로 방이 자동 삭제되지 않으며, 사용자 soft delete만으로 멤버가 자동 삭제되지 않는다.

source_result_id FK는 추천 결과의 존재만 보장한다. 후보 제안 서비스에서 결과 → 실행 → room_id를 조회하여 요청한 방과 같은지 검증해야 한다. 주변 대안역을 제안할 수 있으므로 후보 station_id와 추천 결과 station_id가 같다는 제약은 추가하지 않는다.

## V3 적용 전 배포 점검

**실제 배포 대상 DB의 닉네임 데이터는 미확인이다.** 임시 PostgreSQL 테스트 통과는 대상 DB의 데이터 점검을 대신하지 않는다. 다음 절차는 배포 담당자가 대상 DB에 V3를 적용하기 전에 수행한다. soft delete 사용자를 포함한 모든 users 행이 CHECK 적용 대상이다.

1. 접속한 DB가 배포 대상인지, 현재 Flyway 적용 버전과 후속 번호가 올바른지 확인한다.
2. 읽기 전용 권한 또는 읽기 전용 트랜잭션으로 아래 쿼리를 실행한다. V3와 동일한 식을 사용하며 원본 닉네임을 출력하지 않는다.

```sql
begin transaction read only;

select id, char_length(btrim(nickname)) as trimmed_length
from users
where char_length(btrim(nickname)) not between 1 and 20;

commit;
```

3. 위반 행이 있으면 배포 적용을 보류하고, 담당자가 데이터 처리 방침·영향·실행 주체를 명시적으로 결정한다. V3는 위반 데이터가 있으면 실패하며 데이터를 자동 변경·삭제하지 않는다. NOT VALID로 검증을 우회하지 않는다.
4. 승인된 별도 데이터 처리 후 같은 읽기 전용 쿼리로 위반 행이 없는지 재확인한다. 점검 이후 쓰기가 발생할 수 있으므로 적용 직전 다시 확인하고 점검 결과를 배포 기록에 남긴다.
5. 대상 버전에 맞게 V3~V8을 순서대로 적용하고 Flyway validate를 실행한다. 실패 시 원인을 확인하고 적용을 보류한다. 이미 적용한 SQL의 이름·내용을 바꾸거나 자동 repair로 우회하지 않는다.

V4~V8은 새 테이블을 생성하며 기존 users·refresh_tokens 행을 변경하지 않는다. V3의 `ck_users_nickname_trimmed_length`는 API 부록 A의 닉네임 조건과 같아 별도 중복 CHECK가 필요하지 않다.

## 테스트 실행 조건과 범위

JDK 17을 사용한다. 일반 테스트는 `./gradlew test`, 실제 PostgreSQL 검증은 Docker 데몬에 접근 가능한 환경에서 `./gradlew postgresTest`로 실행한다. Windows에서는 `.\gradlew.bat`을 사용한다. `check`는 postgresTest에 의존하고 `build`는 check를 포함하므로 둘 다 Docker가 필요하다. Docker가 없으면 PostgreSQL 검증은 스킵되지 않고 실패한다.

Testcontainers는 postgres:16 임시 컨테이너와 호스트 임의 포트를 사용하고 테스트마다 새 DB를 생성한다. PostgreSQL 마이그레이션 테스트는 애플리케이션 .env의 개발·운영 DB 접속값을 사용하지 않는다.

- UserNicknameMigrationPostgresTest: V1~V3 적용·validate·재실행, 사용자·토큰·체크섬 보존, 위반 데이터의 원자적 실패 및 닉네임 경계 검증.
- RoomSchemaMigrationPostgresTest: 정식 V1~V8 전체 적용·validate·재실행, V1/V2/V3 업그레이드 시 데이터·체크섬 보존, UNIQUE·부분 인덱스·CHECK·일반/복합 FK와 삭제 관계 검증.
- H2 기반 BaseEntityJpaTest: 테스트용 엔티티의 create-drop 검증. Flyway 비활성화는 해당 테스트 설정에만 적용한다. 운영 `spring.flyway.enabled=true`, `ddl-auto=validate`는 유지한다.
- CI: dev 대상 pull_request 및 dev push에서 일반 테스트 다음에 postgresTest가 필수 단계로 실행된다. 조건부 스킵이나 continue-on-error가 없다.

결과는 `build/reports/tests/test`, `build/reports/tests/postgresTest`와 각 `build/test-results` 디렉터리에서 확인한다. SQL·JDBC/Flyway 테스트가 후속 엔티티의 JPA 매핑 검증이나 앱 실행·API 수동 검증까지 수행하는 것은 아니다.

## 후속 엔티티·API 구현 확인사항

- 정식 V1~V8을 적용한 독립 PostgreSQL 16에서 신규 엔티티와 `ddl-auto=validate`를 함께 검증한다. UUID 생성 시점, bigint 역 참조, timestamptz, enum varchar 길이, jsonb, NULL·기본값 매핑을 확인한다. 현재 마이그레이션 테스트는 이 후속 엔티티 통합 검증을 수행하지 않는다.
- PostgreSQL `char_length`와 Java `codePointCount`의 문자 수 기준을 맞춘다. Java String.length는 UTF-16 코드 단위이므로 이모지 등에서 다를 수 있다. 결합 문자·이모지와 1/20/30자 경계도 확인한다.
- PostgreSQL 기본 `btrim`은 양 끝의 일반 공백(U+0020)을 제거하며 Java `strip`의 공백 기준과 다르다. 탭·줄바꿈·유니코드 공백의 처리 및 저장 전 정규화를 명시적으로 대조한다. 기존 닉네임 수정 API는 strip 후 코드 포인트 2~20자, DB CHECK는 1~20자다. 이 정책 차이를 임의로 없애지 않는다.
- 기존 가입 로직은 원본 닉네임을 최대 20 코드 포인트로 자른다. 잘린 결과가 일반 공백만 남는 외부 입력 등 DB CHECK와 충돌할 수 있는 경계를 후속 작업에서 검증하고 처리 방침을 결정한다. 기존 가입·닉네임 수정 API를 이 스키마 문서 보완으로 변경하지 않는다.
- 초대 코드 UNIQUE 충돌은 새 코드 생성 후 제한된 재시도로 처리한다. 같은 코드 재사용·무한 재시도를 피하고, 실패한 트랜잭션의 경계를 고려한다.
- 중복 참여·후보·방당 투표와 OWNER 부분 유니크, CHECK·FK 위반을 기존 공통 응답과 명세의 오류로 처리한다. 동시 요청·flush/커밋 시점 위반·재요청의 멱등성을 PostgreSQL에서 검증한다. OWNER 승계는 기존 OWNER 삭제를 flush한 후 진행한다.
- source_result_id의 같은 방 소속을 서비스에서 검증하고, 다른 방 결과·삭제된 결과·직접 제안(NULL)·주변 대안역 제안을 테스트한다.
