-- Saudi identity, commercial policy, and trial contract. Run after bootstrap.sql and
-- migrations 0001..0004 on a fresh local PostgreSQL database.
\set ON_ERROR_STOP on

create or replace function pg_temp.expect(p_ok boolean, p_what text) returns void language plpgsql as $$
begin
    if not coalesce(p_ok, false) then
        raise exception 'EXPECTATION FAILED: %', p_what;
    end if;
end;
$$;
grant execute on function pg_temp.expect(boolean, text) to authenticated;

create temp table initial_commercial_audit as
select count(*) as policy_mutations from public.audit_log where action = 'set_commercial_policy';
grant select on initial_commercial_audit to authenticated;

-- These auth rows model server-auth fields only. Provider authorization comes from
-- raw_app_meta_data; raw_user_meta_data is deliberately unable to claim Google.
insert into auth.users (
    id, email, phone, phone_confirmed_at, raw_app_meta_data, raw_user_meta_data
) values
    ('10000000-0000-0000-0000-000000000001', 'admin@example.test', null, null, '{}'::jsonb, '{}'::jsonb),
    ('10000000-0000-0000-0000-000000000002', 'google@example.test', null, null,
     '{"provider":"google","providers":["google"]}'::jsonb,
     '{"full_name":"  Google Driver  ","provider":"phone","status":"active"}'::jsonb),
    ('10000000-0000-0000-0000-000000000003', 'phone@example.test', '+966512345678', now(),
     '{"provider":"phone","providers":["phone"]}'::jsonb, '{}'::jsonb),
    ('10000000-0000-0000-0000-000000000004', 'unconfirmed@example.test', '+966512345679', null,
     '{"provider":"phone","providers":["phone"]}'::jsonb, '{}'::jsonb),
    ('10000000-0000-0000-0000-000000000005', 'metadata-spoof@example.test', null, null,
     '{"provider":"email","providers":["email"]}'::jsonb,
     '{"full_name":"Spoofed","provider":"google","roles":["admin"],"trial_days":999}'::jsonb),
    ('10000000-0000-0000-0000-000000000006', 'second-phone@example.test', '+966512345680', now(),
     '{"provider":"phone","providers":["phone"]}'::jsonb, '{}'::jsonb),
    ('10000000-0000-0000-0000-000000000007', 'wrapper@example.test', '+966512345681', now(),
     '{"provider":"phone","providers":["phone"]}'::jsonb, '{}'::jsonb),
    ('10000000-0000-0000-0000-000000000009', 'digits-phone@example.test', '966512345682', now(),
     '{"provider":"phone","providers":["phone"]}'::jsonb, '{}'::jsonb),
    ('10000000-0000-0000-0000-000000000010', 'null-phone@example.test', null, now(),
     '{"provider":"phone","providers":["phone"]}'::jsonb, '{}'::jsonb);
insert into public.admins (user_id) values ('10000000-0000-0000-0000-000000000001');

-- A metadata-bearing auth insert no longer creates a public profile automatically.
select pg_temp.expect(
    not exists (select 1 from public.drivers where id = '10000000-0000-0000-0000-000000000002'),
    'signup metadata trigger is removed; profile creation requires verified ensure RPC'
);

-- The local stand-in exercises the auth.users trigger that blocks a newly supplied
-- password hash. The exception block rolls back the disallowed auth row as well.
do $$ begin
    insert into auth.users (id, email, encrypted_password)
    values ('10000000-0000-0000-0000-000000000008', 'password@example.test', '$2a$blocked');
    raise exception 'EXPECTATION FAILED: new password signup was accepted';
exception when invalid_parameter_value then null;
end $$;
select pg_temp.expect(
    not exists (select 1 from auth.users where id = '10000000-0000-0000-0000-000000000008'),
    'password-signup rejection does not create an auth account'
);

