-- Admin view of the Sabbaq build each driver runs. Run after bootstrap.sql, all migrations and
-- the earlier suites on a fresh local PostgreSQL database.
\set ON_ERROR_STOP on

create or replace function pg_temp.expect(p_ok boolean, p_what text) returns void language plpgsql as $$
begin
    if not coalesce(p_ok, false) then
        raise exception 'EXPECTATION FAILED: %', p_what;
    end if;
end;
$$;
grant execute on function pg_temp.expect(boolean, text) to authenticated, anon;

insert into auth.users (id, email, phone, phone_confirmed_at, raw_app_meta_data, raw_user_meta_data) values
    ('7e4e0000-0000-0000-0000-000000000001', 'versions-admin@example.test', null, null,
     '{"provider":"google","providers":["google"]}'::jsonb, '{}'::jsonb),
    ('7e4e0000-0000-0000-0000-000000000002', 'versions-driver@example.test', null, null,
     '{"provider":"google","providers":["google"]}'::jsonb, '{"full_name":"Version Driver"}'::jsonb);
insert into public.admins (user_id) values ('7e4e0000-0000-0000-0000-000000000001');

set role authenticated;
set request.jwt.claim.sub = '7e4e0000-0000-0000-0000-000000000002';
select public.ensure_driver_profile();
select public.license_status('device-versions-0000001', 9);
do $$ begin
    perform public.admin_driver_versions(array['7e4e0000-0000-0000-0000-000000000002'::uuid]);
    raise exception 'EXPECTATION FAILED: driver read versions';
exception when insufficient_privilege then null;
end $$;
reset role;

insert into public.offer_telemetry_events (event_id, occurred_at, correlation_id, app_id, app_version, android_api, provider, stage, ingested_by)
values (gen_random_uuid(), now(), 'cccccccccccccccccccccccccccccccc', 'sabbaq', '2.1.0', 34, 'jeeny', 'HEARTBEAT',
        '7e4e0000-0000-0000-0000-000000000002');

set role authenticated;
set request.jwt.claim.sub = '7e4e0000-0000-0000-0000-000000000001';
select pg_temp.expect((public.admin_driver_versions(array['7e4e0000-0000-0000-0000-000000000002'::uuid]) -> 0 ->> 'version_code')::int = 9,
    'licence check records the version code');
select pg_temp.expect(public.admin_driver_versions(array['7e4e0000-0000-0000-0000-000000000002'::uuid]) -> 0 ->> 'version_name' = '2.1.0',
    'latest telemetry gives the version name');
select pg_temp.expect((public.admin_driver_versions(array['7e4e0000-0000-0000-0000-000000000002'::uuid]) -> 0 ->> 'android_api')::int = 34,
    'android level is shown');
select pg_temp.expect(public.admin_driver_versions(array[]::uuid[]) = '[]'::jsonb, 'empty request is empty');
reset role;
