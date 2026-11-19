package com.mju.linkro.subway.arrival;

import com.mju.linkro.subway.arrival.ArrivalStationRepository.Node;
import com.mju.linkro.subway.arrival.ArrivalStationRepository.Station;
import com.mju.linkro.subway.arrival.dto.ArrivalQuery;
import com.mju.linkro.subway.arrival.dto.ArrivalResponse.Train;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static com.mju.linkro.subway.arrival.ArrivalFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 2호선 성수지선·신정지선 (#172): the next station's Seoul id compared with statnTid uses the measured branch ids
 * (2026-11-19), the main line keeps "1002000{code}". Stations and codes as seeded (V11): 성수 211, 건대입구 212, 뚝섬 210,
 * 신도림 234, 대림 235, 용답 244, 신답 245, 도림천 247, 양천구청 248, 신정네거리 249. Rows are hand-made with only the
 * fields the filter reads.
 */
class ArrivalLine2BranchTest {
    private final ArrivalStationRepository stations = mock(ArrivalStationRepository.class);
    private final MutableClock clock = new MutableClock();
    private MockRestServiceServer server;
    private ArrivalService service;

    @BeforeEach
    void setup() {
        for (Object[] row : new Object[][]{{20L, "성수", "211"}, {21L, "건대입구", "212"}, {22L, "뚝섬", "210"}, {234L, "신도림", "234"},
                {235L, "대림", "235"}, {244L, "용답", "244"}, {245L, "신답", "245"}, {247L, "도림천", "247"}, {248L, "양천구청", "248"},
                {249L, "신정네거리", "249"}}) {
            long id = (long) row[0];
            when(stations.station(id)).thenReturn(Optional.of(new Station(id, (String) row[1], true)));
            when(stations.node(id, 2)).thenReturn(Optional.of(new Node(1000 + id, "2", (String) row[2])));
        }
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new ArrivalService(stations, new ArrivalStationNames(), new SeoulArrivalClient(builder.build(), BASE, KEY, clock),
                Duration.ofSeconds(15), Duration.ofSeconds(5), 10, clock);
    }

    /** One row: updnLine, destination, statnTid, barvlDt. */
    private static String row(String updn, String destination, String tid, int seconds) {
        return """
                {"subwayId":"1002","updnLine":"%s","trainLineNm":"%s행","bstatnNm":"%s","statnId":"","statnFid":"","statnTid":"%s",\
                "barvlDt":"%d","arvlCd":"99","arvlMsg2":"%d초 후","arvlMsg3":"","recptnDt":"2026-11-10 08:00:00","lstcarAt":"0",\
                "btrainSttus":"일반","btrainNo":"0000"}""".formatted(updn, destination, destination, tid, seconds, seconds);
    }

    private void respond(String... rows) {
        String body = "{\"errorMessage\":{\"status\":200,\"code\":\"INFO-000\",\"message\":\"\",\"total\":" + rows.length
                + "},\"realtimeArrivalList\":[" + String.join(",", rows) + "]}";
        server.expect(ExpectedCount.once(), requestTo(startsWith(BASE + "/api/subway/"))).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private List<String> destinations(long stationId, long nextStationId) {
        List<String> result = service.arrivals(stationId, ArrivalQuery.of("2", String.valueOf(nextStationId), null, "10")).trains()
                .stream().map(Train::destinationName).toList();
        server.verify();
        return result;
    }

    @Test
    void theEightBranchStationsUseTheMeasuredIds() {
        assertThat(SeoulSubwayIds.stationId("2", "244")).contains("1002002111");
        assertThat(SeoulSubwayIds.stationId("2", "245")).contains("1002002112");
        assertThat(SeoulSubwayIds.stationId("2", "250")).contains("1002002113");
        assertThat(SeoulSubwayIds.stationId("2", "246")).contains("1002002114");
        assertThat(SeoulSubwayIds.stationId("2", "247")).contains("1002002341");
        assertThat(SeoulSubwayIds.stationId("2", "248")).contains("1002002342");
        assertThat(SeoulSubwayIds.stationId("2", "249")).contains("1002002343");
        assertThat(SeoulSubwayIds.stationId("2", "200")).contains("1002002344");
    }

    @Test
    void mainLineIdsAndOtherLinesAreUnchanged() {
        assertThat(SeoulSubwayIds.stationId("2", "211")).contains("1002000211");
        assertThat(SeoulSubwayIds.stationId("2", "234")).contains("1002000234");
        assertThat(SeoulSubwayIds.stationId("2", "201")).contains("1002000201");
        // 신설동 1호선 (156) and 까치산 5호선 (2519) share names with branch stations but build no id, as before.
        assertThat(SeoulSubwayIds.stationId("1", "156")).isEmpty();
        assertThat(SeoulSubwayIds.stationId("5", "2519")).isEmpty();
        // Same codes on another line, odd input: as before.
        assertThat(SeoulSubwayIds.stationId("3", "244")).isEmpty();
        assertThat(SeoulSubwayIds.stationId("2", null)).isEmpty();
        assertThat(SeoulSubwayIds.stationId("2", "24")).isEmpty();
    }

    @Test
    void seongsuToYongdapKeepsTheBranchTrain() {
        respond(row("하행", "신설동", "1002002111", 120), row("내선", "성수", "1002000210", 60), row("외선", "성수", "1002000212", 90));
        assertThat(destinations(20, 244)).containsExactly("신설동");
    }

    @Test
    void sindorimToDorimcheonKeepsTheBranchTrain() {
        respond(row("상행", "까치산", "1002002341", 180), row("내선", "성수", "1002000235", 30), row("외선", "성수", "1002000233", 45));
        assertThat(destinations(234, 247)).containsExactly("까치산");
    }

    @Test
    void insideTheSeongsuBranch() {
        // 용답 -> 신답 (toward 신설동) and the train toward 성수.
        respond(row("하행", "신설동", "1002002112", 100), row("상행", "성수", "1002000211", 200));
        assertThat(destinations(244, 245)).containsExactly("신설동");
    }

    @Test
    void insideTheSinjeongBranch() {
        // 양천구청 -> 신정네거리 (toward 까치산) and the train toward 도림천.
        respond(row("상행", "까치산", "1002002343", 100), row("하행", "신도림", "1002002341", 200));
        assertThat(destinations(248, 249)).containsExactly("까치산");
    }

    @Test
    void fromABranchIntoTheMainLineJunction() {
        // 용답 -> 성수: 성수 is a main-line station, 1002000211.
        respond(row("하행", "신설동", "1002002112", 100), row("상행", "성수", "1002000211", 200));
        assertThat(destinations(244, 20)).containsExactly("성수");
    }

    @Test
    void fromDorimcheonIntoSindorim() {
        respond(row("상행", "까치산", "1002002342", 100), row("하행", "신도림", "1002000234", 200));
        assertThat(destinations(247, 234)).containsExactly("신도림");
    }

    @Test
    void theMainLineAtSeongsuIsUnchanged() {
        // 성수 -> 건대입구 (212): the 외선 train only, not the branch train.
        respond(row("하행", "신설동", "1002002111", 120), row("내선", "성수", "1002000210", 60), row("외선", "성수", "1002000212", 90));
        assertThat(destinations(20, 21)).containsExactly("성수");
        verify(stations, never()).rideDirection(anyLong(), anyLong());
    }
}
