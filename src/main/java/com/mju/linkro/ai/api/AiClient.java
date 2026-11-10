package com.mju.linkro.ai.api;

/**
 * AI use cases for #74 (recommendation), #75 (sign check) and #76 (chat).
 * Every failure is a {@code BusinessException} with {@code AI_UNAVAILABLE} or {@code AI_QUOTA_EXCEEDED};
 * fallbacks (templates, RETAKE) are the caller's job. The AI never computes numbers;
 * for signs its result is used as-is (API spec 9.1).
 */
public interface AiClient {
    ConditionInterpretation interpretCondition(ConditionRequest req);

    /** One call for every criterion of a recommendation; the result has one sentence per explained criterion. */
    ExplanationResult explainRecommendation(ExplanationRequest req);

    SignReading readSign(SignImage image, SignContext ctx);

    GeneratedText replyToChat(ChatReplyRequest req);
}
