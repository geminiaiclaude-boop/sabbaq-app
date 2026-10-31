-- Zone Places contract (0018). Run after the geocode cache suite. Google is replaced by a stub
-- that counts its calls.
\set ON_ERROR_STOP on

create or replace function pg_temp.expect(p_ok boolean, p_what text) returns void language plpgsql as $$
begin
    if not coalesce(p_ok, false) then
        raise exception 'EXPECTATION FAILED: %', p_what;
    end if;
end;
$$;
grant execute on function pg_temp.expect(boolean, text) to authenticated, anon;

create table private.places_stub_calls (n serial, lat double precision, lng double precision, radius integer);
create or replace function private.places_fetch(p_lat double precision, p_lng double precision, p_radius integer,
                                                p_key text) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
begin
    insert into private.places_stub_calls (lat, lng, radius) values (p_lat, p_lng, p_radius);
    if p_lat = 10 then return null; end if;
    return jsonb_build_object('places', jsonb_build_array(jsonb_build_object('name', 'مقهى الورد', 'lat', p_lat, 'lng', p_lng)));
end;
$$;

insert into auth.users (id, email, phone, phone_confirmed_at, raw_app_meta_data, raw_user_meta_data) values
    ('9e0d0000-0000-0000-0000-000000000001', 'pl-admin@example.test', null, null, '{"provider":"google","providers":["google"]}'::jsonb, '{}'::jsonb),
    ('9e0d0000-0000-0000-0000-000000000002', 'pl-driver-a@example.test', null, null, '{"provider":"google","providers":["google"]}'::jsonb, '{"full_name":"Places Alpha"}'::jsonb),
    ('9e0d0000-0000-0000-0000-000000000003', 'pl-driver-b@example.test', null, null, '{"provider":"google","providers":["google"]}'::jsonb, '{"full_name":"Places Beta"}'::jsonb);
insert into public.admins (user_id) values ('9e0d0000-0000-0000-0000-000000000001');
set role authenticated;
set request.jwt.claim.sub = '9e0d0000-0000-0000-0000-000000000002';
select public.ensure_driver_profile();
set request.jwt.claim.sub = '9e0d0000-0000-0000-0000-000000000003';
select public.ensure_driver_profile();
reset role;
-- Paid lookups are for active drivers on a trial or a subscription (0021, H-06).
update public.drivers set status = 'active', trial_until = now() + interval '3 days' where id in ('9e0d0000-0000-0000-0000-000000000002', '9e0d0000-0000-0000-0000-000000000003');
set role authenticated;

-- Drivers cannot read the cells nor switch Places on.
do $$ begin
    perform 1 from private.places_cells;
    raise exception 'EXPECTATION FAILED: driver read the cells';
exception when insufficient_privilege then null;
end $$;
do $$ begin
    perform public.admin_places_settings(true);
    raise exception 'EXPECTATION FAILED: driver switched Places on';
exception when insufficient_privilege then null;
end $$;
do $$ begin
    perform public.zone_places(24.69, 46.68, 100);
    raise exception 'EXPECTATION FAILED: radius below 250 m accepted';
exception when invalid_parameter_value then null;
end $$;

-- Off by default, and off while only Google geocoding is on.
select pg_temp.expect(public.zone_places(24.69, 46.68, 600) ->> 'reason' = 'disabled', 'off by default');
set request.jwt.claim.sub = '9e0d0000-0000-0000-0000-000000000001';
select public.admin_geocode_settings(true, 'test-key', 9000, 200);
set request.jwt.claim.sub = '9e0d0000-0000-0000-0000-000000000002';
select pg_temp.expect(public.zone_places(24.69, 46.68, 600) ->> 'reason' = 'disabled', 'geocoding on is not Places on');
set request.jwt.claim.sub = '9e0d0000-0000-0000-0000-000000000001';
select pg_temp.expect((public.admin_places_settings(true) ->> 'places_enabled')::boolean, 'admin switches Places on');
reset role;
select pg_temp.expect(exists (select 1 from public.audit_log where action = 'set_places_enabled'), 'switch is audited');
select pg_temp.expect((select count(*) from private.places_stub_calls) = 0, 'nothing fetched while off');
set role authenticated;

-- Fetched once per cell, shared with the next driver, counted as two Google requests.
set request.jwt.claim.sub = '9e0d0000-0000-0000-0000-000000000002';
select pg_temp.expect(public.zone_places(24.69001, 46.68001, 600) -> 'places' -> 0 ->> 'name' = 'مقهى الورد', 'first lookup fetches');
set request.jwt.claim.sub = '9e0d0000-0000-0000-0000-000000000003';
select pg_temp.expect(public.zone_places(24.69002, 46.68002, 600) ->> 'status' = 'resolved', 'same cell is served');
reset role;
select pg_temp.expect((select count(*) from private.places_stub_calls) = 1, 'one fetch for one cell');
select pg_temp.expect((select calls from private.geocode_usage where driver_id = '9e0d0000-0000-0000-0000-000000000002' and day = current_date) = 2,
    'two Google requests counted');
set role authenticated;

-- A failed fetch stores nothing; the monthly cap stops new fetches but not stored cells.
select pg_temp.expect(public.zone_places(10, 10, 600) ->> 'reason' = 'fetch_failed', 'failure reported');
reset role;
select pg_temp.expect(not exists (select 1 from private.places_cells where cell like '10.000,%'), 'failure not cached');
update private.geocode_settings set monthly_cap = 4 where id;
set role authenticated;
select pg_temp.expect(public.zone_places(24.80, 46.80, 600) ->> 'reason' = 'monthly_cap', 'monthly cap stops new fetches');
select pg_temp.expect(public.zone_places(24.69, 46.68, 600) ->> 'status' = 'resolved', 'stored cell still served under the cap');
reset role;
update private.geocode_settings set monthly_cap = 9000, enabled = false, places_enabled = false, google_api_key = null where id;
