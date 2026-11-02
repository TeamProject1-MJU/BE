# 약속방 공통 도메인·권한·락 (#34)

## 기준과 매핑

API v1.12, ERD v1.13, 정식 V1~V9 Flyway 스키마를 기준으로 한다. 기존 이슈의 v1.7 참조는 이 기준으로 대조했다. 마이그레이션·첨부 원본·운영 `ddl-auto=validate`는 변경하지 않는다.

`MeetingRoom`은 `BaseUuidTimeEntity`, `MeetingMember`는 `BaseUuidEntity`를 상속한다. 기존 애플리케이션 UUIDv7 생성, `Persistable.isNew`, `PostPersist`/`PostLoad` 상태 전환을 그대로 쓴다. 방 생성·수정 시각은 기존 Auditing으로 기록한다. 멤버는 `@CreatedDate`와 같은 Auditing listener가 최초 persist 때 `joined_at`만 기록한다. 따라서 멤버에 존재하지 않는 created_at/updated_at을 요구하지 않는다. 애플리케이션 저장은 Auditing 값을 INSERT하고, SQL 직접 삽입은 스키마의 now() 기본값을 사용한다.

UUID 참조는 UUID, 역 참조는 Long이며 연관관계·자식 컬렉션·JPA cascade는 없다. 역할은 EnumType.STRING, 시간은 Instant다. maxMembers는 4로 초기화하고 수정자를 제공하지 않는다. createdBy는 최초 생성자 기록이다. OWNER는 현재 meeting_members.role로만 판단한다. 상태는 selectedStationId의 NULL 여부로 계산하며 컬럼을 만들지 않는다. 확정 역과 confirmedAt은 하나의 도메인 메서드로 함께 변경·해제한다.

## 후속 서비스 호출

인증 계층의 `@CurrentUser UUID userId`를 서비스에 전달한다. 요청 본문의 사용자 ID로 대체하지 않는다. 읽기 서비스는 `requireMember`/`requireOwner`로 방 존재와 권한을 확인하며 쓰기 락을 사용하지 않는다.

```java
@Service
@RequiredArgsConstructor
class FutureRoomService {
    private final RoomAccess roomAccess;

    @Transactional // 별도 Spring 빈의 public 메서드를 프록시를 통해 호출
    public void change(UUID roomId, UUID authenticatedUserId, Instant appointmentAt) {
        MeetingRoom room = roomAccess.lockOwner(roomId, authenticatedUserId);
        // 여기서 현재 조건 확인 후 변경. 이 트랜잭션의 종료까지 방 락 유지.
        room.schedule(appointmentAt);
    }
}
```

참여 작업은 `lockByCode(code)`를 사용한다. 이 단계에서 기존 멤버 자격은 요구하지 않는다. 잠금 후 중복·정원 등 참여 조건을 후속 서비스가 확인한다. 일반 멤버의 변경 작업은 `lockMember(roomId, userId)`를 사용한다.

방 ID 없음은 ROOM_NOT_FOUND(404), 존재하는 방의 비참여자는 NOT_ROOM_MEMBER(403), MEMBER의 OWNER 작업은 NOT_ROOM_OWNER(403)다. 초대 코드가 없거나 대기 중 삭제되면 ROOM_CODE_INVALID(404)다.

## 트랜잭션 계약

