package com.mju.linkro.sign.controller;

import com.mju.linkro.ai.api.AiClient;
import com.mju.linkro.ai.api.SignContext;
import com.mju.linkro.ai.api.SignImage;
import com.mju.linkro.ai.api.SignReading;
import com.mju.linkro.ai.api.SignReason;
import com.mju.linkro.ai.api.SignResult;
import com.mju.linkro.auth.jwt.JwtProvider;
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
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
    static final String ROUTE = "{\"stationId\":105,\"lineId\":2,\"direction\":\"INNER\",\"headsign\":\"사당 방면\"}";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired JwtProvider jwtProvider;
    @MockitoBean AiClient ai;
    @MockitoBean StationLookup stations;
    @MockitoBean LineLookup lines;
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
                image("text/plain", "hello".getBytes(StandardCharsets.UTF_8))}) {
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
}
