package com.mju.linkro.ai.api;

import java.util.List;
import java.util.Objects;

public record ConditionRequest(String conditionText, List<String> memberNicknames, List<String> stationNameHints) {
    public ConditionRequest {
        Objects.requireNonNull(conditionText, "conditionText");
        memberNicknames = memberNicknames == null ? List.of() : List.copyOf(memberNicknames);
        stationNameHints = stationNameHints == null ? List.of() : List.copyOf(stationNameHints);
    }
}
