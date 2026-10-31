-- Admin console (0020): plan and live state per driver, server-side search and filters, one
-- driver's page and the activation-code statistics. Run after bootstrap.sql, all migrations and
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
    ('ac0e0000-0000-0000-0000-000000000001', 'console-admin@example.test', null, null, '{"provider":"google","providers":["google"]}'::jsonb, '{}'::jsonb),
    ('ac0e0000-0000-0000-0000-000000000002', 'console-paid@example.test', null, null, '{"provider":"google","providers":["google"]}'::jsonb, '{"full_name":"Console Paid"}'::jsonb),
    ('ac0e0000-0000-0000-0000-000000000003', 'console-trial@example.test', null, null, '{"provider":"google","providers":["google"]}'::jsonb, '{"full_name":"Console Trial"}'::jsonb),
    ('ac0e0000-0000-0000-0000-000000000004', 'console-old@example.test', null, null, '{"provider":"google","providers":["google"]}'::jsonb, '{"full_name":"Console Old"}'::jsonb),
    ('ac0e0000-0000-0000-0000-000000000005', 'console-new@example.test', null, null, '{"provider":"google","providers":["google"]}'::jsonb, '{"full_name":"Console New"}'::jsonb);
insert into public.admins (user_id) values ('ac0e0000-0000-0000-0000-000000000001');

do $$
declare v uuid;
begin
    foreach v in array array['ac0e0000-0000-0000-0000-000000000002', 'ac0e0000-0000-0000-0000-000000000003',
                             'ac0e0000-0000-0000-0000-000000000004', 'ac0e0000-0000-0000-0000-000000000005']::uuid[] loop
        perform set_config('request.jwt.claim.sub', v::text, true);
        perform set_config('role', 'authenticated', true);
        perform public.ensure_driver_profile();
        perform set_config('role', 'postgres', true);
    end loop;
end $$;
reset role;

update public.drivers set full_name = 'Console Paid', phone = '+966500000102', pro_until = now() + interval '20 days', trial_until = now() - interval '5 days'
    where id = 'ac0e0000-0000-0000-0000-000000000002';
update public.drivers set full_name = 'Console Trial', phone = '+966500000103', trial_until = now() + interval '2 days', pro_until = null
    where id = 'ac0e0000-0000-0000-0000-000000000003';
update public.drivers set full_name = 'Console Old', phone = '+966500000104', pro_until = now() - interval '3 days', trial_until = null
    where id = 'ac0e0000-0000-0000-0000-000000000004';
update public.drivers set full_name = 'Console New', phone = '+966500000105', pro_until = null, trial_until = null
    where id = 'ac0e0000-0000-0000-0000-000000000005';

-- Paid driver monitoring now; trial driver online with monitoring off; old driver last seen hours ago.
insert into public.offer_telemetry_events (event_id, occurred_at, correlation_id, app_id, app_version, android_api, provider, stage, reason_code, detail, event_count, ingested_by, received_at) values
    (gen_random_uuid(), now(), 'acacacacacacacacacacacacacacacac', 'sabbaq', '2.0.4-x', 34, 'jeeny', 'HEARTBEAT', 'ok', 'a11y=1,svc=1,act=1', 1, 'ac0e0000-0000-0000-0000-000000000002', now() - interval '2 minutes'),
    (gen_random_uuid(), now(), 'acacacacacacacacacacacacacacacac', 'sabbaq', '2.0.4-x', 34, 'jeeny', 'OFFER_DETECTED', null, null, 7, 'ac0e0000-0000-0000-0000-000000000002', now() - interval '1 hour'),
    (gen_random_uuid(), now(), 'acacacacacacacacacacacacacacacac', 'sabbaq', '2.0.4-x', 34, 'jeeny', 'REJECTED', 'failed_price', null, 4, 'ac0e0000-0000-0000-0000-000000000002', now() - interval '1 hour'),
    (gen_random_uuid(), now(), 'acacacacacacacacacacacacacacacac', 'sabbaq', '2.0.4-x', 34, 'jeeny', 'CONFIRMATION_CONFIRMED', null, null, 2, 'ac0e0000-0000-0000-0000-000000000002', now() - interval '1 hour'),
    (gen_random_uuid(), now(), 'bdbdbdbdbdbdbdbdbdbdbdbdbdbdbdbd', 'sabbaq', '2.0.3-y', 33, 'jeeny', 'HEARTBEAT', 'a11y_disabled', 'a11y=0,svc=0,act=0', 1, 'ac0e0000-0000-0000-0000-000000000003', now() - interval '4 minutes'),
    (gen_random_uuid(), now(), 'cececececececececececececececece', 'sabbaq', '2.0.2-z', 31, 'jeeny', 'HEARTBEAT', 'ok', 'a11y=1,svc=1,act=1', 1, 'ac0e0000-0000-0000-0000-000000000004', now() - interval '5 hours');

