package com.mju.linkro.route.kakao;

import com.mju.linkro.route.kakao.KakaoRouteClient.Failed;
import com.mju.linkro.route.kakao.KakaoRouteClient.Found;
import com.mju.linkro.route.kakao.KakaoRouteClient.Reason;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-06T00:00:00Z"));

    /** A clock the test moves forward to end cooldowns. */
    static final class MutableClock extends Clock {
        private volatile Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private KakaoRouteClient client(String key, Duration call, Duration total, Duration retry) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://dapi.kakao.com");
        server = MockRestServiceServer.bindTo(builder).build();
        return new KakaoRouteClient(builder.build(), key, call, total, retry, Duration.ofMinutes(5), Duration.ofSeconds(30), System::nanoTime, clock);
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
        Object[][] cases = {{HttpStatus.BAD_REQUEST, Reason.CLIENT_ERROR}, {HttpStatus.UNAUTHORIZED, Reason.AUTH_ERROR},
                {HttpStatus.FORBIDDEN, Reason.AUTH_ERROR}, {HttpStatus.TOO_MANY_REQUESTS, Reason.QUOTA}};
        for (Object[] c : cases) {
            KakaoRouteClient client = client(KEY);
            server.expect(ExpectedCount.once(), requestTo(startsWith("https://dapi.kakao.com/v2/routing/publictraffic")))
                    .andRespond(withStatus((HttpStatus) c[0]).contentType(MediaType.APPLICATION_JSON).body("{\"msg\":\"BODY-MARKER\"}"));
            assertThat(client.search(127, 37.5, 127.1, 37.6)).as(c[0].toString()).isEqualTo(new Failed((Reason) c[1]));
            server.verify();
        }
        assertThat(output.getAll()).contains("attempt 1: 429").doesNotContain("attempt 2").doesNotContain("BODY-MARKER").doesNotContain(KEY);
    }

    @Test
    void kakaoFailuresAreWarnings(CapturedOutput output) {
        KakaoRouteClient client = client(KEY);
        server.expect(ExpectedCount.twice(), requestTo(startsWith("https://dapi.kakao.com/v2/routing/publictraffic")))
                .andRespond(withServerError().contentType(MediaType.APPLICATION_JSON).body("{\"msg\":\"BODY-MARKER\"}"));
        assertThat(client.search(127.027539, 37.497958, 126.981631, 37.476536)).isEqualTo(new Failed(Reason.SERVER_ERROR));
        server.verify();
        List<String> lines = output.getAll().lines().filter(line -> line.contains("Kakao route") && line.contains("attempt")).toList();
        assertThat(lines).hasSize(2).allSatisfy(line -> assertThat(line).contains(" WARN ").contains(": 500 in "));
        assertThat(output.getAll()).doesNotContain(KEY).doesNotContain("BODY-MARKER").doesNotContain("127.027539").doesNotContain("37.497958");
    }

    @Test
    void fullPoolFallsBackAtOnceWithoutCallingKakao() throws Exception {
        KakaoRouteClient client = client(KEY, Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofMillis(10));
        CountDownLatch busy = new CountDownLatch(KakaoRouteClient.MAX_THREADS);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        server.expect(ExpectedCount.manyTimes(), requestTo(startsWith("https://dapi.kakao.com/v2/routing/publictraffic")))
                .andRespond(request -> {
                    calls.incrementAndGet();
                    busy.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    return withSuccess(BODY, MediaType.APPLICATION_JSON).createResponse(request);
                });
        ExecutorService callers = Executors.newFixedThreadPool(KakaoRouteClient.MAX_THREADS);
        try {
            List<Future<KakaoRouteClient.Result>> running = new ArrayList<>();
            for (int i = 0; i < KakaoRouteClient.MAX_THREADS; i++) {
                running.add(callers.submit(() -> client.search(127, 37.5, 127.1, 37.6)));
            }
            assertThat(busy.await(5, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            var result = client.search(127, 37.5, 127.1, 37.6);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;

            assertThat(result).isEqualTo(new Failed(Reason.BUSY));
            assertThat(elapsedMs).isLessThan(500);
            assertThat(calls).hasValue(KakaoRouteClient.MAX_THREADS);
            release.countDown();
            for (var call : running) assertThat(call.get(5, TimeUnit.SECONDS)).isInstanceOf(Found.class);
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void keyErrorsStopCallsForFiveMinutesAndLogOneError(CapturedOutput output) {
        KakaoRouteClient client = client(KEY);
        server.expect(ExpectedCount.once(), requestTo(startsWith("https://dapi.kakao.com/v2/routing/publictraffic")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"msg\":\"BODY-MARKER\"}"));
        assertThat(client.search(127, 37.5, 127.1, 37.6)).isEqualTo(new Failed(Reason.AUTH_ERROR));
        server.verify();

        // During the cooldown nothing is called (the server has no further expectation).
        for (int i = 0; i < 3; i++) {
            assertThat(client.search(127, 37.5, 127.1, 37.6)).isEqualTo(new Failed(Reason.COOLDOWN));
            clock.advance(Duration.ofMinutes(1));
        }
        clock.advance(Duration.ofSeconds(119));
        assertThat(client.search(127, 37.5, 127.1, 37.6)).isEqualTo(new Failed(Reason.COOLDOWN)); // 4:59
        server.verify();

        // After 5 minutes the next search tries again.
        clock.advance(Duration.ofSeconds(1));
        server.reset();
        server.expect(ExpectedCount.once(), requestTo(startsWith("https://dapi.kakao.com/v2/routing/publictraffic")))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));
        assertThat(client.search(127, 37.5, 127.1, 37.6)).isInstanceOf(Found.class);
        server.verify();

        List<String> errors = output.getAll().lines().filter(line -> line.contains(" ERROR ")).toList();
        assertThat(errors).singleElement().satisfies(line -> assertThat(line).contains("attempt 1: 401").contains("INTERNAL only for 300 s"));
        assertThat(output.getAll()).doesNotContain(KEY).doesNotContain("BODY-MARKER");
    }

    @Test
    void quotaStopsCallsForThirtySeconds(CapturedOutput output) {
        KakaoRouteClient client = client(KEY);
        server.expect(ExpectedCount.once(), requestTo(startsWith("https://dapi.kakao.com/v2/routing/publictraffic")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(client.search(127, 37.5, 127.1, 37.6)).isEqualTo(new Failed(Reason.QUOTA));
        clock.advance(Duration.ofSeconds(29));
        assertThat(client.search(127, 37.5, 127.1, 37.6)).isEqualTo(new Failed(Reason.COOLDOWN));
        server.verify();

        clock.advance(Duration.ofSeconds(1));
        server.reset();
        server.expect(ExpectedCount.once(), requestTo(startsWith("https://dapi.kakao.com/v2/routing/publictraffic")))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));
        assertThat(client.search(127, 37.5, 127.1, 37.6)).isInstanceOf(Found.class);
        server.verify();
        assertThat(output.getAll().lines().filter(line -> line.contains("quota exceeded"))).singleElement()
                .satisfies(line -> assertThat(line).contains(" WARN ").contains("INTERNAL only for 30 s"));
        assertThat(output.getAll()).doesNotContain(" ERROR ");
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
