-- Fleet health, update channel and shared learning contract (0016). Run after bootstrap.sql,
-- all migrations and the earlier suites on a fresh local PostgreSQL database.
\set ON_ERROR_STOP on

create or replace function pg_temp.expect(p_ok boolean, p_what text) returns void language plpgsql as $$
begin
    if not coalesce(p_ok, false) then
        raise exception 'EXPECTATION FAILED: %', p_what;
    end if;
end;
$$;
grant execute on function pg_temp.expect(boolean, text) to authenticated, anon;

insert into auth.users (id, email, raw_app_meta_data, raw_user_meta_data)
select ('f1ee0000-0000-0000-0000-00000000000' || n)::uuid, 'fleet-' || n || '@example.test',
       '{"provider":"google","providers":["google"]}'::jsonb, jsonb_build_object('full_name', 'Fleet ' || n)
from generate_series(1, 3) n;
insert into public.admins (user_id) values ('f1ee0000-0000-0000-0000-000000000001');

-- Tables stay closed to clients.
set role authenticated;
select set_config('request.jwt.claim.sub', 'f1ee0000-0000-0000-0000-000000000002', false);
do $$ begin
    perform 1 from public.screen_samples;
    raise exception 'EXPECTATION FAILED: drivers cannot read screen samples';
exception when insufficient_privilege then null;
end $$;

-- Update channel: nothing published yet, then only an admin may publish, with strict validation.
select pg_temp.expect((public.app_update_info() ->> 'latest_code')::bigint = 0, 'no release published yet');
do $$ begin
    perform public.admin_publish_app_update(3650000, '2.0.4-20261030.0900', 'https://x.supabase.co/storage/v1/object/public/releases/sabbaq-2.0.4.apk', repeat('a', 64), 30000000);
    raise exception 'EXPECTATION FAILED: a driver cannot publish';
exception when insufficient_privilege then null;
end $$;
select set_config('request.jwt.claim.sub', 'f1ee0000-0000-0000-0000-000000000001', false);
do $$
declare bad record;
begin
    for bad in select * from (values
        ('http://x.supabase.co/releases/a.apk', repeat('a', 64), 1000::bigint, null::bigint),
        ('https://x.supabase.co/releases/a.zip', repeat('a', 64), 1000, null),
        ('https://x.supabase.co/releases/a.apk', 'nothex', 1000, null),
        ('https://x.supabase.co/releases/a.apk', repeat('a', 64), 0, null),
        ('https://x.supabase.co/releases/a.apk', repeat('a', 64), 1000, 3650001)) v(url, sha, size, min_code)
    loop
        begin
            perform public.admin_publish_app_update(3650000, '2.0.4-20261030.0900', bad.url, bad.sha, bad.size, '', '', bad.min_code);
            raise exception 'EXPECTATION FAILED: invalid release accepted (%)', bad.url;
        exception when invalid_parameter_value then null;
        end;
    end loop;
end $$;
select pg_temp.expect((public.admin_publish_app_update(3650000, '2.0.4-20261030.0900',
    'https://x.supabase.co/storage/v1/object/public/releases/sabbaq-2.0.4.apk', upper(repeat('ab', 32)), 30000000,
    'إصلاحات', 'Fixes', 3640000) ->> 'apk_sha256') = repeat('ab', 32), 'release published, hash stored lower-case');
select set_config('request.jwt.claim.sub', 'f1ee0000-0000-0000-0000-000000000002', false);
select pg_temp.expect((public.app_update_info() ->> 'latest_code')::bigint = 3650000
    and (public.app_update_info() ->> 'min_code')::bigint = 3640000
    and public.app_update_info() ->> 'notes_ar' = 'إصلاحات', 'drivers read the release and the minimum');
reset role;
select pg_temp.expect(exists (select 1 from public.audit_log where action = 'publish_app_update'), 'publishing is audited');
-- License status reads the same minimum.
select pg_temp.expect(public.config_int('min_version_code') = 3640000, 'license status minimum updated');
set role anon;
do $$ begin
    perform public.app_update_info();
    raise exception 'EXPECTATION FAILED: anonymous update info';
