-- Self-service account deletion contract. Run after bootstrap.sql and all migrations
-- on a fresh local PostgreSQL database.
\set ON_ERROR_STOP on

create or replace function pg_temp.expect(p_ok boolean, p_what text) returns void language plpgsql as $$
begin
    if not coalesce(p_ok, false) then
        raise exception 'EXPECTATION FAILED: %', p_what;
    end if;
end;
$$;
grant execute on function pg_temp.expect(boolean, text) to authenticated;

insert into auth.users (id, email, phone, phone_confirmed_at, raw_app_meta_data, raw_user_meta_data) values
    -- The typed email is not provider-verified and must never be tombstoned.
    ('20000000-0000-0000-0000-000000000001', 'typed-not-verified@example.test', '+966512399001', now(),
     '{"provider":"phone","providers":["phone"]}'::jsonb, '{}'::jsonb),
    ('20000000-0000-0000-0000-000000000002', 'del-admin@example.test', null, null,
     '{"provider":"google","providers":["google"]}'::jsonb, '{}'::jsonb),
    ('20000000-0000-0000-0000-000000000003', null, '+966512399003', now(),
     '{"provider":"phone","providers":["phone"]}'::jsonb, '{}'::jsonb),
    ('20000000-0000-0000-0000-000000000005', null, '+966512399005', now(),
     '{"provider":"phone","providers":["phone"]}'::jsonb, '{}'::jsonb);
insert into public.admins (user_id) values ('20000000-0000-0000-0000-000000000002');

-- Suites share one database and earlier suites leave the commercial policy changed;
-- this suite states the policy it depends on instead of assuming the seed default.
update public.commercial_policy set trial_enabled = true, trial_days = 3 where singleton;

create temp table baseline as
select (select count(*) from public.deleted_identity_tombstones) as tombstones;
grant select on baseline to authenticated;

-- Anonymous callers have no route to the RPC.
set role anon;
do $$ begin
    perform public.delete_my_account('DELETE');
    raise exception 'EXPECTATION FAILED: anon executed delete_my_account';
exception when insufficient_privilege then null;
end $$;
reset role;

-- A driver with a consumed trial.
set role authenticated;
set request.jwt.claim.sub = '20000000-0000-0000-0000-000000000001';
select public.ensure_driver_profile('Deleting Driver');
select public.license_status('device-delete-0000001', 1);
create temp table first_trial as select public.start_trial('device-delete-0000001', 1) as result;
select pg_temp.expect(
    (select (result ->> 'created')::boolean from first_trial),
    'driver consumes a trial before deletion: ' || (select result::text from first_trial)
);

select pg_temp.expect(
    public.delete_my_account('delete') ->> 'error' = 'confirmation_required'
    and public.delete_my_account(null) ->> 'error' = 'confirmation_required',
    'deletion needs the exact confirmation token'
);
select pg_temp.expect(
    (public.delete_my_account('DELETE') ->> 'ok')::boolean,
    'driver deletes own account'
);
reset role;

select pg_temp.expect(
    not exists (select 1 from auth.users where id = '20000000-0000-0000-0000-000000000001')
    and not exists (select 1 from public.drivers where id = '20000000-0000-0000-0000-000000000001')
    and not exists (select 1 from public.trial_claims where account_id = '20000000-0000-0000-0000-000000000001')
    and not exists (select 1 from public.trial_device_claims where account_id = '20000000-0000-0000-0000-000000000001'),
    'auth row and every keyed row are removed'
);
select pg_temp.expect(
    not exists (
        select 1 from public.audit_log
        where actor = '20000000-0000-0000-0000-000000000001'
           or target = '20000000-0000-0000-0000-000000000001'
    )
    and exists (select 1 from public.audit_log where action = 'account_deleted' and actor is null),
    'audit trail keeps the event but no longer identifies the account'
);
select pg_temp.expect(
    (select count(*) from public.deleted_identity_tombstones) = (select tombstones from baseline) + 2
    and not exists (
        select 1 from public.deleted_identity_tombstones
        where identity_hash like '%966512399001%' or identity_hash like '%device-delete%'
    ),
    'a consumed trial leaves exactly phone and device digests, never raw identifiers'
);
select pg_temp.expect(
    not exists (
        select 1 from public.deleted_identity_tombstones
        where identity_hash = public.identity_digest('email', 'typed-not-verified@example.test')
    ),
    'an unverified email is never tombstoned'
);

-- Re-registering the same verified phone must not mint a second trial.
insert into auth.users (id, email, phone, phone_confirmed_at, raw_app_meta_data, raw_user_meta_data) values
    ('20000000-0000-0000-0000-000000000004', null, '966512399001', now(),
     '{"provider":"phone","providers":["phone"]}'::jsonb, '{}'::jsonb);
set role authenticated;
set request.jwt.claim.sub = '20000000-0000-0000-0000-000000000004';
select public.ensure_driver_profile('Returning Driver');
select pg_temp.expect(
    not (public.license_status('device-delete-0000002', 1) ->> 'trial_eligible')::boolean,
    're-registered identity is not offered a trial'
);
select pg_temp.expect(
    not (public.start_trial('device-delete-0000002', 1) ->> 'created')::boolean
    and public.license_status('device-delete-0000002', 1) ->> 'state' <> 'trial',
    're-registered identity cannot start a trial'
);

-- A different identity on the deleted account's device is refused the device claim.
set request.jwt.claim.sub = '20000000-0000-0000-0000-000000000003';
select public.ensure_driver_profile('Same Device Driver');
select public.license_status('device-delete-0000001', 1);
select pg_temp.expect(
    public.start_trial('device-delete-0000001', 1) ->> 'error' = 'device_trial_already_claimed',
    'deleted device id cannot claim a new trial'
);

-- An account that never consumed a trial leaves nothing behind.
set request.jwt.claim.sub = '20000000-0000-0000-0000-000000000005';
select public.ensure_driver_profile('No Trial Driver');
reset role;
create temp table before_plain as
select (select count(*) from public.deleted_identity_tombstones) as tombstones;
grant select on before_plain to authenticated;
set role authenticated;
set request.jwt.claim.sub = '20000000-0000-0000-0000-000000000005';
select pg_temp.expect((public.delete_my_account('DELETE') ->> 'ok')::boolean, 'trial-free account deletes');
reset role;
select pg_temp.expect(
    (select count(*) from public.deleted_identity_tombstones) = (select tombstones from before_plain)
    and not exists (select 1 from auth.users where id = '20000000-0000-0000-0000-000000000005'),
    'trial-free deletion stores no digest'
);

-- Admin accounts are never self-deleted.
set role authenticated;
set request.jwt.claim.sub = '20000000-0000-0000-0000-000000000002';
select pg_temp.expect(
    public.delete_my_account('DELETE') ->> 'error' = 'admin_account',
    'admin self-deletion is refused'
);
reset role;
select pg_temp.expect(
    exists (select 1 from auth.users where id = '20000000-0000-0000-0000-000000000002'),
    'admin account still exists'
);

select pg_temp.expect(
    has_function_privilege('authenticated', 'public.delete_my_account(text)', 'EXECUTE')
    and not has_function_privilege('anon', 'public.delete_my_account(text)', 'EXECUTE')
    and not has_function_privilege('authenticated', 'public.identity_digest(text, text)', 'EXECUTE')
    and not has_table_privilege('authenticated', 'public.deleted_identity_tombstones', 'SELECT,INSERT,UPDATE,DELETE'),
    'only the deletion RPC is client-callable; digests are not readable'
);

\echo 'ALL ACCOUNT DELETION TESTS PASSED'
