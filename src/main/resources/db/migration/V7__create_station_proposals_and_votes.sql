create table station_proposals (
    id uuid primary key,
    room_id uuid not null,
    station_id bigint not null,
    proposed_by uuid not null,
    source_result_id uuid,
    created_at timestamptz not null default now(),
    constraint uq_proposal_room_station unique (room_id, station_id),
    constraint uq_proposal_id_room unique (id, room_id),
    constraint fk_proposal_room foreign key (room_id) references meeting_rooms (id) on delete cascade,
    constraint fk_proposal_station foreign key (station_id) references stations (id),
    -- Reference the user, so leaving a room does not delete their proposals.
    constraint fk_proposal_author foreign key (proposed_by) references users (id),
    constraint fk_proposal_source foreign key (source_result_id) references recommendation_results (id) on delete set null
);

create index idx_proposals_room_created on station_proposals (room_id, created_at);
create index idx_proposals_author on station_proposals (proposed_by);
create index idx_proposals_source on station_proposals (source_result_id);

create table station_proposal_votes (
    id uuid primary key,
    room_id uuid not null,
    proposal_id uuid not null,
    user_id uuid not null,
    created_at timestamptz not null default now(),
    constraint uq_vote_room_user unique (room_id, user_id),
    constraint fk_vote_room foreign key (room_id) references meeting_rooms (id) on delete cascade,
    constraint fk_vote_proposal foreign key (proposal_id, room_id)
        references station_proposals (id, room_id) on delete cascade,
    constraint fk_vote_member foreign key (room_id, user_id)
        references meeting_members (room_id, user_id) on delete cascade
);

create index idx_votes_proposal on station_proposal_votes (proposal_id);
