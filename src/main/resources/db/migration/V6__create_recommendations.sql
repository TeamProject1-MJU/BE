-- Full formal prerequisite for station_proposals.source_result_id, not a placeholder.
create table recommendation_runs (
    id uuid primary key,
    room_id uuid not null,
    requested_by uuid not null,
    criteria jsonb not null,
    created_at timestamptz not null default now(),
    constraint ck_runs_criteria check (jsonb_typeof(criteria) = 'object'),
    constraint fk_run_room foreign key (room_id) references meeting_rooms (id) on delete cascade,
    constraint fk_run_requester foreign key (requested_by) references users (id)
);

create index idx_runs_room_created on recommendation_runs (room_id, created_at);

create table recommendation_results (
    id uuid primary key,
    run_id uuid not null,
    criterion varchar(8) not null,
    station_id bigint not null,
    metric_value numeric(10,2) not null,
    trips jsonb not null,
    nearby jsonb not null,
    reason text not null,
    reason_source varchar(8) not null,
    constraint uq_result_run_criterion unique (run_id, criterion),
    constraint ck_res_criterion check (criterion in ('TIME', 'FARE', 'DISTANCE')),
    constraint ck_res_reason_src check (reason_source in ('AI', 'TEMPLATE')),
    constraint ck_res_metric check (metric_value >= 0),
    constraint ck_res_trips check (jsonb_typeof(trips) = 'array'),
    constraint ck_res_nearby check (jsonb_typeof(nearby) = 'array'),
    constraint fk_result_run foreign key (run_id) references recommendation_runs (id) on delete cascade,
    constraint fk_result_station foreign key (station_id) references stations (id)
);
