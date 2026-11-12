package com.mju.linkro.room;

import com.mju.linkro.common.exception.*;
import com.mju.linkro.room.dto.StationProposalResponses.Vote;
import com.mju.linkro.room.repository.StationProposalRepository;
import com.mju.linkro.room.service.*;
import java.sql.SQLException;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProposalVoteServiceTest {
    final RoomAccess access=mock(RoomAccess.class);
    final StationProposalRepository proposals=mock(StationProposalRepository.class);
    final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    final com.mju.linkro.realtime.RoomEvents events=mock(com.mju.linkro.realtime.RoomEvents.class);
    final ProposalVoteService service=new ProposalVoteService(access,proposals,events,manager);
    final UUID room=UUID.randomUUID(),user=UUID.randomUUID(),id=UUID.randomUUID();
    @BeforeEach void setup() {
        when(manager.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());
        when(proposals.author(room,id)).thenReturn(Optional.of(user));
        when(proposals.voteByUser(room,user)).thenReturn(Optional.empty());
        when(proposals.voteState(room,id,user)).thenReturn(new Vote(id,1,true));
    }
    @Test void lockScopeExistingInsertStateOrderAndIdempotency() {
        assertThat(service.vote(room,user,id)).isEqualTo(new Vote(id,1,true));
        var order=inOrder(access,proposals);
        order.verify(access).lockMember(room,user);
        order.verify(proposals).author(room,id);
        order.verify(proposals).voteByUser(room,user);
        order.verify(proposals).insertVote(any(),eq(room),eq(id),eq(user));
        order.verify(proposals).voteState(room,id,user);
        when(proposals.voteByUser(room,user)).thenReturn(Optional.of(id));
        assertThat(service.vote(room,user,id)).isEqualTo(new Vote(id,1,true));
        verify(proposals,times(1)).insertVote(any(),any(),any(),any());
        verify(manager,times(2)).getTransaction(argThat(d->d.getIsolationLevel()==TransactionDefinition.ISOLATION_READ_COMMITTED
            && d.getPropagationBehavior()==TransactionDefinition.PROPAGATION_REQUIRES_NEW && !d.isReadOnly()));
    }
    @Test void aNewVotePublishesTheVoteCountAfterTheStateIsReadAndARepeatDoesNot() {
        when(proposals.voteState(room,id,user)).thenReturn(new Vote(id,3,true));
        service.vote(room,user,id);
        var order=inOrder(proposals,events);
        order.verify(proposals).insertVote(any(),eq(room),eq(id),eq(user));
        order.verify(proposals).voteState(room,id,user);
        order.verify(events).publish(room,com.mju.linkro.realtime.RoomEventType.STATION_PROPOSAL_VOTE_CHANGED,
            new com.mju.linkro.realtime.RoomEventPayloads.ProposalVoteChanged(id,3,user));
        reset(events);
        when(proposals.voteByUser(room,user)).thenReturn(Optional.of(id));
        service.vote(room,user,id);
        verifyNoInteractions(events);
    }
    @Test void rejectedVotesPublishNothing() {
        when(proposals.author(room,id)).thenReturn(Optional.empty());
        error(ErrorCode.PROPOSAL_NOT_FOUND);
        when(proposals.author(room,id)).thenReturn(Optional.of(user));
        when(proposals.voteByUser(room,user)).thenReturn(Optional.of(UUID.randomUUID()));
        error(ErrorCode.ALREADY_VOTED);
        doThrow(new BusinessException(ErrorCode.NOT_ROOM_MEMBER)).when(access).lockMember(room,user);
        error(ErrorCode.NOT_ROOM_MEMBER);
        verifyNoInteractions(events);
    }
    @Test void aRetryAfterAUniqueViolationDoesNotPublishTheVoteTheOtherRequestCast() {
        var failure=new DataIntegrityViolationException("insert",new SQLException("constraint \"uq_vote_room_user\"","23505"));
        doThrow(failure).when(proposals).insertVote(any(),any(),any(),any());
        when(proposals.voteByUser(room,user)).thenReturn(Optional.empty(),Optional.of(id));
        service.vote(room,user,id);
        verifyNoInteractions(events);
    }
    @Test void candidateScopePrecedesExistingVoteAndOtherVoteIsRejected() {
        when(proposals.author(room,id)).thenReturn(Optional.empty());
        error(ErrorCode.PROPOSAL_NOT_FOUND);
        verify(proposals,never()).voteByUser(any(),any());
        when(proposals.author(room,id)).thenReturn(Optional.of(user));
        when(proposals.voteByUser(room,user)).thenReturn(Optional.of(UUID.randomUUID()));
        error(ErrorCode.ALREADY_VOTED);
        verify(proposals,never()).insertVote(any(),any(),any(),any());
    }
    void error(ErrorCode code) {
        assertThatThrownBy(()->service.vote(room,user,id)).isInstanceOf(BusinessException.class)
            .extracting(e->((BusinessException)e).getErrorCode()).isEqualTo(code);
    }
    @Test void exactConstraintOnlyRecoversAfterRollbackAndRetryIsBounded() {
        for(String state:List.of("23505","23503")) for(String name:List.of("uq_vote_room_user","other","uq_vote_room_user_extra")) {
            reset(manager,proposals);
            setup();
            var failure=new DataIntegrityViolationException("insert",new SQLException("constraint \""+name+"\"",state));
            doThrow(failure).when(proposals).insertVote(any(),any(),any(),any());
            assertThatThrownBy(()->service.vote(room,user,id)).isSameAs(failure);
            int attempts=state.equals("23505") && name.equals("uq_vote_room_user")?2:1;
            verify(manager,times(attempts)).rollback(any());
            verify(proposals,times(attempts)).insertVote(any(),any(),any(),any());
        }
    }
    @Test void uniqueRecoveryRechecksAllRulesInFreshTransaction() {
        var failure=new DataIntegrityViolationException("insert",new SQLException("constraint \"uq_vote_room_user\"","23505"));
        doThrow(failure).when(proposals).insertVote(any(),any(),any(),any());
        when(proposals.voteByUser(room,user)).thenReturn(Optional.empty(),Optional.of(id));
        assertThat(service.vote(room,user,id).votedByMe()).isTrue();
        var order=inOrder(manager,access,proposals);
        order.verify(manager).getTransaction(any());
        order.verify(access).lockMember(room,user);
        order.verify(proposals).author(room,id);
        order.verify(proposals).voteByUser(room,user);
        order.verify(proposals).insertVote(any(),any(),any(),any());
        order.verify(manager).rollback(any());
        order.verify(manager).getTransaction(any());
        order.verify(access).lockMember(room,user);
        order.verify(proposals).author(room,id);
        order.verify(proposals).voteByUser(room,user);
        order.verify(proposals).voteState(room,id,user);
        order.verify(manager).commit(any());
    }
}
