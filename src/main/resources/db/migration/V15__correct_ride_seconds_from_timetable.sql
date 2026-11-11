-- #96: travel_seconds of RIDE edges whose Seoul Metro timetable ride time (2026-09-01, train-timetable.csv.gz, local
-- trains, B arrival - A departure, median over all trains and day types) differs by 30 s or more. Each direction is
-- its own row. Only the fallback cost (linkro.subway.timetable.enabled=false or no timetable) reads travel_seconds;
-- the dwell setting (30 s) is unchanged. Targets come from TrainTimetableReportPostgresTest (docs/timetable-94.md).
do $$
declare
    expected constant int := 20;
    target record;
    matches int;
    updated int;
begin
    create temporary table v15_targets (line_code text, from_name text, to_name text, old_seconds int, new_seconds int)
        on commit drop;
    insert into v15_targets values
        ('1', '동대문', '동묘앞', 60, 90),
        ('1', '동묘앞', '동대문', 60, 90),
        ('4', '길음', '미아사거리', 90, 120),
        ('4', '미아사거리', '길음', 90, 120),
        ('4', '남태령', '사당', 90, 120),
        ('4', '사당', '남태령', 90, 120),
        ('4', '노원', '창동', 120, 90),
        ('4', '창동', '노원', 120, 90),
        ('4', '불암산', '상계', 90, 150),
        ('4', '상계', '불암산', 90, 150),
        ('4', '신용산', '이촌', 90, 120),
        ('4', '이촌', '신용산', 90, 120),
        ('4', '쌍문', '창동', 90, 120),
        ('4', '창동', '쌍문', 90, 120),
        ('7', '보라매', '신풍', 140, 80),
        ('7', '신풍', '보라매', 140, 80),
        ('7', '상도', '장승배기', 150, 90),
        ('7', '장승배기', '상도', 150, 90),
        ('8', '암사', '암사역사공원', 90, 120),
        ('8', '암사역사공원', '암사', 90, 180);

    -- Every target must be exactly one RIDE edge that still has its V11 value.
    for target in select * from v15_targets loop
        select count(*) into matches
          from station_connections c
          join station_lines f on f.id = c.from_station_line_id join stations fs on fs.id = f.station_id
          join station_lines t on t.id = c.to_station_line_id join stations ts on ts.id = t.station_id
          join subway_lines l on l.id = f.line_id
         where c.connection_type = 'RIDE' and l.line_code = target.line_code and t.line_id = f.line_id
           and fs.name = target.from_name and ts.name = target.to_name and c.travel_seconds = target.old_seconds;
        if matches <> 1 then
            raise exception 'V15 expects one RIDE edge %호선 % -> % with travel_seconds %, found %',
                target.line_code, target.from_name, target.to_name, target.old_seconds, matches;
        end if;
    end loop;

    update station_connections c
       set travel_seconds = v.new_seconds
      from v15_targets v, station_lines f, stations fs, station_lines t, stations ts, subway_lines l
     where c.connection_type = 'RIDE' and f.id = c.from_station_line_id and fs.id = f.station_id
       and t.id = c.to_station_line_id and ts.id = t.station_id and l.id = f.line_id and t.line_id = f.line_id
       and l.line_code = v.line_code and fs.name = v.from_name and ts.name = v.to_name
       and c.travel_seconds = v.old_seconds;
    get diagnostics updated = row_count;
    if updated <> expected then
        raise exception 'V15 expected to update % RIDE edges, updated %', expected, updated;
    end if;
end $$;