exception when insufficient_privilege then null;
end $$;
reset role;

-- New telemetry stages are accepted.
set role authenticated;
select set_config('request.jwt.claim.sub', 'f1ee0000-0000-0000-0000-000000000002', false);
select pg_temp.expect((public.ingest_offer_telemetry(jsonb_build_array(
    jsonb_build_object('eventId', gen_random_uuid(), 'occurredAtEpochMs', (extract(epoch from now()) * 1000)::bigint,
        'correlationId', 'c0ffee00c0ffee00c0ffee00c0ffee00', 'appId', 'app.sabbaq', 'appVersion', '2.0.4-20261030.0900',
        'androidApi', 34, 'provider', 'service', 'stage', 'PROCESS_EXIT', 'reasonCode', 'low_memory', 'detail', 'imp=125'),
    jsonb_build_object('eventId', gen_random_uuid(), 'occurredAtEpochMs', (extract(epoch from now()) * 1000)::bigint,
        'correlationId', 'c0ffee00c0ffee00c0ffee00c0ffee00', 'appId', 'app.sabbaq', 'appVersion', '2.0.4-20261030.0900',
        'androidApi', 34, 'provider', 'service', 'stage', 'DEVICE_INFO', 'reasonCode', 'daily', 'detail', 'mf=xiaomi,bkt=r,hib=1,bat=0'),
    jsonb_build_object('eventId', gen_random_uuid(), 'occurredAtEpochMs', (extract(epoch from now()) * 1000)::bigint,
        'correlationId', 'c0ffee00c0ffee00c0ffee00c0ffee00', 'appId', 'app.sabbaq', 'appVersion', '2.0.4-20261030.0900',
        'androidApi', 34, 'provider', 'jeeny', 'stage', 'ACCEPT_LEARNED', 'reasonCode', 'confirmed', 'detail', 'aw=9,vid=btnaccept,n=25',
        'targetAppVersion', '5.12.0'),
    jsonb_build_object('eventId', gen_random_uuid(), 'occurredAtEpochMs', (extract(epoch from now()) * 1000)::bigint,
        'correlationId', 'c0ffee00c0ffee00c0ffee00c0ffee00', 'appId', 'app.sabbaq', 'appVersion', '2.0.4-20261030.0900',
        'androidApi', 34, 'provider', 'service', 'stage', 'UPDATE_STATE', 'reasonCode', 'installed', 'detail', 'from=3640000')
)) ->> 'accepted')::int = 4, 'new stages ingested');

-- Screen samples: shape-checked, privacy-guarded, once a day per structure, capped per driver.
create temp table sample(v jsonb);
grant all on sample to authenticated;
insert into sample values (jsonb_build_object('provider', 'jeeny', 'target_app_version', '5.12.0', 'app_version', '2.0.4-20261030.0900',
    'reason', 'no_minutes', 'signature', '0123456789abcdef', 'nodes', jsonb_build_array(
        jsonb_build_object('c', 'TextView', 'v', 'tvprice', 'b', jsonb_build_array(0, 10, 100, 40), 'k', false, 'e', true, 't', '25.50 ⃁', 'd', 2),
        jsonb_build_object('c', 'TextView', 'v', '', 'b', jsonb_build_array(0, 50, 100, 80), 'k', false, 'e', true, 't', 'ااااا 4 دقائق', 'd', 2),
        jsonb_build_object('c', 'Button', 'v', 'btngo', 'b', jsonb_build_array(0, 90, 100, 120), 'k', true, 'e', true, 't', 'انطلق الآن', 'd', 2))));
