package com.mju.linkro.room;

import com.mju.linkro.realtime.RoomEvents;
import com.mju.linkro.realtime.RoomEventType;
import com.mju.linkro.realtime.RoomEventPayloads.SelectedStationChanged;
import com.mju.linkro.room.repository.RoomReadRepository;
import com.mju.linkro.room.dto.RoomResponses.Station;
import java.util.List;
import java.util.Map;

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
    final RoomReadRepository reads = mock(RoomReadRepository.class);
    final RoomEvents events = mock(RoomEvents.class);
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
        var order = inOrder(systemMessages,events);
        order.verify(systemMessages).stationCleared(id,user);
        order.verify(events).publish(id, RoomEventType.SELECTED_STATION_CHANGED,
            new SelectedStationChanged(null,null));
        verifyNoMoreInteractions(events);
    }

    @Test void locksValidatesThenConfirmsAndReconfirmsSameStation() {
        when(access.lockOwner(id,user)).thenReturn(room);
        room.confirmStation(112L,Instant.EPOCH);
        clearInvocations(room);
        when(stations.requireSupported(112L)).thenReturn(new StationInfo(112L,"station",0,0,true));
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
        verifyNoInteractions(stations,room,events);
    }

    @ParameterizedTest @ValueSource(strings={"NOT_ROOM_MEMBER","NOT_ROOM_OWNER","ROOM_NOT_FOUND","ROOM_BUSY"})
    void accessFailurePrecedesInputValidation(String code) {
        when(access.lockOwner(id,user)).thenThrow(new BusinessException(ErrorCode.valueOf(code)));
        assertThatThrownBy(()->service.select(id,user,request("{}"))).isInstanceOf(BusinessException.class)
                .extracting(e->((BusinessException)e).getErrorCode()).isEqualTo(ErrorCode.valueOf(code));
        verifyNoInteractions(stations,room,events);
    }

    @ParameterizedTest @ValueSource(strings={"STATION_NOT_FOUND","STATION_UNSUPPORTED"})
    void stationFailuresDoNotChangeRoom(String code) {
        when(access.lockOwner(id,user)).thenReturn(room);
        when(stations.requireSupported(112L)).thenThrow(new BusinessException(ErrorCode.valueOf(code)));
        assertThatThrownBy(()->service.select(id,user,request("{\"stationId\":112}"))).isInstanceOf(BusinessException.class)
                .extracting(e->((BusinessException)e).getErrorCode()).isEqualTo(ErrorCode.valueOf(code));
        verifyNoInteractions(room,events);
    }
    @Test void selectionPayloadAndSystemOrderingIncludingReselection() {
        when(access.lockOwner(id,user)).thenReturn(room);
        var station = new Station(112L,"station",true,List.of());
        when(stations.requireSupported(anyLong())).thenAnswer(i -> new StationInfo(i.getArgument(0),"station",0,0,true));
        var changed = new Station(113L,"changed",true,List.of());
        when(reads.stations(List.of(112L))).thenReturn(Map.of(112L,station));
        when(reads.stations(List.of(113L))).thenReturn(Map.of(113L,changed));
        var order = inOrder(systemMessages,events);
        for (long stationId : new long[]{112,112,113}) {
            service.select(id,user,request("{\"stationId\":"+stationId+"}"));
            if (stationId == 113) order.verify(systemMessages).stationSelected(id,user,"station",true);
            else if (mockingDetails(events).getInvocations().size()==1) order.verify(systemMessages).stationSelected(id,user,"station",false);
            order.verify(events).publish(id,RoomEventType.SELECTED_STATION_CHANGED,
                new SelectedStationChanged(stationId == 113 ? changed : station,room.getConfirmedAt()));
        }
        verifyNoMoreInteractions(events,systemMessages);
    }

}
