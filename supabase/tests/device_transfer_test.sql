-- Self-service device transfer. Run after bootstrap.sql, all migrations and the earlier
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

insert into auth.users (id, email, phone, phone_confirmed_at, raw_app_meta_data, raw_user_meta_data) values
    ('40000000-0000-0000-0000-000000000001', 'mover@example.test', null, null,
     '{"provider":"google","providers":["google"]}'::jsonb, '{"full_name":"Device Mover"}'::jsonb),
    ('40000000-0000-0000-0000-000000000002', null, '+966512377002', now(),
     '{"provider":"phone","providers":["phone"]}'::jsonb, '{}'::jsonb);

set role anon;
do $$ begin
    perform public.transfer_device('device-transfer-anon-01');
    raise exception 'EXPECTATION FAILED: anon moved a device';
exception when insufficient_privilege then null;
end $$;
reset role;

set role authenticated;
set request.jwt.claim.sub = '40000000-0000-0000-0000-000000000001';
select public.ensure_driver_profile();
-- First device binds on the first license check.
select public.license_status('device-transfer-AAAA-01', 6);
select pg_temp.expect(public.license_status('device-transfer-BBBB-02', 6) ->> 'state' = 'device_mismatch',
    'a second device is refused before a transfer');

-- Same device: nothing moves and nothing is counted.
select pg_temp.expect((public.transfer_device('device-transfer-AAAA-01') ->> 'transferred')::boolean = false,
    'transferring to the bound device is a no-op');

-- Move to B, then back to A: both allowed.
select pg_temp.expect((public.transfer_device('device-transfer-BBBB-02') ->> 'transferred')::boolean,
    'first move to a new device succeeds');
select pg_temp.expect(public.license_status('device-transfer-BBBB-02', 6) ->> 'state' <> 'device_mismatch',
    'the new device is accepted after the move');
select pg_temp.expect(public.license_status('device-transfer-AAAA-01', 6) ->> 'state' = 'device_mismatch',
    'the previous device stops after the move');
select pg_temp.expect((public.transfer_device('device-transfer-AAAA-01') ->> 'transferred')::boolean,
    'second move within 30 days succeeds');

-- A third move within 30 days is refused with the date it becomes possible again.
create temp table third_move as select public.transfer_device('device-transfer-CCCC-03') as r;
select pg_temp.expect((select r ->> 'error' = 'transfer_limit' and (r ->> 'retry_after') is not null from third_move),
    'third move within 30 days is refused: ' || (select r::text from third_move));
select pg_temp.expect(public.license_status('device-transfer-AAAA-01', 6) ->> 'state' <> 'device_mismatch',
    'a refused move leaves the account on its current device');
reset role;

select pg_temp.expect(
    (select count(*) = 2 from public.audit_log where actor = '40000000-0000-0000-0000-000000000001' and action = 'device_transferred'),
    'every move is audited, refused attempts are not counted'
);

-- A banned account cannot move itself.
update public.drivers set status = 'banned' where id = '40000000-0000-0000-0000-000000000001';
set role authenticated;
set request.jwt.claim.sub = '40000000-0000-0000-0000-000000000001';
select pg_temp.expect(public.transfer_device('device-transfer-DDDD-04') ->> 'error' = 'account_blocked',
    'banned account cannot transfer');
reset role;

-- An account without a driver profile gets a clear answer, not an exception.
set role authenticated;
set request.jwt.claim.sub = '40000000-0000-0000-0000-000000000002';
select pg_temp.expect(public.transfer_device('device-transfer-EEEE-05') ->> 'error' = 'unregistered',
    'unregistered account is told so');
reset role;
