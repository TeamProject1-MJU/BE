-- Line 4 당고개 is now 불암산 (Kakao and current official name). Only stations.name changes;
-- the station id, station_lines and station_connections stay as they are.
do $$
declare
    old_count int;
    new_count int;
    updated int;
begin
    select count(*) into old_count from stations where name = '당고개';
    select count(*) into new_count from stations where name = '불암산';
    if old_count <> 1 or new_count <> 0 then
        raise exception 'V12 expects exactly one 당고개 and no 불암산 station, found % and %', old_count, new_count;
    end if;

    update stations set name = '불암산' where name = '당고개';
    get diagnostics updated = row_count;
    if updated <> 1 then
        raise exception 'V12 expected to rename exactly one station, renamed %', updated;
    end if;
end $$;
