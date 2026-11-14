package com.mju.linkro.ai.openai;

import com.mju.linkro.ai.api.*;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAI Responses API ({@code POST /v1/responses}). No retries. 429 maps to AI_QUOTA_EXCEEDED, every other
 * failure to AI_UNAVAILABLE. Keys, prompts and images are never logged or kept in exception causes.
 */
@Slf4j
public class OpenAiClient implements AiClient {
    static final String BASE_URL = "https://api.openai.com";
    static final int EXPLANATION_MAX_LENGTH = 300;
    static final int CHAT_MAX_LENGTH = 500;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Map<String, Object> CONDITION_SCHEMA = schema("condition");
    private static final Map<String, Object> EXPLANATION_SCHEMA = schema("explanation");
    private static final Map<String, Object> SIGN_SCHEMA = schema("sign");
    private static final String CONDITION_PROMPT = resource("ai/prompt/condition.txt");
    private static final String EXPLANATION_PROMPT = resource("ai/prompt/explanation.txt");
    private static final String SIGN_PROMPT = resource("ai/prompt/sign.txt");
    private static final String CHAT_PROMPT = resource("ai/prompt/chat.txt");

    private final RestClient textClient;
    private final RestClient visionClient;
    /** Recommendation explanations only, with their own shorter read timeout (#99). */
    private final RestClient explainClient;
    private final String apiKey;
    private final String model;
    /** Sent as reasoning.effort on sign reads only; null or blank sends nothing (the model's own default). */
    private final String visionReasoningEffort;

    OpenAiClient(RestClient textClient, RestClient visionClient, String apiKey, String model) {
        this(textClient, visionClient, textClient, apiKey, model, null);
    }

    OpenAiClient(RestClient textClient, RestClient visionClient, RestClient explainClient, String apiKey, String model,
                 String visionReasoningEffort) {
        this.textClient = textClient;
        this.visionClient = visionClient;
        this.explainClient = explainClient;
        this.apiKey = apiKey;
        this.model = model;
        this.visionReasoningEffort = visionReasoningEffort == null || visionReasoningEffort.isBlank() ? null : visionReasoningEffort.strip();
    }

    @Override
    public ConditionInterpretation interpretCondition(ConditionRequest req) {
        String input = "조건: " + req.conditionText() + "\n멤버 닉네임: " + req.memberNicknames()
                + "\n역 이름 후보: " + req.stationNameHints();
        String json = call(textClient, CONDITION_PROMPT, textInput(input), jsonFormat("condition", CONDITION_SCHEMA));
        return parse(json, ConditionInterpretation.class);
    }

    @Override
    public ExplanationResult explainRecommendation(ExplanationRequest req) {
        var input = new StringBuilder();
        for (ExplanationRequest.Item item : req.items()) {
            input.append("기준: ").append(item.criterion()).append(", 역: ").append(item.stationName())
                    .append(", facts: ").append(write(item.facts())).append('\n');
        }
        String json = call(explainClient, EXPLANATION_PROMPT, textInput(input.toString()), jsonFormat("explanation", EXPLANATION_SCHEMA));
        Reply reply = parse(json, Reply.class);
        Set<String> asked = new HashSet<>();
        req.items().forEach(item -> asked.add(item.criterion()));
        Map<String, String> texts = new LinkedHashMap<>();
        // Only criteria that were asked for; an unknown, repeated or blank entry is ignored, and the caller uses
        // its template for any criterion left out.
        for (ReplyEntry entry : reply.explanations() == null ? List.<ReplyEntry>of() : reply.explanations()) {
            if (entry != null && entry.criterion() != null && entry.text() != null && asked.contains(entry.criterion())
                    && !entry.text().isBlank()) {
                texts.putIfAbsent(entry.criterion(), text(entry.text(), EXPLANATION_MAX_LENGTH).text());
            }
        }
        return new ExplanationResult(texts);
    }

    /** Structured output of {@link #explainRecommendation}: schema ai/schema/explanation.json. */
    record Reply(List<ReplyEntry> explanations) {}

    record ReplyEntry(String criterion, String text) {}

