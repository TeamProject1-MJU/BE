package com.mju.linkro.room.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.mju.linkro.common.exception.*;
import com.mju.linkro.room.dto.StationProposalResponses.Vote;
import com.mju.linkro.room.repository.StationProposalRepository;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ProposalVoteService {
    private final RoomAccess access;
    private final StationProposalRepository proposals;
    private final TransactionTemplate transaction;

    public ProposalVoteService(RoomAccess access, StationProposalRepository proposals, PlatformTransactionManager manager) {
        this.access = access;
        this.proposals = proposals;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Vote vote(UUID roomId, UUID userId, UUID proposalId) {
        try {
            return transaction.execute(status -> voteLocked(roomId, userId, proposalId));
        } catch (RuntimeException failure) {
            if (!isVoteUniqueViolation(failure)) throw failure;
            // execute has already rolled back. Never query a PostgreSQL transaction after a failed INSERT.
            return transaction.execute(status -> voteLocked(roomId, userId, proposalId));
        }
    }

    private Vote voteLocked(UUID roomId, UUID userId, UUID proposalId) {
        access.lockMember(roomId, userId);
        proposals.author(roomId, proposalId).orElseThrow(() -> new BusinessException(ErrorCode.PROPOSAL_NOT_FOUND));
        var existing = proposals.voteByUser(roomId, userId);
        if (existing.isPresent()) {
            if (!existing.get().equals(proposalId)) throw new BusinessException(ErrorCode.ALREADY_VOTED);
        } else {
            proposals.insertVote(UuidCreator.getTimeOrderedEpoch(), roomId, proposalId, userId);
            // #43: capture immutable STATION_PROPOSAL_VOTE_CHANGED payload only for this insert; publish after commit.
        }
        return proposals.voteState(roomId, proposalId, userId);
    }

    private static boolean isVoteUniqueViolation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())
                    && sql.getMessage() != null && sql.getMessage().contains("\"uq_vote_room_user\"")) return true;
        }
        return false;
    }
}
