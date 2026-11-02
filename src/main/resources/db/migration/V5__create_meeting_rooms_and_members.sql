create table meeting_rooms (
    id uuid primary key,
    room_code varchar(6) not null,
    name varchar(30) not null,
    created_by uuid not null,
    max_members int not null default 4,
    appointment_at timestamptz,
    selected_station_id bigint,
    confirmed_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_room_code unique (room_code),
    constraint ck_room_code check (room_code ~ '^[A-Z0-9]{6}$'),
    constraint ck_room_name check (char_length(btrim(name)) between 1 and 30),
    constraint ck_room_max check (max_members = 4),
    constraint ck_room_confirmed check ((selected_station_id is null) = (confirmed_at is null)),
    constraint fk_room_creator foreign key (created_by) references users (id),
    constraint fk_room_selected_station foreign key (selected_station_id) references stations (id)
);

create table meeting_members (
    id uuid primary key,
    room_id uuid not null,
    user_id uuid not null,
    role varchar(6) not null default 'MEMBER',
    departure_station_id bigint,
    joined_at timestamptz not null default now(),
    constraint uq_member_room_user unique (room_id, user_id),
    constraint ck_member_role check (role in ('OWNER', 'MEMBER')),
    constraint fk_member_room foreign key (room_id) references meeting_rooms (id) on delete cascade,
    constraint fk_member_user foreign key (user_id) references users (id),
    constraint fk_member_departure_station foreign key (departure_station_id) references stations (id)
);

-- At most one OWNER. Presence, succession and member count are service responsibilities.
create unique index uq_one_owner_per_room on meeting_members (room_id) where role = 'OWNER';
create index idx_members_user on meeting_members (user_id);
create index idx_members_room_joined_id on meeting_members (room_id, joined_at, id);
