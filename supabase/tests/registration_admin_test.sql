-- Regression tests for the retained admin session API and the replacement of the
-- old raw_user_meta_data signup trigger. Run after bootstrap.sql and migrations.
\set ON_ERROR_STOP on

create or replace function pg_temp.expect(p_ok boolean, p_what text) returns void language plpgsql as $$
begin
    if not coalesce(p_ok, false) then
        raise exception 'EXPECTATION FAILED: %', p_what;
    end if;
end $$;
grant execute on function pg_temp.expect(boolean, text) to authenticated;

insert into auth.users (
    id, email, phone, phone_confirmed_at, raw_app_meta_data, raw_user_meta_data
) values
    ('00000000-0000-0000-0000-00000000000d', 'google@example.test', null, null,
     '{"provider":"google","providers":["google"]}'::jsonb,
     '{"full_name":"  Valid Driver  ","status":"active","is_admin":true}'::jsonb),
    ('00000000-0000-0000-0000-00000000000e', 'spoof@example.test', null, null,
     '{"provider":"email","providers":["email"]}'::jsonb,
     '{"full_name":"Spoofed","provider":"google","status":"active"}'::jsonb),
    ('00000000-0000-0000-0000-000000000011', 'admin-create@example.test', null, null,
     '{}'::jsonb, '{}'::jsonb);

-- Insert into auth.users no longer creates a profile from mutable user metadata.
select pg_temp.expect(
    not exists (select 1 from public.drivers where id = '00000000-0000-0000-0000-00000000000d'),
    'metadata signup trigger was removed'
);
select pg_temp.expect(
    has_function_privilege('authenticated', 'public.ensure_driver_profile(text)', 'EXECUTE')
    and not has_function_privilege('anon', 'public.ensure_driver_profile(text)', 'EXECUTE'),
    'ensure_driver_profile is authenticated-only'
);
select pg_temp.expect(
    has_function_privilege('authenticated', 'public.admin_session_status()', 'EXECUTE')
    and not has_function_privilege('anon', 'public.admin_session_status()', 'EXECUTE'),
    'admin_session_status remains authenticated-only'
);

set role authenticated;
set request.jwt.claim.sub = '00000000-0000-0000-0000-00000000000d';
select pg_temp.expect(
    (public.ensure_driver_profile() ->> 'created')::boolean,
    'server app metadata authorizes the Google profile RPC'
);
select pg_temp.expect(
    (select full_name = 'Valid Driver' and phone is null and status = 'pending'
     from public.drivers where id = auth.uid()),
    'Google profile keeps nullable phone and ignores metadata status/admin claims'
);

set request.jwt.claim.sub = '00000000-0000-0000-0000-00000000000e';
do $$ begin
    perform public.ensure_driver_profile('Spoofed Provider');
    raise exception 'EXPECTATION FAILED: user metadata provider authorized profile creation';
exception when insufficient_privilege then null;
end $$;

reset role;
insert into public.admins (user_id) values ('00000000-0000-0000-0000-000000000011');
set role authenticated;
set request.jwt.claim.sub = '00000000-0000-0000-0000-00000000000d';
do $$ begin
    perform public.admin_session_status();
    raise exception 'EXPECTATION FAILED: non-admin called admin_session_status';
exception when insufficient_privilege then null;
end $$;

set request.jwt.claim.sub = '00000000-0000-0000-0000-000000000011';
create temp table admin_status_audit_count as select count(*) as count from public.audit_log;
select pg_temp.expect(
    public.admin_session_status() = '{"is_admin":true}'::jsonb,
    'admin_session_status returns the explicit success contract'
);
select pg_temp.expect(
    (select count(*) from public.audit_log) = (select count from admin_status_audit_count),
    'admin_session_status performs no writes'
);
reset role;

set role anon;
do $$ begin
    perform public.admin_session_status();
    raise exception 'EXPECTATION FAILED: anon called admin_session_status';
exception when insufficient_privilege then null;
end $$;
reset role;

\echo 'ALL REGISTRATION/ADMIN CONTRACT TESTS PASSED'
