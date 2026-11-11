package com.mju.linkro.room.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.mju.linkro.chat.service.ChatSystemMessages;
import com.mju.linkro.common.exception.*;
import com.mju.linkro.room.RoomProperties;
import com.mju.linkro.room.domain.RoomRole;
import com.mju.linkro.room.dto.StationProposalRequest;
import com.mju.linkro.room.dto.StationProposalResponses.*;
import com.mju.linkro.room.repository.*;
import com.mju.linkro.subway.api.StationLookup;
import java.sql.SQLException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@RequiredArgsConstructor
public class StationProposalService {
    private final RoomAccess access;
    private final StationLookup stations;
    private final StationProposalRepository proposals;
    private final MeetingMemberRepository members;
    private final RoomReadRepository reads;
    private final RoomProperties properties;
    private final ChatSystemMessages systemMessages;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Created create(UUID roomId, UUID userId, StationProposalRequest request) {
        access.lockMember(roomId, userId);
        long stationId = request.validatedStationId();
        UUID resultId = request.validatedResultId();
        stations.requireSupported(stationId);
        if (resultId != null && !proposals.resultBelongsToRoom(resultId, roomId)) throw error(ErrorCode.RESULT_NOT_FOUND);
        if (proposals.exists(roomId, stationId)) throw error(ErrorCode.ALREADY_PROPOSED);
        if (proposals.count(roomId) >= properties.getMaxStationProposals()) throw error(ErrorCode.PROPOSAL_LIMIT_EXCEEDED);
        Created created;
        try {
            created = proposals.insert(UuidCreator.getTimeOrderedEpoch(), roomId, stationId, userId, resultId);
        } catch (RuntimeException failure) {
            // Let the translated runtime exception cross the transaction proxy and roll back the failed insert.
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())
                        && sql.getMessage() != null && sql.getMessage().contains("\"uq_proposal_room_station\""))
                    throw new BusinessException(ErrorCode.ALREADY_PROPOSED, failure);
            }
            throw failure;
        }
        systemMessages.proposalCreated(roomId, created.proposedBy().nickname(), created.station().name());
        // #43: capture immutable created payload; publish WS only after outer commit.
        return created;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public Listing list(UUID roomId, UUID userId) {
        var room = access.requireMember(roomId, userId);
        Long selected = room.getSelectedStationId();
        var station = selected == null ? null : reads.stations(List.of(selected)).get(selected);
        return new Listing(station, properties.getMaxStationProposals(), proposals.list(roomId, userId, selected));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(UUID roomId, UUID userId, UUID proposalId) {
        access.lockMember(roomId, userId);
        UUID author = proposals.author(roomId, proposalId).orElseThrow(() -> error(ErrorCode.PROPOSAL_NOT_FOUND));
        if (!author.equals(userId) && members.findCurrentRole(roomId, userId).orElseThrow(() -> error(ErrorCode.NOT_ROOM_MEMBER)) != RoomRole.OWNER)
            throw error(ErrorCode.NOT_PROPOSAL_OWNER);
        String stationName = proposals.stationName(roomId, proposalId).orElseThrow(() -> error(ErrorCode.PROPOSAL_NOT_FOUND));
        proposals.delete(roomId, proposalId);
        systemMessages.proposalDeleted(roomId, userId, stationName);
        // #43: capture deleted proposal ID; publish WS only after outer commit.
    }

    private static BusinessException error(ErrorCode code) { return new BusinessException(code); }
}
