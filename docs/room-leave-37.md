# 방 나가기·자동 승계 (#37)

## 기준과 사전 확인

- 기준: 첨부 API 명세 v1.12(2026-10-29) 7.8·7.12, ERD v1.13 및 실제 V1~V12 마이그레이션과 방 코드. 원본 DOCX·ERD·마이그레이션은 수정하지 않는다.
- 실제 이슈: https://github.com/TeamProject1-MJU/BE/issues/37 . 오래된 v1.7 표기보다 첨부 최신 자료를 우선한다.
- 시작 브랜치 `feat/37-room-leave`, HEAD `5c3b2ef`. fetch 후 origin/dev와 차이 0/0. 시작 시 미커밋 변경 없음. 저장소·상위 디렉터리에서 AGENTS.md는 발견되지 않았다.
- #36의 PR #64 병합 `ea8f8bf`, 구현 `e4c252f`, 응답·문서 보완 `630cb0c`가 현재 HEAD의 조상이다. JWT 주입·고유 스키마·오류 예시 관례를 재사용한다.
- 실제 PR #64 리뷰 댓글도 확인했다. 병합된 응답·문서 정리는 포함되었지만, 리뷰의 비차단 제안 중 OpenAPI null 방어와 검증 순서의 이유 명시는 시작 코드에 없었다. 이번 DELETE 오류 예시가 공유하는 커스터마이저에는 null 방어를 추가했다. 출발역 검증은 권한 확인을 우선하여 비참여자의 잘못된 값도 403이 되는 기존 순서를 유지한다. 이미 제공된 StationLookup으로의 교체는 출발역 기능 후속 개선으로 남긴다.

## API 계약과 명세 판단

`DELETE /api/v1/rooms/{roomId}/members/me`는 JWT/@CurrentUser의 사용자만 퇴장시킨다. roomId는 필수 UUID 경로 파라미터이며 userId 입력은 제공하지 않는다. 성공은 200이다.

| 상황 | data |
| --- | --- |
| 일반 멤버 퇴장 | `{"roomDeleted":false,"newOwnerId":null}` |
| OWNER 퇴장·승계 | `{"roomDeleted":false,"newOwnerId":"후임의 사용자 UUID"}` |
| 마지막 멤버 퇴장 | `{"roomDeleted":true,"newOwnerId":null}` |

명세 7.8은 멤버 삭제 후 flush, 마지막 멤버이면 방 삭제, OWNER이면 joined_at ASC·id ASC 승계와 사용자 ID 응답을 명시한다. 승계 없는 두 경우의 newOwnerId 표현은 명시하지 않는다. **구현 판단**으로 필드를 생략하지 않고 null로 반환한다. 기존 null 응답 관례에 맞추고 DTO에 JsonInclude.ALWAYS를 적용한다. 일반 멤버 퇴장 시 기존 방장 ID를 newOwnerId에 넣지 않는다.

방 없음 ROOM_NOT_FOUND(404), 비참여자 NOT_ROOM_MEMBER(403), 잠금 대기 초과 ROOM_BUSY(409), UUID 형식 오류 VALIDATION_ERROR(400), 인증 오류는 기존 보안 계약을 유지한다. 재요청 시 방이 남으면 403, 삭제됐으면 404이며 성공으로 치환하지 않는다.

## 트랜잭션·삭제 정책

RoomLeaveService.leave는 READ COMMITTED 쓰기 트랜잭션을 시작하거나 기존 트랜잭션에 참여한다. RoomAccess.lockMember → 현재 role scalar 조회 → 본인 멤버 삭제 → members.flush → 남은 인원 조회 → 방 삭제 또는 findSuccessor → promoteToOwner 순서다. 엔티티를 조회한 뒤라도 역할 판단은 scalar DB 조회로 한다. DB 승계 조회를 Java UUID 정렬로 대체하지 않는다.

RoomAccess의 FOR NO KEY UPDATE·약 3초 lock_timeout·실제 격리 수준 및 Session.isDirty 검사를 유지한다. 명세의 FOR UPDATE 예시보다 기존 검증된 잠금 구현을 재사용하라는 이번 요구를 따른다. #35 읽기용 REPEATABLE READ/REQUIRES_NEW는 적용하지 않는다. 잠금 전 persist·update·remove를 하지 않으며 dirty 검사를 flush/clear 등으로 우회하지 않는다.

부분 유니크 인덱스는 OWNER 최대 1명만 보장한다. 삭제 SQL을 명시적으로 flush한 후 승격하여 Hibernate의 UPDATE 우선 실행으로 인한 충돌을 방지한다. 승격 및 방 삭제는 같은 트랜잭션에서 커밋되고 실패하면 멤버·투표 cascade까지 전부 롤백한다.

- 일반 퇴장은 현재 OWNER를 유지한다. created_by는 최초 생성자 기록으로 유지한다. 현재 방장은 OWNER 멤버의 userId이다.
- 투표는 기존 `(room_id,user_id)` 복합 FK cascade로 삭제한다.
- 방이 남으면 퇴장자의 제안 후보·과거 채팅·추천 실행 및 결과를 보존한다. 사용자 계정은 삭제하거나 비활성화하지 않는다.
- 마지막 멤버이면 meeting_rooms만 JPA로 삭제하고, 연결된 멤버·추천·후보·투표·채팅은 DB cascade가 정리한다. 다른 방은 유지한다.
- 자식 cascade 컬렉션과 새 마이그레이션은 추가하지 않는다.

## #38 재사용 계약