select pg_temp.expect((public.ingest_screen_sample((select v from sample)) ->> 'stored')::boolean, 'sample stored');
select pg_temp.expect(public.ingest_screen_sample((select v from sample)) ->> 'reason' = 'duplicate', 'same structure once a day');
do $$
declare bad jsonb;
begin
    for bad in select jsonb_set((select v from sample), '{nodes,0,t}', to_jsonb(x)) from unnest(array['0551234567', '055 123 4567', '055-123-4567', 'me@x.com', 'https://x', repeat('a', 41)]) x
    union all select jsonb_set((select v from sample), '{nodes,0,k}', '"yes"')
    union all select jsonb_set((select v from sample), '{nodes,0,b}', '[0, 1, 2, "x"]')
    union all select jsonb_set((select v from sample), '{nodes,0,d}', '"deep"')
    union all select jsonb_set((select v from sample), '{nodes,0,t}', '7')
    union all select jsonb_set((select v from sample), '{nodes,0,x}', '"extra"')
    union all select jsonb_set((select v from sample), '{nodes}', '[]')
    union all select jsonb_set((select v from sample), '{provider}', '"Jeeny!"')
    loop
        begin
            perform public.ingest_screen_sample(jsonb_set(bad, '{signature}', '"fedcba9876543210"'));
            raise exception 'EXPECTATION FAILED: unsafe sample accepted %', bad;
        exception when invalid_parameter_value then null;
        end;
    end loop;
end $$;
do $$ begin
    for i in 1..30 loop
        perform public.ingest_screen_sample(jsonb_set((select v from sample), '{signature}', to_jsonb(lpad(to_hex(i), 16, '0'))));
    end loop;
end $$;
-- Two more drivers see the same new button word (k-anonymity needs three).
do $$ begin
    for i in 1..3 by 2 loop
        perform set_config('request.jwt.claim.sub', 'f1ee0000-0000-0000-0000-00000000000' || i, false);
        perform public.ingest_screen_sample((select v from sample));
    end loop;
end $$;
select set_config('request.jwt.claim.sub', 'f1ee0000-0000-0000-0000-000000000002', false);
reset role;
select pg_temp.expect((select count(*) from public.screen_samples where ingested_by = 'f1ee0000-0000-0000-0000-000000000002') = 20, 'daily cap of 20 samples');

-- Shared lexicon: admins only, validated, versioned; drivers read it.
set role authenticated;
do $$ begin
    perform public.admin_set_shared_lexicon(array['انطلق'], null, null, null);
    raise exception 'EXPECTATION FAILED: a driver cannot change the lexicon';
exception when insufficient_privilege then null;
end $$;
select set_config('request.jwt.claim.sub', 'f1ee0000-0000-0000-0000-000000000001', false);
do $$ begin
    perform public.admin_set_shared_lexicon(array['قبول 2'], null, null, null);
    raise exception 'EXPECTATION FAILED: digits in a lexicon word';
exception when invalid_parameter_value then null;
end $$;
select pg_temp.expect((public.admin_learning_report() -> 'candidate_words') @> '[{"word":"انطلق"}]', 'unknown button word is a candidate');
select pg_temp.expect(not ((public.admin_learning_report() -> 'candidate_words') @> '[{"word":"ااااا"}]'), 'shape tokens are not candidates');
select pg_temp.expect((public.admin_learning_report() -> 'accept_signatures') @> '[{"detail":"aw=9,vid=btnaccept,n=25"}]', 'accept signatures reported');
select pg_temp.expect((public.admin_set_shared_lexicon(array['انطلق'], array['يصل خلال'], null, null) ->> 'version')::int = 1, 'lexicon version 1');
select pg_temp.expect(not ((public.admin_learning_report() -> 'candidate_words') @> '[{"word":"انطلق"}]'), 'learned word leaves the candidates');
reset role;
create temp table first_sample as select min(id) id from public.screen_samples where ingested_by = 'f1ee0000-0000-0000-0000-000000000002';
grant select on first_sample to authenticated;
set role authenticated;
select pg_temp.expect(public.admin_mark_screen_sample((select id from first_sample), 'learned'), 'sample marked learned');
select pg_temp.expect(jsonb_array_length(public.admin_screen_samples('learned', 10)) = 1, 'learned samples listed');

