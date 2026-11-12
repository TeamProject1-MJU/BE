package com.mju.linkro.room;

import com.mju.linkro.common.exception.*;
import com.mju.linkro.room.domain.*;
import com.mju.linkro.room.dto.SelectedStationRequest;
import com.mju.linkro.room.service.*;
import com.mju.linkro.subway.api.StationInfo;
import com.mju.linkro.subway.api.StationLookup;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SelectedStationServiceTest {
    final RoomAccess access = mock(RoomAccess.class);
    final StationLookup stations = mock(StationLookup.class);
    final com.mju.linkro.chat.service.ChatSystemMessages systemMessages = mock(com.mju.linkro.chat.service.ChatSystemMessages.class);
    final com.mju.linkro.room.repository.RoomReadRepository reads = mock(com.mju.linkro.room.repository.RoomReadRepository.class);
    final com.mju.linkro.realtime.RoomEvents events = mock(com.mju.linkro.realtime.RoomEvents.class);
    final SelectedStationService service = new SelectedStationService(access, stations, systemMessages, reads, events);
    final UUID id = UUID.randomUUID(), user = UUID.randomUUID();
    final MeetingRoom room = spy(new MeetingRoom("ABC123", "room", user));
    final JsonMapper mapper = JsonMapper.builder().build();
    SelectedStationRequest request(String body) { return mapper.readValue(body, SelectedStationRequest.class); }

    @Test void clearingReturnsChangeOnlyOnceAndAlwaysChecksOwner() {
        when(access.lockOwner(id,user)).thenReturn(room);
        room.confirmStation(112L,Instant.EPOCH);
        assertThat(service.clear(id,user)).isTrue();
        assertThat(service.clear(id,user)).isFalse();
        assertThat(service.clear(id,user)).isFalse();
        verify(access,times(3)).lockOwner(id,user);
        verify(room,times(1)).clearSelectedStation();
        assertThat(room.getStatus()).isEqualTo(RoomStatus.OPEN);
        assertThat(room.getSelectedStationId()).isNull();
        assertThat(room.getConfirmedAt()).isNull();
        verifyNoInteractions(stations);
        verify(systemMessages,times(1)).stationCleared(id,user);
        // Only the real clear is announced, once, with both fields null.
        verify(events,times(1)).publish(id,com.mju.linkro.realtime.RoomEventType.SELECTED_STATION_CHANGED,
            new com.mju.linkro.realtime.RoomEventPayloads.SelectedStationChanged(null,null));
        verifyNoMoreInteractions(events);
    }

    @Test void everySelectionIsAnnouncedWithTheStationAndConfirmedAtAfterTheSystemMessage() {
        when(access.lockOwner(id,user)).thenReturn(room);
        when(stations.requireSupported(112L)).thenReturn(new StationInfo(112L,"사당",0,0,true));
        when(stations.requireSupported(113L)).thenReturn(new StationInfo(113L,"강남",0,0,true));
        var sadang=new com.mju.linkro.room.dto.RoomResponses.Station(112L,"사당",true,java.util.List.of());
        var gangnam=new com.mju.linkro.room.dto.RoomResponses.Station(113L,"강남",true,java.util.List.of());
        when(reads.stations(java.util.List.of(112L))).thenReturn(java.util.Map.of(112L,sadang));
        when(reads.stations(java.util.List.of(113L))).thenReturn(java.util.Map.of(113L,gangnam));
        var captor=org.mockito.ArgumentCaptor.forClass(Object.class);
        var type=com.mju.linkro.realtime.RoomEventType.SELECTED_STATION_CHANGED;

        var order=inOrder(systemMessages,events);
        service.select(id,user,request("{\"stationId\":112}"));   // set
        service.select(id,user,request("{\"stationId\":112}"));   // same station again
        service.select(id,user,request("{\"stationId\":113}"));   // changed

        order.verify(systemMessages).stationSelected(id,user,"사당",false);
        order.verify(events,times(2)).publish(eq(id),eq(type),captor.capture());          // same station: event, no SYSTEM message
        order.verify(systemMessages).stationSelected(id,user,"강남",true);
        order.verify(events).publish(eq(id),eq(type),captor.capture());
        verify(systemMessages,times(2)).stationSelected(any(),any(),any(),anyBoolean());
        var sent=captor.getAllValues().stream().map(v->(com.mju.linkro.realtime.RoomEventPayloads.SelectedStationChanged)v).toList();
        assertThat(sent).extracting(v->v.station()).containsExactly(sadang,sadang,gangnam);
        assertThat(sent.get(1).confirmedAt()).isAfterOrEqualTo(sent.get(0).confirmedAt());
        assertThat(sent.get(2).confirmedAt()).isEqualTo(room.getConfirmedAt());
        assertThat(sent).allSatisfy(v->assertThat(v.confirmedAt().getNano()%1000).as("whole microseconds").isZero());
    }

    @Test void aRefusedSelectionOrClearSendsNoEvent() {
        when(access.lockOwner(id,user)).thenReturn(room);
        when(stations.requireSupported(112L)).thenThrow(new BusinessException(ErrorCode.STATION_UNSUPPORTED));
        assertThatThrownBy(()->service.select(id,user,request("{\"stationId\":112}"))).isInstanceOf(BusinessException.class);
        assertThat(service.clear(id,user)).isFalse();
        verifyNoInteractions(events);
    }

    @Test void locksValidatesThenConfirmsAndReconfirmsSameStation() {
        when(access.lockOwner(id,user)).thenReturn(room);
        room.confirmStation(112L,Instant.EPOCH);
        clearInvocations(room);
        service.select(id,user,request("{\"stationId\":112}"));
        var order=inOrder(access,stations,room);
        order.verify(access).lockOwner(id,user);
        order.verify(stations).requireSupported(112L);
        order.verify(room).confirmStation(eq(112L),any(Instant.class));
        assertThat(room.getSelectedStationId()).isEqualTo(112L);
        assertThat(room.getConfirmedAt()).isAfter(Instant.EPOCH);
        assertThat(room.getStatus()).isEqualTo(RoomStatus.CONFIRMED);
        verifyNoInteractions(systemMessages);
    }

    @Test void systemMessageSaysSetThenChanged() {
        when(access.lockOwner(id,user)).thenReturn(room);
        when(stations.requireSupported(112L)).thenReturn(new StationInfo(112L,"사당",0,0,true));
        when(stations.requireSupported(113L)).thenReturn(new StationInfo(113L,"강남",0,0,true));
        service.select(id,user,request("{\"stationId\":112}"));
        service.select(id,user,request("{\"stationId\":113}"));
        var order=inOrder(systemMessages);
        order.verify(systemMessages).stationSelected(id,user,"사당",false);
        order.verify(systemMessages).stationSelected(id,user,"강남",true);
    }

    @ParameterizedTest @ValueSource(strings={"{}","{\"stationId\":null}","{\"stationId\":\"112\"}","{\"stationId\":1.0}",
            "{\"stationId\":true}","{\"stationId\":[]}","{\"stationId\":{}}","{\"stationId\":9223372036854775808}"})
    void invalidValuesAreRejectedOnlyAfterOwnerLock(String body) {
        when(access.lockOwner(id,user)).thenReturn(room);
        assertThatThrownBy(()->service.select(id,user,request(body))).isInstanceOf(BusinessException.class)
                .extracting(e->((BusinessException)e).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        verify(access).lockOwner(id,user);
        verifyNoInteractions(stations,room);
    }

    @ParameterizedTest @ValueSource(strings={"NOT_ROOM_MEMBER","NOT_ROOM_OWNER","ROOM_NOT_FOUND","ROOM_BUSY"})
    void accessFailurePrecedesInputValidation(String code) {
        when(access.lockOwner(id,user)).thenThrow(new BusinessException(ErrorCode.valueOf(code)));
        assertThatThrownBy(()->service.select(id,user,request("{}"))).isInstanceOf(BusinessException.class)
                .extracting(e->((BusinessException)e).getErrorCode()).isEqualTo(ErrorCode.valueOf(code));
        verifyNoInteractions(stations,room);
    }

    @ParameterizedTest @ValueSource(strings={"STATION_NOT_FOUND","STATION_UNSUPPORTED"})
    void stationFailuresDoNotChangeRoom(String code) {
        when(access.lockOwner(id,user)).thenReturn(room);
        when(stations.requireSupported(112L)).thenThrow(new BusinessException(ErrorCode.valueOf(code)));
        assertThatThrownBy(()->service.select(id,user,request("{\"stationId\":112}"))).isInstanceOf(BusinessException.class)
                .extracting(e->((BusinessException)e).getErrorCode()).isEqualTo(ErrorCode.valueOf(code));
        verifyNoInteractions(room);
    }
}
