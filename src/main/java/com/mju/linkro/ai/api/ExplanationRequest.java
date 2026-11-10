package com.mju.linkro.ai.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * All criteria results of one recommendation, explained in a single call (API spec 8.1: one explanation call per
 * recommendation, up to three sentences). Each item's {@code facts} holds only server-computed values; the AI must
 * not invent numbers.
 */
public record ExplanationRequest(List<Item> items) {
    public ExplanationRequest {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** One criterion (TIME, FARE or DISTANCE) with its best station and the numbers behind it. */
    public record Item(String criterion, String stationName, Map<String, Object> facts) {
        public Item {
            Objects.requireNonNull(criterion, "criterion");
            Objects.requireNonNull(stationName, "stationName");
            facts = facts == null ? Map.of() : Map.copyOf(facts);
        }
    }
}
