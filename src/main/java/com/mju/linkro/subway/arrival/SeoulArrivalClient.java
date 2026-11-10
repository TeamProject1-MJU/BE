package com.mju.linkro.subway.arrival;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 서울 열린데이터광장 realtimeStationArrival. The key sits in the URL path, so the URL, the request and upstream
 * exception messages are never logged or rethrown: failures become {@link ArrivalUpstreamException} with a safe
 * message (the KakaoClient pattern). One call, no retry.
 */
@Slf4j
@Component
public class SeoulArrivalClient {
    static final String PATH = "/api/subway/{key}/json/realtimeStationArrival/0/30/{name}";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Upstream answer by result code: INFO-000 rows, or INFO-200 (no data). */
    public sealed interface Fetch permits Rows, NoData {}

    public record Rows(List<Arrival> arrivals, Instant receivedAt) implements Fetch {}

    public record NoData(Instant receivedAt) implements Fetch {}

    /** The fields used from one realtimeArrivalList row; values as the API sends them (strings). */
    public record Arrival(String subwayId, String updnLine, String trainLineNm, String bstatnNm, String statnId,
                          String statnFid, String statnTid, String barvlDt, String arvlCd, String arvlMsg2,
                          String arvlMsg3, String recptnDt, String lstcarAt, String btrainSttus, String btrainNo) {}

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;
    private final Clock clock;

    @Autowired
    public SeoulArrivalClient(RestClient.Builder builder,
                              @Value("${linkro.arrival.seoul.api-key:${SEOUL_ARRIVAL_API_KEY:}}") String apiKey,
                              @Value("${linkro.arrival.seoul.base-url:http://swopenAPI.seoul.go.kr}") String baseUrl,
                              @Value("${linkro.arrival.seoul.timeout:3s}") Duration timeout) {
        this(restClient(builder, timeout), baseUrl, apiKey, Clock.systemUTC());
    }

    SeoulArrivalClient(RestClient restClient, String baseUrl, String apiKey, Clock clock) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.clock = clock;
        if (!this.apiKey.isEmpty()) {
            log.info("Seoul arrival client is enabled: GET /stations/{id}/arrivals calls the realtime API.");
        } else {
            log.info("SEOUL_ARRIVAL_API_KEY is empty: GET /stations/{id}/arrivals answers available=false (DISABLED).");
        }
    }

    private static RestClient restClient(RestClient.Builder builder, Duration timeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return builder.requestFactory(factory).build();
    }

    public boolean enabled() {
        return !apiKey.isEmpty();
    }

    /** Key and name are URI variables, strictly encoded once; the URI object is not encoded again. */
    URI uri(String queryName) {
        return UriComponentsBuilder.fromUriString(baseUrl).path(PATH).encode()
                .buildAndExpand(Map.of("key", apiKey, "name", queryName.strip())).toUri();
    }

    /**
     * @throws ArrivalUpstreamException any other result code, a network error or timeout, or an unreadable body.
     *         Its message never contains the key or the URL.
     */
    public Fetch fetch(String queryName) {
        String body;
        try {
            // exchange(): the result code decides, not the HTTP status (INFO-200 may come with status 500).
            body = restClient.get().uri(uri(queryName))
                    .exchange((request, response) -> StreamUtils.copyToString(response.getBody(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (RestClientException exception) {
            Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
            throw ArrivalUpstreamException.wrap(cause.getClass().getSimpleName(), exception);
        }
        Instant receivedAt = clock.instant();
        return parse(body, receivedAt);
    }

    /** errorMessage.code when present (normal answers), else the top-level code (no data and errors). */
    static Fetch parse(String body, Instant receivedAt) {
        JsonNode root;
        try {
            root = JSON.readTree(body == null ? "" : body);
        } catch (JacksonException exception) {
            throw new ArrivalUpstreamException("unreadable body");
        }
        if (root == null || !root.isObject()) throw new ArrivalUpstreamException("unreadable body");
        String code = root.has("errorMessage") ? root.path("errorMessage").path("code").asString("") : root.path("code").asString("");
        if ("INFO-200".equals(code)) return new NoData(receivedAt);
        if (!"INFO-000".equals(code)) throw new ArrivalUpstreamException(code.isEmpty() ? "no result code" : "result code " + code);
        List<Arrival> arrivals = new ArrayList<>();
        for (JsonNode row : root.path("realtimeArrivalList")) {
            arrivals.add(new Arrival(text(row, "subwayId"), text(row, "updnLine"), text(row, "trainLineNm"), text(row, "bstatnNm"),
                    text(row, "statnId"), text(row, "statnFid"), text(row, "statnTid"), text(row, "barvlDt"), text(row, "arvlCd"),
                    text(row, "arvlMsg2"), text(row, "arvlMsg3"), text(row, "recptnDt"), text(row, "lstcarAt"),
                    text(row, "btrainSttus"), text(row, "btrainNo")));
        }
        return new Rows(List.copyOf(arrivals), receivedAt);
    }

    private static String text(JsonNode row, String field) {
        JsonNode value = row.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }
}
