package com.mju.linkro.subway.congestion.holiday;

import com.mju.linkro.subway.congestion.holiday.KasiHolidayClient.Failed;
import com.mju.linkro.subway.congestion.holiday.KasiHolidayClient.Month;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static com.mju.linkro.subway.congestion.holiday.KasiStub.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** KASI calls are stubbed with MockRestServiceServer; nothing goes to the real API. */
class KasiHolidayClientTest {
    /** Looks like a data.go.kr "Decoding" key: + / = must be encoded exactly once. */
    static final String KEY = "ab+cd/ef==";
    static final String URL = "https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService/getRestDeInfo";

    private MockRestServiceServer server;

    private KasiHolidayClient client(String key) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new KasiHolidayClient(builder.build(), "https://apis.data.go.kr", key);
    }

    private void respond(String body) {
        server.expect(ExpectedCount.once(), requestTo(startsWith(URL))).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void keyIsEncodedOnceAndParametersFollowTheDocument() {
        KasiHolidayClient client = client(KEY);
        server.expect(ExpectedCount.once(), requestTo(startsWith(URL)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(request -> {
                    String query = request.getURI().getRawQuery();
                    assertThat(query).contains("serviceKey=ab%2Bcd%2Fef%3D%3D").doesNotContain("%25").doesNotContain("ab+cd");
                    assertThat(query).contains("solYear=2027", "solMonth=03", "_type=json");
                })
                .andRespond(withSuccess(EMPTY, MediaType.APPLICATION_JSON));
        assertThat(client.holidays(2027, 3)).isEqualTo(new Month(java.util.List.of()));
        server.verify();
    }

    @Test
    void arraySingleObjectAndEmptyItems() {
        KasiHolidayClient client = client(KEY);
        respond(ok(array(item("설날", "20270206", "Y"), item("설날", "20270207", "Y"), item("기념일", "20270214", "N")), 3));
        assertThat(client.holidays(2027, 2)).isEqualTo(new Month(java.util.List.of(LocalDate.of(2027, 2, 6), LocalDate.of(2027, 2, 7))));
        server.verify();

        client = client(KEY);
        respond(ok(single(item("삼일절", "20270301", "Y")), 1));
        assertThat(client.holidays(2027, 3)).isEqualTo(new Month(java.util.List.of(LocalDate.of(2027, 3, 1))));
        server.verify();

        client = client(KEY);
        respond(EMPTY);
        assertThat(client.holidays(2027, 4)).isEqualTo(new Month(java.util.List.of()));
        server.verify();

        // locdate may also come as a string.
        client = client(KEY);
        respond(ok(single(item("어린이날", "\"20270505\"", "Y")), 1));
        assertThat(client.holidays(2027, 5)).isEqualTo(new Month(java.util.List.of(LocalDate.of(2027, 5, 5))));
    }

    @Test
    void abnormalAnswersAreFailures() {
        Object[][] cases = {
                {"{\"response\":{\"header\":{\"resultCode\":\"30\",\"resultMsg\":\"SERVICE KEY IS NOT REGISTERED ERROR.\"}}}", "result code 30"},
                {"<OpenAPI_ServiceResponse><cmmMsgHeader><returnReasonCode>30</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>", "unreadable body"},
                {ok(single(item("설날", "20270206", "Y")), 3), "incomplete page (1 of 3)"},
                {ok(single(item("다른 달", "20270101", "Y")), 1), "date outside 2027-2"},
                {ok("{\"item\":5}", 1), "unexpected items"},
        };
        for (Object[] c : cases) {
            KasiHolidayClient client = client(KEY);
            respond((String) c[0]);
            assertThat(client.holidays(2027, 2)).as((String) c[1]).isEqualTo(new Failed((String) c[1]));
        }
        KasiHolidayClient client = client(KEY);
        server.expect(ExpectedCount.once(), requestTo(startsWith(URL))).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThat(client.holidays(2027, 2)).isEqualTo(new Failed("HTTP 500"));
    }

    @Test
    void withoutAKeyNothingIsCalled() {
        KasiHolidayClient client = client("  ");
        assertThat(client.enabled()).isFalse();
        assertThat(client.holidays(2027, 1)).isEqualTo(new Failed("no service key"));
        server.verify();
    }
}
