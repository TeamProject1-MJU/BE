package com.mju.linkro.subway.arrival;

import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.subway.api.DirectionCode;
import com.mju.linkro.subway.arrival.ArrivalStationRepository.Node;
import com.mju.linkro.subway.arrival.ArrivalStationRepository.Station;
import com.mju.linkro.subway.arrival.dto.ArrivalQuery;
import com.mju.linkro.subway.arrival.dto.ArrivalResponse;
import com.mju.linkro.subway.arrival.dto.ArrivalResponse.Reason;
import com.mju.linkro.subway.arrival.dto.ArrivalResponse.Train;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static com.mju.linkro.subway.arrival.ArrivalFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Rules of GET /stations/{id}/arrivals with a stubbed upstream and hand-made station data:
 * 건대입구(21) on 2호선(line 2, code 212) and 7호선(line 7, code 2729); 성수(20, 2호선 211); 어린이대공원(204, 7호선 2728);
 * 역촌(187, 6호선 loop) next to 불광(80); 응암(160, 6호선); 새절(161, 6호선).
 */
@ExtendWith(OutputCaptureExtension.class)
class ArrivalServiceTest {
    private final ArrivalStationRepository stations = mock(ArrivalStationRepository.class);
    private final MutableClock clock = new MutableClock();
    private MockRestServiceServer server;
    private ArrivalService service;

    @BeforeEach
    void setup() {
        station(21, "건대입구");
        station(20, "성수");
        station(204, "어린이대공원");
        station(187, "역촌");
        station(80, "불광");
        station(160, "응암");
        station(161, " 새절 ");
        node(21, 2, 1021, "2", "212");
        node(21, 7, 7021, "7", "2729");
        node(20, 2, 1020, "2", "211");
        node(204, 7, 7204, "7", "2728");
        node(187, 6, 6187, "6", "2612");
        node(80, 6, 6080, "6", "2613");
        node(160, 6, 6160, "6", "2611");
        node(161, 6, 6161, "6", "2618");
        when(stations.rideDirection(7021, 7204)).thenReturn(Optional.of(DirectionCode.UP));
        when(stations.rideDirection(6187, 6080)).thenReturn(Optional.of(DirectionCode.LOOP));
        service = service(KEY);
    }