RoomLeaveProcessor.leaveLocked는 MANDATORY이므로 기존 트랜잭션 없이 호출하면 실패한다. 잠금 획득은 호출자의 책임이며 새 트랜잭션이나 방별 커밋을 만들지 않는다. 회원 탈퇴 전체는 구현하지 않는다.

후속 서비스는 사용자 단위 직렬화와 참여 방 목록 안정성을 먼저 보장한 뒤 아래 순서를 따른다. 여러 방 목록 조회·회원 탈퇴 정책은 #38에서 확정한다.

```java
@Transactional(isolation = Isolation.READ_COMMITTED)
public void futureCleanup(UUID userId) {
    // 이 위치까지 pending 변경을 만들지 않는다.
    // DB: SELECT room_id FROM meeting_members WHERE user_id = ? ORDER BY room_id ASC
    List<UUID> roomIds = readRoomIdsInDatabaseOrder(userId);
    List<MeetingRoom> locked = new ArrayList<>();
    for (UUID id : roomIds) locked.add(roomAccess.lockMember(id, userId));
    // 모든 방 잠금 획득 완료 후에만 변경한다.
    for (MeetingRoom room : locked) roomLeaveProcessor.leaveLocked(room, userId);
    // 이후 사용자 계정 관련 정책 처리도 같은 트랜잭션에 참여시킨다.
}
```

Java UUID 정렬은 PostgreSQL 순서와 다르므로 사용하지 않는다. 방 하나를 변경한 후 다음 lockMember를 호출하는 패턴은 지원하지 않는다. 중간 flush로 dirty 검사를 통과시키는 패턴도 재사용 예시로 삼지 않는다. 이미 READ COMMITTED가 아닌 외부 트랜잭션에 참여하면 기존 RoomAccess 검증이 변경 전에 거절한다.

## #43 이벤트·WS 연결

현재는 이벤트 발행·SYSTEM 저장·WS 세션 관리를 구현하지 않는다. 처리기 주석에 연결 위치를 남겼다.

- 살아 있는 방의 퇴장·방장 변경 SYSTEM 메시지는 방 잠금 후 같은 트랜잭션에 저장한다. 저장 실패도 전체 변경을 롤백해야 한다.
- MEMBER_LEFT·OWNER_CHANGED는 immutable payload를 등록하고 최외곽 트랜잭션의 성공 커밋 후에만 전달한다(예: AFTER_COMMIT 또는 outbox). 처리기 반환이나 내부 서비스 반환은 커밋 완료를 뜻하지 않는다. 롤백 시 발행하지 않는다.
- 방 삭제 분기에는 SYSTEM 메시지를 저장하지 않는다. 커밋 후 삭제된 방에 새 메시지를 INSERT하지 않는다.
- 퇴장자의 기존 구독에서 이후 수신을 막는 작업은 WS 세션 관리 후속 연동이다. 이번 HTTP 권한 검증만으로 기존 구독 차단이 완료되지는 않는다.

## 검증과 수동 확인

일반 서비스 테스트는 잠금·삭제·flush·승격의 호출 순서를 검증한다. PostgreSQL 테스트는 실제 마이그레이션·JWT/MockMvc 웹 경로·DB cascade와 실패 롤백·별도 연결 경합을 검증한다. 경합은 latch/pg_stat_activity의 실제 Lock 대기와 timeout을 사용하고 최종 DB 상태를 검사한다. UUID 동률 테스트는 Java와 PostgreSQL 순서가 갈리는 7fff…/8000…를 사용한다. 다중 방 선잠금 후 변경과 외부 롤백도 검증한다.

OpenAPI는 `/v3/api-docs`를 자동 호출하여 bearerAuth, 숨겨진 userId, 필수 UUID roomId, 고유 RoomLeaveResponse/RoomLeftData 및 오류 응답 참조·nullable 필드를 검사한다.

Swagger UI 브라우저 수동 확인은 수행하지 않았다. 확인 순서:

1. Swagger UI에서 Authorize에 사용자 A의 Access Token을 입력하고 방을 생성한다.
2. 사용자 B·C로 코드 참여 후 A로 DELETE를 호출하여 newOwnerId가 B의 사용자 UUID인지 확인한다.
3. B로 상세·초대 코드 조회 성공, A로 접근 거절을 확인한다.
4. 일반 멤버 C 퇴장 시 false/null, 마지막 B 퇴장 시 true/null, 재호출 404를 확인한다.
5. 남은 방에서 중복 퇴장 403, 비참여자 403, 잘못된 UUID 400, 토큰 제거 후 401을 확인한다.
6. DELETE 문서에서 userId 입력 없음, roomId 필수 UUID, 200의 세 예시 및 400/401/403/404/409/500 응답을 확인한다.

최종 실행: `.\gradlew.bat test postgresTest --no-daemon` BUILD SUCCESSFUL. 일반 443개·PostgreSQL 230개 통과, 실패·오류·스킵 각각 0개. 이번 추가 테스트는 서비스 1개·PostgreSQL 14개다. `git diff --check`도 통과했다. 초기 실행에서는 같은 트랜잭션 내 pg_stat_activity 통계 스냅샷으로 경합 관찰 테스트 2개가 실패했다. 관찰 루프에 pg_stat_clear_snapshot()을 추가한 뒤 전체 재실행으로 통과를 확인했다. 실제 WS·이벤트 발행과 회원 탈퇴 전체는 후속 범위이며 이번 수동 확인 완료로 간주하지 않는다.
