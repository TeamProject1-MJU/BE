package com.mju.linkro.recommendation.service;

import com.mju.linkro.ai.api.AiClient;
import com.mju.linkro.ai.api.ExplanationRequest;
import com.mju.linkro.recommendation.dto.RecommendationResponse.Metrics;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The explanation sentence of a result (API 8.1). The AI only words what the server computed: the facts go in, and a
 * sentence with a number that is not among the facts is thrown away. A failed call or a mismatch gives the template
 * sentence with source TEMPLATE.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExplanationWriter {
    public static final String SOURCE_AI = "AI";
    public static final String SOURCE_TEMPLATE = "TEMPLATE";

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:,\\d{3})*(?:\\.\\d+)?");

    private final AiClient ai;

    public record Explanation(String text, String source) {}

    /** One result to explain: its numbers and the station the AI names. */
    public record Item(Facts facts, String stationName) {}

    /** The numbers behind one result; the same values are the only ones an AI sentence may contain. */
    public record Facts(Criterion criterion, int participantCount, Metrics metrics, int totalFareWon, int totalDistanceM) {

        double totalDistanceKm() {
            return Math.round(totalDistanceM / 100.0) / 10.0;
        }

        Map<String, Object> asMap() {
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("participantCount", participantCount);
            facts.put("maxMinutes", metrics.maxMinutes());
            facts.put("averageMinutes", metrics.averageMinutes());
            facts.put("maxDifferenceMinutes", metrics.maxDifferenceMinutes());
            facts.put("totalFareWon", totalFareWon);
            facts.put("averageFareWon", metrics.averageFare());
            facts.put("totalDistanceM", totalDistanceM);
            facts.put("totalDistanceKm", totalDistanceKm());
            facts.put("totalTransfers", metrics.totalTransfers());
            return facts;
        }
    }

    /**
     * One AI call for all items (API 8.1: one explanation call per recommendation; none when there is no item).
     * Each sentence is checked against its own facts: a missing, blank or mismatching sentence gives that criterion's
     * template, the others keep the AI sentence. A failed call gives every criterion its template.
     */
    public Map<Criterion, Explanation> explain(List<Item> items) {
        Map<Criterion, Explanation> explanations = new EnumMap<>(Criterion.class);
        if (items.isEmpty()) {
            return explanations;
        }
        Map<String, String> texts = Map.of();
        try {
            texts = ai.explainRecommendation(new ExplanationRequest(items.stream()
                    .map(item -> new ExplanationRequest.Item(item.facts().criterion().name(), item.stationName(), item.facts().asMap()))
                    .toList())).texts();
        } catch (RuntimeException exception) {
            log.warn("AI explanation failed, using templates: {}", exception.getClass().getSimpleName());
        }
        for (Item item : items) {
            Criterion criterion = item.facts().criterion();
            String text = texts.get(criterion.name());
            text = text == null ? "" : text.strip();
            if (!text.isEmpty() && numbersMatch(text, item.stationName(), item.facts())) {
                explanations.put(criterion, new Explanation(text, SOURCE_AI));
            } else {
                if (!texts.isEmpty()) {
                    log.warn("AI explanation for {} dropped: {}", criterion, text.isEmpty() ? "missing" : "number mismatch");
                }
                explanations.put(criterion, new Explanation(template(item.facts()), SOURCE_TEMPLATE));
            }
        }
        return explanations;
    }

    static String template(Facts facts) {
        return switch (facts.criterion()) {
            case TIME -> "가장 오래 걸리는 사람이 " + facts.metrics().maxMinutes() + "분으로 모두에게 가장 공평한 역입니다.";
            case FARE -> "참여자 총요금이 " + won(facts.totalFareWon()) + "원으로 가장 저렴한 역입니다.";
            case DISTANCE -> "참여자 이동거리 합이 " + String.format(Locale.ROOT, "%.1f", facts.totalDistanceKm()) + "km로 가장 짧은 역입니다.";
        };
    }

    /**
     * Every number in the sentence (commas ignored) must equal one of the facts. The station name and line names such
     * as "2호선" are removed first: digits in names (종로3가) are not claims about the numbers.
     */
    static boolean numbersMatch(String sentence, String stationName, Facts facts) {
        String text = sentence.replace(stationName, " ").replaceAll("\\d+호선", " ");
        Set<Double> allowed = new HashSet<>();
        facts.asMap().values().forEach(value -> allowed.add(((Number) value).doubleValue()));
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            double value = Double.parseDouble(matcher.group().replace(",", ""));
            if (allowed.stream().noneMatch(fact -> Math.abs(fact - value) < 1e-9)) {
                return false;
            }
        }
        return true;
    }

    private static String won(int value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
