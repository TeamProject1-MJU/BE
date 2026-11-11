package com.mju.linkro.room;

import com.mju.linkro.auth.jwt.JwtProvider;
import com.mju.linkro.common.exception.*;
import com.mju.linkro.room.domain.*;
import com.mju.linkro.room.dto.StationProposalResponses.*;
import com.mju.linkro.room.repository.*;
import com.mju.linkro.room.service.RoomAccess;
import com.mju.linkro.subway.api.StationLookup;
import com.mju.linkro.user.domain.User;
import com.mju.linkro.user.repository.UserRepository;
import java.time.Instant;
import java.util.*;
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
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="linkro.room.max-station-proposals=3")
@ActiveProfiles("test")
class StationProposalHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired JwtProvider jwt;
    @Autowired UserRepository users;
    @MockitoBean RoomAccess access;
    @MockitoBean StationLookup stations;
    @MockitoBean com.mju.linkro.chat.service.ChatSystemMessages systemMessages;
    @MockitoBean StationProposalRepository proposals;
    @MockitoBean MeetingMemberRepository members;
    MockMvc mvc;
    UUID user,room,id,result;
    String path;
    @BeforeEach void setup() {
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        user=users.saveAndFlush(new User(UUID.randomUUID().toString(),"멤버")).getId();
        room=UUID.randomUUID(); id=UUID.randomUUID(); result=UUID.randomUUID();
        path="/api/v1/rooms/"+room+"/station-proposals";
        var entity=new MeetingRoom("H12345","room",user);
        when(access.lockMember(room,user)).thenReturn(entity); when(access.requireMember(room,user)).thenReturn(entity);
        when(proposals.insert(any(),eq(room),eq(112L),eq(user),nullable(UUID.class))).thenAnswer(invocation ->
            new Created(id,new Station(112,"역"),new Author(user,"멤버"),invocation.getArgument(4),Instant.EPOCH));
        when(proposals.resultBelongsToRoom(result,room)).thenReturn(true);
        when(proposals.author(room,id)).thenReturn(Optional.of(user));
        when(proposals.stationName(room,id)).thenReturn(Optional.of("역"));
        when(proposals.list(room,user,null)).thenReturn(List.of());
    }
    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization","Bearer "+jwt.createAccessToken(user));
    }
    @Test void voteFirstIdempotentConflictAndErrorEnvelopes() throws Exception {
        when(proposals.voteByUser(room,user)).thenReturn(Optional.empty());
        when(proposals.voteState(room,id,user)).thenReturn(new Vote(id,1,true));
        mvc.perform(auth(put(path+"/"+id+"/vote"))).andExpect(status().isOk())
            .andExpect(content().json("{\"success\":true,\"data\":{\"proposalId\":\""+id+"\",\"voteCount\":1,\"votedByMe\":true}}",org.springframework.test.json.JsonCompareMode.STRICT));
        when(proposals.voteByUser(room,user)).thenReturn(Optional.of(id));
        mvc.perform(auth(put(path+"/"+id+"/vote"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.voteCount").value(1)).andExpect(jsonPath("$.data.votedByMe").value(true));
        verify(proposals,times(1)).insertVote(any(),eq(room),eq(id),eq(user));
        when(proposals.voteByUser(room,user)).thenReturn(Optional.of(UUID.randomUUID()));
        failure(put(path+"/"+id+"/vote"),ErrorCode.ALREADY_VOTED);
        when(proposals.author(room,id)).thenReturn(Optional.empty());
        failure(put(path+"/"+id+"/vote"),ErrorCode.PROPOSAL_NOT_FOUND);
        for(var code:List.of(ErrorCode.ROOM_NOT_FOUND,ErrorCode.NOT_ROOM_MEMBER,ErrorCode.ROOM_BUSY)) {
            doThrow(new BusinessException(code)).when(access).lockMember(room,user);
            failure(put(path+"/"+id+"/vote"),code);
        }
        mvc.perform(put(path+"/"+id+"/vote")).andExpect(status().isUnauthorized());
        failure(put("/api/v1/rooms/bad/station-proposals/"+id+"/vote"),ErrorCode.VALIDATION_ERROR);
    }

    @Test void directResultListAndDeleteEnvelopes() throws Exception {
        for(String suffix:List.of("",",\"resultId\":\""+result+"\"")) {
            mvc.perform(auth(post(path).contentType("application/json").content("{\"stationId\":112"+suffix+"}")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(id.toString())).andExpect(jsonPath("$.data.station.id").value(112))
                .andExpect(jsonPath("$.data.proposedBy.userId").value(user.toString()))
                .andExpect(jsonPath("$.data.createdAt").value("1970-01-01T00:00:00Z"));
        }
        mvc.perform(auth(get(path))).andExpect(status().isOk())
            .andExpect(content().json("{\"success\":true,\"data\":{\"selectedStation\":null,\"maxProposals\":3,\"items\":[]}}",org.springframework.test.json.JsonCompareMode.STRICT));
        when(proposals.list(room,user,null)).thenReturn(List.of(new Item(id,new Station(112,"역"),new Author(user,"멤버"),null,false,2,true,Instant.EPOCH)));
        mvc.perform(auth(get(path))).andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].isSelected").value(false))
            .andExpect(jsonPath("$.data.items[0].voteCount").value(2)).andExpect(jsonPath("$.data.items[0].votedByMe").value(true));
        mvc.perform(auth(delete(path+"/"+id))).andExpect(status().isOk())
            .andExpect(content().json("{\"success\":true,\"data\":null}",org.springframework.test.json.JsonCompareMode.STRICT));
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"stationId\":null}","{\"stationId\":\"112\"}","{\"stationId\":1.5}","{\"stationId\":112,\"resultId\":\"bad\"}"})
    void validationEnvelope(String body) throws Exception {
        mvc.perform(auth(post(path).contentType("application/json").content(body))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.error.details").isMap()).andExpect(jsonPath("$.data").doesNotExist());
        verify(access).lockMember(room,user); verifyNoInteractions(stations,proposals);
    }
    @Test void allBusinessErrorsHaveCommonEnvelope() throws Exception {
        for(var code:List.of(ErrorCode.ROOM_NOT_FOUND,ErrorCode.NOT_ROOM_MEMBER,ErrorCode.ROOM_BUSY)) {
            doThrow(new BusinessException(code)).when(access).lockMember(room,user);
            failure(post(path).contentType("application/json").content("{}"),code);
            failure(delete(path+"/"+id),code);
        }
        reset(access);
        for(var code:List.of(ErrorCode.STATION_NOT_FOUND,ErrorCode.STATION_UNSUPPORTED)) {
            doThrow(new BusinessException(code)).when(stations).requireSupported(112);
            failure(post(path).contentType("application/json").content("{\"stationId\":112}"),code);
        }
        reset(stations);
        failure(post(path).contentType("application/json").content("{\"stationId\":112,\"resultId\":\""+UUID.randomUUID()+"\"}"),ErrorCode.RESULT_NOT_FOUND);
        when(proposals.exists(room,112)).thenReturn(true);
        failure(post(path).contentType("application/json").content("{\"stationId\":112}"),ErrorCode.ALREADY_PROPOSED);
        when(proposals.exists(room,112)).thenReturn(false); when(proposals.count(room)).thenReturn(3L);
        failure(post(path).contentType("application/json").content("{\"stationId\":112}"),ErrorCode.PROPOSAL_LIMIT_EXCEEDED);
        when(proposals.author(room,id)).thenReturn(Optional.empty());
        failure(delete(path+"/"+id),ErrorCode.PROPOSAL_NOT_FOUND);
        when(proposals.author(room,id)).thenReturn(Optional.of(UUID.randomUUID()));
        when(members.findCurrentRole(room,user)).thenReturn(Optional.of(RoomRole.MEMBER));
        failure(delete(path+"/"+id),ErrorCode.NOT_PROPOSAL_OWNER);
        when(members.findCurrentRole(room,user)).thenReturn(Optional.of(RoomRole.OWNER));
        mvc.perform(auth(delete(path+"/"+id))).andExpect(status().isOk());
    }
    void failure(MockHttpServletRequestBuilder request,ErrorCode code) throws Exception {
        mvc.perform(auth(request)).andExpect(status().is(code.getStatus().value()))
            .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.error.code").value(code.name()))
            .andExpect(jsonPath("$.error.message").isString()).andExpect(jsonPath("$.error.details").isMap())
            .andExpect(jsonPath("$.data").doesNotExist());
    }
    @Test void unauthorizedMalformedAndMissingBodies() throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        for(String body:List.of("","null","{","[]"))
            failure(post(path).contentType("application/json").content(body),ErrorCode.VALIDATION_ERROR);
        failure(delete("/api/v1/rooms/bad/station-proposals/"+id),ErrorCode.VALIDATION_ERROR);
        verifyNoInteractions(access,stations,proposals);
    }
    @Test void openApiDocumentsRequestsEnvelopesNullableSelectionAndErrorExamples() throws Exception {
        var body=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var root=tools.jackson.databind.json.JsonMapper.builder().build().readTree(body);
        var paths=root.at("/paths/~1api~1v1~1rooms~1{roomId}~1station-proposals");
        assertThat(paths.at("/post/responses/201/content/application~1json/schema/$ref").asString()).endsWith("CreatedEnvelope");
        assertThat(paths.at("/post/requestBody/required").asBoolean()).isTrue();
        assertThat(root.at("/components/schemas/StationProposalRequest/properties/stationId/type").asString()).isEqualTo("integer");
        assertThat(root.at("/components/schemas/StationProposalListing/properties/selectedStation/anyOf/1/type").asString()).isEqualTo("null");
        for(var code:List.of(ErrorCode.ROOM_NOT_FOUND,ErrorCode.NOT_ROOM_MEMBER,ErrorCode.ROOM_BUSY,ErrorCode.STATION_NOT_FOUND,
                ErrorCode.STATION_UNSUPPORTED,ErrorCode.RESULT_NOT_FOUND,ErrorCode.ALREADY_PROPOSED,ErrorCode.PROPOSAL_LIMIT_EXCEEDED))
            assertThat(paths.at("/post/responses/"+code.getStatus().value()+"/content/application~1json/examples/"+code.name()+"/value/error/code").asString()).isEqualTo(code.name());
        assertThat(paths.at("/get/security/0/bearerAuth").isArray()).isTrue();
        assertThat(paths.at("/get/responses/404/description").asString()).isEqualTo("ROOM_NOT_FOUND");
        var deletion=root.at("/paths/~1api~1v1~1rooms~1{roomId}~1station-proposals~1{proposalId}/delete");
        assertThat(deletion.at("/responses/200/content/application~1json/example/data").isNull()).isTrue();
        assertThat(deletion.at("/responses/403/content/application~1json/examples/NOT_PROPOSAL_OWNER/value/error/code").asString()).isEqualTo("NOT_PROPOSAL_OWNER");
        var vote=root.at("/paths/~1api~1v1~1rooms~1{roomId}~1station-proposals~1{proposalId}~1vote/put");
        assertThat(vote.has("requestBody")).isFalse();
        assertThat(vote.at("/responses/200/content/application~1json/schema/$ref").asString()).endsWith("VoteEnvelope");
        for(var code:List.of(ErrorCode.ROOM_NOT_FOUND,ErrorCode.PROPOSAL_NOT_FOUND,ErrorCode.NOT_ROOM_MEMBER,
                ErrorCode.ROOM_BUSY,ErrorCode.ALREADY_VOTED))
            assertThat(vote.at("/responses/"+code.getStatus().value()+"/content/application~1json/examples/"+code.name()+"/value/error/code").asString()).isEqualTo(code.name());
    }
}
