-- Review fixes (0021). Run after bootstrap.sql, all migrations and the earlier suites.
\set ON_ERROR_STOP on

create or replace function pg_temp.expect(p_ok boolean, p_what text) returns void language plpgsql as $$
begin
    if not coalesce(p_ok, false) then
        raise exception 'EXPECTATION FAILED: %', p_what;
    end if;
end;
$$;
grant execute on function pg_temp.expect(boolean, text) to authenticated, anon;

-- H-01: addresses with spaces and Arabic are percent-encoded.
select pg_temp.expect(private.url_encode('حي العليا ?&+/') = '%D8%AD%D9%8A%20%D8%A7%D9%84%D8%B9%D9%84%D9%8A%D8%A7%20%3F%26%2B%2F', 'arabic, space and reserved characters encoded');
select pg_temp.expect(private.url_encode('Az09-._~') = 'Az09-._~', 'unreserved characters stay');

-- H-02: no phone (Google sign-in) belongs to SA; Jordan stays Jordan.
select pg_temp.expect(private.zone_country(null) = 'SA' and private.zone_country('') = 'SA', 'no phone is SA');
select pg_temp.expect(private.zone_country('+962790000000') = 'JO', 'Jordan by phone');
select pg_temp.expect(private.zone_country('+966500000000') = 'SA', 'Saudi by phone');

insert into auth.users (id, email, phone, phone_confirmed_at, raw_app_meta_data, raw_user_meta_data) values
    ('f1e00000-0000-0000-0000-000000000001', 'fix-referrer@example.test', null, null, '{"provider":"google","providers":["google"]}'::jsonb, '{"full_name":"Fix Referrer"}'::jsonb),
    ('f1e00000-0000-0000-0000-000000000002', 'fix-referred@example.test', null, null, '{"provider":"google","providers":["google"]}'::jsonb, '{"full_name":"Fix Referred"}'::jsonb),
    ('f1e00000-0000-0000-0000-000000000003', 'fix-reuser@example.test', null, null, '{"provider":"google","providers":["google"]}'::jsonb, '{"full_name":"Fix Reuser"}'::jsonb),
    ('f1e00000-0000-0000-0000-000000000004', 'fix-other@example.test', null, null, '{"provider":"google","providers":["google"]}'::jsonb, '{"full_name":"Fix Other"}'::jsonb);
do $$
declare v uuid;
begin
    foreach v in array array['f1e00000-0000-0000-0000-000000000001', 'f1e00000-0000-0000-0000-000000000002',
                             'f1e00000-0000-0000-0000-000000000003', 'f1e00000-0000-0000-0000-000000000004']::uuid[] loop
        perform set_config('request.jwt.claim.sub', v::text, true);
        perform set_config('role', 'authenticated', true);
        perform public.ensure_driver_profile();
        perform set_config('role', 'postgres', true);
    end loop;
end $$;
reset role;

-- C-01: a code redeemed by an account that was then deleted can never be redeemed again.
insert into public.activation_codes (code, days) values ('F1E0-0000-0000-0001', 30);
set role authenticated;
set request.jwt.claim.sub = 'f1e00000-0000-0000-0000-000000000003';
select pg_temp.expect((public.redeem_code('F1E0-0000-0000-0001', 'device-fix-reuser-000001') ->> 'ok')::boolean, 'first redemption works');
reset role;
delete from auth.users where id = 'f1e00000-0000-0000-0000-000000000003';
select pg_temp.expect((select redeemed_by is null and redeemed_at is not null from public.activation_codes where code = 'F1E0-0000-0000-0001'), 'deletion leaves redeemed_at');
set role authenticated;
set request.jwt.claim.sub = 'f1e00000-0000-0000-0000-000000000004';
select pg_temp.expect(public.redeem_code('F1E0-0000-0000-0001', 'device-fix-other-0000001') ->> 'error' = 'invalid_code', 'the same code is refused afterwards');
reset role;

-- H-07: the referrer's reward makes a pending account active; negative days are no payment.
update public.drivers set status = 'pending', pro_until = null, trial_until = null where id = 'f1e00000-0000-0000-0000-000000000001';
update public.drivers set referred_by = 'f1e00000-0000-0000-0000-000000000001', has_ever_paid = false, pro_until = null,
       device_id = 'device-fix-referred-00001', phone = null where id = 'f1e00000-0000-0000-0000-000000000002';
