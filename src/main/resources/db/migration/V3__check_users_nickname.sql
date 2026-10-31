-- Validate existing rows too. Invalid legacy data must be reviewed before deployment;
-- this migration deliberately neither deletes users nor rewrites nicknames.
alter table users
    add constraint ck_users_nickname_trimmed_length
    check (char_length(btrim(nickname)) between 1 and 20);
