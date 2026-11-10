-- #89: line 1 UP/DOWN follow Seoul 상행/하행 (상행 = towards 청량리). V11 took DOWN from the row order of OA-12034,
-- which starts at 서울역 for line 1, so every line 1 UP/DOWN was the opposite of Seoul's (and of the 상선/하선 the
-- congestion data uses). Only line 1 changes: its 18 RIDE edges swap UP <-> DOWN and its 10 MAIN seq values reverse
-- (MAIN seq follows DOWN: 청량리 1 ... 서울역 10). data/edges.csv keeps the V11 orientation (docs/subway-seed-51.md).
do $$
declare
    line1 bigint;
    up_count int;
    down_count int;
    other_count int;
    jonggak_up text;
    seoul_seq int;
    node_count int;
    updated int;
begin
    select id into line1 from subway_lines where line_code = '1';
    select count(*) filter (where c.direction = 'UP'), count(*) filter (where c.direction = 'DOWN'),
           count(*) filter (where c.direction not in ('UP', 'DOWN'))
      into up_count, down_count, other_count
      from station_connections c join station_lines f on f.id = c.from_station_line_id
     where c.connection_type = 'RIDE' and f.line_id = line1;
    select t.name into jonggak_up
      from station_connections c
      join station_lines f on f.id = c.from_station_line_id join stations s on s.id = f.station_id
      join station_lines g on g.id = c.to_station_line_id join stations t on t.id = g.station_id
     where c.connection_type = 'RIDE' and f.line_id = line1 and s.name = '종각' and c.direction = 'UP';
    select sl.seq into seoul_seq from station_lines sl join stations s on s.id = sl.station_id
     where sl.line_id = line1 and s.name = '서울역';
    select count(*) into node_count from station_lines where line_id = line1;
    if up_count <> 9 or down_count <> 9 or other_count <> 0 or jonggak_up is distinct from '시청'
            or seoul_seq is distinct from 1 or node_count <> 10 then
        raise exception 'V14 expects the V11 line 1 seed (9 UP + 9 DOWN RIDE, 종각 UP -> 시청, 서울역 seq 1, 10 nodes), found % UP, % DOWN, % other, 종각 UP -> %, 서울역 seq %, % nodes',
            up_count, down_count, other_count, jonggak_up, seoul_seq, node_count;
    end if;

    update station_connections c
       set direction = case c.direction when 'UP' then 'DOWN' else 'UP' end
      from station_lines f
     where c.from_station_line_id = f.id and f.line_id = line1 and c.connection_type = 'RIDE';
    get diagnostics updated = row_count;
    if updated <> 18 then
        raise exception 'V14 expected to swap 18 line 1 RIDE edges, swapped %', updated;
    end if;

    -- Two steps so uq_sl_line_branch_seq never sees a duplicate while the values move.
    update station_lines set seq = seq + 1000 where line_id = line1;
    update station_lines set seq = node_count + 1 - (seq - 1000) where line_id = line1;
    get diagnostics updated = row_count;
    if updated <> 10 then
        raise exception 'V14 expected to reverse 10 line 1 seq values, reversed %', updated;
    end if;
end $$;
