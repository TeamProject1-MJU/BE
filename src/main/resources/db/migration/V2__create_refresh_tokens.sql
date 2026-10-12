create table refresh_tokens (
    id uuid not null,
    user_id uuid not null,
    family_id uuid not null,
    token_hash char(64) not null,
    expires_at timestamp(6) with time zone not null,
    revoked_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone not null default now(),
    primary key (id),
    constraint uk_refresh_tokens_token_hash unique (token_hash),
    constraint fk_refresh_tokens_user_id foreign key (user_id) references users (id)
);

create index idx_refresh_tokens_user_id on refresh_tokens (user_id);
create index idx_refresh_tokens_family_id on refresh_tokens (family_id);