-- public_offer is the intentionally anonymous, read-only surface. Its policy table
-- is still not directly readable by either anonymous or authenticated clients.
select pg_temp.expect(
    has_function_privilege('anon', 'public.public_offer()', 'EXECUTE'),
    'anon may execute public_offer without a session'
);
select pg_temp.expect(
    not has_table_privilege('anon', 'public.commercial_policy', 'SELECT')
    and not has_table_privilege('authenticated', 'public.commercial_policy', 'SELECT'),
    'commercial policy has no direct client table read grant'
);
set role anon;
select 1 / case when public.public_offer() =
    '{"currency":"SAR","trial_days":3,"price_minor":1875,"trial_enabled":true,"subscription_days":30,"purchase_contact_url":null,"admin_whatsapp":null}'::jsonb
    then 1 else 0 end as anon_offer_defaults_assertion;
reset role;

-- Google is derived from raw_app_meta_data and can legitimately produce NULL phone.
set role authenticated;
set request.jwt.claim.sub = '10000000-0000-0000-0000-000000000002';
select pg_temp.expect(
    (public.ensure_driver_profile() ->> 'created')::boolean,
    'verified Google identity creates a profile explicitly'
);
select pg_temp.expect(
    (select full_name = 'Google Driver' and phone is null and status = 'pending'
     from public.drivers where id = auth.uid()),
    'Google profile copies personal name only and never fabricates a phone or active status'
);

-- A confirmed Saudi phone is accepted only from auth.users; the legacy wrapper has
-- the same behavior and ignores its unverified p_phone argument.
set request.jwt.claim.sub = '10000000-0000-0000-0000-000000000003';
select pg_temp.expect(
    (public.ensure_driver_profile('Phone Driver') ->> 'created')::boolean,
    'confirmed Saudi phone creates a profile'
);
select pg_temp.expect(
    (select full_name = 'Phone Driver' and phone = '+966512345678'
     from public.drivers where id = auth.uid()),
    'confirmed Saudi auth.users.phone is the only copied phone'
);
set request.jwt.claim.sub = '10000000-0000-0000-0000-000000000007';
select public.register_driver('Wrapper Driver', '+962 79-000-0000');
select pg_temp.expect(
    (select phone = '+966512345681' from public.drivers where id = auth.uid()),
    'legacy register_driver signature cannot write its p_phone argument'
);

set request.jwt.claim.sub = '10000000-0000-0000-0000-000000000009';
select public.ensure_driver_profile('Canonical Phone');
select pg_temp.expect(
    (select phone = '+966512345682' from public.drivers where id = auth.uid()),
    'trusted confirmed digits-only auth phone becomes canonical E164'
);
set request.jwt.claim.sub = '10000000-0000-0000-0000-000000000010';
do $$ begin
    perform public.ensure_driver_profile('Null Phone');
    raise exception 'EXPECTATION FAILED: NULL phone passed identity validation';
exception when insufficient_privilege then null;
end $$;

-- Neither an unconfirmed phone nor user-controlled provider metadata may authorize
-- a profile. Both failures happen inside SECURITY DEFINER identity validation.
set request.jwt.claim.sub = '10000000-0000-0000-0000-000000000004';
do $$ begin
    perform public.ensure_driver_profile('Unconfirmed Phone');
    raise exception 'EXPECTATION FAILED: unconfirmed phone created a profile';
exception when insufficient_privilege then null;
end $$;
set request.jwt.claim.sub = '10000000-0000-0000-0000-000000000005';
do $$ begin
    perform public.ensure_driver_profile('Spoofed Provider');
    raise exception 'EXPECTATION FAILED: raw_user_meta_data provider created a profile';
exception when insufficient_privilege then null;
end $$;

-- The default policy is admin-readable but admin-only to change. All commercial
-- fields move in one atomic RPC and the old app_config key rejects a silent no-op.
set request.jwt.claim.sub = '10000000-0000-0000-0000-000000000003';
do $$ begin
    perform public.admin_set_commercial_policy(true, 7, 2500, 31, 'https://example.test/contact');
    raise exception 'EXPECTATION FAILED: non-admin changed commercial policy';
