package com.mju.linkro.ai.openai;

import com.mju.linkro.ai.api.ConditionRequest;
import com.mju.linkro.ai.api.ExplanationRequest;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Read timeouts of the real HTTP clients built by {@link OpenAiConfig} (#99), against a local server that answers
 * slowly. Never calls OpenAI. The explanation timeout applies to explainRecommendation only.
 */
class OpenAiReadTimeoutTest {
    static final String CONDITION = "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\","
            + "\"text\":\"{\\\"maxMinutes\\\":40,\\\"maxTransfers\\\":null,\\\"maxFareWon\\\":null,\\\"excludeStationNames\\\":[],"
            + "\\\"memberLimits\\\":[]}\"}]}]}";

    HttpServer server;
    final AtomicLong delayMillis = new AtomicLong();
    OpenAiClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                Thread.sleep(delayMillis.get());
                byte[] body = CONDITION.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException | IOException ignored) {
                // The client gave up; nothing to answer.
            } finally {
                exchange.close();
            }
        });
        server.start();
        OpenAiProperties props = new OpenAiProperties();
        props.setApiKey("test-dummy");
        props.setModel("test-model");
        props.setReadTimeout(Duration.ofSeconds(3));
        props.setVisionReadTimeout(Duration.ofSeconds(3));
        props.setExplainReadTimeout(Duration.ofMillis(300));
        client = OpenAiConfig.client(props, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void theExplanationGivesUpAfterItsOwnReadTimeout() {
        delayMillis.set(1500);
        long started = System.nanoTime();
        assertThatThrownBy(() -> client.explainRecommendation(new ExplanationRequest(List.of(
                new ExplanationRequest.Item("TIME", "교대", Map.of("maxMinutes", 20))))))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AI_UNAVAILABLE));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1400));
    }

    @Test
    void conditionParsingKeepsTheLongerReadTimeout() {
        // Slower than the explanation timeout (300 ms), well within the text read timeout (3 s).
        delayMillis.set(700);
        assertThat(client.interpretCondition(new ConditionRequest("40분 이내", List.of(), List.of())).maxMinutes()).isEqualTo(40);
    }

    @Test
    void theDefaultsKeepTheOtherTimeouts() {
        OpenAiProperties defaults = new OpenAiProperties();
        assertThat(defaults.getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(defaults.getReadTimeout()).isEqualTo(Duration.ofSeconds(8));
        assertThat(defaults.getVisionReadTimeout()).isEqualTo(Duration.ofSeconds(4));
        assertThat(defaults.getExplainReadTimeout()).isEqualTo(Duration.ofSeconds(6));
    }
}
