package com.mju.linkro.recommendation.service;

import com.mju.linkro.room.repository.RoomReadRepository.MemberRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * isStale / staleReasons of API 8.1~8.3, computed when a run is read and never stored.
 * Both comparisons treat null as a value: null = null is equal, null and a value differ.
 */
final class StaleCheck {
    static final String APPOINTMENT_CHANGED = "APPOINTMENT_CHANGED";
    static final String MEMBERS_CHANGED = "MEMBERS_CHANGED";

    private StaleCheck() {}

    /** Empty when the run is still up to date; otherwise APPOINTMENT_CHANGED and/or MEMBERS_CHANGED, in that order. */
    static List<String> reasons(RunCriteria criteria, Instant currentAppointment, List<MemberRow> currentMembers) {
        List<String> reasons = new ArrayList<>();
        Instant saved = criteria.appointmentAt() == null ? null : Instant.parse(criteria.appointmentAt());
        if (!Objects.equals(saved, currentAppointment)) {
            reasons.add(APPOINTMENT_CHANGED);
        }
        Set<RunCriteria.Participant> current = new HashSet<>();
        currentMembers.forEach(member -> current.add(new RunCriteria.Participant(member.userId(), member.stationId())));
        if (!current.equals(new HashSet<>(criteria.participants()))) {
            reasons.add(MEMBERS_CHANGED);
        }
        return reasons;
    }
}
