-- AI predictions (HGB_20261103_39T) are unrounded; rounding to numeric(5,1) breaks ck_cong_level at level boundaries.
alter table congestion_stats alter column congestion_pct type numeric;

-- Slots: 05:30~23:30 every 30 minutes, plus 00:00 and 00:30 of the same service day (39 slots).
alter table congestion_stats drop constraint ck_cong_slot;
alter table congestion_stats add constraint ck_cong_slot check (
    extract(minute from slot_start) in (0, 30)
    and (slot_start between time '05:30' and time '23:30' or slot_start in (time '00:00', time '00:30'))
);
