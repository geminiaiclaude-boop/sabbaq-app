-- Contract tests for the post-signup driver profile and explicit admin status RPC.
-- Run after bootstrap.sql and migrations; errors make psql (ON_ERROR_STOP) fail.
\set ON_ERROR_STOP on

-- The production auth.users table has this field. Add it only to the local minimal
-- bootstrap stand-in rather than changing the shared bootstrap or a prior migration.
alter table auth.users add column if not exists raw_user_meta_data jsonb;

create or replace function pg_temp.expect(p_ok boolean, p_what text) returns void language plpgsql as $$
begin
    if not coalesce(p_ok, false) then
        raise exception 'EXPECTATION FAILED: %', p_what;
    end if;
end $$;
grant execute on function pg_temp.expect(boolean, text) to authenticated;

-- The new RPC is callable by authenticated users only; authorization is checked
-- inside the SECURITY DEFINER function rather than exposed by its SQL grant.
select pg_temp.expect(
    has_function_privilege('authenticated', 'public.admin_session_status()', 'EXECUTE'),
    'authenticated can execute admin_session_status'
);
select pg_temp.expect(
    not has_function_privilege('anon', 'public.admin_session_status()', 'EXECUTE'),
    'anon cannot execute admin_session_status'
);
select pg_temp.expect(
    not has_function_privilege('authenticated', 'public.create_driver_from_signup_metadata()', 'EXECUTE'),
    'authenticated cannot call the privileged signup trigger function directly'
);

-- Valid metadata creates only the matching pending driver row and applies the same
-- canonical whitespace/hyphen cleanup used by register_driver before table checks.
insert into auth.users (id, email, raw_user_meta_data) values
    ('00000000-0000-0000-0000-00000000000d', 'valid-signup@example.test',
     '{"full_name":"  Valid Driver  ","phone":"+962 79-000-0000","status":"active","is_admin":true}'::jsonb),
    ('00000000-0000-0000-0000-00000000000e', 'missing-metadata@example.test', null),
    ('00000000-0000-0000-0000-00000000000f', 'bad-name@example.test',
     '{"full_name":"x","phone":"+962790000000"}'::jsonb),
    ('00000000-0000-0000-0000-000000000010', 'bad-phone@example.test',
     '{"full_name":"Invalid Phone","phone":"not-a-phone"}'::jsonb),
    ('00000000-0000-0000-0000-000000000011', 'admin-create@example.test', null),
    ('00000000-0000-0000-0000-000000000012', 'empty-object@example.test', '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000013', 'missing-phone@example.test', '{"full_name":"Missing Phone"}'::jsonb);

select pg_temp.expect(
    exists (
        select 1 from public.drivers
        where id = '00000000-0000-0000-0000-00000000000d'
          and full_name = 'Valid Driver'
          and phone = '+962790000000'
          and status = 'pending'
    ),
    'valid signup metadata creates a canonical pending driver profile'
);
select pg_temp.expect(
    not exists (
        select 1 from public.drivers
        where id in (
            '00000000-0000-0000-0000-00000000000e',
            '00000000-0000-0000-0000-00000000000f',
            '00000000-0000-0000-0000-000000000010',
            '00000000-0000-0000-0000-000000000011',
            '00000000-0000-0000-0000-000000000012',
            '00000000-0000-0000-0000-000000000013'
        )
    ),
    'missing or invalid metadata does not block auth creation or create a profile'
);
select pg_temp.expect(
    not exists (select 1 from public.admins where user_id = '00000000-0000-0000-0000-00000000000d'),
    'signup metadata cannot grant admin membership'
);

-- The admin user is intentionally created without metadata to prove administrative
-- account creation is independent of the driver-profile trigger.
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
