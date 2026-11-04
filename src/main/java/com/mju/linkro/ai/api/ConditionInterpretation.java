package com.mju.linkro.ai.api;

import java.util.List;

/**
 * {@code conditionText} turned into API spec 8.1 filter items. Null limits mean "not mentioned". Station names and
 * nicknames are returned as written; mapping to stationId/userId and range checks belong to #74.
 */
public record ConditionInterpretation(Integer maxMinutes, Integer maxTransfers, Integer maxFareWon,
        List<String> excludeStationNames, List<MemberLimit> memberLimits) {
    public ConditionInterpretation {
        excludeStationNames = excludeStationNames == null ? List.of() : List.copyOf(excludeStationNames);
        memberLimits = memberLimits == null ? List.of() : List.copyOf(memberLimits);
    }
}
