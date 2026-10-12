create table users (
    id uuid not null,
    created_at timestamp(6) with time zone not null default now(),
    updated_at timestamp(6) with time zone not null default now(),
    deleted_at timestamp(6) with time zone,
    kakao_id varchar(64) not null,
    nickname varchar(20) not null,
    primary key (id),
    constraint uk_users_kakao_id unique (kakao_id)
);
