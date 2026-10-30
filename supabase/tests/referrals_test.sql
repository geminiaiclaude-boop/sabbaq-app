-- Referrals and rewards contract. Run after bootstrap.sql, all migrations and the earlier suites
-- on a fresh local PostgreSQL database.
\set ON_ERROR_STOP on

create or replace function pg_temp.expect(p_ok boolean, p_what text) returns void language plpgsql as $$
begin
    if not coalesce(p_ok, false) then
        raise exception 'EXPECTATION FAILED: %', p_what;
    end if;
end;
$$;
grant execute on function pg_temp.expect(boolean, text) to authenticated, anon;

insert into auth.users (id, email, phone, phone_confirmed_at, raw_app_meta_data, raw_user_meta_data)
select ('5e5e0000-0000-0000-0000-00000000000' || n)::uuid, 'ref-' || n || '@example.test', null, null,
       '{"provider":"google","providers":["google"]}'::jsonb, jsonb_build_object('full_name', 'Ref ' || n)
from generate_series(1, 9) n;
insert into public.admins (user_id) values ('5e5e0000-0000-0000-0000-000000000001');
set role authenticated;
do $$ begin
    for i in 2..9 loop
        perform set_config('request.jwt.claim.sub', '5e5e0000-0000-0000-0000-00000000000' || i, false);
        perform public.ensure_driver_profile();
    end loop;
end $$;
reset role;
-- 2 = referrer A, 3 = referred B, 4 = C, 5 = same device as A, 6 = old account, 7 = paid, 8 = fraud at payment, 9 = banned
update public.drivers set device_id = 'device-aaaaaaaaaaaaaaaa' where id in ('5e5e0000-0000-0000-0000-000000000002', '5e5e0000-0000-0000-0000-000000000005');
update public.drivers set created_at = now() - interval '10 days' where id = '5e5e0000-0000-0000-0000-000000000006';
update public.drivers set pro_until = now() + interval '5 days' where id = '5e5e0000-0000-0000-0000-000000000007';
update public.drivers set status = 'banned' where id = '5e5e0000-0000-0000-0000-000000000009';
select pg_temp.expect((select has_ever_paid from public.drivers where id = '5e5e0000-0000-0000-0000-000000000007'), 'a paid driver is marked');
select pg_temp.expect((select count(distinct referral_code) from public.drivers where id::text like '5e5e%') = 8, 'every driver has a unique code');
select pg_temp.expect((select referral_code from public.drivers where id = '5e5e0000-0000-0000-0000-000000000002') ~ '^SB-[2-9A-HJKMNP-Z]{8}$', 'code shape');

create temp table codes as select id, referral_code from public.drivers where id::text like '5e5e%';
grant select on codes to authenticated;

-- No direct table access.
set role authenticated;
set request.jwt.claim.sub = '5e5e0000-0000-0000-0000-000000000003';
do $$ begin
    perform 1 from public.referral_claims;
    raise exception 'EXPECTATION FAILED: driver read claims directly';
exception when insufficient_privilege then null;
end $$;

-- Claim rules.
select pg_temp.expect(public.claim_referral_code('nope!') ->> 'code' = 'invalid_code', 'malformed code');
select pg_temp.expect(public.claim_referral_code('SB-ZZZZZZZZ') ->> 'code' = 'invalid_code', 'unknown code');
select pg_temp.expect(public.claim_referral_code((select referral_code from codes where id = '5e5e0000-0000-0000-0000-000000000009')) ->> 'code' = 'invalid_code', 'banned referrer');
select pg_temp.expect(public.claim_referral_code((select referral_code from codes where id = '5e5e0000-0000-0000-0000-000000000003')) ->> 'code' = 'self_referral', 'own code');
select pg_temp.expect((public.claim_referral_code(lower((select referral_code from codes where id = '5e5e0000-0000-0000-0000-000000000002'))) ->> 'ok')::boolean, 'B attaches A''s code (any case)');
select pg_temp.expect(public.claim_referral_code((select referral_code from codes where id = '5e5e0000-0000-0000-0000-000000000004')) ->> 'code' = 'already_claimed', 'only one code per account');
set request.jwt.claim.sub = '5e5e0000-0000-0000-0000-000000000005';
select pg_temp.expect(public.claim_referral_code((select referral_code from codes where id = '5e5e0000-0000-0000-0000-000000000002')) ->> 'code' = 'same_device', 'same device refused');
set request.jwt.claim.sub = '5e5e0000-0000-0000-0000-000000000006';
select pg_temp.expect(public.claim_referral_code((select referral_code from codes where id = '5e5e0000-0000-0000-0000-000000000002')) ->> 'code' = 'claim_window_expired', 'window closed after 7 days');
set request.jwt.claim.sub = '5e5e0000-0000-0000-0000-000000000007';
select pg_temp.expect(public.claim_referral_code((select referral_code from codes where id = '5e5e0000-0000-0000-0000-000000000002')) ->> 'code' = 'already_paid', 'paid driver refused');
set request.jwt.claim.sub = '5e5e0000-0000-0000-0000-000000000008';
select pg_temp.expect((public.claim_referral_code((select referral_code from codes where id = '5e5e0000-0000-0000-0000-000000000002')) ->> 'ok')::boolean, 'F attaches A''s code');
set request.jwt.claim.sub = '5e5e0000-0000-0000-0000-000000000004';
select pg_temp.expect((public.claim_referral_code((select referral_code from codes where id = '5e5e0000-0000-0000-0000-000000000003')) ->> 'ok')::boolean, 'C attaches B''s code (B not paid yet)');
reset role;