exception when insufficient_privilege then null;
end $$;
set request.jwt.claim.sub = '10000000-0000-0000-0000-000000000001';
select pg_temp.expect(
    (public.admin_commercial_policy() ->> 'price_minor')::integer = 1875,
    'admin sees default 1875-halalah policy'
);
select pg_temp.expect(
    (public.admin_set_commercial_policy(true, 7, 2500, 31, 'https://example.test/contact') ->> 'currency') = 'SAR',
    'admin atomically replaces the commercial policy'
);
select pg_temp.expect(
    public.public_offer() =
    '{"currency":"SAR","trial_days":7,"price_minor":2500,"trial_enabled":true,"subscription_days":31,"purchase_contact_url":"https://example.test/contact"}'::jsonb,
    'public offer observes all fields from one policy update'
);
do $$ begin
    perform public.admin_set_commercial_policy(true, 0, 2500, 31, null);
    raise exception 'EXPECTATION FAILED: zero trial days accepted';
exception when invalid_parameter_value then null;
end $$;
do $$ begin
    perform public.admin_set_commercial_policy(true, 7, 2500, 31, 'http://not-https.test');
    raise exception 'EXPECTATION FAILED: non-HTTPS contact accepted';
exception when invalid_parameter_value then null;
end $$;
do $$ begin
    perform public.admin_set_config('trial_days', '4'::jsonb);
    raise exception 'EXPECTATION FAILED: retired app_config.trial_days was silently accepted';
exception when invalid_parameter_value then null;
end $$;
do $$ begin
    perform public.admin_set_commercial_policy(true, 7, 2500, 31, 'https://user:pass@example.test');
    raise exception 'EXPECTATION FAILED: URL credentials were accepted';
exception when invalid_parameter_value then null;
end $$;
select pg_temp.expect(
    (select count(*) from public.audit_log where action = 'set_commercial_policy') =
        (select policy_mutations + 1 from initial_commercial_audit),
    'one successful policy mutation creates one audit row'
);
-- Restore the contract defaults before exercising the fixed 3-day new-trial rule.
select public.admin_set_commercial_policy(true, 3, 1875, 30, null);

-- admin_set_status can change an account state but cannot auto-mint a trial.
select public.admin_set_status('10000000-0000-0000-0000-000000000002', 'active', 'verified');
set request.jwt.claim.sub = '10000000-0000-0000-0000-000000000002';
select pg_temp.expect(
    public.license_status('device-google-00000000', 1) ->> 'state' = 'expired',
    'admin activation does not auto-grant a trial'
);
select pg_temp.expect(
    (public.license_status('device-google-00000000', 1) ->> 'trial_eligible')::boolean,
    'verified account is eligible before the first explicit trial claim'
);

-- A clear user action grants exactly one 3-day account claim. A repeat is a safe
-- acknowledgement with created=false; revalidation is still required for a lease.
select pg_temp.expect(
    (public.start_trial('device-google-00000000', 1) ->> 'created')::boolean,
    'first explicit start_trial writes a claim'
);
select pg_temp.expect(
    public.license_status('device-google-00000000', 1) ->> 'state' = 'trial',
    'unexpired enabled trial is a trial license state'
);
select pg_temp.expect(
    (public.license_status('device-google-00000000', 1) ->> 'trial_days')::integer = 3
    and not (public.license_status('device-google-00000000', 1) ->> 'trial_eligible')::boolean,
    'license status exposes current policy and one-time claim eligibility'
);
select pg_temp.expect(
    not (public.start_trial('device-google-00000000', 1) ->> 'created')::boolean,
    'retry does not extend or recreate the trial'
);
reset role;
select pg_temp.expect(
    (select count(*) = 1 from public.trial_claims where account_id = '10000000-0000-0000-0000-000000000002'),
    'account trial ledger has exactly one row'
);
select pg_temp.expect(
    (select trial_until between now() + interval '2 days 23 hours' and now() + interval '3 days 1 hour'
     from public.trial_claims where account_id = '10000000-0000-0000-0000-000000000002'),
    'default first claim uses a three-day server interval'
);
set role authenticated;

