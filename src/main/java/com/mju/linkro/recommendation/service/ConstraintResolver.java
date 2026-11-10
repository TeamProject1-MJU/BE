package com.mju.linkro.recommendation.service;

import com.mju.linkro.ai.api.AiClient;
import com.mju.linkro.ai.api.ConditionInterpretation;
import com.mju.linkro.ai.api.ConditionRequest;
import com.mju.linkro.recommendation.dto.RecommendationRequest;
import com.mju.linkro.recommendation.dto.RecommendationRequest.Filter;
import com.mju.linkro.recommendation.dto.RecommendationResponse.AppliedConstraints;
import com.mju.linkro.recommendation.dto.RecommendationResponse.MemberLimit;
import com.mju.linkro.recommendation.repository.RecommendationRepository;
import com.mju.linkro.recommendation.service.Candidate.Member;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Turns {@code conditionText} and {@code filter} into the constraints that are applied (API 8.1).
 *
 * <p>The AI only returns strings and numbers; this class checks the ranges, maps station names to stationId and
 * nicknames to userId, and drops what it cannot map. An AI failure is not an error: the filter alone is used and
 * {@code parseFailed} is set. A filter item always wins over the same item from the AI.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConstraintResolver {
    private final AiClient ai;
    private final RecommendationRepository repository;

    /**
     * @param dropped what the AI said but could not be applied (unknown station or nickname, value out of range),
     *                for the response message; never stored
     */
    public record Resolved(AppliedConstraints applied, boolean parseFailed, List<String> dropped) {}

    /** The filter is already validated; this never throws because of the AI. */
    public Resolved resolve(RecommendationRequest request, List<Member> members) {
        Filter filter = request.filter();
        ConditionInterpretation parsed = null;
        boolean parseFailed = false;
        List<String> dropped = new ArrayList<>();
        Map<String, List<Long>> stationIds = Map.of();
        String text = request.text();
        if (text != null) {
            stationIds = repository.supportedStationIdsByName();
            try {
                parsed = ai.interpretCondition(new ConditionRequest(text,
                        members.stream().map(Member::nickname).toList(), hints(text, stationIds.keySet())));
            } catch (RuntimeException exception) {
                // Business errors carry AI_UNAVAILABLE / AI_QUOTA_EXCEEDED; anything else must not stop the run either.
                log.warn("Condition interpretation failed, using the filter only: {}", exception.getClass().getSimpleName());
                parseFailed = true;
            }
        }

        Integer maxMinutes = filter != null && filter.maxMinutes() != null ? filter.maxMinutes()
                : ranged(parsed == null ? null : parsed.maxMinutes(), RecommendationRequest.MIN_MINUTES,
                        RecommendationRequest.MAX_MINUTES, "최대 시간", dropped);
        Integer maxTransfers = filter != null && filter.maxTransfers() != null ? filter.maxTransfers()
                : ranged(parsed == null ? null : parsed.maxTransfers(), RecommendationRequest.MIN_TRANSFERS,
                        RecommendationRequest.MAX_TRANSFERS, "최대 환승", dropped);
        Integer maxFareWon = filter != null && filter.maxFareWon() != null ? filter.maxFareWon()
                : ranged(parsed == null ? null : parsed.maxFareWon(), RecommendationRequest.MIN_FARE_WON,
                        RecommendationRequest.MAX_FARE_WON, "최대 요금", dropped);

        List<Long> excluded;
        if (filter != null && filter.excludeStationIds() != null && !filter.excludeStationIds().isEmpty()) {
            excluded = new ArrayList<>(new LinkedHashSet<>(filter.excludeStationIds()));
        } else {
            Set<Long> ids = new LinkedHashSet<>();
            for (String name : parsed == null ? List.<String>of() : parsed.excludeStationNames()) {
                Long id = stationId(stationIds, name);
                if (id == null) {
                    dropped.add("제외 역 '" + name + "'");
                } else {
                    ids.add(id);
                }
            }
            excluded = new ArrayList<>(ids);
        }

        Map<UUID, Integer> limits = new LinkedHashMap<>();
        if (filter != null && filter.memberLimits() != null && !filter.memberLimits().isEmpty()) {
            filter.memberLimits().forEach(limit -> limits.put(limit.userId(), limit.maxMinutes()));
        } else if (parsed != null) {
            for (com.mju.linkro.ai.api.MemberLimit limit : parsed.memberLimits()) {
                UUID userId = userId(members, limit.nickname());
                Integer minutes = ranged(limit.maxMinutes(), RecommendationRequest.MIN_MINUTES,
                        RecommendationRequest.MAX_MINUTES, null, dropped);
                if (userId == null || minutes == null) {
                    dropped.add("개인 한도 '" + limit.nickname() + "'");
                } else {
                    limits.put(userId, minutes);
                }
            }
        }
        AppliedConstraints applied = new AppliedConstraints(maxMinutes, maxTransfers, maxFareWon, excluded,
                limits.entrySet().stream().map(entry -> new MemberLimit(entry.getKey(), entry.getValue())).toList());
        return new Resolved(applied, parseFailed, List.copyOf(dropped));
    }

    /** Station names that occur in the text, so the AI knows which of ours the user may mean. */
    private static List<String> hints(String text, Set<String> names) {
        return names.stream().filter(text::contains).sorted().toList();
    }

    /** One station for the name (a trailing 역 is ignored), or null when unknown or shared by several stations. */
    private static Long stationId(Map<String, List<Long>> stationIds, String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.strip();
        List<Long> ids = stationIds.get(trimmed);
        if (ids == null && trimmed.length() > 1 && trimmed.endsWith("역")) {
            ids = stationIds.get(trimmed.substring(0, trimmed.length() - 1));
        }
        return ids != null && ids.size() == 1 ? ids.get(0) : null;
    }

    /** The one member with this nickname, or null when nobody or several have it. */
    private static UUID userId(List<Member> members, String nickname) {
        if (nickname == null) {
            return null;
        }
        List<Member> matches = members.stream().filter(member -> member.nickname().equals(nickname.strip())).toList();
        return matches.size() == 1 ? matches.get(0).userId() : null;
    }

    /** The value when inside the range, otherwise null; a value that is out of range is noted when a label is given. */
    private static Integer ranged(Integer value, int min, int max, String label, List<String> dropped) {
        if (value == null) {
            return null;
        }
        if (value < min || value > max) {
            if (label != null) {
                dropped.add(label);
            }
            return null;
        }
        return value;
    }
}