insert into public.activation_codes (code, days, batch, redeemed_by, redeemed_at) values
    ('AC0E-0000-0000-0001', 30, 'console-a', 'ac0e0000-0000-0000-0000-000000000002', now() - interval '2 days'),
    ('AC0E-0000-0000-0002', 30, 'console-a', null, null),
    ('AC0E-0000-0000-0003', 90, 'console-b', null, null);
insert into public.activation_codes (code, days, batch, revoked) values ('AC0E-0000-0000-0004', 90, 'console-b', true);

-- Only an administrator reads the console.
set role authenticated;
set request.jwt.claim.sub = 'ac0e0000-0000-0000-0000-000000000002';
do $$ begin
    perform public.admin_driver_list();
    raise exception 'EXPECTATION FAILED: a driver read the drivers list';
exception when insufficient_privilege then null;
end $$;
do $$ begin
    perform public.admin_driver_detail('ac0e0000-0000-0000-0000-000000000002');
    raise exception 'EXPECTATION FAILED: a driver read a driver page';
exception when insufficient_privilege then null;
end $$;
do $$ begin
    perform public.admin_code_stats();
    raise exception 'EXPECTATION FAILED: a driver read the code statistics';
exception when insufficient_privilege then null;
end $$;
reset role;

set role authenticated;
set request.jwt.claim.sub = 'ac0e0000-0000-0000-0000-000000000001';

-- Plan state: one clear word per account, never a bare "end date" on an account that has none.
create temporary table console_rows as
    select r from jsonb_array_elements(public.admin_driver_list(null, 'all', 0, 100) -> 'items') r;
select pg_temp.expect((select r ->> 'plan' from console_rows where r ->> 'id' = 'ac0e0000-0000-0000-0000-000000000002') = 'subscribed', 'paid driver is subscribed');
select pg_temp.expect((select r ->> 'plan' from console_rows where r ->> 'id' = 'ac0e0000-0000-0000-0000-000000000003') = 'trial', 'trial driver is on trial');
select pg_temp.expect((select r ->> 'plan' from console_rows where r ->> 'id' = 'ac0e0000-0000-0000-0000-000000000004') = 'expired', 'lapsed driver is expired');
select pg_temp.expect((select r ->> 'plan' from console_rows where r ->> 'id' = 'ac0e0000-0000-0000-0000-000000000005') = 'none', 'never-paid driver has none');

