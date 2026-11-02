-- Schema only, to complete the room-deletion contract from issue #33.
create table room_messages (
    id uuid primary key,
    room_id uuid not null,
    sender_id uuid,
    sender_type varchar(6) not null,
    content text not null,
    meta jsonb,
    created_at timestamptz not null default now(),
    constraint ck_msg_type check (sender_type in ('USER', 'AI', 'SYSTEM')),
    constraint ck_msg_sender check (
        (sender_type = 'USER' and sender_id is not null) or
        (sender_type <> 'USER' and sender_id is null)
    ),
    constraint ck_msg_content check (char_length(content) between 1 and 4000),
    constraint fk_message_room foreign key (room_id) references meeting_rooms (id) on delete cascade,
    constraint fk_message_sender foreign key (sender_id) references users (id)
);

create index idx_messages_room_id on room_messages (room_id, id);
