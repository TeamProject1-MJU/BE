package com.mju.linkro.room;

import com.mju.linkro.common.exception.*;
import com.mju.linkro.room.domain.*;
import com.mju.linkro.room.dto.SelectedStationRequest;
import com.mju.linkro.room.service.*;
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
    final SelectedStationService service = new SelectedStationService(access, stations);
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