insert into public.referral_claims (referred_id, referrer_id) values ('f1e00000-0000-0000-0000-000000000002', 'f1e00000-0000-0000-0000-000000000001');
update public.drivers set pro_until = now() - interval '1 day' where id = 'f1e00000-0000-0000-0000-000000000002';
select pg_temp.expect(not (select has_ever_paid from public.drivers where id = 'f1e00000-0000-0000-0000-000000000002'), 'a date in the past is not a payment');
update public.drivers set pro_until = now() + interval '30 days' where id = 'f1e00000-0000-0000-0000-000000000002';
select pg_temp.expect((select status::text = 'active' and pro_until > now() from public.drivers where id = 'f1e00000-0000-0000-0000-000000000001'),
    'the pending referrer is rewarded and becomes active');

-- M-11: the same device written in the paying update is caught.
insert into auth.users (id, email, raw_app_meta_data, raw_user_meta_data) values
    ('f1e00000-0000-0000-0000-000000000005', 'fix-same@example.test', '{"provider":"google","providers":["google"]}'::jsonb, '{"full_name":"Fix Same"}'::jsonb);
set role authenticated;
set request.jwt.claim.sub = 'f1e00000-0000-0000-0000-000000000005';
select public.ensure_driver_profile();
reset role;
update public.drivers set device_id = 'device-fix-referrer-00001' where id = 'f1e00000-0000-0000-0000-000000000001';
update public.drivers set referred_by = 'f1e00000-0000-0000-0000-000000000001', has_ever_paid = false, pro_until = null, device_id = null
    where id = 'f1e00000-0000-0000-0000-000000000005';
insert into public.referral_claims (referred_id, referrer_id) values ('f1e00000-0000-0000-0000-000000000005', 'f1e00000-0000-0000-0000-000000000001');
update public.drivers set pro_until = now() + interval '30 days', device_id = 'device-fix-referrer-00001' where id = 'f1e00000-0000-0000-0000-000000000005';
select pg_temp.expect((select status = 'rejected_fraud' from public.referral_claims where referred_id = 'f1e00000-0000-0000-0000-000000000005'),
    'the device set in the same update counts');

-- H-06: a pending or expired account spends no paid Google quota.
update public.drivers set status = 'pending', pro_until = null, trial_until = null where id = 'f1e00000-0000-0000-0000-000000000004';
set role authenticated;
set request.jwt.claim.sub = 'f1e00000-0000-0000-0000-000000000004';
do $$ begin
    perform public.geocode_resolve('x', 'حي العليا');
    raise exception 'EXPECTATION FAILED: a pending account used paid geocoding';
exception when insufficient_privilege then null;
end $$;

-- M-12: drivers no longer read app_config directly.
select pg_temp.expect((select count(*) from public.app_config) = 0, 'app_config is hidden from drivers');
reset role;

-- H-05: three accounts without a trial or subscription teach the fleet nothing.
insert into auth.users (id, email, raw_app_meta_data, raw_user_meta_data)
select ('f1e10000-0000-0000-0000-00000000000' || n)::uuid, 'fix-free-' || n || '@example.test',
       '{"provider":"google","providers":["google"]}'::jsonb, '{}'::jsonb
from generate_series(1, 3) n;
insert into public.drivers (id, status) select ('f1e10000-0000-0000-0000-00000000000' || n)::uuid, 'pending' from generate_series(1, 3) n
on conflict (id) do update set status = 'pending', trial_until = null, pro_until = null;
insert into public.screen_samples (ingested_by, provider, app_version, reason, signature, nodes)
select ('f1e10000-0000-0000-0000-00000000000' || n)::uuid, 'jeeny', '2.0.4', 'miss_no_minutes', lpad(to_hex(4096 + n), 16, 'b'),
       '[{"c":"TextView","t":"5 دقيقات"}]'::jsonb
from generate_series(1, 3) n;
select private.learn_from_fleet();
select pg_temp.expect(not exists (select 1 from public.lexicon_proposals where word = 'دقيقات'), 'free accounts propose nothing');
