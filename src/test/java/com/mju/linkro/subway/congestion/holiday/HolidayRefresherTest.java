package com.mju.linkro.subway.congestion.holiday;

import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import static com.mju.linkro.subway.congestion.holiday.KasiStub.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@ExtendWith(OutputCaptureExtension.class)
class HolidayRefresherTest {
    static final String KEY = "ab+cd/ef==";
    static final String URL = "https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService/getRestDeInfo";
    /** 2026-11-08 in Seoul: this year 2026, next year 2027. */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T01:00:00Z"), ZoneOffset.UTC);
    /** Defaults as in subway-holidays.properties, shortened. */
    static final String DEFAULTS = "2026-01-01,2026-12-25,2027-01-01,2027-12-25";

    private MockRestServiceServer server;
    private final RefreshableHolidayCalendar calendar = new RefreshableHolidayCalendar(DEFAULTS);

    /** Real client on a stub server that answers each (year, month) with {@code answer}. */
    private HolidayRefresher refresher(BiFunction<Integer, Integer, String> answer) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        server.expect(ExpectedCount.manyTimes(), requestTo(startsWith(URL))).andRespond(request -> {
            var params = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
            int year = Integer.parseInt(params.getFirst("solYear"));
            int month = Integer.parseInt(params.getFirst("solMonth"));
            String body = answer.apply(year, month);
            return body == null ? withStatus(HttpStatus.INTERNAL_SERVER_ERROR).createResponse(request)
                    : withSuccess(body, MediaType.APPLICATION_JSON).createResponse(request);
        });
        return new HolidayRefresher(new KasiHolidayClient(builder.build(), "https://apis.data.go.kr", KEY), calendar, CLOCK);
    }

    /** January: two holidays (array); March: one (single object); October: one; others none (""). */
    static String year(int year, int month) {
        return switch (month) {
            case 1 -> ok(array(item("1월1일", year + "0101", "Y"), item("임시", year + "0102", "Y")), 2);
            case 3 -> ok(single(item("삼일절", year + "0301", "Y")), 1);
            case 10 -> ok(single(item("한글날", year + "1009", "Y")), 1);
            default -> EMPTY;
        };
    }

    @Test
    void bothYearsAreReplacedWhenEveryMonthSucceeds(CapturedOutput output) {
        assertThat(refresher(HolidayRefresherTest::year).refresh()).isEqualTo(Map.of(2026, true, 2027, true));

        assertThat(calendar.holidays(2026)).containsExactlyInAnyOrder(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2),
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 10, 9));
        // The refreshed list replaces the default: 2027-12-25 is gone, 2027-01-02 is new.
        assertThat(calendar.isHoliday(LocalDate.of(2027, 12, 25))).isFalse();
        assertThat(calendar.isHoliday(LocalDate.of(2027, 1, 2))).isTrue();
        assertThat(output.getAll()).contains("Holiday refresh 2026: 4 holidays", "Holiday refresh 2027: 4 holidays")
                .doesNotContain(KEY).doesNotContain("ab%2Bcd").doesNotContain("한글날").doesNotContain("apis.data.go.kr");
    }

    @Test
    void aFailedMonthKeepsThatYearsPreviousList(CapturedOutput output) {
        // 2027 July fails: 2026 is replaced, 2027 keeps the properties default.
        var refresher = refresher((year, month) -> year == 2027 && month == 7 ? null : year(year, month));
        assertThat(refresher.refresh()).isEqualTo(Map.of(2026, true, 2027, false));
        assertThat(calendar.isRefreshed(2026)).isTrue();
        assertThat(calendar.isRefreshed(2027)).isFalse();
        assertThat(calendar.holidays(2027)).containsExactlyInAnyOrder(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 25));
        assertThat(output.getAll().lines().filter(line -> line.contains("Holiday refresh 2027: month 7 failed (HTTP 500)")))
                .singleElement().satisfies(line -> assertThat(line).contains(" WARN ").contains("keeping the previous 2027 list (2 holidays)"));
        assertThat(output.getAll()).doesNotContain(KEY).doesNotContain("ab%2Bcd");
    }

    @Test
    void aLaterFailureKeepsTheLastSuccessfulList() {
        refresher(HolidayRefresherTest::year).refresh();
        // Next day everything for 2027 fails after March: the 2027 list from the first refresh stays.
        refresher((year, month) -> year == 2027 && month > 3 ? null : year(year, month)).refresh();
        assertThat(calendar.holidays(2027)).containsExactlyInAnyOrder(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 2),
                LocalDate.of(2027, 3, 1), LocalDate.of(2027, 10, 9));
    }

    @Test
    void withoutAKeyNothingIsCalled(CapturedOutput output) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build(); // no expectation: any call fails the test
        var refresher = new HolidayRefresher(new KasiHolidayClient(builder.build(), "https://apis.data.go.kr", ""), calendar, CLOCK);

        refresher.onReady();
        refresher.daily();

        server.verify();
        assertThat(calendar.isRefreshed(2026)).isFalse();
        assertThat(output.getAll().lines().filter(line -> line.contains("KASI_SPECIAL_DAY_KEY is empty"))).singleElement()
                .satisfies(line -> assertThat(line).contains(" INFO "));
    }

    @Test
    void startupDoesNotWaitForTheRefresh() throws Exception {
        KasiHolidayClient slow = mock(KasiHolidayClient.class);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch called = new CountDownLatch(1);
        when(slow.enabled()).thenReturn(true);
        when(slow.holidays(anyInt(), anyInt())).thenAnswer(invocation -> {
            called.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new KasiHolidayClient.Month(List.of());
        });
        var refresher = new HolidayRefresher(slow, calendar, CLOCK);
        try {
            long started = System.nanoTime();
            refresher.onReady();
            assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(200);
            assertThat(called.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(calendar.isRefreshed(2026)).isFalse();
        } finally {
            release.countDown();
            refresher.shutdown();
        }
    }

    @Test
    void dailyRunIsScheduledAtThreeInSeoul() throws Exception {
        Scheduled scheduled = HolidayRefresher.class.getMethod("daily").getAnnotation(Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("0 0 3 * * *");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");

        KasiHolidayClient client = mock(KasiHolidayClient.class);
        new ApplicationContextRunner()
                .withUserConfiguration(HolidayRefreshConfig.class)
                .withBean(KasiHolidayClient.class, () -> client)
                .withBean(RefreshableHolidayCalendar.class, () -> calendar)
                .withBean(HolidayRefresher.class)
                .run(context -> {
                    var tasks = context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                            .flatMap(holder -> holder.getScheduledTasks().stream()).toList();
                    assertThat(tasks).anySatisfy(task -> {
                        assertThat(task.getTask()).isInstanceOf(CronTask.class);
                        assertThat(((CronTask) task.getTask()).getExpression()).isEqualTo("0 0 3 * * *");
                    });
                });
    }
}