-- B pays (days granted by the administration): A is rewarded once, and A does not count as paying.
set role authenticated;
set request.jwt.claim.sub = '5e5e0000-0000-0000-0000-000000000001';
select public.admin_grant_days('5e5e0000-0000-0000-0000-000000000003', 30);
reset role;
select pg_temp.expect((select status from public.referral_claims where referred_id = '5e5e0000-0000-0000-0000-000000000003') = 'rewarded', 'claim rewarded');
select pg_temp.expect((select pro_until between now() + interval '6 days 23 hours' and now() + interval '7 days 1 hour'
                       from public.drivers where id = '5e5e0000-0000-0000-0000-000000000002'), 'A got 7 days');
select pg_temp.expect(not (select has_ever_paid from public.drivers where id = '5e5e0000-0000-0000-0000-000000000002'), 'a reward is not a payment');
select pg_temp.expect((select referral_days_earned from public.drivers where id = '5e5e0000-0000-0000-0000-000000000002') = 7, 'days earned counted');
select pg_temp.expect(exists (select 1 from public.driver_notifications where driver_id = '5e5e0000-0000-0000-0000-000000000002' and kind = 'success'), 'A notified');
-- C's referrer is B; B was rewarded nothing because only C paying rewards B.
select pg_temp.expect((select referral_days_earned from public.drivers where id = '5e5e0000-0000-0000-0000-000000000003') = 0, 'B earns nothing yet');
-- A second payment by B gives nothing more.
set role authenticated;
set request.jwt.claim.sub = '5e5e0000-0000-0000-0000-000000000001';
select public.admin_grant_days('5e5e0000-0000-0000-0000-000000000003', 30);
reset role;
select pg_temp.expect((select referral_days_earned from public.drivers where id = '5e5e0000-0000-0000-0000-000000000002') = 7, 'rewarded once only');

-- F moves to A's device before paying: rejected at payment, no reward.
update public.drivers set device_id = 'device-aaaaaaaaaaaaaaaa' where id = '5e5e0000-0000-0000-0000-000000000008';
update public.drivers set pro_until = now() + interval '30 days' where id = '5e5e0000-0000-0000-0000-000000000008';
select pg_temp.expect((select status from public.referral_claims where referred_id = '5e5e0000-0000-0000-0000-000000000008') = 'rejected_fraud', 'fraud caught at payment');
select pg_temp.expect((select referral_days_earned from public.drivers where id = '5e5e0000-0000-0000-0000-000000000002') = 7, 'no reward for fraud');

-- A's page.
set role authenticated;
set request.jwt.claim.sub = '5e5e0000-0000-0000-0000-000000000002';
select pg_temp.expect((public.my_referrals() ->> 'invited')::int = 2 and (public.my_referrals() ->> 'rewarded')::int = 1, 'A sees 2 invited, 1 rewarded');
select pg_temp.expect((public.my_referrals() -> 'referrals' -> 0 ->> 'subscription') in ('active', 'awaiting_first_payment'), 'states listed');
select pg_temp.expect((public.my_referrals() ->> 'can_claim')::boolean and (public.my_referrals() ->> 'days_earned')::int = 7, 'A may still enter a code and sees 7 days earned');

-- Administration.
do $$ begin
    perform public.admin_referral_settings(false);
    raise exception 'EXPECTATION FAILED: driver changed referral settings';
exception when insufficient_privilege then null;
end $$;
set request.jwt.claim.sub = '5e5e0000-0000-0000-0000-000000000001';
select pg_temp.expect((public.admin_referral_settings(null, 10, null) ->> 'reward_days')::int = 10, 'reward days changed');
select pg_temp.expect(not (public.admin_referral_settings(false) ->> 'enabled')::boolean, 'referrals paused');
select pg_temp.expect(public.admin_set_referral_code('5e5e0000-0000-0000-0000-000000000004', 'sb-agent01') = 'SB-AGENT01', 'agent code set');
do $$ begin
    perform public.admin_referral_settings(null, 500);
    raise exception 'EXPECTATION FAILED: out-of-range reward accepted';
exception when sqlstate '22023' then null;
end $$;
set request.jwt.claim.sub = '5e5e0000-0000-0000-0000-000000000005';
select pg_temp.expect(public.claim_referral_code('SB-AGENT01') ->> 'code' = 'referrals_paused', 'paused refuses new claims');
set request.jwt.claim.sub = '5e5e0000-0000-0000-0000-000000000001';
select public.admin_referral_settings(true, 7, 7);
reset role;
