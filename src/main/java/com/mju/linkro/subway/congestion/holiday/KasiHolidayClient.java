package com.mju.linkro.subway.congestion.holiday;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 한국천문연구원 특일 정보 getRestDeInfo (공공데이터포털): public holidays of one month.
 *
 * <p>The service key is the "Decoding" key and is encoded exactly once here (URI template variable, strict
 * encoding), so {@code + / =} are sent as {@code %2B %2F %3D} and never as {@code %252B}. Failures are returned as
 * a short reason (HTTP status, result code, exception type); the key, the URL and the body are never exposed.
 */
@Component
public class KasiHolidayClient {
    static final String PATH = "/B090041/openapi/service/SpcdeInfoService/getRestDeInfo";
    private static final DateTimeFormatter LOCDATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Holidays of the month, or why there are none. */
    public sealed interface Result permits Month, Failed {}

    public record Month(List<LocalDate> holidays) implements Result {}

    public record Failed(String reason) implements Result {}

    private final RestClient restClient;
    private final String baseUrl;
    private final String serviceKey;

    @Autowired
    public KasiHolidayClient(RestClient.Builder builder,
                             @Value("${linkro.holiday.kasi.service-key:${KASI_SPECIAL_DAY_KEY:}}") String serviceKey,
                             @Value("${linkro.holiday.kasi.base-url:https://apis.data.go.kr}") String baseUrl,
                             @Value("${linkro.holiday.kasi.connect-timeout:2s}") Duration connectTimeout,
                             @Value("${linkro.holiday.kasi.read-timeout:3s}") Duration readTimeout) {
        this(restClient(builder, connectTimeout, readTimeout), baseUrl, serviceKey);
    }

    KasiHolidayClient(RestClient restClient, String baseUrl, String serviceKey) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
        this.serviceKey = serviceKey == null ? "" : serviceKey.strip();
    }

    private static RestClient restClient(RestClient.Builder builder, Duration connectTimeout, Duration readTimeout) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return builder.requestFactory(factory).build();
    }

    public boolean enabled() {
        return !serviceKey.isEmpty();
    }

    /** Request URI; the key is a template variable, encoded once with strict encoding. */
    URI uri(int year, int month) {
        return UriComponentsBuilder.fromUriString(baseUrl).path(PATH)
                .queryParam("serviceKey", "{serviceKey}")
                .queryParam("solYear", year)
                .queryParam("solMonth", "%02d".formatted(month))
                .queryParam("_type", "json")
                .encode()
                .buildAndExpand(Map.of("serviceKey", serviceKey))
                .toUri();
    }

    public Result holidays(int year, int month) {
        if (!enabled()) return new Failed("no service key");
        String body;
        try {
            // A URI object is sent as is: RestClient does not encode it again.
            body = restClient.get().uri(uri(year, month)).retrieve().body(String.class);
        } catch (RestClientResponseException exception) {
            return new Failed("HTTP " + exception.getStatusCode().value());
        } catch (RestClientException exception) {
            Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
            return new Failed(cause.getClass().getSimpleName());
        }
        return parse(body, year, month);
    }

    /**
     * {@code response.header.resultCode} must be "00". {@code response.body.items} is "" when the month has no
     * holiday; {@code items.item} is one object or an array. Only {@code isHoliday = Y} entries are kept.
     */
    static Result parse(String body, int year, int month) {
        if (body == null || body.isBlank()) return new Failed("empty body");
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JacksonException exception) {
            // An invalid key comes back as an XML error page even with _type=json.
            return new Failed("unreadable body");
        }
        JsonNode response = root.path("response");
        String code = response.path("header").path("resultCode").asString("");
        if (!"00".equals(code)) return new Failed(code.isEmpty() ? "no result code" : "result code " + code);
        JsonNode items = response.path("body").path("items");
        List<JsonNode> entries = new ArrayList<>();
        JsonNode item = items.path("item");
        if (item.isArray()) item.forEach(entries::add);
        else if (item.isObject()) entries.add(item);
        else if (!(items.isMissingNode() || items.isNull() || (items.isString() && items.asString().isBlank()))) {
            return new Failed("unexpected items");
        }
        int total = response.path("body").path("totalCount").asInt(entries.size());
        if (total > entries.size()) return new Failed("incomplete page (" + entries.size() + " of " + total + ")");
        List<LocalDate> holidays = new ArrayList<>();
        for (JsonNode entry : entries) {
            if (!"Y".equals(entry.path("isHoliday").asString(""))) continue;
            LocalDate date;
            try {
                date = LocalDate.parse(entry.path("locdate").asString(""), LOCDATE);
            } catch (DateTimeParseException exception) {
                return new Failed("bad locdate");
            }
            if (date.getYear() != year || date.getMonthValue() != month) return new Failed("date outside " + year + "-" + month);
            holidays.add(date);
        }
        return new Month(List.copyOf(holidays));
    }
}
