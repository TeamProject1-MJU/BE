package com.mju.linkro.route.kakao;

import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Kakao public-transport route search (GET /v2/routing/publictraffic, API 6.1).
 *
 * <ul>
 *   <li>The key is {@code linkro.route.kakao.rest-api-key}, else {@code KAKAO_REST_API_KEY}. Without it Kakao is never
 *       called and every search returns {@link Reason#NO_KEY}.</li>
 *   <li>One call waits at most {@code call-timeout} (3 s); the whole search, retry included, at most
 *       {@code total-timeout} (5 s). A timeout, I/O error or 5xx is retried once after {@code retry-delay}, only
 *       within the time left. 4xx is not retried.</li>
 *   <li>Calls run on a bounded pool ({@value #CORE_THREADS}~{@value #MAX_THREADS} daemon threads, no queue). When it
 *       is full the search returns {@link Reason#BUSY} at once without calling Kakao.</li>
 *   <li>401/403 (key problem) stops calls for {@code auth-cooldown} (5 min), 429 (quota) for {@code quota-cooldown}
 *       (30 s); meanwhile searches return {@link Reason#COOLDOWN}. The cooldown lives in memory only.</li>
 *   <li>Only a request id, the HTTP status or failure reason and the elapsed time are logged; never the key, the
 *       coordinates, the response body or exception messages.</li>
 * </ul>
 */
@Slf4j
@Component
public class KakaoRouteClient {
    static final String PATH = "/v2/routing/publictraffic";
    static final int CORE_THREADS = 4;
    static final int MAX_THREADS = 16;

    public enum Reason {
        /** No key configured: expected, INTERNAL only. */
        NO_KEY,
        /** Skipped during a cooldown after 401/403 or 429: expected, Kakao not called. */
        COOLDOWN,
        /** The call pool is full: Kakao not called. */
        BUSY,
        TIMEOUT, IO_ERROR, SERVER_ERROR,
        /** 401/403. */
        AUTH_ERROR,
        /** 429. */
        QUOTA,
        /** Any other 4xx. */
        CLIENT_ERROR,
        BAD_RESPONSE;

        /** Expected states that are not Kakao failures (logged at INFO by callers). */
        public boolean expected() {
            return this == NO_KEY || this == COOLDOWN;
        }
    }

    /** Either the parsed body or why there is none. */
    public sealed interface Result permits Found, Failed {}

    public record Found(KakaoRouteResponse body) implements Result {}

    public record Failed(Reason reason) implements Result {}

    private record Cooldown(Instant until, Reason reason) {}

    private final RestClient restClient;
    private final String apiKey;
    private final Duration callTimeout;
    private final Duration totalTimeout;
    private final Duration retryDelay;
    private final Duration authCooldown;
    private final Duration quotaCooldown;
    private final LongSupplier nanoTime;
    private final Clock clock;
    private final ThreadPoolExecutor executor;
    private final AtomicReference<Cooldown> cooldown = new AtomicReference<>();

    @Autowired
    public KakaoRouteClient(RestClient.Builder builder,
                            @Value("${linkro.route.kakao.rest-api-key:${KAKAO_REST_API_KEY:}}") String apiKey,
                            @Value("${linkro.route.kakao.base-url:https://dapi.kakao.com}") String baseUrl,
                            @Value("${linkro.route.kakao.call-timeout:3s}") Duration callTimeout,
                            @Value("${linkro.route.kakao.total-timeout:5s}") Duration totalTimeout,
                            @Value("${linkro.route.kakao.retry-delay:200ms}") Duration retryDelay,
                            @Value("${linkro.route.kakao.auth-cooldown:5m}") Duration authCooldown,
                            @Value("${linkro.route.kakao.quota-cooldown:30s}") Duration quotaCooldown) {
        this(restClient(builder, baseUrl, callTimeout), apiKey, callTimeout, totalTimeout, retryDelay, authCooldown,
                quotaCooldown, System::nanoTime, Clock.systemUTC());
    }

    KakaoRouteClient(RestClient restClient, String apiKey, Duration callTimeout, Duration totalTimeout,
                     Duration retryDelay, Duration authCooldown, Duration quotaCooldown, LongSupplier nanoTime, Clock clock) {
        this.restClient = restClient;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.callTimeout = callTimeout;
        this.totalTimeout = totalTimeout;
        this.retryDelay = retryDelay;
        this.authCooldown = authCooldown;
        this.quotaCooldown = quotaCooldown;
        this.nanoTime = nanoTime;
        this.clock = clock;
        AtomicInteger threads = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(CORE_THREADS, MAX_THREADS, 60, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "kakao-route-" + threads.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        if (!this.apiKey.isEmpty()) {
            log.info("Kakao route client is enabled: POST /routes/search tries Kakao first (call {} ms, total {} ms, 1 retry).",
                    callTimeout.toMillis(), totalTimeout.toMillis());
        } else {
            log.info("KAKAO_REST_API_KEY is empty: POST /routes/search uses INTERNAL routes only.");
        }
    }

    private static RestClient restClient(RestClient.Builder builder, String baseUrl, Duration callTimeout) {
        // Our own factory so the per-call timeouts hold whatever HTTP client Boot would pick.
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(callTimeout);
        factory.setReadTimeout(callTimeout);
        return builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    public boolean enabled() {
        return !apiKey.isEmpty();
    }

    /** Coordinates are WGS84: x = longitude, y = latitude. */
    public Result search(double startX, double startY, double endX, double endY) {
        if (!enabled()) {
            return new Failed(Reason.NO_KEY);
        }
        Cooldown current = cooldown.get();
        if (current != null && clock.instant().isBefore(current.until())) {
            return new Failed(Reason.COOLDOWN);
        }
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        long deadline = nanoTime.getAsLong() + totalTimeout.toNanos();
        Reason last = Reason.TIMEOUT;
        for (int attempt = 1; attempt <= 2; attempt++) {
            if (attempt == 2) {
                long left = deadline - nanoTime.getAsLong();
                if (left <= retryDelay.toNanos()) break;
                sleep(retryDelay);
            }
            long left = deadline - nanoTime.getAsLong();
            if (left <= 0) break;
            long started = nanoTime.getAsLong();
            Attempt outcome = attempt(startX, startY, endX, endY, Math.min(left, callTimeout.toNanos()));
            long elapsedMs = (nanoTime.getAsLong() - started) / 1_000_000;
            if (outcome.result() instanceof Found) {
                log.info("Kakao route {} attempt {}: {} in {} ms", requestId, attempt, outcome.status(), elapsedMs);
                return outcome.result();
            }
            last = ((Failed) outcome.result()).reason();
            switch (last) {
                case AUTH_ERROR -> startCooldown(authCooldown, last, requestId, attempt, outcome.status(), elapsedMs);
                case QUOTA -> startCooldown(quotaCooldown, last, requestId, attempt, outcome.status(), elapsedMs);
                default -> log.warn("Kakao route {} attempt {}: {} in {} ms", requestId, attempt, outcome.status(), elapsedMs);
            }
            if (last != Reason.TIMEOUT && last != Reason.IO_ERROR && last != Reason.SERVER_ERROR) break;
        }
        return new Failed(last);
    }

    /** Starts a cooldown unless one is running; only the request that starts it logs (ERROR for keys, WARN for quota). */
    private void startCooldown(Duration length, Reason reason, String requestId, int attempt, String status, long elapsedMs) {
        Instant now = clock.instant();
        Cooldown running = cooldown.get();
        if (running != null && now.isBefore(running.until())) return;
        Cooldown next = new Cooldown(now.plus(length), reason);
        if (!cooldown.compareAndSet(running, next)) return;
        if (reason == Reason.AUTH_ERROR) {
            log.error("Kakao route {} attempt {}: {} in {} ms. Kakao rejected the key; INTERNAL only for {} s. Check KAKAO_REST_API_KEY.",
                    requestId, attempt, status, elapsedMs, length.toSeconds());
        } else {
            log.warn("Kakao route {} attempt {}: {} in {} ms. Kakao quota exceeded; INTERNAL only for {} s.",
                    requestId, attempt, status, elapsedMs, length.toSeconds());
        }
    }

    /** One call's result and, for the log line only, its HTTP status or failure reason. */
    private record Attempt(Result result, String status) {}

    private Attempt attempt(double startX, double startY, double endX, double endY, long timeoutNanos) {
        Future<KakaoRouteResponse> call;
        try {
            call = executor.submit(() -> restClient.get()
                    .uri(builder -> builder.path(PATH)
                            .queryParam("start_x", startX).queryParam("start_y", startY)
                            .queryParam("end_x", endX).queryParam("end_y", endY)
                            .queryParam("input_coord", "WGS84").queryParam("output_coord", "WGS84").build())
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + apiKey)
                    .retrieve()
                    .body(KakaoRouteResponse.class));
        } catch (RejectedExecutionException exception) {
            return new Attempt(new Failed(Reason.BUSY), "call pool full (" + MAX_THREADS + " running)");
        }
        try {
            KakaoRouteResponse body = call.get(timeoutNanos, TimeUnit.NANOSECONDS);
            return body == null ? new Attempt(new Failed(Reason.BAD_RESPONSE), "200 empty body") : new Attempt(new Found(body), "200");
        } catch (TimeoutException exception) {
            call.cancel(true);
            return new Attempt(new Failed(Reason.TIMEOUT), "timeout");
        } catch (InterruptedException exception) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            return new Attempt(new Failed(Reason.TIMEOUT), "interrupted");
        } catch (ExecutionException exception) {
            return failure(exception.getCause());
        }
    }

    private static Attempt failure(Throwable cause) {
        // Only the status code or exception type is kept: messages can echo the request or the body.
        if (cause instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            Reason reason = status == 401 || status == 403 ? Reason.AUTH_ERROR
                    : status == 429 ? Reason.QUOTA
                    : response.getStatusCode().is4xxClientError() ? Reason.CLIENT_ERROR : Reason.SERVER_ERROR;
            return new Attempt(new Failed(reason), String.valueOf(status));
        }
        if (cause instanceof ResourceAccessException) {
            boolean timeout = cause.getCause() instanceof java.net.SocketTimeoutException;
            return new Attempt(new Failed(timeout ? Reason.TIMEOUT : Reason.IO_ERROR), timeout ? "timeout" : "I/O error");
        }
        String type = cause == null ? "unknown" : cause.getClass().getSimpleName();
        return new Attempt(new Failed(cause instanceof RestClientException ? Reason.BAD_RESPONSE : Reason.IO_ERROR), type);
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
