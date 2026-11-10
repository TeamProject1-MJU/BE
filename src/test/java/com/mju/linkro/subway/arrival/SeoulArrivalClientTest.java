package com.mju.linkro.subway.arrival;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static com.mju.linkro.subway.arrival.ArrivalFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class SeoulArrivalClientTest {
    private MockRestServiceServer server;

    private SeoulArrivalClient client() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new SeoulArrivalClient(builder.build(), BASE, KEY, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void keyAndNameAreEncodedOnceInThePath() {
        var client = client();
        String path = client.uri("  새절(신사) ").getRawPath();
        assertThat(path).startsWith("/api/subway/FAKE%2BKEY%2Ftest%3D/json/realtimeStationArrival/0/30/")
                .endsWith("/%EC%83%88%EC%A0%88%28%EC%8B%A0%EC%82%AC%29").doesNotContain("%25").doesNotContain(" ");
    }

    @Test
    void normalAnswerIsReadFromRealtimeArrivalList() {
        var client = client();
        server.expect(ExpectedCount.once(), requestTo(startsWith(BASE + "/api/subway/")))
                .andRespond(withSuccess(json("konkuk.json"), MediaType.APPLICATION_JSON));
        var fetch = client.fetch("건대입구");
        assertThat(fetch).isInstanceOfSatisfying(SeoulArrivalClient.Rows.class, rows -> {
            assertThat(rows.arrivals()).hasSize(10);
            assertThat(rows.receivedAt()).isEqualTo(NOW);
            assertThat(rows.arrivals().get(0)).satisfies(first -> {
                assertThat(first.subwayId()).isEqualTo("1007");
                assertThat(first.updnLine()).isEqualTo("상행");
                assertThat(first.statnTid()).isEqualTo("1007000726");
                assertThat(first.barvlDt()).isEqualTo("170");
                assertThat(first.recptnDt()).isEqualTo("2026-11-10 07:59:55");
            });
        });
    }

    @Test
    void theCodeDecidesNotTheHttpStatus() {
        var client = client();
        server.expect(ExpectedCount.once(), requestTo(startsWith(BASE)))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON).body(json("no-data.json")));
        assertThat(client.fetch("응암")).isEqualTo(new SeoulArrivalClient.NoData(NOW));
    }

    @Test
    void otherCodesBadBodiesAndTimeoutsAreSafeFailures() {
        Object[][] cases = {{json("error-code.json"), "result code ERROR-337"}, {"not json", "unreadable body"},
                {"{\"errorMessage\":{\"code\":\"INFO-100\"}}", "result code INFO-100"}, {"[]", "unreadable body"}};
        for (Object[] c : cases) {
            var client = client();
            server.expect(ExpectedCount.once(), requestTo(startsWith(BASE))).andRespond(withSuccess((String) c[0], MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> client.fetch("건대입구")).isInstanceOf(ArrivalUpstreamException.class)
                    .hasMessage("Seoul arrival request failed: " + c[1]).hasNoCause();
        }
        var client = client();
        server.expect(ExpectedCount.once(), requestTo(startsWith(BASE))).andRespond(withException(new SocketTimeoutException("Read timed out " + BASE + "/api/subway/" + KEY)));
        assertThatThrownBy(() -> client.fetch("건대입구")).isInstanceOf(ArrivalUpstreamException.class)
                .hasMessage("Seoul arrival request failed: SocketTimeoutException").hasNoCause()
                .satisfies(error -> assertThat(error.toString()).doesNotContain(KEY).doesNotContain("FAKE").doesNotContain("swopenAPI"));
    }
}
