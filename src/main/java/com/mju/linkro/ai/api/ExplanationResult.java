package com.mju.linkro.ai.api;

import java.util.Map;

/**
 * Explanation sentences of one {@link AiClient#explainRecommendation} call, by criterion name. A criterion the AI
 * did not explain is simply missing; the caller uses its template for it.
 */
public record ExplanationResult(Map<String, String> texts) {
    public ExplanationResult {
        texts = texts == null ? Map.of() : Map.copyOf(texts);
    }
}
