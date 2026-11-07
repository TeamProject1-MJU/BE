package com.mju.linkro.route.kakao;

import com.mju.linkro.route.kakao.KakaoRouteClient.Failed;
import com.mju.linkro.route.kakao.KakaoRouteClient.Found;
import com.mju.linkro.route.kakao.KakaoRouteClient.Reason;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** Kakao calls are stubbed with MockRestServiceServer; nothing goes to the real API. */
@ExtendWith(OutputCaptureExtension.class)
class KakaoRouteClientTest {
    static final String KEY = "test-key-0123456789";
    /** A marker that must never reach the log: the body is not logged. */
    static final String BODY = "{\"status\":\"OK\",\"routes\":[{\"properties\":{\"type\":\"SUBWAY\",\"totalTime\":600},"
            + "\"steps\":[{\"properties\":{\"type\":\"SUBWAY\",\"guidance\":\"BODY-MARKER\",\"stops\":[{\"name\":\"강남\"}]}}]}]}";

    private MockRestServiceServer server;

    private KakaoRouteClient client(String key, Duration call, Duration total, Duration retry) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dapi.kakao.com");
        server = MockRestServiceServer.bindTo(builder).build();
        return new KakaoRouteClient(builder.build(), key, call, total, retry, System::nanoTime);
    }

    private KakaoRouteClient client(String key) {
        return client(key, Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofMillis(10));
    }

    @Test
    void okCallsKakaoOnceWithTheKeyAndCoordinates(CapturedOutput output) {
        KakaoRouteClient client = client(KEY);
        server.expect(ExpectedCount.once(), requestTo(startsWith("https://dapi.kakao.com/v2/routing/publictraffic?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "KakaoAK " + KEY))
                .andExpect(queryParam("start_x", "127.027539")).andExpect(queryParam("start_y", "37.497958"))
                .andExpect(queryParam("end_x", "126.981631")).andExpect(queryParam("end_y", "37.476536"))
                .andExpect(queryParam("input_coord", "WGS84")).andExpect(queryParam("output_coord", "WGS84"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        var result = client.search(127.027539, 37.497958, 126.981631, 37.476536);

        server.verify();
        assertThat(result).isInstanceOfSatisfying(Found.class, found -> {
            assertThat(found.body().routes()).hasSize(1);
            assertThat(found.body().routes().get(0).properties().totalTime()).isEqualTo(600);
        });
        assertThat(output.getAll()).contains("Kakao route client is enabled").contains("attempt 1: 200")
                .doesNotContain(KEY).doesNotContain("BODY-MARKER").doesNotContain("127.027539");
    }

    @Test
    void withoutAKeyNothingIsCalled(CapturedOutput output) {
        KakaoRouteClient client = client("  ");
        assertThat(client.enabled()).isFalse();

        assertThat(client.search(127, 37.5, 127.1, 37.6)).isEqualTo(new Failed(Reason.NO_KEY));
        server.verify(); // no expectation: any request would fail the test
        assertThat(output.getAll()).contains("KAKAO_REST_API_KEY is empty: POST /routes/search uses INTERNAL routes only.");
    }

    @Test
    void clientErrorsAreNotRetried(CapturedOutput output) {
        for (HttpStatus status : new HttpStatus[] {HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED, HttpStatus.TOO_MANY_REQUESTS}) {
            KakaoRouteClient client = client(KEY);
            server.expect(ExpectedCount.once(), requestTo(startsWith("https://dapi.kakao.com/v2/routing/publictraffic")))
                    .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body("{\"msg\":\"BODY-MARKER\"}"));
            assertThat(client.search(127, 37.5, 127.1, 37.6)).as(status.toString()).isEqualTo(new Failed(Reason.CLIENT_ERROR));
            server.verify();
        }
        assertThat(output.getAll()).contains("attempt 1: 429").doesNotContain("attempt 2").doesNotContain("BODY-MARKER").doesNotContain(KEY);
    }

    @Test
    void serverErrorIsRetriedOnce() {
        KakaoRouteClient client = client(KEY);
        server.expect(ExpectedCount.once(), requestTo(startsWith("https://dapi.kakao.com/v2/routing/publictraffic")))
                .andRespond(withServerError());
        server.expect(ExpectedCount.once(), requestTo(startsWith("https://dapi.kakao.com/v2/routing/publictraffic")))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));
        assertThat(client.search(127, 37.5, 127.1, 37.6)).isInstanceOf(Found.class);
        server.verify();

        KakaoRouteClient failing = client(KEY);
        server.expect(ExpectedCount.twice(), requestTo(startsWith("https://dapi.kakao.com/v2/routing/publictraffic")))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThat(failing.search(127, 37.5, 127.1, 37.6)).isEqualTo(new Failed(Reason.SERVER_ERROR));
        server.verify();
    }

    @Test
    void slowAnswersTimeOutRetryOnceAndStayWithinTheTotal() {
        // Scaled down: 200 ms per call, 450 ms in total.
        KakaoRouteClient client = client(KEY, Duration.ofMillis(200), Duration.ofMillis(450), Duration.ofMillis(20));
        server.expect(ExpectedCount.twice(), requestTo(startsWith("https://dapi.kakao.com/v2/routing/publictraffic")))
                .andRespond(request -> {
                    try {
                        Thread.sleep(1_000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    return withSuccess(BODY, MediaType.APPLICATION_JSON).createResponse(request);
                });
        long started = System.nanoTime();

        var result = client.search(127, 37.5, 127.1, 37.6);

        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(result).isEqualTo(new Failed(Reason.TIMEOUT));
        assertThat(elapsedMs).isBetween(400L, 700L);
    }

    @Test
    void retryOnlyRunsInTheTimeLeft() {
        // The first call uses the whole budget, so there is no second call.
        KakaoRouteClient client = client(KEY, Duration.ofMillis(300), Duration.ofMillis(300), Duration.ofMillis(20));
        server.expect(ExpectedCount.once(), requestTo(startsWith("https://dapi.kakao.com/v2/routing/publictraffic")))
                .andRespond(request -> {
                    try {
                        Thread.sleep(1_000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    return withSuccess(BODY, MediaType.APPLICATION_JSON).createResponse(request);
                });
        assertThat(client.search(127, 37.5, 127.1, 37.6)).isEqualTo(new Failed(Reason.TIMEOUT));
    }

    @Test
    void unreadableBodyIsABadResponse() {
        KakaoRouteClient client = client(KEY);
        server.expect(ExpectedCount.once(), requestTo(startsWith("https://dapi.kakao.com/v2/routing/publictraffic")))
                .andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));
        assertThat(client.search(127, 37.5, 127.1, 37.6)).isEqualTo(new Failed(Reason.BAD_RESPONSE));
        server.verify();
    }
}