-- A supplied device ID gets only best-effort protection: another verified account
-- cannot use this recorded ID for a second trial, but no attestation is claimed.
set request.jwt.claim.sub = '10000000-0000-0000-0000-000000000006';
select public.ensure_driver_profile('Second Phone');
select pg_temp.expect(
    not (public.license_status('device-google-00000000', 1) ->> 'trial_eligible')::boolean,
    'a previously claimed reported device is not advertised as trial eligible'
);
select pg_temp.expect(
    public.start_trial('device-google-00000000', 1) ->> 'error' = 'device_trial_already_claimed',
    'reported device ledger rejects a second account on the same device id'
);

-- Turning trials off never edits dates, but it immediately removes a trial lease.
set request.jwt.claim.sub = '10000000-0000-0000-0000-000000000001';
select public.admin_set_commercial_policy(false, 3, 1875, 30, null);
set request.jwt.claim.sub = '10000000-0000-0000-0000-000000000002';
select pg_temp.expect(
    public.license_status('device-google-00000000', 1) ->> 'state' = 'expired'
    and (public.license_status('device-google-00000000', 1) -> 'valid_until') = 'null'::jsonb
    and (public.license_status('device-google-00000000', 1) -> 'trial_until') <> 'null'::jsonb,
    'disabled trial stops the lease safely without erasing trial_until'
);

-- A paid period remains valid while trials are disabled; price display itself never
-- creates entitlement. The capture RLS policy follows that paid entitlement.
set request.jwt.claim.sub = '10000000-0000-0000-0000-000000000001';
select public.admin_grant_days('10000000-0000-0000-0000-000000000002', 30);
set request.jwt.claim.sub = '10000000-0000-0000-0000-000000000002';
select pg_temp.expect(
    public.license_status('device-google-00000000', 1) ->> 'state' = 'active',
    'disabled global trial does not stop a paid pro_until entitlement'
);
insert into public.calibration_captures (package, tree) values ('app.qannas', '{}'::jsonb);

set request.jwt.claim.sub = '10000000-0000-0000-0000-000000000001';
select public.admin_set_status('10000000-0000-0000-0000-000000000002', 'pending');
set request.jwt.claim.sub = '10000000-0000-0000-0000-000000000002';
select pg_temp.expect(
    public.license_status('device-google-00000000', 1) ->> 'state' = 'pending',
    'administrative pending suspension overrides stored paid dates'
);
do $$ begin
    insert into public.calibration_captures (package, tree) values ('app.qannas', '{}'::jsonb);
    raise exception 'EXPECTATION FAILED: pending account ingested a capture';
exception when insufficient_privilege then null;
end $$;
set request.jwt.claim.sub = '10000000-0000-0000-0000-000000000001';
select public.admin_grant_days('10000000-0000-0000-0000-000000000009', 30);
select public.admin_set_status('10000000-0000-0000-0000-000000000009', 'active');
set request.jwt.claim.sub = '10000000-0000-0000-0000-000000000009';
select pg_temp.expect(
    not (public.license_status('device-canonical-00000000', 1) ->> 'trial_eligible')::boolean,
    'paid account without a trial claim is still not offered a trial'
);

-- The privileged implementation helpers and mutable tables have no accidental
-- client surface beyond the documented RPC grants.
reset role;
select pg_temp.expect(
    not has_function_privilege('authenticated', 'public.is_supported_customer_identity(uuid)', 'EXECUTE')
    and not has_function_privilege('authenticated', 'public.reject_new_password_signup()', 'EXECUTE')
    and not has_table_privilege('authenticated', 'public.trial_claims', 'SELECT,INSERT,UPDATE,DELETE')
    and not has_table_privilege('authenticated', 'public.trial_device_claims', 'SELECT,INSERT,UPDATE,DELETE'),
    'identity and claim internals are not client-callable or client-readable'
);

\echo 'ALL SAUDI COMMERCIAL/TRIAL TESTS PASSED'
