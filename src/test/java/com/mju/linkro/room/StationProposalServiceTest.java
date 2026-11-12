package com.mju.linkro.room;

import com.mju.linkro.common.exception.*;
import com.mju.linkro.room.domain.*;
import com.mju.linkro.room.dto.*;
import com.mju.linkro.room.dto.StationProposalResponses.*;
import com.mju.linkro.room.repository.*;
import com.mju.linkro.room.service.*;
import com.mju.linkro.subway.api.StationLookup;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StationProposalServiceTest {
    final RoomAccess access = mock(RoomAccess.class);
    final StationLookup stations = mock(StationLookup.class);
    final StationProposalRepository proposals = mock(StationProposalRepository.class);
    final MeetingMemberRepository members = mock(MeetingMemberRepository.class);
    final RoomReadRepository reads = mock(RoomReadRepository.class);
    final RoomProperties properties = new RoomProperties();
    final com.mju.linkro.chat.service.ChatSystemMessages systemMessages = mock(com.mju.linkro.chat.service.ChatSystemMessages.class);
    final StationProposalService service = new StationProposalService(access, stations, proposals, members, reads, properties, systemMessages);
    final UUID room = UUID.randomUUID(), user = UUID.randomUUID(), result = UUID.randomUUID(), id = UUID.randomUUID();
    StationProposalRequest request(String body) { return JsonMapper.builder().build().readValue(body, StationProposalRequest.class); }
    void error(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException)e).getErrorCode()).isEqualTo(code);
    }

    @Test void directAndResultCreationFollowValidationOrder() {
        var created = new Created(id, new Station(112,"역"), new Author(user,"멤버"), result, Instant.EPOCH);
        when(proposals.resultBelongsToRoom(result,room)).thenReturn(true);
        when(proposals.insert(any(),eq(room),eq(112L),eq(user),nullable(UUID.class))).thenReturn(created);
        assertThat(service.create(room,user,request("{\"stationId\":112}"))).isEqualTo(created);
        verify(systemMessages).proposalCreated(room,"멤버","역");
        service.create(room,user,request("{\"stationId\":112,\"resultId\":\""+result+"\"}"));
        var order=inOrder(access,stations,proposals);
        order.verify(access).lockMember(room,user);
        order.verify(stations).requireSupported(112);
        order.verify(proposals).exists(room,112);
        order.verify(proposals).count(room);
        order.verify(proposals).insert(any(),eq(room),eq(112L),eq(user),isNull());
        order.verify(access).lockMember(room,user);
        order.verify(stations).requireSupported(112);
        order.verify(proposals).resultBelongsToRoom(result,room);
        order.verify(proposals).exists(room,112);
    }

    @ParameterizedTest @ValueSource(strings={"{}","{\"stationId\":null}","{\"stationId\":\"112\"}","{\"stationId\":1.0}","{\"stationId\":true}","{\"stationId\":[]}","{\"stationId\":{}}","{\"stationId\":9223372036854775808}","{\"stationId\":112,\"resultId\":1}","{\"stationId\":112,\"resultId\":\"1-1-1-1-1\"}"})
    void rejectsInvalidFieldsAfterLock(String body) {
        error(()->service.create(room,user,request(body)),ErrorCode.VALIDATION_ERROR);
        verify(access).lockMember(room,user); verifyNoInteractions(stations,proposals);
    }

    @Test void resultThenDuplicateThenConfiguredLimit() {
        var body=request("{\"stationId\":112,\"resultId\":\""+result+"\"}");
        error(()->service.create(room,user,body),ErrorCode.RESULT_NOT_FOUND);
        when(proposals.resultBelongsToRoom(result,room)).thenReturn(true);
        when(proposals.exists(room,112)).thenReturn(true);
        when(proposals.count(room)).thenReturn(9L);
        error(()->service.create(room,user,body),ErrorCode.ALREADY_PROPOSED);
        when(proposals.exists(room,112)).thenReturn(false);
        properties.setMaxStationProposals(2); when(proposals.count(room)).thenReturn(2L);
        error(()->service.create(room,user,body),ErrorCode.PROPOSAL_LIMIT_EXCEEDED);
        verify(proposals,never()).insert(any(),any(),anyLong(),any(),any());
    }

    @Test void onlyExactUniqueConstraintAndSqlStateAreTranslated() {
        for(String state:List.of("23505","23503")) for(String constraint:List.of("uq_proposal_room_station","other_constraint")) {
            var failure=new DataIntegrityViolationException("insert",new SQLException("duplicate key violates unique constraint \""+constraint+"\"",state));
            doThrow(failure).when(proposals).insert(any(),any(),anyLong(),any(),isNull());
            if(state.equals("23505") && constraint.equals("uq_proposal_room_station"))
                error(()->service.create(room,user,request("{\"stationId\":112}")),ErrorCode.ALREADY_PROPOSED);
            else assertThatThrownBy(()->service.create(room,user,request("{\"stationId\":112}"))).isSameAs(failure);
        }
    }

    @Test void deleteChecksScopedExistenceAndCurrentOwnerOrAuthor() {
        error(()->service.delete(room,user,id),ErrorCode.PROPOSAL_NOT_FOUND);
        when(proposals.author(room,id)).thenReturn(Optional.of(UUID.randomUUID()));
        when(members.findCurrentRole(room,user)).thenReturn(Optional.of(RoomRole.MEMBER));
        error(()->service.delete(room,user,id),ErrorCode.NOT_PROPOSAL_OWNER);
        when(members.findCurrentRole(room,user)).thenReturn(Optional.of(RoomRole.OWNER));
        when(proposals.stationName(room,id)).thenReturn(Optional.of("사당"));
        service.delete(room,user,id);
        when(proposals.author(room,id)).thenReturn(Optional.of(user));
        service.delete(room,user,id);
        verify(proposals,times(2)).delete(room,id);
        verify(systemMessages,times(2)).proposalDeleted(room,user,"사당");
        verify(access,times(4)).lockMember(room,user);
    }

    @Test void listUsesMemberSnapshotAndSameConfiguredLimit() {
        var entity=new MeetingRoom("ABC123","room",user); entity.confirmStation(112L,Instant.EPOCH);
        var selected=new RoomResponses.Station(112,"역",true,List.of());
        when(access.requireMember(room,user)).thenReturn(entity);
        when(reads.stations(List.of(112L))).thenReturn(Map.of(112L,selected));
        when(proposals.list(room,user,112L)).thenReturn(List.of()); properties.setMaxStationProposals(3);
        assertThat(service.list(room,user)).isEqualTo(new Listing(selected,3,List.of()));
    }
}
