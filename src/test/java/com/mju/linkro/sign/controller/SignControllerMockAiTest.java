package com.mju.linkro.sign.controller;

import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.sign.SignTestImages;
import com.mju.linkro.auth.repository.RefreshTokenRepository;
import com.mju.linkro.subway.api.LineLookup;
import com.mju.linkro.subway.api.StationInfo;
import com.mju.linkro.subway.api.StationLookup;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The default (mock) AI module wired for real: with no OpenAI key the endpoint works and echoes the expected route as CORRECT. */
@SpringBootTest
@ActiveProfiles("test")
class SignControllerMockAiTest {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired JwtProvider jwtProvider;
    @MockitoBean StationLookup stations;
    @MockitoBean LineLookup lines;

    @Test
    void mockAiAnswersCorrectEchoingTheExpectedRoute() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        refreshTokens.deleteAll();
        users.deleteAll();
        User user = users.saveAndFlush(new User("123", "현근"));
        when(stations.require(105)).thenReturn(new StationInfo(105, "강남", 37.49, 127.02, true));
        when(lines.name(2)).thenReturn(Optional.of("2호선"));

        mvc.perform(multipart(SignControllerTest.URL)
                        .file(new MockMultipartFile("image", "sign.png", "image/png",
                                SignTestImages.png()))
                        .file(new MockMultipartFile("route", "", "text/plain", SignControllerTest.ROUTE.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtProvider.createAccessToken(user.getId())))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"success":true,"data":{"result":"CORRECT","reason":null,
                         "recognized":{"stationName":"강남","lineName":"2호선","headsign":"사당 방면"},
                         "expected":{"stationName":"강남","lineName":"2호선","headsign":"사당 방면"}}}""", JsonCompareMode.LENIENT));
    }
}