-- Reports.
select pg_temp.expect((public.admin_version_report() -> 'versions') @> '[{"app_version":"2.0.4-20261030.0900","system_kills":1}]', 'low-memory kill counted per version');
select pg_temp.expect((public.admin_version_report() -> 'manufacturers') @> '[{"manufacturer":"xiaomi","at_risk":1}]', 'manufacturer at risk');
select pg_temp.expect((public.admin_app_update() -> 'adoption') @> '[{"app_version":"2.0.4-20261030.0900","drivers":1}]', 'adoption per version');
select set_config('request.jwt.claim.sub', 'f1ee0000-0000-0000-0000-000000000003', false);
select pg_temp.expect((public.shared_lexicon() -> 'accept') @> '["انطلق"]', 'every driver reads the shared lexicon');
do $$ begin
    perform public.admin_version_report();
    raise exception 'EXPECTATION FAILED: a driver read the version report';
exception when insufficient_privilege then null;
end $$;
reset role;

-- Retention keeps learned samples.
update public.screen_samples set received_at = now() - interval '40 days' where ingested_by = 'f1ee0000-0000-0000-0000-000000000002';
select pg_temp.expect(public.purge_screen_samples() = 19, 'old unlearned samples purged');
select pg_temp.expect((select count(*) from public.screen_samples where ingested_by <> 'f1ee0000-0000-0000-0000-000000000002') = 2, 'recent samples kept');
select pg_temp.expect((select count(*) from public.screen_samples where status = 'learned') = 1, 'learned sample kept');

-- Automatic engine learning: proposal from three drivers, shadow test on the phones, automatic
-- adoption of a proven time word, administration approval for an accept word, revert.
insert into auth.users (id, email, raw_app_meta_data, raw_user_meta_data)
select ('f1ee0000-0000-0000-0000-00000000001' || n)::uuid, 'learn-' || n || '@example.test',
       '{"provider":"google","providers":["google"]}'::jsonb, '{}'::jsonb
from generate_series(1, 3) n;
-- Only active drivers on a trial or a subscription teach the fleet (0021, H-05).
insert into public.drivers (id, status, trial_until)
select ('f1ee0000-0000-0000-0000-00000000001' || n)::uuid, 'active', now() + interval '3 days' from generate_series(1, 3) n
on conflict (id) do update set status = 'active', trial_until = excluded.trial_until;
insert into public.screen_samples (ingested_by, provider, app_version, reason, signature, nodes)
select ('f1ee0000-0000-0000-0000-00000000001' || n)::uuid, 'jeeny', '2.0.4', r.reason, lpad(to_hex(n * 16 + r.k), 16, 'a'), r.nodes
from generate_series(1, 3) n, (values
    (1, 'miss_no_minutes', '[{"c":"TextView","t":"5 دقايق"},{"c":"TextView","t":"7 دقائق"},{"c":"TextView","t":"ااا ااا"}]'::jsonb),
    (2, 'miss_no_accept', '[{"c":"MaterialButton","k":true,"e":true,"t":"خذ الرحلة"},{"c":"Button","k":true,"e":true,"t":"رفض"},{"c":"LinearLayout","k":true,"t":"محمد"}]'::jsonb)
) r(k, reason, nodes);
select pg_temp.expect(((private.learn_from_fleet()) -> 'shadow' -> 'time') @> '["دقايق"]', 'new time word goes to the shadow list');
select pg_temp.expect(not exists (select 1 from public.lexicon_proposals where word in ('دقائق', 'رفض', 'محمد', 'ااا')),
    'built-in words, reject words, non-button rows and shape tokens are never proposed');
select pg_temp.expect((select status from public.lexicon_proposals where word = 'خذ الرحلة') = 'shadow', 'button label proposed for accept');
set role authenticated;
select set_config('request.jwt.claim.sub', 'f1ee0000-0000-0000-0000-000000000011', false);
select pg_temp.expect((public.shared_lexicon() -> 'shadow' -> 'accept') @> '["خذ الرحلة"]', 'phones receive the shadow words');
reset role;
-- Two phones only: not enough.
insert into public.offer_telemetry_events (event_id, occurred_at, correlation_id, app_id, app_version, android_api, provider, stage, reason_code, event_count, detail, ingested_by)
select gen_random_uuid(), now(), 'c0ffee00c0ffee00c0ffee00c0ffee0' || n, 'app.sabbaq', '2.0.4', 34, 'jeeny', 'ACCEPT_LEARNED', 'shadow_hit:' || c, 3,
       'lv=' || (private.shadow_lexicon_row() ->> 'version') || ',c=' || c || ',i=0', ('f1ee0000-0000-0000-0000-00000000001' || n)::uuid
