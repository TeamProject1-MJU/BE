package com.mju.linkro.recommendation.service;

import com.mju.linkro.recommendation.dto.RecommendationRequest.Filter;
import com.mju.linkro.recommendation.dto.RecommendationResponse.AppliedConstraints;
import java.util.List;
import java.util.UUID;

/**
 * recommendation_runs.criteria (API 8.1): the request and the room as they were when the run was made.
 *
 * @param appointmentAt ISO-8601 instant of the room's appointment_at, null when the room had none
 * @param participants  the room's members at that time, for the MEMBERS_CHANGED comparison
 * @param unreachableByTrain stations left out because a member cannot arrive in time on the timetable's trains (#96);
 *                      null in older runs
 */
public record RunCriteria(String conditionText, Filter filter, AppliedConstraints appliedConstraints,
                          boolean conditionParseFailed, String appointmentAt, List<Participant> participants,
                          Integer unreachableByTrain) {

    public RunCriteria(String conditionText, Filter filter, AppliedConstraints appliedConstraints,
                       boolean conditionParseFailed, String appointmentAt, List<Participant> participants) {
        this(conditionText, filter, appliedConstraints, conditionParseFailed, appointmentAt, participants, null);
    }

    public record Participant(UUID userId, Long departureStationId) {}
}
