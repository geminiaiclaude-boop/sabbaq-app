-- Shared geocode cache contract. Run after bootstrap.sql, all migrations and the earlier suites
-- on a fresh local PostgreSQL database. Google is replaced by a stub that counts its calls.
\set ON_ERROR_STOP on

create or replace function pg_temp.expect(p_ok boolean, p_what text) returns void language plpgsql as $$
begin
    if not coalesce(p_ok, false) then
        raise exception 'EXPECTATION FAILED: %', p_what;
    end if;
end;
$$;
grant execute on function pg_temp.expect(boolean, text) to authenticated, anon;

create table private.geocode_stub_calls (n serial, text text, bounds text);
create or replace function private.geocode_fetch(p_text text, p_bounds text, p_key text) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
begin
    insert into private.geocode_stub_calls (text, bounds) values (p_text, p_bounds);
    if p_text = 'down' then return null; end if;
    if p_text = 'nowhere' then return jsonb_build_object('points', '[]'::jsonb); end if;
    return jsonb_build_object('points', jsonb_build_array(jsonb_build_object('lat', 24.7, 'lng', 46.6)));
end;
$$;

insert into auth.users (id, email, phone, phone_confirmed_at, raw_app_meta_data, raw_user_meta_data) values
    ('9e0c0000-0000-0000-0000-000000000001', 'geo-admin@example.test', null, null, '{"provider":"google","providers":["google"]}'::jsonb, '{}'::jsonb),
    ('9e0c0000-0000-0000-0000-000000000002', 'geo-driver-a@example.test', null, null, '{"provider":"google","providers":["google"]}'::jsonb, '{"full_name":"Geo Alpha"}'::jsonb),
    ('9e0c0000-0000-0000-0000-000000000003', 'geo-driver-b@example.test', null, null, '{"provider":"google","providers":["google"]}'::jsonb, '{"full_name":"Geo Beta"}'::jsonb);
insert into public.admins (user_id) values ('9e0c0000-0000-0000-0000-000000000001');
set role authenticated;
set request.jwt.claim.sub = '9e0c0000-0000-0000-0000-000000000002';
select public.ensure_driver_profile();
set request.jwt.claim.sub = '9e0c0000-0000-0000-0000-000000000003';
select public.ensure_driver_profile();

-- Drivers cannot touch the cache, the key or the usage directly.
do $$ begin
    perform 1 from public.geocode_cache;
    raise exception 'EXPECTATION FAILED: driver read the cache directly';
exception when insufficient_privilege then null;
end $$;
do $$ begin
    perform 1 from private.geocode_settings;
    raise exception 'EXPECTATION FAILED: driver read the key';
exception when insufficient_privilege then null;
end $$;
do $$ begin
    perform public.admin_geocode_settings(true, 'k');
    raise exception 'EXPECTATION FAILED: driver changed the settings';
exception when insufficient_privilege then null;
end $$;

-- Off by default: nothing is fetched.
select pg_temp.expect(public.geocode_resolve('حي العليا', 'حي العليا', 24.71, 46.67) ->> 'reason' = 'disabled', 'off until the admin enables it');
reset role;
select pg_temp.expect((select count(*) from private.geocode_stub_calls) = 0, 'no call while disabled');

-- The admin sets a key and caps; the key is never returned.
set role authenticated;
set request.jwt.claim.sub = '9e0c0000-0000-0000-0000-000000000001';
select pg_temp.expect((public.admin_geocode_settings(true, ' secret-key ', 100, 2) ->> 'has_key')::boolean, 'key stored');
select pg_temp.expect(public.admin_geocode_settings() ? 'google_api_key' = false, 'key never returned');

-- First driver resolves once; the second driver gets the cached answer without a new call.
set request.jwt.claim.sub = '9e0c0000-0000-0000-0000-000000000002';
select pg_temp.expect(public.geocode_resolve('حي العليا', 'حي العليا', 24.71, 46.67) ->> 'status' = 'resolved', 'first lookup resolved');
set request.jwt.claim.sub = '9e0c0000-0000-0000-0000-000000000003';
select pg_temp.expect(public.geocode_resolve('حي العليا', 'حي العليا') ->> 'status' = 'hit', 'second driver hits the cache');
reset role;
select pg_temp.expect((select count(*) from private.geocode_stub_calls) = 1, 'Google called once for two drivers');
select pg_temp.expect((select bounds from private.geocode_stub_calls order by n limit 1) = '23.5,45.5|25.5,47.5',
    'only a coarse region (0.5° grid, ±1°) is sent');

-- "Not found" is cached as empty; a failed call is not cached.
set role authenticated;
set request.jwt.claim.sub = '9e0c0000-0000-0000-0000-000000000003';
select pg_temp.expect(jsonb_array_length(public.geocode_resolve('nowhere', 'nowhere') -> 'points') = 0, 'not found answered empty');
select pg_temp.expect(public.geocode_resolve('nowhere', 'nowhere') ->> 'status' = 'hit', 'not found is cached');
reset role;
update private.geocode_usage set calls = 0;
set role authenticated;
set request.jwt.claim.sub = '9e0c0000-0000-0000-0000-000000000003';
select pg_temp.expect(public.geocode_resolve('down', 'down') ->> 'reason' = 'fetch_failed', 'failure reported');
select pg_temp.expect(public.geocode_resolve('down', 'down') ->> 'reason' = 'fetch_failed', 'failure not cached');

-- Per-driver daily cap (2) and monthly cap.
select pg_temp.expect(public.geocode_resolve('cap-a', 'cap-a') ->> 'reason' = 'driver_cap', 'daily cap stops the driver');
reset role;
update private.geocode_usage set calls = 0;
update private.geocode_settings set monthly_cap = 0;
set role authenticated;
set request.jwt.claim.sub = '9e0c0000-0000-0000-0000-000000000002';
select pg_temp.expect(public.geocode_resolve('cap-b', 'cap-b') ->> 'reason' = 'monthly_cap', 'monthly cap stops everyone');
select pg_temp.expect(public.geocode_resolve('حي العليا', 'حي العليا') ->> 'status' = 'hit', 'cached answers still served at the cap');

-- Input bounds and anonymous callers.
do $$ begin
    perform public.geocode_resolve('', 'x');
    raise exception 'EXPECTATION FAILED: empty key accepted';
exception when sqlstate '22023' then null;
end $$;
reset role;
set role anon;
do $$ begin
    perform public.geocode_resolve('k', 'x');
    raise exception 'EXPECTATION FAILED: anonymous lookup accepted';
exception when insufficient_privilege then null;
end $$;
reset role;
select pg_temp.expect(public.purge_geocode_cache() = 0, 'fresh not-found answers kept');
