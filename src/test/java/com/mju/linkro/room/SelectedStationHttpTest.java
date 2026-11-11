package com.mju.linkro.room;

import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.common.exception.*;
import com.mju.linkro.room.domain.*;
import com.mju.linkro.room.service.RoomAccess;
import com.mju.linkro.subway.api.StationLookup;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC, JWT/current-user resolution and service; H2 users, mocked room lock and station adapters. */
@SpringBootTest
@ActiveProfiles("test")
class SelectedStationHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired JwtProvider jwt;
    @Autowired UserRepository users;
    @MockitoBean RoomAccess access;
    @MockitoBean StationLookup stations;
    MockMvc mvc;
    UUID user,id;
    String path;
    @BeforeEach void setup() {
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        user=users.saveAndFlush(new User(UUID.randomUUID().toString(),"owner")).getId();
        id=UUID.randomUUID(); path="/api/v1/rooms/"+id+"/selected-station";
        when(access.lockOwner(id,user)).thenReturn(new MeetingRoom("H12345","room",user));
    }
    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization","Bearer "+jwt.createAccessToken(user));
    }
    @Test void bothEndpointsReturnExactSupplementalSuccessEnvelopeAndUseCurrentUser() throws Exception {
        mvc.perform(auth(put(path).contentType("application/json").content("{\"stationId\":112}")))
            .andExpect(status().isOk()).andExpect(content().json("{\"success\":true,\"data\":null}",org.springframework.test.json.JsonCompareMode.STRICT));
        // A clean transient room models the already unconfirmed DELETE path.
        when(access.lockOwner(id,user)).thenReturn(new MeetingRoom("H12345","room",user));
        for(int i=0;i<2;i++) mvc.perform(auth(delete(path)))
            .andExpect(status().isOk()).andExpect(content().json("{\"success\":true,\"data\":null}",org.springframework.test.json.JsonCompareMode.STRICT));
        verify(access,times(3)).lockOwner(id,user);
        verify(stations).requireSupported(112L);
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"stationId\":null}","{\"stationId\":\"112\"}","{\"stationId\":1.0}","{\"stationId\":true}","{\"stationId\":[]}","{\"stationId\":{}}","{\"stationId\":9223372036854775808}"})
    void invalidFieldsReturnValidationAfterOwnerCheck(String body) throws Exception {
        mvc.perform(auth(put(path).contentType("application/json").content(body))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.error.details.stationId").exists());
        verify(access).lockOwner(id,user); verifyNoInteractions(stations);
    }
    @ParameterizedTest @ValueSource(strings={"PUT","DELETE"})
    void authenticationAndAccessErrorsArePreserved(String method) throws Exception {
        mvc.perform(method.equals("PUT")?put(path):delete(path)).andExpect(status().isUnauthorized());
        for(var code:new ErrorCode[]{ErrorCode.ROOM_NOT_FOUND,ErrorCode.NOT_ROOM_MEMBER,ErrorCode.NOT_ROOM_OWNER,ErrorCode.ROOM_BUSY}) {
            doThrow(new BusinessException(code)).when(access).lockOwner(id,user);
            var request=method.equals("PUT")?put(path).contentType("application/json").content("{}"):delete(path);
            mvc.perform(auth(request)).andExpect(status().is(code.getStatus().value()))
                .andExpect(jsonPath("$.error.code").value(code.name()));
        }
        verifyNoInteractions(stations);
    }
    @ParameterizedTest @ValueSource(strings={"STATION_NOT_FOUND","STATION_UNSUPPORTED"})
    void stationErrorsReachHttp(String name) throws Exception {
        var code=ErrorCode.valueOf(name);
        when(stations.requireSupported(112L)).thenThrow(new BusinessException(code));
        mvc.perform(auth(put(path).contentType("application/json").content("{\"stationId\":112}")))
            .andExpect(status().is(code.getStatus().value())).andExpect(jsonPath("$.error.code").value(name));
    }
    @ParameterizedTest @ValueSource(strings={"", "null", "{", "[]"})
    void malformedOrMissingBodyIsRejectedBeforeService(String body) throws Exception {
        mvc.perform(auth(put(path).contentType("application/json").content(body))).andExpect(status().isBadRequest());
        verifyNoInteractions(access,stations);
    }
    @Test void invalidUuidNeverEntersService() throws Exception {
        mvc.perform(auth(delete("/api/v1/rooms/not-a-uuid/selected-station"))).andExpect(status().isBadRequest());
        verifyNoInteractions(access,stations);
    }
    @Test void openApiDescribesActualRequestResponseSecurityAndErrors() throws Exception {
        var json=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var root=JsonMapper.builder().build().readTree(json);
        var operations=root.at("/paths/~1api~1v1~1rooms~1{roomId}~1selected-station");
        for(String method:new String[]{"put","delete"}) {
            var op=operations.get(method);
            assertThat(op.at("/security/0/bearerAuth").isArray()).isTrue();
            assertThat(op.get("parameters").size()).isEqualTo(1);
            assertThat(op.at("/parameters/0/name").asString()).isEqualTo("roomId");
            assertThat(op.at("/parameters/0/in").asString()).isEqualTo("path");
            assertThat(op.at("/parameters/0/required").asBoolean()).isTrue();
            assertThat(op.at("/parameters/0/schema/format").asString()).isEqualTo("uuid");
            assertThat(op.at("/responses/200/description").asString()).contains("보충 계약");
            assertThat(op.at("/responses/200/content/application~1json/schema/$ref").asString()).endsWith("ApiEmptySuccessResponse");
            assertThat(op.at("/responses/200/content/application~1json/example/success").asBoolean()).isTrue();
            assertThat(op.at("/responses/200/content/application~1json/example/data").isNull()).isTrue();
            for(String code:new String[]{"VALIDATION_ERROR","AUTH_REQUIRED","TOKEN_INVALID","TOKEN_EXPIRED","NOT_ROOM_MEMBER","NOT_ROOM_OWNER","ROOM_NOT_FOUND","ROOM_BUSY","INTERNAL_ERROR"}) {
                String status=Integer.toString(ErrorCode.valueOf(code).getStatus().value());
                assertThat(op.at("/responses/"+status+"/content/application~1json/examples/"+code+"/value/error/code").asString()).isEqualTo(code);
            }
        }
        var put=operations.get("put");
        assertThat(put.at("/requestBody/required").asBoolean()).isTrue();
        assertThat(put.at("/requestBody/content/application~1json/schema/$ref").asString()).endsWith("SelectedStationRequest");
        assertThat(put.at("/requestBody/content/application~1json/example/stationId").asLong()).isEqualTo(112L);
        var schema=root.at("/components/schemas/SelectedStationRequest");
        assertThat(schema.get("required").toString()).contains("stationId");
        assertThat(schema.at("/properties/stationId/type").asString()).isEqualTo("integer");
        assertThat(schema.at("/properties/stationId/format").asString()).isEqualTo("int64");
        assertThat(operations.at("/delete/description").asString()).contains("보충 계약","이미 미확정");
        for(String code:new String[]{"STATION_NOT_FOUND","STATION_UNSUPPORTED"})
            assertThat(put.at("/responses/"+ErrorCode.valueOf(code).getStatus().value()+"/content/application~1json/examples/"+code+"/value/error/code").asString()).isEqualTo(code);
    }
}
