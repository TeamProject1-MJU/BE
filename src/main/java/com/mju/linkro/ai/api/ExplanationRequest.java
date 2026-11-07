package com.mju.linkro.ai.api;

import java.util.Map;
import java.util.Objects;

/** {@code facts} holds only server-computed values; the AI must not invent numbers. */
public record ExplanationRequest(String criterion, String stationName, Map<String, Object> facts) {
    public ExplanationRequest {
        Objects.requireNonNull(criterion, "criterion");
        Objects.requireNonNull(stationName, "stationName");
        facts = facts == null ? Map.of() : Map.copyOf(facts);
    }
}
