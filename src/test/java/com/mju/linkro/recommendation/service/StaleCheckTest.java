package com.mju.linkro.recommendation.service;

import com.mju.linkro.recommendation.service.RunCriteria.Participant;
import com.mju.linkro.room.domain.RoomRole;
import com.mju.linkro.room.repository.RoomReadRepository.MemberRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StaleCheckTest {
    static final UUID A = UUID.randomUUID();
    static final UUID B = UUID.randomUUID();
    static final Instant NOON = Instant.parse("2026-11-12T03:00:00Z");

    private static List<String> reasons(RunCriteria criteria, Instant appointment, List<MemberRow> members) {
        return StaleCheck.reasons(criteria, appointment, members);
    }

    private static RunCriteria criteria(Instant appointment, Participant... participants) {
        return new RunCriteria(null, null, null, false, appointment == null ? null : appointment.toString(), List.of(participants));
    }

    private static MemberRow member(UUID user, Long station) {
        return new MemberRow(UUID.randomUUID(), user, "닉", RoomRole.MEMBER, Instant.EPOCH, station);
    }

    @Test
    void unchangedRoomIsNotStaleEvenWhenMembersComeInAnotherOrder() {
        assertThat(reasons(criteria(NOON, new Participant(A, 1L), new Participant(B, 2L)), NOON,
                List.of(member(B, 2L), member(A, 1L)))).isEmpty();
    }

    @Test
    void appointmentChange() {
        var saved = criteria(NOON, new Participant(A, 1L), new Participant(B, 2L));
        var members = List.of(member(A, 1L), member(B, 2L));
        assertThat(reasons(saved, NOON.plusSeconds(60), members)).containsExactly("APPOINTMENT_CHANGED");
        assertThat(reasons(saved, null, members)).containsExactly("APPOINTMENT_CHANGED");
        assertThat(reasons(criteria(null, new Participant(A, 1L), new Participant(B, 2L)), NOON, members))
                .containsExactly("APPOINTMENT_CHANGED");
        assertThat(reasons(criteria(null, new Participant(A, 1L), new Participant(B, 2L)), null, members)).isEmpty();
    }

    @Test
    void memberChanges() {
        var saved = criteria(NOON, new Participant(A, 1L), new Participant(B, 2L));
        // departure station changed, a member left, a member joined
        assertThat(reasons(saved, NOON, List.of(member(A, 1L), member(B, 3L)))).containsExactly("MEMBERS_CHANGED");
        assertThat(reasons(saved, NOON, List.of(member(A, 1L)))).containsExactly("MEMBERS_CHANGED");
        assertThat(reasons(saved, NOON, List.of(member(A, 1L), member(B, 2L), member(UUID.randomUUID(), null)))).containsExactly("MEMBERS_CHANGED");
    }

    @Test
    void nullDepartureStationEqualsNullAndDiffersFromAValue() {
        var saved = criteria(NOON, new Participant(A, null), new Participant(B, 2L));
        assertThat(reasons(saved, NOON, List.of(member(A, null), member(B, 2L)))).isEmpty();
        assertThat(reasons(saved, NOON, List.of(member(A, 1L), member(B, 2L)))).containsExactly("MEMBERS_CHANGED");
    }

    @Test
    void bothReasonsAreListedInOrder() {
        var saved = criteria(NOON, new Participant(A, 1L), new Participant(B, 2L));
        assertThat(reasons(saved, NOON.plusSeconds(1), List.of(member(A, 1L)))).containsExactly("APPOINTMENT_CHANGED", "MEMBERS_CHANGED");
    }
}
