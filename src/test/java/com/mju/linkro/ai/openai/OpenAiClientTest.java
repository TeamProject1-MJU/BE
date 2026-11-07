package com.mju.linkro.ai.openai;

import com.mju.linkro.ai.api.*;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.subway.api.DirectionCode;
import java.net.SocketTimeoutException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** MockRestServiceServer only; never calls the real OpenAI API. */
class OpenAiClientTest {
    private static final String URL = "https://api.openai.test/v1/responses";
    private MockRestServiceServer server;
    private OpenAiClient client;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder().baseUrl("https://api.openai.test");
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        client = new OpenAiClient(restClient, restClient, "test-dummy", "test-model");
    }

    @Test
    void interpretConditionSendsStrictSchemaAndParsesOutput() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-dummy"))
                .andExpect(jsonPath("$.model").value("test-model"))
                .andExpect(jsonPath("$.text.format.type").value("json_schema"))
                .andExpect(jsonPath("$.text.format.strict").value(true))
                .andExpect(jsonPath("$.text.format.schema.additionalProperties").value(false))
                .andExpect(jsonPath("$.text.format.schema.required[2]").value("maxFareWon"))
                .andRespond(withSuccess(response("{\\\"maxMinutes\\\":40,\\\"maxTransfers\\\":1,\\\"maxFareWon\\\":null,"
                        + "\\\"excludeStationNames\\\":[\\\"강남\\\"],\\\"memberLimits\\\":[{\\\"nickname\\\":\\\"민수\\\",\\\"maxMinutes\\\":30}]}"),
                        MediaType.APPLICATION_JSON));
        var result = client.interpretCondition(new ConditionRequest("다들 40분 안쪽, 환승 한 번 이하, 강남 빼고, 민수는 30분", null, null));
        assertThat(result).isEqualTo(new ConditionInterpretation(40, 1, null, List.of("강남"),
                List.of(new MemberLimit("민수", 30))));
        server.verify();
    }

    @Test
    void readSignSendsExpectedRouteAndBase64ImageAndParsesResult() {
        byte[] bytes = {1, 2, 3};
        String dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes);
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.model").value("test-model"))
                .andExpect(jsonPath("$.text.format.type").value("json_schema"))
                .andExpect(jsonPath("$.text.format.name").value("sign"))
                .andExpect(jsonPath("$.text.format.schema.required[0]").value("result"))
                .andExpect(jsonPath("$.input[0].content[0].text").value(containsString("INNER")))
                .andExpect(jsonPath("$.input[0].content[0].text").value(containsString("사당 방면")))
                .andExpect(jsonPath("$.input[0].content[1].type").value("input_image"))
                .andExpect(jsonPath("$.input[0].content[1].image_url").value(dataUrl))
                .andExpect(jsonPath("$.input[0].content[1].detail").value("low"))
                .andRespond(withSuccess(response("{\\\"result\\\":\\\"WRONG\\\",\\\"reason\\\":\\\"DIRECTION_MISMATCH\\\","
                        + "\\\"stationName\\\":\\\"강남\\\",\\\"lineName\\\":\\\"2호선\\\",\\\"headsign\\\":\\\"성수 방면\\\"}"),
                        MediaType.APPLICATION_JSON));
        var reading = client.readSign(new SignImage(bytes, "image/jpeg"),
                new SignContext("강남", "2호선", DirectionCode.INNER, "사당 방면"));
        assertThat(reading).isEqualTo(new SignReading(SignResult.WRONG, SignReason.DIRECTION_MISMATCH, "강남", "2호선", "성수 방면"));
        server.verify();
    }

    @Test
    void readSignWithInconsistentResultMapsToAiUnavailable() {
        server.expect(requestTo(URL)).andRespond(withSuccess(response("{\\\"result\\\":\\\"CORRECT\\\",\\\"reason\\\":\\\"UNREADABLE\\\","
                + "\\\"stationName\\\":null,\\\"lineName\\\":null,\\\"headsign\\\":null}"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.readSign(new SignImage(new byte[] {1}, "image/png"),
                new SignContext("강남", "2호선", null, "사당 방면")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.AI_UNAVAILABLE));
        server.verify();
    }

    @Test
    void textUseCasesReturnTrimmedText() {
        server.expect(requestTo(URL)).andExpect(jsonPath("$.text").doesNotExist())
                .andRespond(withSuccess(response("  강남역이 가장 공평해요. "), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL))
                .andRespond(withSuccess(response("x".repeat(600)), MediaType.APPLICATION_JSON));
        assertThat(client.explainRecommendation(new ExplanationRequest("공평", "강남", Map.of("avg", 20))).text())
                .isEqualTo("강남역이 가장 공평해요.");
        assertThat(client.replyToChat(new ChatReplyRequest("어디?", List.of(new ChatTurn("민수", "안녕")), null)).text())
                .hasSize(OpenAiClient.CHAT_MAX_LENGTH);
        server.verify();
    }

    @Test
    void quotaMapsToAiQuotaExceeded() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertCode(ErrorCode.AI_QUOTA_EXCEEDED);
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"BAD_REQUEST", "UNAUTHORIZED", "FORBIDDEN", "INTERNAL_SERVER_ERROR"})
    void otherStatusesMapToAiUnavailable(HttpStatus status) {
        server.expect(requestTo(URL)).andRespond(withStatus(status));
        assertCode(ErrorCode.AI_UNAVAILABLE);
    }

    @Test
    void timeoutMapsToAiUnavailableWithoutRawMessage() {
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("Bearer test-dummy")));
        BusinessException e = assertCode(ErrorCode.AI_UNAVAILABLE);
        assertThat(e.getCause().getMessage()).doesNotContain("test-dummy");
        assertThat(e.getCause().getCause()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json",
            "{\"status\":\"incomplete\",\"output\":[]}",
            "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"no\"}]}]}",
            "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"{broken\"}]}]}"
    })
    void brokenIncompleteOrRefusedResponsesMapToAiUnavailable(String body) {
        server.expect(requestTo(URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertCode(ErrorCode.AI_UNAVAILABLE);
    }

    private BusinessException assertCode(ErrorCode code) {
        var thrown = assertThatThrownBy(() -> client.interpretCondition(new ConditionRequest("조건", null, null)))
                .isInstanceOf(BusinessException.class).actual();
        BusinessException e = (BusinessException) thrown;
        assertThat(e.getErrorCode()).isEqualTo(code);
        server.verify();
        return e;
    }

    /** Responses API body with one output_text part; {@code escapedText} is already JSON-string escaped. */
    private static String response(String escapedText) {
        return "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":"
                + "[{\"type\":\"output_text\",\"text\":\"" + escapedText + "\"}]}]}";
    }
}
