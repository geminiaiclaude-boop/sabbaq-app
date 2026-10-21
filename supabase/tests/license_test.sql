-- Behavioural tests for the licensing API. Any failed expectation raises,
-- which makes psql (ON_ERROR_STOP) exit non-zero and fails CI.
\set ON_ERROR_STOP on

insert into auth.users (id, email, phone, phone_confirmed_at, raw_app_meta_data) values
    ('00000000-0000-0000-0000-00000000000a', 'admin@example.test', null, null, '{}'::jsonb),
    ('00000000-0000-0000-0000-00000000000b', 'driver@example.test', '+966512345678', now(),
     '{"provider":"phone","providers":["phone"]}'::jsonb),
    ('00000000-0000-0000-0000-00000000000c', 'other@example.test', '+966512345679', now(),
     '{"provider":"phone","providers":["phone"]}'::jsonb);
insert into public.admins (user_id) values ('00000000-0000-0000-0000-00000000000a');

create or replace function pg_temp.expect(p_ok boolean, p_what text) returns void language plpgsql as $$
begin
    if not coalesce(p_ok, false) then raise exception 'EXPECTATION FAILED: %', p_what; end if;
end $$;
grant execute on function pg_temp.expect(boolean, text) to authenticated;

set role authenticated;

-- driver registers and is pending; engine state reflects it
set request.jwt.claim.sub = '00000000-0000-0000-0000-00000000000b';
select public.register_driver('Driver One', '+962 79-000-0000');
select pg_temp.expect(public.license_status('device-aaaaaaaaaaaaaaaa') ->> 'state' = 'pending', 'new driver is pending');

-- Helpers and direct mutation are not a client API, and pending accounts cannot
-- upload large calibration captures.
do $$ begin
    perform public.config_int('trial_days');
    raise exception 'EXPECTATION FAILED: driver called private config helper';
exception when insufficient_privilege then null; end $$;
do $$ begin
    update public.drivers set status = 'active' where id = auth.uid();
    raise exception 'EXPECTATION FAILED: driver updated own licence state';
exception when insufficient_privilege then null; end $$;
do $$ begin
    insert into public.calibration_captures (package, tree) values ('app.sabbaq', '{}'::jsonb);
    raise exception 'EXPECTATION FAILED: pending driver uploaded a capture';
exception when insufficient_privilege then null; end $$;

-- a driver cannot call admin functions
do $$ begin
    perform public.admin_generate_codes(30, 1, null);
    raise exception 'EXPECTATION FAILED: driver generated codes';
exception when insufficient_privilege then null; end $$;

-- a driver cannot read codes or other drivers
select pg_temp.expect((select count(*) from public.activation_codes) = 0, 'driver cannot see codes');

-- admin generates a code
set request.jwt.claim.sub = '00000000-0000-0000-0000-00000000000a';
do $$ begin
    perform public.admin_generate_codes(0, 1, null);
    raise exception 'EXPECTATION FAILED: admin generated a zero-day code';
exception when invalid_parameter_value then null; end $$;
do $$ begin
    perform public.admin_set_config('trial_days', '1.5'::jsonb);
    raise exception 'EXPECTATION FAILED: admin set a fractional day count';
exception when invalid_parameter_value then null; end $$;
create temp table issued as select public.admin_generate_codes(30, 2, 'test') as code;
select pg_temp.expect((select count(*) from issued) = 2, 'two codes issued');
select pg_temp.expect((select bool_and(code ~ '^[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){3}$') from issued), 'code format');

-- wrong code is rejected and counted
set request.jwt.claim.sub = '00000000-0000-0000-0000-00000000000b';
select pg_temp.expect((select count(*) from public.activation_codes) = 0, 'driver cannot read issued codes');
do $$ begin
    perform public.redeem_code((select min(code) from issued), null);
    raise exception 'EXPECTATION FAILED: code redeemed without a device';
exception when invalid_parameter_value then null; end $$;
do $$ begin
    perform public.license_status('device-aaaaaaaaaaaaaaaa', 0);
    raise exception 'EXPECTATION FAILED: licence accepted invalid version code';
exception when invalid_parameter_value then null; end $$;
select pg_temp.expect(public.redeem_code('AAAA-AAAA-AAAA-AAAA', 'device-aaaaaaaaaaaaaaaa') ->> 'error' = 'invalid_code', 'invalid code rejected');

-- valid code (typed without dashes, lower case) activates and binds the device
select pg_temp.expect(
    (public.redeem_code(lower(replace((select min(code) from issued), '-', '')), 'device-aaaaaaaaaaaaaaaa') ->> 'ok')::boolean,
    'valid code redeemed');
select pg_temp.expect(public.license_status('device-aaaaaaaaaaaaaaaa') ->> 'state' = 'active', 'active after redeem');
insert into public.calibration_captures (package, tree) values ('app.sabbaq', '{}'::jsonb);
select pg_temp.expect((select count(*) from public.calibration_captures) = 0, 'driver cannot read calibration captures');

