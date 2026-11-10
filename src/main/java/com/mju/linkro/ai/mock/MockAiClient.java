package com.mju.linkro.ai.mock;

import com.mju.linkro.ai.api.*;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic AI for local runs and tests. Conditions: "N분 이내", "N원 이하", "환승 없이", "환승 N번 이하",
 * "<역> 빼고" (exclude), "<닉네임>는 N분" (memberLimits, then maxMinutes is left null). Any text containing {@link #FAIL_MARKER} or {@link #QUOTA_MARKER}
 * throws the matching error so callers can test their fallbacks. Sign images: first byte {@link #SIGN_RETAKE_BYTE}
 * gives RETAKE/UNREADABLE, {@link #SIGN_WRONG_BYTE} gives WRONG/DIRECTION_MISMATCH, anything else CORRECT echoing ctx.
 */
public class MockAiClient implements AiClient {
    public static final String FAIL_MARKER = "__AI_FAIL__";
    public static final String QUOTA_MARKER = "__AI_QUOTA__";
    public static final byte SIGN_RETAKE_BYTE = 0;
    public static final byte SIGN_WRONG_BYTE = 'W';

    private static final Pattern MINUTES = Pattern.compile("(\\d+)\\s*분\\s*(이내|안|이하)");
    private static final Pattern FARE = Pattern.compile("(\\d+)\\s*원\\s*(이내|안|이하)");
    private static final Pattern TRANSFERS = Pattern.compile("환승\\s*(\\d+)\\s*(번|회)\\s*(이내|이하)");

    @Override
    public ConditionInterpretation interpretCondition(ConditionRequest req) {
        String text = req.conditionText();
        checkMarkers(text);
        Integer maxTransfers = text.contains("환승 없이") ? Integer.valueOf(0) : number(TRANSFERS, text);
        List<String> exclude = req.stationNameHints().stream().filter(name -> text.contains(name + " 빼고")).toList();
        List<MemberLimit> limits = req.memberNicknames().stream()
                .map(nickname -> new MemberLimit(nickname, number(Pattern.compile(Pattern.quote(nickname) + "\\S*\\s*(\\d+)\\s*분"), text)))
                .filter(limit -> limit.maxMinutes() != null).toList();
        Integer maxMinutes = limits.isEmpty() ? number(MINUTES, text) : null;
        return new ConditionInterpretation(maxMinutes, maxTransfers, number(FARE, text), exclude, limits);
    }

    @Override
    public ExplanationResult explainRecommendation(ExplanationRequest req) {
        Map<String, String> texts = new LinkedHashMap<>();
        for (ExplanationRequest.Item item : req.items()) {
            checkMarkers(item.criterion());
            checkMarkers(item.stationName());
            texts.put(item.criterion(), item.stationName() + "역은 '" + item.criterion() + "' 기준으로 추천된 약속 장소입니다.");
        }
        return new ExplanationResult(texts);
    }

    @Override
    public SignReading readSign(SignImage image, SignContext ctx) {
        checkMarkers(ctx.lineName());
        checkMarkers(ctx.headsign());
        byte first = image.bytes()[0];
        if (first == SIGN_RETAKE_BYTE) {
            return new SignReading(SignResult.RETAKE, SignReason.UNREADABLE, null, null, null);
        }
        if (first == SIGN_WRONG_BYTE) {
            return new SignReading(SignResult.WRONG, SignReason.DIRECTION_MISMATCH, ctx.stationName(), ctx.lineName(), "반대 방면");
        }
        return new SignReading(SignResult.CORRECT, null, ctx.stationName(), ctx.lineName(), ctx.headsign());
    }

    @Override
    public GeneratedText replyToChat(ChatReplyRequest req) {
        checkMarkers(req.question());
        return new GeneratedText("(모의 응답) 질문을 확인했어요: " + req.question());
    }

    private static void checkMarkers(String text) {
        if (text == null) return;
        if (text.contains(QUOTA_MARKER)) throw new BusinessException(ErrorCode.AI_QUOTA_EXCEEDED);
        if (text.contains(FAIL_MARKER)) throw new BusinessException(ErrorCode.AI_UNAVAILABLE);
    }

    private static Integer number(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }
}