-- Live state from the heartbeat.
select pg_temp.expect((select r ->> 'live' from console_rows where r ->> 'id' = 'ac0e0000-0000-0000-0000-000000000002') = 'monitoring', 'monitoring now');
select pg_temp.expect((select r ->> 'live' from console_rows where r ->> 'id' = 'ac0e0000-0000-0000-0000-000000000003') = 'online', 'online, monitoring off');
select pg_temp.expect((select r ->> 'issue' from console_rows where r ->> 'id' = 'ac0e0000-0000-0000-0000-000000000003') = 'a11y_disabled', 'its problem is shown');
select pg_temp.expect((select r ->> 'live' from console_rows where r ->> 'id' = 'ac0e0000-0000-0000-0000-000000000004') = 'offline', 'old heartbeat is offline');
select pg_temp.expect((select r ->> 'live' from console_rows where r ->> 'id' = 'ac0e0000-0000-0000-0000-000000000005') = 'never', 'no heartbeat ever');
select pg_temp.expect((select r ->> 'version_name' from console_rows where r ->> 'id' = 'ac0e0000-0000-0000-0000-000000000002') = '2.0.4-x', 'version from the heartbeat');

-- Filters and search run on the server across every account, not one page.
select pg_temp.expect((public.admin_driver_list(null, 'monitoring', 0, 100) -> 'items') @> '[{"id":"ac0e0000-0000-0000-0000-000000000002"}]'
    and not (public.admin_driver_list(null, 'monitoring', 0, 100) -> 'items') @> '[{"id":"ac0e0000-0000-0000-0000-000000000003"}]', 'monitoring filter');
select pg_temp.expect((public.admin_driver_list(null, 'trial', 0, 100) -> 'items') @> '[{"id":"ac0e0000-0000-0000-0000-000000000003"}]', 'trial filter');
select pg_temp.expect((public.admin_driver_list(null, 'offline', 0, 100) -> 'items') @> '[{"id":"ac0e0000-0000-0000-0000-000000000005"}]', 'never seen counts as offline');
select pg_temp.expect((public.admin_driver_list('Console Old', 'all', 0, 100) ->> 'total')::int = 1, 'search by name');
select pg_temp.expect((public.admin_driver_list('0000105', 'all', 0, 100) -> 'items' -> 0 ->> 'id') = 'ac0e0000-0000-0000-0000-000000000005', 'search by phone digits');
select pg_temp.expect((public.admin_driver_list('Console', 'all', 0, 2) ->> 'has_more')::boolean, 'paging says there is more');
select pg_temp.expect((public.admin_driver_list(null, 'all', 0, 100) -> 'counts' ->> 'subscribed')::int >= 1, 'counts per plan');
do $$ begin
    perform public.admin_driver_list(null, 'nonsense', 0, 10);
    raise exception 'EXPECTATION FAILED: unknown filter accepted';
exception when invalid_parameter_value then null;
end $$;
do $$ begin
    perform public.admin_driver_list(null, 'all', 0, 500);
    raise exception 'EXPECTATION FAILED: oversized page accepted';
exception when invalid_parameter_value then null;
end $$;