-- code is single use
select pg_temp.expect(public.redeem_code((select min(code) from issued), 'device-aaaaaaaaaaaaaaaa') ->> 'error' = 'invalid_code', 'code single use');

-- another phone with the same account is refused
select pg_temp.expect(public.license_status('device-bbbbbbbbbbbbbbbb') ->> 'state' = 'device_mismatch', 'second device refused');
select pg_temp.expect(public.redeem_code((select max(code) from issued), 'device-bbbbbbbbbbbbbbbb') ->> 'error' = 'device_mismatch', 'redeem from second device refused');

-- second code stacks on top of the first
select pg_temp.expect((public.redeem_code((select max(code) from issued), 'device-aaaaaaaaaaaaaaaa') ->> 'ok')::boolean, 'second code redeemed');
reset role;
select pg_temp.expect(
    (select pro_until between now() + interval '59 days' and now() + interval '61 days' from public.drivers where id = '00000000-0000-0000-0000-00000000000b'),
    'codes stack to 60 days');
set role authenticated;

-- admin resets the device; new phone binds
set request.jwt.claim.sub = '00000000-0000-0000-0000-00000000000a';
select public.admin_reset_device('00000000-0000-0000-0000-00000000000b');
set request.jwt.claim.sub = '00000000-0000-0000-0000-00000000000b';
select pg_temp.expect(public.license_status('device-bbbbbbbbbbbbbbbb') ->> 'state' = 'active', 'new device bound after reset');

-- ban wins over a paid period
set request.jwt.claim.sub = '00000000-0000-0000-0000-00000000000a';
select public.admin_set_status('00000000-0000-0000-0000-00000000000b', 'banned', 'abuse');
set request.jwt.claim.sub = '00000000-0000-0000-0000-00000000000b';
select pg_temp.expect(public.license_status('device-bbbbbbbbbbbbbbbb') ->> 'state' = 'banned', 'banned driver blocked');

-- brute force is throttled
set request.jwt.claim.sub = '00000000-0000-0000-0000-00000000000c';
select public.register_driver('Driver Two', '0790000001');
do $$ declare i int; begin
    for i in 1..10 loop perform public.redeem_code('ZZZZ-ZZZZ-ZZZZ-ZZZZ', 'device-cccccccccccccccc'); end loop;
end $$;
select pg_temp.expect(public.redeem_code('ZZZZ-ZZZZ-ZZZZ-ZZZZ', 'device-cccccccccccccccc') ->> 'error' = 'rate_limited', 'redeem throttled');

-- Trials now require a clear user action. Admin status changes do not mint them.
set request.jwt.claim.sub = '00000000-0000-0000-0000-00000000000a';
select public.admin_set_commercial_policy(true, 3, 1875, 30, null);
set request.jwt.claim.sub = '00000000-0000-0000-0000-00000000000c';
select pg_temp.expect((public.start_trial('device-cccccccccccccccc', 1) ->> 'created')::boolean, 'explicit trial claim');
select pg_temp.expect(public.license_status('device-cccccccccccccccc') ->> 'state' = 'trial', 'trial after explicit claim');

-- a driver only sees its own row
select pg_temp.expect((select count(*) from public.drivers) = 1, 'driver sees only itself');

-- profile versions only move forward
set request.jwt.claim.sub = '00000000-0000-0000-0000-00000000000a';
select public.admin_upsert_profile('{"id":"genie","name":"Genie","packages":["x.y"],"acceptLabels":["accept"],"currencyTokens":["jd"],"version":1}'::jsonb);
do $$ begin
    perform public.admin_upsert_profile('{"id":"genie","name":"Genie","packages":["x.y"],"acceptLabels":["accept"],"currencyTokens":["jd"],"version":1}'::jsonb);
    raise exception 'EXPECTATION FAILED: profile version did not have to increase';
exception when invalid_parameter_value then null; end $$;
do $$ begin
    perform public.admin_upsert_profile('{"id":"decimal","packages":["x.y"],"acceptLabels":["accept"],"currencyTokens":["jd"],"version":1.5}'::jsonb);
    raise exception 'EXPECTATION FAILED: profile accepted fractional version';
exception when invalid_parameter_value then null; end $$;

-- anonymous callers get nothing
reset role;
set role anon;
do $$ begin
    perform public.license_status('device-aaaaaaaaaaaaaaaa');
    raise exception 'EXPECTATION FAILED: anon called license_status';
exception when insufficient_privilege then null; end $$;
reset role;

-- The migration removes PostgreSQL's implicit EXECUTE-on-PUBLIC default for
-- functions created later by the migration role.
drop function if exists public.future_default_privilege_probe();
create function public.future_default_privilege_probe() returns text language sql as $$ select 'ok'::text $$;
set role authenticated;
do $$ begin
    perform public.future_default_privilege_probe();
    raise exception 'EXPECTATION FAILED: later function was executable by driver';
exception when insufficient_privilege then null; end $$;
reset role;
drop function public.future_default_privilege_probe();

\echo 'ALL LICENSE TESTS PASSED'
