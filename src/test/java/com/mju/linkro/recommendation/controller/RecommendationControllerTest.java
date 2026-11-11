package com.mju.linkro.recommendation.controller;

import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.auth.repository.RefreshTokenRepository;
import com.mju.linkro.common.exception.BusinessException;
import com.mju.linkro.common.exception.ErrorCode;
import com.mju.linkro.recommendation.dto.RecommendationRequest;
import com.mju.linkro.recommendation.dto.RecommendationResponse;
import com.mju.linkro.recommendation.dto.RecommendationResponse.AppliedConstraints;
import com.mju.linkro.recommendation.service.RecommendationService;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full context (real JWT filter, @CurrentUser, error envelope); the service is a mock. */
@SpringBootTest
@ActiveProfiles("test")
class RecommendationControllerTest {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired JwtProvider jwtProvider;
    @MockitoBean RecommendationService service;
    MockMvc mvc;
    User user;
    UUID roomId = UUID.randomUUID();
    String base;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        refreshTokens.deleteAll();
        users.deleteAll();
        user = users.saveAndFlush(new User("123", "현근"));
        base = "/api/v1/rooms/" + roomId + "/recommendations";
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.createAccessToken(user.getId()));
    }

    private static RecommendationResponse empty(Boolean stale, List<String> reasons) {
        return new RecommendationResponse("0192f3b0-1a2b-7c3d-8e4f-0a1b2c3d4e05", "2026-10-02T18:00:00+09:00", stale, reasons,
                null, AppliedConstraints.none(), "조건에 맞는 중간역이 없습니다. 조건을 완화해 보세요.", List.of());
    }

    @Test
    void allThreeEndpointsRequireAuthentication() throws Exception {
        mvc.perform(post(base)).andExpect(status().isUnauthorized());
        mvc.perform(get(base + "/latest")).andExpect(status().isUnauthorized());
        mvc.perform(get(base + "/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void postReturns200WithTheSpecFieldNames() throws Exception {
        when(service.create(eq(user.getId()), eq(roomId), any())).thenReturn(empty(false, List.of()));
        mvc.perform(auth(post(base)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"conditionText":"40분 안쪽","filter":{"maxFareWon":2000,"excludeStationIds":[201],
                         "memberLimits":[{"userId":"0192f3a4-9d3e-7e5a-9c43-8f5b1c9e4d03","maxMinutes":30}]}}"""))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"success":true,"data":{"runId":"0192f3b0-1a2b-7c3d-8e4f-0a1b2c3d4e05","createdAt":"2026-10-02T18:00:00+09:00","isStale":false,"staleReasons":[],
                         "conditionText":null,"message":"조건에 맞는 중간역이 없습니다. 조건을 완화해 보세요.","results":[],
                         "appliedConstraints":{"maxMinutes":null,"maxTransfers":null,"maxFareWon":null,"excludeStationIds":[],"memberLimits":[]}}}""",
                        JsonCompareMode.STRICT));
        ArgumentCaptor<RecommendationRequest> request = ArgumentCaptor.forClass(RecommendationRequest.class);
        verify(service).create(eq(user.getId()), eq(roomId), request.capture());
        assertThat(request.getValue().conditionText()).isEqualTo("40분 안쪽");
        assertThat(request.getValue().filter().maxFareWon()).isEqualTo(2000);
        assertThat(request.getValue().filter().excludeStationIds()).containsExactly(201L);
        assertThat(request.getValue().filter().memberLimits().get(0).userId()).hasToString("0192f3a4-9d3e-7e5a-9c43-8f5b1c9e4d03");
        assertThat(request.getValue().filter().memberLimits().get(0).maxMinutes()).isEqualTo(30);
    }

    @Test
    void postBodyIsOptional() throws Exception {
        when(service.create(eq(user.getId()), eq(roomId), any())).thenReturn(empty(false, List.of()));
        mvc.perform(auth(post(base))).andExpect(status().isOk());
        verify(service).create(user.getId(), roomId, null);
    }

    @Test
    void departureMissingIsA422WithMissingUserIdsInDetails() throws Exception {
        String missing = "0192f3a4-9d3e-7e5a-9c43-8f5b1c9e4d03";
        when(service.create(any(), any(), any())).thenThrow(
                new BusinessException(ErrorCode.DEPARTURE_MISSING, Map.of("missingUserIds", List.of(missing))));
        mvc.perform(auth(post(base)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().json("{\"success\":false,\"error\":{\"code\":\"DEPARTURE_MISSING\",\"details\":{\"missingUserIds\":[\""
                        + missing + "\"]}}}"));
    }

    @Test
    void tooFewMembersIsA422() throws Exception {
        when(service.create(any(), any(), any())).thenThrow(new BusinessException(ErrorCode.TOO_FEW_MEMBERS));
        mvc.perform(auth(post(base))).andExpect(status().isUnprocessableContent())
                .andExpect(content().json("{\"success\":false,\"error\":{\"code\":\"TOO_FEW_MEMBERS\"}}"));
    }

    @Test
    void noServiceTimeIsA422WithItsMessage() throws Exception {
        when(service.create(any(), any(), any())).thenThrow(new BusinessException(ErrorCode.NO_SERVICE_TIME));
        mvc.perform(auth(post(base))).andExpect(status().isUnprocessableContent())
                .andExpect(content().json("{\"success\":false,\"error\":{\"code\":\"NO_SERVICE_TIME\","
                        + "\"message\":\"해당 시간대에는 지하철이 운행하지 않습니다. 약속 시간을 바꿔 주세요.\"}}"));
    }

    @Test
    void aCalculationInProgressIsA409() throws Exception {
        when(service.create(any(), any(), any())).thenThrow(new BusinessException(ErrorCode.RECOMMENDATION_IN_PROGRESS));
        mvc.perform(auth(post(base))).andExpect(status().isConflict())
                .andExpect(content().json("{\"success\":false,\"error\":{\"code\":\"RECOMMENDATION_IN_PROGRESS\","
                        + "\"message\":\"추천을 계산하는 중입니다. 잠시 후 다시 시도해 주세요.\"}}"));
    }

    @Test
    void aPastAppointmentIsA422WithItsMessage() throws Exception {
        when(service.create(any(), any(), any())).thenThrow(new BusinessException(ErrorCode.APPOINTMENT_PASSED));
        mvc.perform(auth(post(base))).andExpect(status().isUnprocessableContent())
                .andExpect(content().json("{\"success\":false,\"error\":{\"code\":\"APPOINTMENT_PASSED\","
                        + "\"message\":\"약속 시각이 이미 지났습니다. 약속 시간을 다시 설정해 주세요.\"}}"));
    }

    @Test
    void latestWithoutARunIsDataNull() throws Exception {
        when(service.latest(user.getId(), roomId)).thenReturn(null);
        mvc.perform(auth(get(base + "/latest"))).andExpect(status().isOk())
                .andExpect(content().json("{\"success\":true,\"data\":null}", JsonCompareMode.STRICT));
    }

    @Test
    void anOlderRunHasNullStaleFields() throws Exception {
        UUID runId = UUID.randomUUID();
        when(service.byRun(user.getId(), roomId, runId)).thenReturn(empty(null, null));
        mvc.perform(auth(get(base + "/" + runId))).andExpect(status().isOk())
                .andExpect(content().json("{\"success\":true,\"data\":{\"isStale\":null,\"staleReasons\":null}}"));
    }

    @Test
    void unknownRunIsA404() throws Exception {
        when(service.byRun(any(), any(), any())).thenThrow(new BusinessException(ErrorCode.RUN_NOT_FOUND));
        mvc.perform(auth(get(base + "/" + UUID.randomUUID()))).andExpect(status().isNotFound())
                .andExpect(content().json("{\"success\":false,\"error\":{\"code\":\"RUN_NOT_FOUND\"}}"));
    }

    @Test
    void aRunIdThatIsNotAUuidIsABadRequest() throws Exception {
        mvc.perform(auth(get(base + "/not-a-uuid"))).andExpect(status().isBadRequest());
    }
}
