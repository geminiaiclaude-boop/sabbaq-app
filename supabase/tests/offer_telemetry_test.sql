-- Offer visibility telemetry contract. Run after bootstrap.sql, all migrations and the earlier
-- suites on a fresh local PostgreSQL database.
\set ON_ERROR_STOP on

create or replace function pg_temp.expect(p_ok boolean, p_what text) returns void language plpgsql as $$
begin
    if not coalesce(p_ok, false) then
        raise exception 'EXPECTATION FAILED: %', p_what;
    end if;
end;
$$;
grant execute on function pg_temp.expect(boolean, text) to authenticated, anon;

create or replace function pg_temp.event(p_id text, p_stage text, p_extra jsonb default '{}'::jsonb) returns jsonb language sql as $$
    select jsonb_build_object(
        'eventId', p_id, 'occurredAtEpochMs', (extract(epoch from now()) * 1000)::bigint,
        'correlationId', '5eef581d3799dfc07675dedb7025613c', 'appId', 'sabbaq', 'appVersion', '1.3.0',
        'androidApi', 34, 'provider', 'jeeny', 'stage', p_stage) || p_extra;
$$;
grant execute on function pg_temp.event(text, text, jsonb) to authenticated, anon;

insert into auth.users (id, email, phone, phone_confirmed_at, raw_app_meta_data, raw_user_meta_data) values
    ('40000000-0000-0000-0000-000000000001', 'telemetry-admin@example.test', null, null,
     '{"provider":"google","providers":["google"]}'::jsonb, '{}'::jsonb),
    ('40000000-0000-0000-0000-000000000002', null, '+966512399002', now(),
     '{"provider":"phone","providers":["phone"]}'::jsonb, '{}'::jsonb);
insert into public.admins (user_id) values ('40000000-0000-0000-0000-000000000001');

-- Anonymous clients cannot write telemetry.
set role anon;
do $$ begin
    perform public.ingest_offer_telemetry(jsonb_build_array(pg_temp.event('00000000-0000-4000-8000-000000000001', 'HEARTBEAT')));
    raise exception 'EXPECTATION FAILED: anonymous ingest accepted';
exception when insufficient_privilege then null;
end $$;
reset role;

-- A signed-in driver ingests v1 and v2 rows; duplicates are idempotent.
set role authenticated;
set request.jwt.claim.sub = '40000000-0000-0000-0000-000000000002';
select pg_temp.expect((public.ingest_offer_telemetry(jsonb_build_array(
    pg_temp.event('00000000-0000-4000-8000-000000000011', 'EVENT_RECEIVED'),
    pg_temp.event('00000000-0000-4000-8000-000000000012', 'SIGNAL_MISS',
        '{"reasonCode":"cls_none","eventCount":41,"detail":"a1,f0,d1,t1,p1,o0,c0,n212,cap0","targetAppVersion":"7.4.2"}'::jsonb),
    pg_temp.event('00000000-0000-4000-8000-000000000013', 'ROOT_UNAVAILABLE', '{"reasonCode":"active_other","eventCount":3}'::jsonb)
)) ->> 'accepted')::int = 3, 'driver batch stored');
select pg_temp.expect((public.ingest_offer_telemetry(jsonb_build_array(
    pg_temp.event('00000000-0000-4000-8000-000000000012', 'SIGNAL_MISS')
)) ->> 'accepted')::int = 0, 'duplicate event is ignored');

-- Free text in detail, unknown stages and oversized batches are refused as invalid input (HTTP 400).
do $$ begin
    perform public.ingest_offer_telemetry(jsonb_build_array(pg_temp.event('00000000-0000-4000-8000-000000000021', 'SIGNAL_MISS', '{"detail":"SAR 5.50 street"}'::jsonb)));
    raise exception 'EXPECTATION FAILED: free-text detail accepted';
exception when sqlstate '22023' then null;
end $$;
do $$ begin
    perform public.ingest_offer_telemetry(jsonb_build_array(pg_temp.event('00000000-0000-4000-8000-000000000022', 'NOT_A_STAGE')));
    raise exception 'EXPECTATION FAILED: unknown stage accepted';
exception when sqlstate '22023' then null;
end $$;
do $$ begin
    perform public.ingest_offer_telemetry((select jsonb_agg(pg_temp.event(gen_random_uuid()::text, 'HEARTBEAT')) from generate_series(1, 51)));
    raise exception 'EXPECTATION FAILED: oversized batch accepted';