from generate_series(1, 2) n, unnest(array['t', 'a']) c;
select pg_temp.expect((private.learn_from_fleet() ->> 'adopted')::int = 0, 'two drivers are not enough to adopt');
insert into public.offer_telemetry_events (event_id, occurred_at, correlation_id, app_id, app_version, android_api, provider, stage, reason_code, event_count, detail, ingested_by)
select gen_random_uuid(), now(), 'c0ffee00c0ffee00c0ffee00c0ffee03', 'app.sabbaq', '2.0.4', 34, 'jeeny', 'ACCEPT_LEARNED', 'shadow_hit:' || c, 1,
       'lv=' || (private.shadow_lexicon_row() ->> 'version') || ',c=' || c || ',i=0', 'f1ee0000-0000-0000-0000-000000000013'
from unnest(array['t', 'a']) c;
select pg_temp.expect((private.learn_from_fleet() ->> 'adopted')::int = 1, 'proven time word adopted automatically');
select pg_temp.expect((private.shared_lexicon_row() -> 'time') @> '["دقايق"]', 'adopted word is live for every phone');
select pg_temp.expect((select status from public.lexicon_proposals where word = 'خذ الرحلة') = 'awaiting_approval', 'proven accept word waits for the administration');
select pg_temp.expect(not ((private.shared_lexicon_row() -> 'accept') @> '["خذ الرحلة"]'), 'accept word not live before approval');
select pg_temp.expect(not ((private.shadow_lexicon_row() -> 'time') @> '["دقايق"]'), 'adopted word leaves the shadow list');
set role authenticated;
select set_config('request.jwt.claim.sub', 'f1ee0000-0000-0000-0000-000000000011', false);
do $$ begin
    perform public.admin_run_learning();
    raise exception 'EXPECTATION FAILED: a driver ran the learning job';
exception when insufficient_privilege then null;
end $$;
select set_config('request.jwt.claim.sub', 'f1ee0000-0000-0000-0000-000000000001', false);
select pg_temp.expect(jsonb_array_length(public.admin_lexicon_proposals()) >= 2, 'administration sees the proposals');
reset role;
create temp table accept_proposal as select id from public.lexicon_proposals where word = 'خذ الرحلة';
create temp table time_proposal as select id from public.lexicon_proposals where word = 'دقايق';
grant select on accept_proposal, time_proposal to authenticated;
set role authenticated;
select set_config('request.jwt.claim.sub', 'f1ee0000-0000-0000-0000-000000000001', false);
select pg_temp.expect(public.admin_decide_proposal((select id from accept_proposal), 'approve') ->> 'status' = 'approved', 'accept word approved');
select pg_temp.expect((public.shared_lexicon() -> 'accept') @> '["خذ الرحلة"]', 'approved accept word is live');
select pg_temp.expect(public.admin_decide_proposal((select id from time_proposal), 'revert') ->> 'status' = 'reverted', 'adopted word reverted');
select pg_temp.expect(not ((public.shared_lexicon() -> 'time') @> '["دقايق"]'), 'reverted word withdrawn');
do $$ begin
    perform public.admin_decide_proposal((select id from time_proposal), 'reject');
    raise exception 'EXPECTATION FAILED: rejecting a reverted word';
exception when invalid_parameter_value then null;
end $$;
reset role;
select pg_temp.expect(exists (select 1 from public.audit_log where action = 'lexicon_add') and exists (select 1 from public.audit_log where action = 'lexicon_remove'), 'lexicon changes audited');