- 동일 방 변경: 방 잠금 → 현재 권한·조건 조회 → 변경 → 커밋/롤백. 방·멤버를 미리 수정하거나 삭제한 뒤 잠금을 시도하지 않는다. 잠금 시 기존에 로딩된 방은 refresh하므로 잠금 전 미반영 변경을 보존하는 용도로 사용하면 안 된다.
- 공통 쓰기 메서드는 MANDATORY이며 신규 트랜잭션을 열지 않는다. 트랜잭션 누락, readOnly, READ COMMITTED 이외 격리 수준은 거절한다. 직접 new로 서비스 생성 또는 서비스 자기 호출에 의존하지 않는다. 별도 주입된 RoomAccess 프록시가 MANDATORY를 검사하고 내부에서도 활성 쓰기 트랜잭션을 확인한다.
- 방 락은 PESSIMISTIC_WRITE다. ID·코드 조회 모두 같은 meeting_rooms 행을 잠근다. refresh도 같은 트랜잭션에서 수행한다. 권한은 락 획득 뒤 scalar role 조회를 통해 확인하여 기존 영속성 컨텍스트의 낡은 멤버 상태를 사용하지 않는다. READ COMMITTED에서 대기 중 커밋된 역할을 볼 수 있다.
- 여러 방 정리는 DB 쿼리의 `ORDER BY room_id ASC` 결과 순서로 하나씩 잠근다. Java UUID 정렬은 PostgreSQL UUID 순서와 다를 수 있으므로 쓰지 않는다. 방 목록 변경 가능성·재조회는 회원 탈퇴 서비스에서 처리한다.
- OWNER 삭제 후 승계는 삭제 → flush → 남은 MEMBER 승격 순서다. 부분 유니크 인덱스 때문에 승격 전에 삭제 SQL이 실행되어야 한다. 실제 삭제·승격은 후속 이슈다.
- `findSuccessor(roomId, excludedUserId)`는 해당 방의 MEMBER만 대상으로 퇴장 사용자 UUID를 제외하며, DB에서 joined_at ASC, id ASC로 정렬해 첫 행을 조회한다. OWNER는 후보에서 제외된다. 방 락 획득 후 사용한다.
- Repository의 잠금 쿼리는 MANDATORY이나 제한 시간·권한·refresh 정책은 RoomAccess에 있다. 변경 서비스는 Repository 잠금 쿼리를 직접 호출하지 않는다.

## PostgreSQL 락 제한과 오류

JPA timeout 힌트에 의존하지 않는다. PostgreSQL `set_config('lock_timeout', '3s', true)`로 현재 트랜잭션의 잠금 대기를 제한한다. 성공하면 이전 값을 복원한다. 오류 시 SQL을 추가 실행하지 않고 밖으로 전파하여 전체 트랜잭션을 롤백한다. LOCAL 설정은 커밋/롤백 시 사라져 연결 풀에 남지 않는다. 커넥션 전체 또는 DB 전역 설정을 바꾸지 않는다.

잠금 구간의 SQLException SQLSTATE 55P03(lock_not_available)만 ROOM_BUSY BusinessException으로 변환한다. 무결성 위반·연결 오류·기타 SQL 오류는 이 코드로 변환하지 않는다. 기존 GlobalExceptionHandler가 HTTP 409 공통 응답을 만든다. 실패를 호출 서비스 안에서 잡아서 변경을 계속하지 않는다. PostgreSQL은 해당 트랜잭션을 실패 상태로 만들며 RoomAccess 프록시도 RuntimeException에 대해 롤백을 표시한다. 재시도가 필요하면 이전 트랜잭션이 완전히 종료된 뒤 전체 작업을 새 트랜잭션으로 시작한다.

## 검증

```powershell
.\gradlew.bat test postgresTest --no-daemon
git diff --check
```

Docker가 필수다. postgresTest는 독립 postgres:16 Testcontainers DB에 정식 전체 Flyway를 적용한다. RoomDomainPostgresTest는 전체 Spring 컨텍스트를 ddl-auto=validate로 기동한다. 기존 필수 CI postgresTest와 check 의존성은 유지한다. 개발·운영 DB와 볼륨을 사용하지 않는다.

검증 대상은 엔티티 저장/재조회·UUIDv7/Persistable·Auditing·문자열 역할·nullable/Instant·계산 상태, 오류 구분·현재 OWNER, DB 정렬 승계 후보, 트랜잭션 오용 거절, 같은 방 ID/코드 경합·다른 방 독립성·커밋/롤백 해제, 대기 중 역할 변경과 낡은 엔티티, 실제 약 3초 timeout·HTTP 응답·실패 작업 롤백·풀 연결 설정 복원이다. latch와 pg_stat_activity의 Lock 대기를 사용하고 모든 대기에 제한 시간을 둔다.

검증 결과: PostgreSQL 16, Hibernate 7.4.5.Final에서 실제 잠금 호출 대기 3.015초. 일반 테스트 302개, PostgreSQL 테스트 66개(공통 도메인 검증 10개 포함)가 통과했으며 실패·오류·스킵은 각각 0개다. HTTP 초기화·응답 직렬화 비용을 제외하고 실제 공통 잠금 호출 구간을 측정한다.

방 API, 참여·정원·퇴장·실제 승계·마지막 멤버 방 삭제·회원 탈퇴 연동·역 데이터·후보/투표·추천/채팅/이벤트는 후속 이슈에서 구현한다.