exception when sqlstate '22023' then null;
end $$;

-- The driver cannot read telemetry back, directly or through the admin RPCs.
do $$ begin
    perform count(*) from public.offer_telemetry_events;
    raise exception 'EXPECTATION FAILED: driver read the table';
exception when insufficient_privilege then null;
end $$;
do $$ begin
    perform public.admin_offer_telemetry('5eef581d3799dfc07675dedb7025613c');
    raise exception 'EXPECTATION FAILED: driver used the admin RPC';
exception when insufficient_privilege then null;
end $$;
reset role;

-- The admin sees counts summed per stage and reason, the latest detail, and the fleet overview.
set role authenticated;
set request.jwt.claim.sub = '40000000-0000-0000-0000-000000000001';
select pg_temp.expect(
    (select (f ->> 'count')::int from jsonb_array_elements(public.admin_offer_telemetry('5eef581d3799dfc07675dedb7025613c') -> 'funnel') f
     where f ->> 'stage' = 'SIGNAL_MISS') = 41, 'funnel sums aggregated counts');
select pg_temp.expect(
    (select f ->> 'detail' from jsonb_array_elements(public.admin_offer_telemetry('5eef581d3799dfc07675dedb7025613c') -> 'funnel') f
     where f ->> 'stage' = 'SIGNAL_MISS') = 'a1,f0,d1,t1,p1,o0,c0,n212,cap0', 'funnel carries classifier flags');
select pg_temp.expect(
    not (public.admin_offer_telemetry('5eef581d3799dfc07675dedb7025613c') -> 'events' -> 0 ? 'ingested_by'), 'account id never leaves the server');
select pg_temp.expect(
    jsonb_array_length(public.admin_offer_telemetry_overview() -> 'devices') = 1, 'overview lists the reporting device');
select pg_temp.expect(
    (public.admin_offer_telemetry_for_driver('40000000-0000-0000-0000-000000000002') ->> 'correlation_id') = '5eef581d3799dfc07675dedb7025613c',
    'complaint lookup by driver account finds the device');
select pg_temp.expect(
    (public.admin_offer_telemetry_for_driver('40000000-0000-0000-0000-000000000001') ->> 'driver_has_telemetry')::boolean = false,
    'driver without telemetry is reported explicitly');
reset role;
set role authenticated;
set request.jwt.claim.sub = '40000000-0000-0000-0000-000000000002';
do $$ begin
    perform public.admin_offer_telemetry_for_driver('40000000-0000-0000-0000-000000000002');
    raise exception 'EXPECTATION FAILED: driver used the complaint lookup';
exception when insufficient_privilege then null;
end $$;
reset role;

-- Hourly bound: rows beyond it are acknowledged but not stored.
insert into public.offer_telemetry_events(event_id, occurred_at, correlation_id, app_id, app_version, android_api, provider, stage, ingested_by)
select gen_random_uuid(), now(), 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'sabbaq', '1', 34, 'jeeny', 'HEARTBEAT', '40000000-0000-0000-0000-000000000002'
from generate_series(1, 3000);
set role authenticated;
set request.jwt.claim.sub = '40000000-0000-0000-0000-000000000002';
select pg_temp.expect((public.ingest_offer_telemetry(jsonb_build_array(
    pg_temp.event('00000000-0000-4000-8000-000000000031', 'HEARTBEAT'))) ->> 'rate_limited')::boolean, 'hourly bound applies');
reset role;

-- Retention removes rows older than 30 days and is not callable by clients.
insert into public.offer_telemetry_events(event_id, occurred_at, correlation_id, app_id, app_version, android_api, provider, stage, received_at)
values (gen_random_uuid(), now() - interval '40 days', 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', 'sabbaq', '1', 34, 'jeeny', 'HEARTBEAT', now() - interval '40 days');
select pg_temp.expect(public.purge_offer_telemetry() = 1, 'old row purged');
set role authenticated;
set request.jwt.claim.sub = '40000000-0000-0000-0000-000000000001';
do $$ begin
    perform public.purge_offer_telemetry();
    raise exception 'EXPECTATION FAILED: client ran retention';
exception when insufficient_privilege then null;
end $$;
reset role;
