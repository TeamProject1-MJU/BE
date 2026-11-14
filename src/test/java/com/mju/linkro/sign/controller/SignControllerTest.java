package com.mju.linkro.sign.controller;

import com.mju.linkro.ai.api.AiClient;
import com.mju.linkro.ai.api.SignContext;
import com.mju.linkro.ai.api.SignImage;
import com.mju.linkro.ai.api.SignReading;
import com.mju.linkro.ai.api.SignReason;
import com.mju.linkro.ai.api.SignResult;
import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.sign.SignTestImages;
import com.mju.linkro.auth.repository.RefreshTokenRepository;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.subway.api.DirectionCode;
import com.mju.linkro.subway.api.LineLookup;
import com.mju.linkro.subway.api.StationInfo;
import com.mju.linkro.subway.api.StationLookup;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full context (real JWT filter, multipart, error envelope). Station/line lookups and the AI are mocks. */
@SpringBootTest
@ActiveProfiles("test")
class SignControllerTest {
    static final String URL = "/api/v1/ai/direction-check";
    static final byte[] JPEG = SignTestImages.jpeg();
    static final String ROUTE = "{\"stationId\":105,\"lineId\":2,\"direction\":\"INNER\",\"headsign\":\"사당 방면\"}";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired JwtProvider jwtProvider;
    @MockitoBean AiClient ai;
    @MockitoBean StationLookup stations;
    @MockitoBean LineLookup lines;
    @MockitoBean com.mju.linkro.sign.service.SignRouteLookup routes;
    MockMvc mvc;
    User user;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        refreshTokens.deleteAll();
        users.deleteAll();
        user = users.saveAndFlush(new User("123", "현근"));
        when(stations.require(105)).thenReturn(new StationInfo(105, "강남", 37.49, 127.02, true));
        when(lines.name(2)).thenReturn(Optional.of("2호선"));
    }

    private ResultActions post(MockMultipartFile image, String route, boolean authenticated) throws Exception {
        var request = multipart(URL);
        if (image != null) request.file(image);
        if (route != null) request.file(new MockMultipartFile("route", "", "text/plain", route.getBytes(StandardCharsets.UTF_8)));
        if (authenticated) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.createAccessToken(user.getId()));
        return mvc.perform(request);
    }

    static MockMultipartFile image(String type, byte[] bytes) {
        return new MockMultipartFile("image", "sign.jpg", type, bytes);
    }

    @Test
    void requiresAuthentication() throws Exception {
        post(image("image/jpeg", JPEG), ROUTE, false).andExpect(status().isUnauthorized());
        verifyNoInteractions(ai);
    }

    @Test
    void wrongDirectionReturnsSpecExample() throws Exception {
        when(ai.readSign(any(), any())).thenReturn(new SignReading(SignResult.WRONG, SignReason.DIRECTION_MISMATCH, "강남", "2호선", "성수 방면"));

        post(image("image/jpeg", JPEG), ROUTE, true).andExpect(status().isOk()).andExpect(content().json("""
                {"success":true,"data":{"result":"WRONG","reason":"DIRECTION_MISMATCH",
                 "recognized":{"stationName":"강남","lineName":"2호선","headsign":"성수 방면"},
                 "expected":{"stationName":"강남","lineName":"2호선","headsign":"사당 방면"},
                 "message":"반대 방향입니다. 맞은편 승강장으로 이동하세요."}}""", JsonCompareMode.STRICT));

        ArgumentCaptor<SignImage> image = ArgumentCaptor.forClass(SignImage.class);
        ArgumentCaptor<SignContext> ctx = ArgumentCaptor.forClass(SignContext.class);
        verify(ai).readSign(image.capture(), ctx.capture());
        assertThat(image.getValue().bytes()).isEqualTo(JPEG);
        assertThat(ctx.getValue()).isEqualTo(new SignContext("강남", "2호선", DirectionCode.INNER, "사당 방면"));
    }

    @Test
    void correctHasNullReasonAndRetakeKeepsNullRecognizedFields() throws Exception {
        when(ai.readSign(any(), any())).thenReturn(new SignReading(SignResult.CORRECT, null, "강남", "2호선", "사당 방면"));
        post(image("image/jpeg", JPEG), "{\"stationId\":105,\"lineId\":2,\"headsign\":\"사당 방면\"}", true)
                .andExpect(status().isOk()).andExpect(content().json("""
                        {"success":true,"data":{"result":"CORRECT","reason":null,"message":"올바른 방향입니다."}}""", JsonCompareMode.LENIENT));

        when(ai.readSign(any(), any())).thenReturn(new SignReading(SignResult.RETAKE, SignReason.UNREADABLE, null, null, null));
        post(image("image/jpeg", JPEG), ROUTE, true).andExpect(status().isOk()).andExpect(content().json("""
                {"success":true,"data":{"result":"RETAKE","reason":"UNREADABLE",
                 "recognized":{"stationName":null,"lineName":null,"headsign":null}}}""", JsonCompareMode.LENIENT));
    }

    @Test
    void aiFailuresKeepTheirHttpStatus() throws Exception {
        doThrow(new BusinessException(ErrorCode.AI_QUOTA_EXCEEDED)).when(ai).readSign(any(), any());
        post(image("image/jpeg", JPEG), ROUTE, true).andExpect(status().isTooManyRequests())
                .andExpect(content().json("{\"success\":false,\"error\":{\"code\":\"AI_QUOTA_EXCEEDED\"}}", JsonCompareMode.LENIENT));
        doThrow(new BusinessException(ErrorCode.AI_UNAVAILABLE)).when(ai).readSign(any(), any());
        post(image("image/jpeg", JPEG), ROUTE, true).andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"success\":false,\"error\":{\"code\":\"AI_UNAVAILABLE\"}}", JsonCompareMode.LENIENT));
    }

    @Test
    void badImagesAreImageInvalid400WithoutCallingTheAi() throws Exception {
        for (MockMultipartFile bad : new MockMultipartFile[] {
                null, image("image/jpeg", new byte[0]), image("image/jpeg", "GIF89a....".getBytes(StandardCharsets.UTF_8)),
                image("text/plain", "hello".getBytes(StandardCharsets.UTF_8)),
                image("image/jpeg", SignTestImages.jpegHeaderOnly()), image("image/png", SignTestImages.truncated(SignTestImages.png())),
                image("image/jpeg", SignTestImages.corrupt(JPEG))}) {
            post(bad, ROUTE, true).andExpect(status().isBadRequest())
                    .andExpect(content().json("{\"success\":false,\"error\":{\"code\":\"IMAGE_INVALID\"}}", JsonCompareMode.LENIENT));
        }
        verifyNoInteractions(ai);
    }

    @Test
    void routeProblemsAreValidationError400() throws Exception {
        for (String bad : new String[] {null, "not json", "[]", "null", "{\"stationId\":105,\"lineId\":2,\"headsign\":\"\"}",
                "{\"stationId\":\"x\",\"lineId\":2,\"headsign\":\"사당 방면\"}",
                "{\"stationId\":105,\"lineId\":2,\"direction\":\"SIDEWAYS\",\"headsign\":\"사당 방면\"}"}) {
            post(image("image/jpeg", JPEG), bad, true).andExpect(status().isBadRequest())
                    .andExpect(content().json("{\"success\":false,\"error\":{\"code\":\"VALIDATION_ERROR\"}}", JsonCompareMode.LENIENT));
        }
        verifyNoInteractions(ai);
    }

    @Test
    void unknownStationIs404() throws Exception {
        when(stations.require(999)).thenThrow(new BusinessException(ErrorCode.STATION_NOT_FOUND));
        post(image("image/jpeg", JPEG), "{\"stationId\":999,\"lineId\":2,\"headsign\":\"사당 방면\"}", true)
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"success\":false,\"error\":{\"code\":\"STATION_NOT_FOUND\"}}", JsonCompareMode.LENIENT));
    }

    @Test
    void directionOnlyHasExplicitNullHeadsignAndPhotoRetakeIs200() throws Exception {
        String route = "{\"stationId\":105,\"lineId\":2,\"direction\":\"INNER\"}";
        when(ai.readSign(any(), any())).thenReturn(new SignReading(SignResult.CORRECT, null, "강남", "2호선", "내선순환"));
        post(image("image/jpeg", JPEG), route, true).andExpect(status().isOk()).andExpect(content().json("""
                {"success":true,"data":{"result":"CORRECT","reason":null,
                 "recognized":{"stationName":"강남","lineName":"2호선","headsign":"내선순환"},
                 "expected":{"stationName":"강남","lineName":"2호선","headsign":null},
                 "message":"올바른 방향입니다."}}""", JsonCompareMode.STRICT));
        when(ai.readSign(any(), any())).thenReturn(new SignReading(SignResult.CORRECT, null, "강남", "2호선", "성수 방면"));
        post(image("image/jpeg", JPEG), route, true).andExpect(status().isOk()).andExpect(content().json("""
                {"success":true,"data":{"result":"RETAKE","reason":"MISSING_INFO",
                 "expected":{"stationName":"강남","lineName":"2호선","headsign":null}}}""", JsonCompareMode.LENIENT));
    }

    @Test
    void directionOnlyInvalidBoardingRouteIs400BeforeAi() throws Exception {
        doThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, java.util.Map.of("route", "해당 역·노선에서 이용할 수 없는 방향입니다.")))
                .when(routes).requireDirection(105, 2, DirectionCode.UP);
        for (String route : new String[]{"{\"stationId\":105,\"lineId\":2}",
                "{\"stationId\":105,\"lineId\":2,\"direction\":\"UP\"}",
                "{\"stationId\":105,\"lineId\":2,\"direction\":\"invalid\"}"}) {
            post(image("image/jpeg", JPEG), route, true).andExpect(status().isBadRequest())
                    .andExpect(content().json("{\"success\":false,\"error\":{\"code\":\"VALIDATION_ERROR\"}}", JsonCompareMode.LENIENT));
        }
        verifyNoInteractions(ai);
    }

    @Test
    void missingBothDirectionAndHeadsignHasStableValidationEnvelope() throws Exception {
        post(image("image/jpeg", JPEG), "{\"stationId\":105,\"lineId\":2}", true)
                .andExpect(status().isBadRequest()).andExpect(content().json("""
                {"success":false,"error":{"code":"VALIDATION_ERROR","message":"요청 값이 올바르지 않습니다.",
                 "details":{"direction":"headsign이 없으면 direction은 필수입니다."}}}""", JsonCompareMode.STRICT));
        verifyNoInteractions(ai, routes);
    }

    @Test
    void swaggerPublishesDirectionOnlyResponseAndErrorExamples() throws Exception {
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var operation = tools.jackson.databind.json.JsonMapper.builder().build().readTree(body)
                .path("paths").path(URL).path("post");
        assertThat(operation.path("description").asString()).contains("headsign이 없으면 direction 필수");
        var examples = operation.path("responses").path("200").path("content").path("application/json").path("examples");
        assertThat(examples.path("headsign 없는 정상 판정").path("value").path("data").path("result").asString()).isEqualTo("CORRECT");
        assertThat(examples.path("headsign 없는 정상 판정").path("value").path("data").path("expected").path("headsign").isNull()).isTrue();
        assertThat(examples.path("방향 정보 부족").path("value").path("data").path("reason").asString()).isEqualTo("MISSING_INFO");
        assertThat(operation.path("responses").path("400").path("content").path("application/json").path("examples")
                .path("headsign과 direction 모두 누락").path("value").path("error").path("code").asString()).isEqualTo("VALIDATION_ERROR");
    }
}
