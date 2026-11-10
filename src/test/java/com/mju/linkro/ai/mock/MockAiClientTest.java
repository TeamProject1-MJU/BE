package com.mju.linkro.ai.mock;

import com.mju.linkro.ai.api.*;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.subway.api.DirectionCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockAiClientTest {
    private final MockAiClient client = new MockAiClient();

    @Test
    void interpretsSpecFilterItems() {
        var result = client.interpretCondition(new ConditionRequest("30분 이내, 2000원 이하로 환승 없이, 강남 빼고",
                List.of("민수"), List.of("강남", "홍대")));
        assertThat(result).isEqualTo(new ConditionInterpretation(30, 0, 2000, List.of("강남"), List.of()));
        var limits = client.interpretCondition(new ConditionRequest("민수는 30분, 지영은 상관없음", List.of("민수", "지영"), null));
        assertThat(limits).isEqualTo(new ConditionInterpretation(null, null, null, List.of(), List.of(new MemberLimit("민수", 30))));
        assertThat(client.interpretCondition(new ConditionRequest("환승 1번 이하", null, null)).maxTransfers()).isEqualTo(1);
        assertThat(client.interpretCondition(new ConditionRequest("아무데나", null, null)).maxMinutes()).isNull();
    }

    @Test
    void readSignResultFollowsFirstByte() {
        var ctx = new SignContext("강남", "2호선", DirectionCode.INNER, "사당 방면");
        assertThat(client.readSign(new SignImage(new byte[] {1}, "image/jpeg"), ctx))
                .isEqualTo(new SignReading(SignResult.CORRECT, null, "강남", "2호선", "사당 방면"));
        assertThat(client.readSign(new SignImage(new byte[] {MockAiClient.SIGN_WRONG_BYTE}, "image/jpeg"), ctx))
                .isEqualTo(new SignReading(SignResult.WRONG, SignReason.DIRECTION_MISMATCH, "강남", "2호선", "반대 방면"));
        assertThat(client.readSign(new SignImage(new byte[] {MockAiClient.SIGN_RETAKE_BYTE}, "image/jpeg"), ctx))
                .isEqualTo(new SignReading(SignResult.RETAKE, SignReason.UNREADABLE, null, null, null));
    }

    @Test
    void signReadingRejectsReasonNotMatchingResult() {
        assertThatThrownBy(() -> new SignReading(SignResult.CORRECT, SignReason.UNREADABLE, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SignReading(SignResult.WRONG, SignReason.NOT_A_SIGN, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SignReading(SignResult.RETAKE, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void textsAreFixedTemplates() {
        var explanations = client.explainRecommendation(new ExplanationRequest(List.of(
                new ExplanationRequest.Item("TIME", "강남", Map.of("maxMinutes", 20)),
                new ExplanationRequest.Item("FARE", "사당", Map.of("totalFareWon", 3100)))));
        assertThat(explanations.texts()).containsOnlyKeys("TIME", "FARE");
        assertThat(explanations.texts().get("TIME")).contains("강남", "TIME");
        assertThat(explanations.texts().get("FARE")).contains("사당", "FARE");
        assertThat(client.explainRecommendation(new ExplanationRequest(List.of())).texts()).isEmpty();
        assertThat(client.replyToChat(new ChatReplyRequest("어디서 만나?", null, null)).text()).contains("어디서 만나?");
    }

    @Test
    void markersReproduceFailures() {
        assertCode(() -> client.interpretCondition(new ConditionRequest("x" + MockAiClient.FAIL_MARKER, null, null)),
                ErrorCode.AI_UNAVAILABLE);
        // one marked item fails the whole explanation call
        assertCode(() -> client.explainRecommendation(new ExplanationRequest(List.of(
                new ExplanationRequest.Item("TIME", "강남", null), new ExplanationRequest.Item("FARE", "x" + MockAiClient.FAIL_MARKER, null)))),
                ErrorCode.AI_UNAVAILABLE);
        assertCode(() -> client.replyToChat(new ChatReplyRequest(MockAiClient.QUOTA_MARKER, null, null)),
                ErrorCode.AI_QUOTA_EXCEEDED);
        assertCode(() -> client.readSign(new SignImage(new byte[] {1}, "image/png"),
                new SignContext(null, MockAiClient.FAIL_MARKER, null, null)), ErrorCode.AI_UNAVAILABLE);
    }

    @Test
    void signImageToStringHidesBytes() {
        assertThat(new SignImage(new byte[] {42, 43}, "image/png").toString()).isEqualTo("SignImage[contentType=image/png, size=2]");
    }

    private static void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(code));
    }
}
