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
 */
public record RunCriteria(String conditionText, Filter filter, AppliedConstraints appliedConstraints,
                          boolean conditionParseFailed, String appointmentAt, List<Participant> participants) {

    public record Participant(UUID userId, Long departureStationId) {}
}
