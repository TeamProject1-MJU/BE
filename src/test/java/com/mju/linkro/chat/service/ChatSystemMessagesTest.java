package com.mju.linkro.chat.service;

import com.mju.linkro.chat.repository.RoomMessageRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatSystemMessagesTest {
    ChatMessageWriter writer = mock(ChatMessageWriter.class);
    RoomMessageRepository messages = mock(RoomMessageRepository.class);
    ChatSystemMessages system = new ChatSystemMessages(writer, messages);
    UUID room = UUID.randomUUID(), alice = UUID.randomUUID(), bob = UUID.randomUUID();

    ChatSystemMessagesTest() {
        when(messages.nickname(alice)).thenReturn(Optional.of("앨리스"));
        when(messages.nickname(bob)).thenReturn(Optional.of("밥"));
    }

    @Test void joinedAndLeft() {
        system.joined(room, alice);
        verify(writer).recordSystem(room, "앨리스님이 입장했어요.");
        system.left(room, alice, null);
        verify(writer).recordSystem(room, "앨리스님이 나갔어요.");
        verify(writer, never()).recordSystem(room, "밥님이 새 방장이 됐어요.");
    }

    @Test void aLeavingOwnerAlsoAnnouncesTheNewOwner() {
        system.left(room, alice, bob);
        verify(writer).recordSystem(room, "앨리스님이 나갔어요.");
        verify(writer).recordSystem(room, "밥님이 새 방장이 됐어요.");
    }

    @Test void proposalsAndSelectedStationUseTheStationLabelWithYeokOnce() {
        system.proposalCreated(room, "앨리스", "사당");
        verify(writer).recordSystem(room, "앨리스님이 사당역을 약속역 후보로 제안했어요.");
        system.proposalDeleted(room, bob, "서울역");
        verify(writer).recordSystem(room, "밥님이 약속역 후보 서울역을 삭제했어요.");
        system.stationSelected(room, alice, "강남", false);
        verify(writer).recordSystem(room, "앨리스님이 약속역을 강남역으로 지정했어요.");
        system.stationSelected(room, alice, "강남", true);
        verify(writer).recordSystem(room, "앨리스님이 약속역을 강남역으로 변경했어요.");
        system.stationCleared(room, bob);
        verify(writer).recordSystem(room, "밥님이 약속역 지정을 해제했어요.");
    }

    @Test void anUnknownUserHasAFallbackName() {
        system.joined(room, UUID.randomUUID());
        verify(writer).recordSystem(room, "알 수 없는 사용자님이 입장했어요.");
    }
}
