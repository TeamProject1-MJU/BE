-- Preserve the existing CHECK expression and data, including databases with V3 applied.
alter table users
    rename constraint ck_users_nickname_trimmed_length to ck_users_nickname;