    private ArrivalService service(String key) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        SeoulArrivalClient client = new SeoulArrivalClient(builder.build(), BASE, key, clock);
        return new ArrivalService(stations, new ArrivalStationNames(), client, Duration.ofSeconds(15), Duration.ofSeconds(5), clock);
    }

    private void station(long id, String name) {
        when(stations.station(id)).thenReturn(Optional.of(new Station(id, name, true)));
    }

    private void node(long stationId, long lineId, long nodeId, String lineCode, String code) {
        when(stations.node(stationId, lineId)).thenReturn(Optional.of(new Node(nodeId, lineCode, code)));
    }

    private void respond(String fixture, ExpectedCount count) {
        server.expect(count, requestTo(startsWith(BASE + "/api/subway/"))).andRespond(withSuccess(json(fixture), MediaType.APPLICATION_JSON));
    }

    private ArrivalResponse get(long stationId, String lineId, String next, String direction, String limit) {
        return service.arrivals(stationId, ArrivalQuery.of(lineId, next, direction, limit));
    }

    private static List<String> summary(ArrivalResponse response) {
        return response.trains().stream().map(t -> t.directionCode() + " " + t.secondsUntil() + " " + t.message()).toList();
    }

    @Test
    void onlyTheLineIsKeptSortedWithAdjustedSeconds() {
        respond("konkuk.json", ExpectedCount.once());
        ArrivalResponse line2 = get(21, "2", null, null, "10");
        assertThat(line2.available()).isTrue();
        assertThat(line2.reason()).isNull();
        assertThat(line2.receivedAt()).isEqualTo("2026-11-10T08:00:00+09:00");
        // arvlCd 1 -> 0; 99: 120 - 10 s old = 110, 400 - 20 = 380; 2 (departed) dropped; empty barvlDt -> null, last.
        assertThat(summary(line2)).containsExactly("OUTER 0 건대입구 도착", "INNER 110 2분 후", "OUTER 380 6분 40초 후", "INNER null 구의 출발");
        assertThat(line2.trains()).allSatisfy(train -> assertThat(train.destinationName()).isEqualTo("성수"));
    }

    @Test
    void arrivalCodesAgeAndLastTrain() {
        respond("konkuk.json", ExpectedCount.once());
        ArrivalResponse line7 = get(21, "7", null, null, "10");
        // arvlCd 0 -> 0; 5: 60 - 30 = 30; 99: 170 - 5 = 165; barvlDt "abc" -> null; 6 min old row dropped.
        assertThat(summary(line7)).containsExactly("UP 0 건대입구 진입", "DOWN 30 [2]번째 전역 (어린이대공원(세종대))", "UP 165 2분 50초 후", "DOWN null 곧 도착");
        assertThat(line7.trains()).extracting(Train::lastTrain).containsExactly(false, true, false, false);
        assertThat(line7.trains()).extracting(Train::destinationName).containsExactly("장암", "석남", "장암", "석남");
        // The default limit is 3.
        assertThat(get(21, "7", null, null, null).trains()).hasSize(3);
    }

    @Test
    void nextStationIdIsMatchedByStationIdOn2호선() {
        respond("konkuk.json", ExpectedCount.once());
        // 성수(211) -> statnTid 1002000211.
        assertThat(summary(get(21, "2", "20", null, "10"))).containsExactly("INNER 110 2분 후", "INNER null 구의 출발");
        verify(stations, never()).rideDirection(anyLong(), anyLong());
    }

    @Test
    void elsewhereNextStationIdGivesTheDirectionThroughOurEdge() {
        respond("konkuk.json", ExpectedCount.once());
        // 7호선 codes cannot build the external id: 건대입구 -> 어린이대공원 is UP on our graph, so 상행 only.
        assertThat(summary(get(21, "7", "204", null, "10"))).containsExactly("UP 0 건대입구 진입", "UP 165 2분 50초 후");
        // Without nextStationId the directionCode is used.
        assertThat(summary(get(21, "7", null, "DOWN", "10"))).containsExactly("DOWN 30 [2]번째 전역 (어린이대공원(세종대))", "DOWN null 곧 도착");
        // A nextStationId that is not on the line falls back to directionCode.
        when(stations.node(999, 7)).thenReturn(Optional.empty());
        assertThat(summary(get(21, "7", "999", "DOWN", "10"))).hasSize(2);
    }

    @Test
    void loopDirectionDoesNotFilterUpdnLine() {
        respond("yeokchon.json", ExpectedCount.once());
        // 역촌 -> 불광 is LOOP on our graph; the loop runs as 하행, so updnLine is not compared.
        assertThat(get(187, "6", "80", null, "10").trains()).hasSize(2);
        assertThat(get(187, "6", null, "LOOP", "10").trains()).hasSize(2);
        assertThat(get(187, "6", null, "DOWN", "10").trains()).hasSize(2);
        assertThat(get(187, "6", null, "UP", "10").trains()).isEmpty();
    }

    @Test
    void eungamUsesItsOfficialNameAndNoDirectionFilter() {
        server.expect(ExpectedCount.once(), requestTo(containsString("/0/30/%EC%9D%91%EC%95%94%EC%88%9C%ED%99%98%28%EC%83%81%EC%84%A0%29")))
                .andRespond(withSuccess(json("eungam.json"), MediaType.APPLICATION_JSON));
        // statnFid/Tid break the neighbour rule here, so nextStationId and directionCode are ignored.
        ArrivalResponse response = get(160, "6", "161", "UP", "10");
        server.verify();
        assertThat(summary(response)).containsExactly("DOWN 0 응암순환(상선) 도착", "DOWN 90 1분 40초 후", "UP 190 3분 20초 후", "UP 490 8분 20초 후");
        assertThat(response.trains().get(2).destinationName()).isEqualTo("응암순환(상선)");
    }

    @Test
    void namesAreTrimmedAndMapped() {
        // DB " 새절 " -> 새절(신사); 역촌 has no exception and is used as is.
        server.expect(ExpectedCount.once(), requestTo(containsString("/0/30/%EC%83%88%EC%A0%88%28%EC%8B%A0%EC%82%AC%29")))
                .andRespond(withSuccess(json("yeokchon.json"), MediaType.APPLICATION_JSON));
        get(161, "6", null, null, null);
        server.verify();
        assertThat(new ArrivalStationNames().queryName("6", "역촌")).isEqualTo("역촌");
        assertThat(new ArrivalStationNames().queryName("7", " 어린이대공원")).isEqualTo("어린이대공원(세종대)");
        assertThat(new ArrivalStationNames().queryName("6", "증산")).isEqualTo("증산(명지대앞)");
        assertThat(new ArrivalStationNames().queryName("6", "월드컵경기장")).isEqualTo("월드컵경기장(성산)");
        // The mapping is per line: 7호선 응암 would not be mapped.
        assertThat(new ArrivalStationNames().queryName("7", "응암")).isEqualTo("응암");
    }

    @Test
    void upstreamCodesAndFailures(CapturedOutput output) {
        server.expect(ExpectedCount.once(), requestTo(startsWith(BASE)))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON).body(json("no-data.json")));
        ArrivalResponse noData = get(187, "6", null, null, null);
        assertThat(noData).isEqualTo(new ArrivalResponse(false, Reason.NO_DATA, "2026-11-10T08:00:00+09:00", List.of()));

        for (String body : new String[] {json("error-code.json"), "<html>not json</html>"}) {
            service = service(KEY);
            server.expect(ExpectedCount.once(), requestTo(startsWith(BASE))).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertThat(get(21, "2", null, null, null)).isEqualTo(ArrivalResponse.unavailable(Reason.UPSTREAM_ERROR, null));
        }
        service = service(KEY);
        server.expect(ExpectedCount.once(), requestTo(startsWith(BASE))).andRespond(withException(new SocketTimeoutException("Read timed out")));
        assertThat(get(21, "2", null, null, null)).isEqualTo(ArrivalResponse.unavailable(Reason.UPSTREAM_ERROR, null));

        assertThat(output.getAll()).contains("Seoul arrival request failed: result code ERROR-337", "Seoul arrival request failed: SocketTimeoutException")
                .doesNotContain("FAKE").doesNotContain("swopenAPI").doesNotContain("/api/subway/");
    }

    @Test
    void withoutAKeyNothingIsCalled() {
        service = service("");
        assertThat(get(21, "2", null, null, null)).isEqualTo(ArrivalResponse.unavailable(Reason.DISABLED, null));
        server.verify();
    }

    @Test
    void unsupportedLinesAndUnknownStations() {
        when(stations.node(21, 99)).thenReturn(Optional.empty());
        assertThat(get(21, "99", null, null, null)).isEqualTo(ArrivalResponse.unavailable(Reason.UNSUPPORTED_STATION, null));
        when(stations.station(300)).thenReturn(Optional.of(new Station(300, "미지원역", false)));
        node(300, 2, 9300, "2", "299");
        assertThat(get(300, "2", null, null, null).reason()).isEqualTo(Reason.UNSUPPORTED_STATION);
        when(stations.station(999)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> get(999, "2", null, null, null)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.STATION_NOT_FOUND));
        server.verify(); // none of these called upstream
    }

    @Test
    void answersAreCachedPerNameAndLine() {
        respond("konkuk.json", ExpectedCount.twice());
        get(21, "2", null, null, null);
        clock.advance(Duration.ofSeconds(14));
        get(21, "2", null, "INNER", null);                  // cache hit
        get(21, "7", null, null, null);                     // same name, other subwayId: second call
        server.verify();

        service = service(KEY);
        respond("konkuk.json", ExpectedCount.twice());
        get(21, "2", null, null, null);
        clock.advance(Duration.ofSeconds(15));
        get(21, "2", null, null, null);                     // expired: called again
        server.verify();
    }

    @Test
    void cachedRowsAgeWithTheClock() {
        respond("konkuk.json", ExpectedCount.once());
        assertThat(get(21, "2", "20", null, null).trains().get(0).secondsUntil()).isEqualTo(110);
        clock.advance(Duration.ofSeconds(10));
        assertThat(get(21, "2", "20", null, null).trains().get(0).secondsUntil()).isEqualTo(100);
        server.verify();
    }

    @Test
    void failuresAreCachedBriefly() {
        server.expect(ExpectedCount.twice(), requestTo(startsWith(BASE))).andRespond(withSuccess(json("error-code.json"), MediaType.APPLICATION_JSON));
        get(21, "2", null, null, null);
        clock.advance(Duration.ofSeconds(4));
        get(21, "2", null, null, null);                     // still cached
        clock.advance(Duration.ofSeconds(1));
        get(21, "2", null, null, null);                     // 5 s later: called again
        server.verify();
    }

    @Test
    void concurrentRequestsShareOneCall() throws Exception {
        CountDownLatch inCall = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        server.expect(ExpectedCount.manyTimes(), requestTo(startsWith(BASE))).andRespond(request -> {
            calls.incrementAndGet();
            inCall.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return withSuccess(json("konkuk.json"), MediaType.APPLICATION_JSON).createResponse(request);
        });
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<ArrivalResponse>> answers = new ArrayList<>();
            answers.add(pool.submit(() -> get(21, "2", null, null, null)));
            assertThat(inCall.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 3; i++) answers.add(pool.submit(() -> get(21, "2", null, null, null)));
            Thread.sleep(100);
            release.countDown();
            for (var answer : answers) assertThat(answer.get(5, TimeUnit.SECONDS).available()).isTrue();
            assertThat(calls).hasValue(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