    @Override
    public SignReading readSign(SignImage image, SignContext ctx) {
        String dataUrl = "data:" + image.contentType() + ";base64," + Base64.getEncoder().encodeToString(image.bytes());
        String expected = "기대 경로: 역=" + ctx.stationName() + ", 노선=" + ctx.lineName()
                + ", 방향=" + (ctx.direction() == null ? "알 수 없음(행선지로 비교)" : ctx.direction().name())
                + ", 행선지=" + (ctx.headsign() == null ? "미상(종착역 추정 금지)" : ctx.headsign())
                + ", 비교방식=" + (ctx.directionOnly() ? "DIRECTION_ONLY" : "HEADSIGN");
        var content = List.of(
                Map.of("type", "input_text", "text", expected),
                Map.of("type", "input_image", "image_url", dataUrl, "detail", "low"));
        var input = List.of(Map.of("role", "user", "content", content));
        String json = call(visionClient, SIGN_PROMPT, input, jsonFormat("sign", SIGN_SCHEMA), visionReasoningEffort);
        return parse(json, SignReading.class);
    }

    @Override
    public GeneratedText replyToChat(ChatReplyRequest req) {
        var input = new StringBuilder();
        if (req.roomContext() != null) input.append("방 정보: ").append(req.roomContext()).append('\n');
        for (ChatTurn turn : req.recentMessages()) {
            input.append(turn.nickname()).append(": ").append(turn.content()).append('\n');
        }
        input.append("질문: ").append(req.question());
        return text(call(textClient, CHAT_PROMPT, textInput(input.toString()), null), CHAT_MAX_LENGTH);
    }

    private String call(RestClient client, String instructions, Object input, Map<String, Object> format) {
        return call(client, instructions, input, format, null);
    }

    private String call(RestClient client, String instructions, Object input, Map<String, Object> format, String reasoningEffort) {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("instructions", instructions);
        body.put("input", input);
        if (format != null) body.put("text", Map.of("format", format));
        if (reasoningEffort != null) body.put("reasoning", Map.of("effort", reasoningEffort));
        try {
            String response = client.post().uri("/v1/responses")
                    .headers(headers -> headers.setBearerAuth(apiKey))
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (request, res) -> { throw mapStatus(res.getStatusCode()); })
                    .body(String.class);
            return outputText(response);
        } catch (RestClientException | JacksonException | IllegalArgumentException exception) {
            throw unavailable(exception);
        }
    }

    private static BusinessException mapStatus(HttpStatusCode status) {
        int code = status.value();
        if (code == 429) {
            log.warn("OpenAI request rate limited or quota exceeded (429)");
            return new BusinessException(ErrorCode.AI_QUOTA_EXCEEDED);
        }
        if (code == 400 || code == 401 || code == 403) {
            log.error("OpenAI rejected the request ({}): check OPENAI_API_KEY, OPENAI_MODEL and the request format", code);
        } else {
            log.warn("OpenAI request failed ({})", code);
        }
        return new BusinessException(ErrorCode.AI_UNAVAILABLE);
    }

    /** Joins output_text parts; refusal, incomplete or empty responses are failures. */
    private static String outputText(String response) {
        if (response == null || response.isBlank()) throw new IllegalArgumentException("empty response");
        JsonNode root = MAPPER.readTree(response);
        if (!"completed".equals(root.path("status").asString(""))) {
            throw new IllegalArgumentException("response not completed");
        }
        var text = new StringBuilder();
        for (JsonNode item : root.path("output")) {
            for (JsonNode part : item.path("content")) {
                String type = part.path("type").asString("");
                if ("refusal".equals(type)) throw new IllegalArgumentException("refusal");
                if ("output_text".equals(type)) text.append(part.path("text").asString(""));
            }
        }
        if (text.isEmpty()) throw new IllegalArgumentException("no output text");
        return text.toString();
    }

    private static <T> T parse(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JacksonException | IllegalArgumentException | NullPointerException exception) {
            throw unavailable(exception);
        }
    }

    private static GeneratedText text(String text, int maxLength) {
        String trimmed = text.strip();
        return new GeneratedText(trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed);
    }

    private static BusinessException unavailable(Exception exception) {
        // Upstream messages may echo prompts or keys; keep only the type and stack location (Kakao pattern).
        var safeCause = new RestClientException("OpenAI request failed: " + exception.getClass().getSimpleName());
        safeCause.setStackTrace(exception.getStackTrace());
        log.warn("OpenAI call failed: {}", exception.getClass().getSimpleName());
        return new BusinessException(ErrorCode.AI_UNAVAILABLE, safeCause);
    }

    private static List<Map<String, Object>> textInput(String text) {
        return List.of(Map.of("role", "user", "content", text));
    }

    private static Map<String, Object> jsonFormat(String name, Map<String, Object> schema) {
        return Map.of("type", "json_schema", "name", name, "strict", true, "schema", schema);
    }

    private static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(String name) {
        return MAPPER.readValue(resource("ai/schema/" + name + ".json"), Map.class);
    }

    private static String resource(String path) {
        try (InputStream in = OpenAiClient.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing classpath resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