-- One driver's page.
select pg_temp.expect(public.admin_driver_detail('ac0e0000-0000-0000-0000-000000000002') #>> '{account,email}' = 'console-paid@example.test', 'email on the page');
select pg_temp.expect(public.admin_driver_detail('ac0e0000-0000-0000-0000-000000000002') #>> '{plan,state}' = 'subscribed', 'plan on the page');
select pg_temp.expect(jsonb_array_length(public.admin_driver_detail('ac0e0000-0000-0000-0000-000000000002') #> '{plan,codes}') = 1, 'its redeemed code');
select pg_temp.expect((public.admin_driver_detail('ac0e0000-0000-0000-0000-000000000002') #>> '{activity,day,offers}')::int = 7, 'offers today');
select pg_temp.expect((public.admin_driver_detail('ac0e0000-0000-0000-0000-000000000002') #>> '{activity,day,confirmed}')::int = 2, 'accepted today');
select pg_temp.expect(public.admin_driver_detail('ac0e0000-0000-0000-0000-000000000002') #>> '{activity,reject_reasons,0,reason}' = 'failed_price', 'top rejection reason');
select pg_temp.expect(public.admin_driver_detail('ac0e0000-0000-0000-0000-000000000002') #>> '{live,state}' = 'monitoring', 'live state on the page');
do $$ begin
    perform public.admin_driver_detail('ac0e0000-0000-0000-0000-0000000000ff');
    raise exception 'EXPECTATION FAILED: unknown driver page';
exception when no_data_found then null;
end $$;

-- Code statistics.
select pg_temp.expect((public.admin_code_stats() ->> 'available')::int >= 2, 'available codes');
select pg_temp.expect((public.admin_code_stats() ->> 'redeemed_7d')::int >= 1, 'redeemed this week');
select pg_temp.expect((public.admin_code_stats() ->> 'revoked')::int >= 1, 'revoked codes');
select pg_temp.expect((public.admin_code_stats() -> 'by_batch') @> '[{"batch":"console-a","total":2,"redeemed":1,"available":1}]', 'per batch');
reset role;

-- Learning from confirmed accepts: a button id confirmed by 3 drivers in 5 accepts reaches every phone.
insert into public.offer_telemetry_events (event_id, occurred_at, correlation_id, app_id, app_version, android_api, provider, stage, reason_code, detail, event_count, ingested_by, received_at) values
    (gen_random_uuid(), now(), 'acacacacacacacacacacacacacacacac', 'sabbaq', '2.0.4-x', 34, 'jeeny', 'ACCEPT_LEARNED', 'confirmed:1a', 'aw=0,vid=btnaccept,n=40', 2, 'ac0e0000-0000-0000-0000-000000000002', now()),
    (gen_random_uuid(), now(), 'bdbdbdbdbdbdbdbdbdbdbdbdbdbdbdbd', 'sabbaq', '2.0.4-x', 34, 'jeeny', 'ACCEPT_LEARNED', 'confirmed:1a', 'aw=0,vid=btnaccept,n=38', 2, 'ac0e0000-0000-0000-0000-000000000003', now()),
    (gen_random_uuid(), now(), 'cececececececececececececececece', 'sabbaq', '2.0.4-x', 34, 'jeeny', 'ACCEPT_LEARNED', 'confirmed:1a', 'aw=0,vid=btnaccept,n=41', 1, 'ac0e0000-0000-0000-0000-000000000004', now()),
    (gen_random_uuid(), now(), 'cececececececececececececececece', 'sabbaq', '2.0.4-x', 34, 'jeeny', 'ACCEPT_LEARNED', 'confirmed:2b', 'aw=-1,vid=none,n=12', 9, 'ac0e0000-0000-0000-0000-000000000004', now()),
    (gen_random_uuid(), now(), 'acacacacacacacacacacacacacacacac', 'sabbaq', '2.0.4-x', 34, 'jeeny', 'ACCEPT_LEARNED', 'confirmed:3c', 'aw=0,vid=onlyone,n=12', 9, 'ac0e0000-0000-0000-0000-000000000002', now());
select pg_temp.expect((private.learn_accept_ids() ->> 'changed')::boolean, 'a proven button id is published');
select pg_temp.expect(private.shared_lexicon_row() -> 'accept_ids' = '["btnaccept"]'::jsonb, 'only the id proven by three drivers, never "none"');
select pg_temp.expect(not (private.learn_accept_ids() ->> 'changed')::boolean, 'no new version when nothing changed');

set role authenticated;
set request.jwt.claim.sub = 'ac0e0000-0000-0000-0000-000000000002';
select pg_temp.expect(public.shared_lexicon() -> 'accept_ids' = '["btnaccept"]'::jsonb, 'every phone receives it');
do $$ begin
    perform public.admin_accept_learning();
    raise exception 'EXPECTATION FAILED: a driver read the accept learning';
exception when insufficient_privilege then null;
end $$;
set request.jwt.claim.sub = 'ac0e0000-0000-0000-0000-000000000001';
select pg_temp.expect(public.admin_accept_learning() -> 'live' = '["btnaccept"]'::jsonb, 'the administration sees what was learned');
select pg_temp.expect(jsonb_array_length(public.admin_accept_learning() -> 'candidates') >= 2, 'and the candidates still gathering proof');
reset role;
