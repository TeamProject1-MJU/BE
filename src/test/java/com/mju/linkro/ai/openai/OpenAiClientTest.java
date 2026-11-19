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
    void visionReasoningEffortIsSentOnlyWhenConfiguredAndOnlyForSigns() {
        String ok = response("{\\\"result\\\":\\\"RETAKE\\\",\\\"reason\\\":\\\"UNREADABLE\\\","
                + "\\\"stationName\\\":null,\\\"lineName\\\":null,\\\"headsign\\\":null}");
        var ctx = new SignContext("강남", "2호선", null, "사당 방면");
        var image = new SignImage(new byte[] {1}, "image/jpeg");

        server.expect(requestTo(URL)).andExpect(jsonPath("$.reasoning").doesNotExist())
                .andRespond(withSuccess(ok, MediaType.APPLICATION_JSON));
        client.readSign(image, ctx);
        server.verify();

        var builder = RestClient.builder().baseUrl("https://api.openai.test");
        var configuredServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        var configured = new OpenAiClient(restClient, restClient, restClient, "test-dummy", "test-model", " low ", null);
        configuredServer.expect(requestTo(URL)).andExpect(jsonPath("$.reasoning.effort").value("low"))
                .andRespond(withSuccess(ok, MediaType.APPLICATION_JSON));
        configured.readSign(image, ctx);
        configuredServer.verify();
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
    void directionOnlySendsUnknownHeadsignAndNoTerminalInferenceInstruction() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.input[0].content[0].text").value(containsString("DIRECTION_ONLY")))
                .andExpect(jsonPath("$.input[0].content[0].text").value(containsString("행선지=미상")))
                .andExpect(jsonPath("$.instructions").value(containsString("지리 지식으로 추정하지 않는다")))
                .andRespond(withSuccess(response("{\\\"result\\\":\\\"RETAKE\\\",\\\"reason\\\":\\\"MISSING_INFO\\\","
                        + "\\\"stationName\\\":null,\\\"lineName\\\":null,\\\"headsign\\\":null}"), MediaType.APPLICATION_JSON));
        var result = client.readSign(new SignImage(new byte[]{1}, "image/png"),
                new SignContext("강남", "2호선", DirectionCode.INNER, null));
        assertThat(result.reason()).isEqualTo(SignReason.MISSING_INFO);
        server.verify();
    }

    @Test
    void readSignAsksForStationCodeAndTowardStationsAndParsesThem() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.text.format.schema.required[5]").value("stationCode"))
                .andExpect(jsonPath("$.text.format.schema.required[6]").value("towardStations"))
                .andExpect(jsonPath("$.instructions").value(containsString("towardStations")))
                .andRespond(withSuccess(response("{\\\"result\\\":\\\"RETAKE\\\",\\\"reason\\\":\\\"MISSING_INFO\\\","
                        + "\\\"stationName\\\":\\\"을지로입구\\\",\\\"lineName\\\":null,\\\"headsign\\\":null,"
                        + "\\\"stationCode\\\":\\\"202\\\",\\\"towardStations\\\":[\\\"시청\\\"]}"), MediaType.APPLICATION_JSON));
        var result = client.readSign(new SignImage(new byte[]{1}, "image/png"),
                new SignContext("을지로입구", "2호선", DirectionCode.OUTER, null));
        assertThat(result).isEqualTo(new SignReading(SignResult.RETAKE, SignReason.MISSING_INFO, "을지로입구", null, null, "202", List.of("시청")));
        server.verify();
    }

    @Test
    void explainRecommendationSendsAllCriteriaInOneCallAndParsesPerCriterionTexts() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.text.format.type").value("json_schema"))
                .andExpect(jsonPath("$.text.format.name").value("explanation"))
                .andExpect(jsonPath("$.text.format.strict").value(true))
                .andExpect(jsonPath("$.text.format.schema.required[0]").value("explanations"))
                .andExpect(jsonPath("$.input[0].content").value(containsString("기준: TIME, 역: 강남")))
                .andExpect(jsonPath("$.input[0].content").value(containsString("기준: FARE, 역: 사당")))
                .andExpect(jsonPath("$.input[0].content").value(containsString("\"maxMinutes\":20")))
                .andRespond(withSuccess(response("{\\\"explanations\\\":["
                        + "{\\\"criterion\\\":\\\"TIME\\\",\\\"text\\\":\\\"  강남역이 가장 공평해요. \\\"},"
                        + "{\\\"criterion\\\":\\\"FARE\\\",\\\"text\\\":\\\"" + "x".repeat(600) + "\\\"},"
                        + "{\\\"criterion\\\":\\\"DISTANCE\\\",\\\"text\\\":\\\"묻지 않은 기준\\\"},"
                        + "{\\\"criterion\\\":\\\"TIME\\\",\\\"text\\\":\\\"두 번째 TIME\\\"}]}"),
                        MediaType.APPLICATION_JSON));
        var result = client.explainRecommendation(new ExplanationRequest(List.of(
                new ExplanationRequest.Item("TIME", "강남", Map.of("maxMinutes", 20)),
                new ExplanationRequest.Item("FARE", "사당", Map.of("totalFareWon", 3100)))));
        // trimmed, cut at the length limit, and only the asked criteria (the first sentence of a repeated one)
        assertThat(result.texts()).containsOnlyKeys("TIME", "FARE");
        assertThat(result.texts().get("TIME")).isEqualTo("강남역이 가장 공평해요.");
        assertThat(result.texts().get("FARE")).hasSize(OpenAiClient.EXPLANATION_MAX_LENGTH);
        server.verify();
    }

    @Test
    void explainRecommendationWithAnUnparsableAnswerMapsToAiUnavailable() {
        server.expect(requestTo(URL)).andRespond(withSuccess(response("그냥 문장입니다"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.explainRecommendation(new ExplanationRequest(List.of(
                new ExplanationRequest.Item("TIME", "강남", Map.of())))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.AI_UNAVAILABLE));
        server.verify();
    }

    @Test
    void chatReturnsTrimmedAndLimitedText() {
        server.expect(requestTo(URL)).andExpect(jsonPath("$.text").doesNotExist())
                .andRespond(withSuccess(response("x".repeat(600)), MediaType.APPLICATION_JSON));
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

    // ---- chat tool calling (#129) ----

    /** A tool-calling toolbox that records its calls and answers every call with {@code {"ok":true}}. */
    private static class RecordingTools implements ChatToolbox {
        final List<String> calls = new java.util.ArrayList<>();

        @Override
        public List<ChatToolSpec> specs() {
            return List.of(new ChatToolSpec("get_room_info", "방 정보", Map.of("type", "object", "properties", Map.of(),
                    "required", List.of(), "additionalProperties", false)));
        }

        @Override
        public String run(String toolName, String argumentsJson) {
            calls.add(toolName + argumentsJson);
            return "{\"ok\":true}";
        }
    }

    private static String functionCall(String responseId, String callId) {
        return "{\"id\":\"" + responseId + "\",\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"id\":\"rs_" + callId
                + "\",\"summary\":[],\"encrypted_content\":\"enc_" + callId + "\"},{\"type\":\"function_call\",\"id\":\"fc_" + callId
                + "\",\"call_id\":\"" + callId + "\",\"name\":\"get_room_info\",\"arguments\":\"{}\",\"status\":\"completed\"}]}";
    }

    private static ChatReplyRequest chatWith(ChatToolbox tools) {
        return new ChatReplyRequest("약속 언제야?", List.of(), "방 정보", tools);
    }

    @Test
    void chatToolCallRunsTheToolSendsItsResultBackAndReturnsTheFinalAnswer() {
        var tools = new RecordingTools();
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.tools.length()").value(1))
                .andExpect(jsonPath("$.tools[0].type").value("function"))
                .andExpect(jsonPath("$.tools[0].name").value("get_room_info"))
                .andExpect(jsonPath("$.tools[0].strict").value(true))
                .andExpect(jsonPath("$.store").value(false))
                .andExpect(jsonPath("$.include[0]").value("reasoning.encrypted_content"))
                .andExpect(jsonPath("$.previous_response_id").doesNotExist())
                .andExpect(jsonPath("$.input.length()").value(1))
                .andRespond(withSuccess(functionCall("resp_1", "call_1"), MediaType.APPLICATION_JSON));
        // round 2 resends everything so far: the question, the model's output items, then the tool result
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.store").value(false))
                .andExpect(jsonPath("$.include[0]").value("reasoning.encrypted_content"))
                .andExpect(jsonPath("$.previous_response_id").doesNotExist())
                .andExpect(jsonPath("$.input.length()").value(4))
                .andExpect(jsonPath("$.input[0].role").value("user"))
                .andExpect(jsonPath("$.input[1].type").value("reasoning"))
                .andExpect(jsonPath("$.input[1].encrypted_content").value("enc_call_1"))
                .andExpect(jsonPath("$.input[2].type").value("function_call"))
                .andExpect(jsonPath("$.input[2].call_id").value("call_1"))
                .andExpect(jsonPath("$.input[3].type").value("function_call_output"))
                .andExpect(jsonPath("$.input[3].call_id").value("call_1"))
                .andExpect(jsonPath("$.input[3].output").value("{\"ok\":true}"))
                .andExpect(jsonPath("$.tools.length()").value(1))
                .andRespond(withSuccess(response("오후 7시예요."), MediaType.APPLICATION_JSON));

        assertThat(client.replyToChat(chatWith(tools)).text()).isEqualTo("오후 7시예요.");
        assertThat(tools.calls).containsExactly("get_room_info{}");
        server.verify();
    }

    @Test
    void chatCallsCarryTheChatReasoningEffortWhileSignReadsKeepTheirOwn() {
        var builder = RestClient.builder().baseUrl("https://api.openai.test");
        var configuredServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        var configured = new OpenAiClient(restClient, restClient, restClient, "test-dummy", "test-model", "low", " medium ");
        configuredServer.expect(requestTo(URL)).andExpect(jsonPath("$.reasoning.effort").value("medium"))
                .andRespond(withSuccess(functionCall("resp_1", "call_1"), MediaType.APPLICATION_JSON));
        configuredServer.expect(requestTo(URL)).andExpect(jsonPath("$.reasoning.effort").value("medium"))
                .andRespond(withSuccess(response("답."), MediaType.APPLICATION_JSON));
        configuredServer.expect(requestTo(URL)).andExpect(jsonPath("$.reasoning.effort").value("medium"))
                .andRespond(withSuccess(response("툴 없는 답."), MediaType.APPLICATION_JSON));
        configuredServer.expect(requestTo(URL)).andExpect(jsonPath("$.reasoning.effort").value("low"))
                .andRespond(withSuccess("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\","
                        + "\"text\":\"{\\\"result\\\":\\\"RETAKE\\\",\\\"reason\\\":\\\"UNREADABLE\\\",\\\"stationName\\\":null,"
                        + "\\\"lineName\\\":null,\\\"headsign\\\":null}\"}]}]}", MediaType.APPLICATION_JSON));
        assertThat(configured.replyToChat(chatWith(new RecordingTools())).text()).isEqualTo("답.");
        assertThat(configured.replyToChat(new ChatReplyRequest("안녕", List.of(), "방 정보")).text()).isEqualTo("툴 없는 답.");
        configured.readSign(new SignImage(new byte[] {1}, "image/jpeg"), new SignContext("강남", "2호선", null, "사당 방면"));
        configuredServer.verify();
    }

    @Test
    void chatReasoningEffortUnsetSendsNothingAndTheSignSettingDoesNotLeakIntoChat() {
        var builder = RestClient.builder().baseUrl("https://api.openai.test");
        var chatServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        var signOnly = new OpenAiClient(restClient, restClient, restClient, "test-dummy", "test-model", "low", " ");
        chatServer.expect(requestTo(URL)).andExpect(jsonPath("$.reasoning").doesNotExist())
                .andRespond(withSuccess(response("답."), MediaType.APPLICATION_JSON));
        signOnly.replyToChat(chatWith(new RecordingTools()));
        chatServer.verify();
    }

    private static String functionCalls(int count) {
        var items = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            if (i > 1) items.append(',');
            items.append("{\"type\":\"function_call\",\"call_id\":\"call_").append(i)
                    .append("\",\"name\":\"get_room_info\",\"arguments\":\"{}\",\"status\":\"completed\"}");
        }
        return "{\"status\":\"completed\",\"output\":[" + items + "]}";
    }

    private static final String REASONING_UNSUPPORTED = "{\"error\":{\"message\":\"Unsupported parameter: 'reasoning.effort' is not supported with this model.\"}}";

    private static OpenAiClient chatMedium(RestClient restClient) {
        return new OpenAiClient(restClient, restClient, restClient, "test-dummy", "test-model", "low", "medium");
    }

    @Test
    void chatWithoutToolsRetriesOnceWithoutReasoningWhenTheModelRejectsEffort() {
        var builder = RestClient.builder().baseUrl("https://api.openai.test");
        var chatServer = MockRestServiceServer.bindTo(builder).build();
        var medium = chatMedium(builder.build());
        chatServer.expect(requestTo(URL)).andExpect(jsonPath("$.reasoning.effort").value("medium"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(REASONING_UNSUPPORTED));
        chatServer.expect(requestTo(URL)).andExpect(jsonPath("$.reasoning").doesNotExist())
                .andRespond(withSuccess(response("그대로 답해요."), MediaType.APPLICATION_JSON));
        assertThat(medium.replyToChat(new ChatReplyRequest("안녕", List.of(), "방 정보")).text()).isEqualTo("그대로 답해요.");
        chatServer.verify();
    }

    @Test
    void chatToolCallingDropsReasoningForTheWholeQuestionWhenTheModelRejectsEffort() {
        var builder = RestClient.builder().baseUrl("https://api.openai.test");
        var chatServer = MockRestServiceServer.bindTo(builder).build();
        var medium = chatMedium(builder.build());
        chatServer.expect(requestTo(URL)).andExpect(jsonPath("$.reasoning.effort").value("medium"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(REASONING_UNSUPPORTED));
        chatServer.expect(requestTo(URL)).andExpect(jsonPath("$.reasoning").doesNotExist())
                .andRespond(withSuccess(functionCall("resp_1", "call_1"), MediaType.APPLICATION_JSON));
        chatServer.expect(requestTo(URL)).andExpect(jsonPath("$.reasoning").doesNotExist())
                .andExpect(jsonPath("$.input.length()").value(4))
                .andRespond(withSuccess(response("답."), MediaType.APPLICATION_JSON));
        assertThat(medium.replyToChat(chatWith(new RecordingTools())).text()).isEqualTo("답.");
        chatServer.verify();
    }

    @Test
    void noReasoningRetryIsSentWhenTheTotalLimitPassedWhileTheRejectedRequestWasRunning() {
        var builder = RestClient.builder().baseUrl("https://api.openai.test");
        var chatServer = MockRestServiceServer.bindTo(builder).build();
        var medium = chatMedium(builder.build());
        medium.chatTotalLimit(java.time.Duration.ofMillis(50));
        // the rejected request itself takes longer than the whole limit; only this one request is expected
        chatServer.expect(requestTo(URL)).andRespond(request -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(REASONING_UNSUPPORTED).createResponse(request);
        });
        assertThatThrownBy(() -> medium.replyToChat(chatWith(new RecordingTools()))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_UNAVAILABLE));
        chatServer.verify();
    }

    @Test
    void aBadRequestThatIsNotAboutReasoningIsNotRetried() {
        var builder = RestClient.builder().baseUrl("https://api.openai.test");
        var chatServer = MockRestServiceServer.bindTo(builder).build();
        var medium = chatMedium(builder.build());
        chatServer.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"message\":\"bad model\"}}"));
        assertThatThrownBy(() -> medium.replyToChat(chatWith(new RecordingTools()))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_UNAVAILABLE));
        chatServer.verify();
    }

    @Test
    void onlyTheFirstFourToolCallsOfARoundRunTheRestGetNoDataResults() {
        var tools = new RecordingTools();
        server.expect(requestTo(URL)).andRespond(withSuccess(functionCalls(6), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.input.length()").value(13))
                .andExpect(jsonPath("$.input[7].output").value("{\"ok\":true}"))
                .andExpect(jsonPath("$.input[10].output").value("{\"ok\":true}"))
                .andExpect(jsonPath("$.input[11].call_id").value("call_5"))
                .andExpect(jsonPath("$.input[11].output").value(org.hamcrest.Matchers.containsString("NO_DATA")))
                .andExpect(jsonPath("$.input[12].call_id").value("call_6"))
                .andExpect(jsonPath("$.input[12].output").value(org.hamcrest.Matchers.containsString("NO_DATA")))
                .andRespond(withSuccess(response("끝."), MediaType.APPLICATION_JSON));
        assertThat(client.replyToChat(chatWith(tools)).text()).isEqualTo("끝.");
        assertThat(tools.calls).hasSize(4);
        server.verify();
    }

    @Test
    void theTotalLimitIsCheckedBeforeEachToolRunAndStopsTheAnswer() {
        var ran = new java.util.ArrayList<String>();
        ChatToolbox slow = new RecordingTools() {
            @Override
            public String run(String toolName, String argumentsJson) {
                ran.add(toolName);
                try {
                    Thread.sleep(120);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "{\"ok\":true}";
            }
        };
        client.chatTotalLimit(java.time.Duration.ofMillis(60));
        server.expect(requestTo(URL)).andRespond(withSuccess(functionCalls(3), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.replyToChat(chatWith(slow))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_UNAVAILABLE));
        assertThat(ran).hasSize(1);
        server.verify();
    }

    @Test
    void chatWithoutToolCallsAnswersInOneRequest() {
        var tools = new RecordingTools();
        server.expect(requestTo(URL)).andRespond(withSuccess(response("그냥 답이에요."), MediaType.APPLICATION_JSON));
        assertThat(client.replyToChat(chatWith(tools)).text()).isEqualTo("그냥 답이에요.");
        assertThat(tools.calls).isEmpty();
        server.verify();
    }

    @Test
    void chatThreeToolRoundsAreAllowedAndTheFourthRequestForToolsIsAiUnavailable() {
        var tools = new RecordingTools();
        // three rounds are answered, the answer after the third result is still a tool request: over the limit
        for (int round = 1; round <= 4; round++) {
            server.expect(requestTo(URL)).andRespond(withSuccess(functionCall("resp_" + round, "call_" + round), MediaType.APPLICATION_JSON));
        }
        assertThatThrownBy(() -> client.replyToChat(chatWith(tools))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_UNAVAILABLE));
        assertThat(tools.calls).hasSize(3);
        server.verify();
    }

    @Test
    void chatThreeToolRoundsThenAnAnswerIsFine() {
        var tools = new RecordingTools();
        for (int round = 1; round <= 3; round++) {
            server.expect(requestTo(URL)).andRespond(withSuccess(functionCall("resp_" + round, "call_" + round), MediaType.APPLICATION_JSON));
        }
        // the fourth request carries the question plus three rounds of reasoning, function_call and function_call_output
        server.expect(requestTo(URL)).andExpect(jsonPath("$.input.length()").value(10))
                .andExpect(jsonPath("$.input[1].encrypted_content").value("enc_call_1"))
                .andExpect(jsonPath("$.input[4].encrypted_content").value("enc_call_2"))
                .andExpect(jsonPath("$.input[9].call_id").value("call_3"))
                .andRespond(withSuccess(response("끝."), MediaType.APPLICATION_JSON));
        assertThat(client.replyToChat(chatWith(tools)).text()).isEqualTo("끝.");
        assertThat(tools.calls).hasSize(3);
        server.verify();
    }

    @Test
    void chatAiFailureAfterAToolRoundIsAiUnavailableAndQuotaStaysQuota() {
        var tools = new RecordingTools();
        server.expect(requestTo(URL)).andRespond(withSuccess(functionCall("resp_1", "call_1"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThatThrownBy(() -> client.replyToChat(chatWith(tools))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_UNAVAILABLE));
        server.verify();

        server.reset();
        server.expect(requestTo(URL)).andRespond(withSuccess(functionCall("resp_1", "call_1"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> client.replyToChat(chatWith(new RecordingTools()))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_QUOTA_EXCEEDED));
    }

    @Test
    void chatToolResponsesThatAreIncompleteAreAiUnavailable() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"status\":\"incomplete\",\"output\":[]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.replyToChat(chatWith(new RecordingTools()))).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_UNAVAILABLE));
    }

    /** Responses API body with one output_text part; {@code escapedText} is already JSON-string escaped. */
    private static String response(String escapedText) {
        return "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":"
                + "[{\"type\":\"output_text\",\"text\":\"" + escapedText + "\"}]}]}";
    }
}
