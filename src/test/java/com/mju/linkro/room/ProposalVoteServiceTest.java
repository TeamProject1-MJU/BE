package com.mju.linkro.room;

import com.mju.linkro.realtime.RoomEvents;
import com.mju.linkro.realtime.RoomEventType;
import com.mju.linkro.realtime.RoomEventPayloads.ProposalVoteChanged;
import com.mju.linkro.realtime.RoomEvent;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.mockito.ArgumentCaptor;
import java.util.concurrent.atomic.AtomicInteger;

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
    final RoomEvents events=mock(RoomEvents.class);
    final ProposalVoteService service=new ProposalVoteService(access,proposals,manager,events);
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
        verify(events).publish(room,RoomEventType.STATION_PROPOSAL_VOTE_CHANGED,
            new ProposalVoteChanged(id,1,user));
        verifyNoMoreInteractions(events);
        verify(manager,times(2)).getTransaction(argThat(d->d.getIsolationLevel()==TransactionDefinition.ISOLATION_READ_COMMITTED
            && d.getPropagationBehavior()==TransactionDefinition.PROPAGATION_REQUIRES_NEW && !d.isReadOnly()));
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
        verifyNoInteractions(events);
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
        verifyNoInteractions(events);
    }

    @Test void uniqueInsertFailureThenSuccessfulInsertPublishesOnce() {
        var failure = new DataIntegrityViolationException("insert",
            new SQLException("constraint \"uq_vote_room_user\"", "23505"));
        doThrow(failure).doNothing().when(proposals).insertVote(any(),any(),any(),any());
        assertThat(service.vote(room,user,id)).isEqualTo(new Vote(id,1,true));
        verify(manager).rollback(any());
        verify(manager).commit(any());
        verify(events).publish(room,RoomEventType.STATION_PROPOSAL_VOTE_CHANGED,
            new ProposalVoteChanged(id,1,user));
        verifyNoMoreInteractions(events);
    }
    @Test void realTransactionDefersDeliveryAndInnerCommitSurvivesOuterRollback() {
        var broker = mock(SimpMessagingTemplate.class);
        var actualEvents = mock(RoomEvents.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(actualEvents,"broker",broker);
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:vote-events", "sa", "");
        var actualManager = new DataSourceTransactionManager(dataSource);
        var actualService = new ProposalVoteService(access,proposals,actualManager,actualEvents);
        when(proposals.voteState(room,id,user)).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
            verifyNoInteractions(broker);
            return new Vote(id,7,true);
        });
        var outer = new TransactionTemplate(actualManager);
        outer.executeWithoutResult(status -> {
            assertThat(actualService.vote(room,user,id).voteCount()).isEqualTo(7);
            verify(broker).convertAndSend(eq("/topic/rooms/"+room),any(RoomEvent.class));
            status.setRollbackOnly();
        });
        verifyNoMoreInteractions(broker);
    }

    @Test void retryDiscardsRegisteredEventOnRollbackAndDeliversOnlySuccessfulAttempt() {
        transactionDeliveryScenario(true);
    }

    @Test void rollbackDiscardsRegisteredEventWithoutOuterTransaction() {
        transactionDeliveryScenario(false);
    }

    void transactionDeliveryScenario(boolean retry) {
        var broker = mock(SimpMessagingTemplate.class);
        var actualEvents = mock(RoomEvents.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(actualEvents,"broker",broker);
        var actualManager = new DataSourceTransactionManager(
            new DriverManagerDataSource("jdbc:h2:mem:vote-retry", "sa", ""));
        var actualService = new ProposalVoteService(access,proposals,actualManager,actualEvents);
        RuntimeException failure = retry ? new DataIntegrityViolationException("commit",
            new SQLException("constraint \"uq_vote_room_user\"","23505")) : new IllegalStateException("rollback");
        var attempts = new AtomicInteger();
        doAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
            i.callRealMethod();
            verifyNoInteractions(broker);
            if (attempts.getAndIncrement()==0) throw failure;
            return null;
        }).when(actualEvents).publish(any(),any(),any());
        if (retry) {
            assertThat(actualService.vote(room,user,id)).isEqualTo(new Vote(id,1,true));
            var captured = ArgumentCaptor.forClass(RoomEvent.class);
            verify(broker).convertAndSend(eq("/topic/rooms/"+room),captured.capture());
            assertThat(captured.getValue().payload()).isEqualTo(
                new ProposalVoteChanged(id,1,user));
            assertThat(attempts.get()).isEqualTo(2);
        } else {
            assertThatThrownBy(() -> actualService.vote(room,user,id)).isSameAs(failure);
            verifyNoInteractions(broker);
        }
    }

}
