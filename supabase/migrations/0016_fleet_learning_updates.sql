-- Fleet health, version tracking, the in-app update channel and shared learning.
--
-- 1. Telemetry stages: why Android ended the process (PROCESS_EXIT), the device and keep-alive
--    state (DEVICE_INFO), how a confirmed accept was found (ACCEPT_LEARNED) and the update
--    lifecycle (UPDATE_STATE).
-- 2. Update channel: the administration publishes the newest signed APK; every app compares its
--    version code, offers the update and, below min_version_code, stops accepting until updated.
-- 3. Version report: per app version and manufacturer, what the fleet actually did.
-- 4. Learning: redacted structure samples of offer screens Sabbaq could not use for a reason
--    other than the driver's conditions, plus a shared lexicon the administration grows from them
--    and every app merges into its reader.

-- 1. Telemetry stages ---------------------------------------------------------------------------
alter table public.offer_telemetry_events drop constraint if exists offer_telemetry_events_stage_check;
alter table public.offer_telemetry_events add constraint offer_telemetry_events_stage_check check (stage in (
    'EVENT_RECEIVED','SIGNAL_FOUND','SIGNAL_MISS','FRAME_INCOMPLETE','PARSED','REJECTED','ACCEPT_NODE',
    'ACCEPT_DISPATCHED','CLICK_RESULT','CONFIRMATION_CONFIRMED','CONFIRMATION_TIMEOUT','STALE','PAUSED',
    'QUEUE_DROPPED','UPLOAD_REJECTED','SERVICE_CONNECTED','SERVICE_INTERRUPTED','SERVICE_DESTROYED',
    'GATE_BLOCKED','PACKAGE_SEEN','ROOT_UNAVAILABLE','TREE_REJECTED','OFFER_NOT_PARSED','OFFER_DETECTED',
    'DECISION_ACCEPTED','HEARTBEAT','APP_CRASH',
    'PROCESS_EXIT','DEVICE_INFO','ACCEPT_LEARNED','UPDATE_STATE'));

-- 2. Update channel ------------------------------------------------------------------------------
-- app_config 'app_update': {latest_code, latest_name, apk_url, apk_sha256, apk_size, notes_ar, notes_en, published_at}.
-- The minimum stays in 'min_version_code' (read by license status since 0001).
create or replace function private.app_update_row() returns jsonb
language sql stable security definer set search_path = pg_catalog, public as $$
    select jsonb_build_object(
        'latest_code', coalesce((v ->> 'latest_code')::bigint, 0),
        'latest_name', coalesce(v ->> 'latest_name', ''),
        'apk_url', coalesce(v ->> 'apk_url', ''),
        'apk_sha256', coalesce(v ->> 'apk_sha256', ''),
        'apk_size', coalesce((v ->> 'apk_size')::bigint, 0),
        'notes_ar', coalesce(v ->> 'notes_ar', ''),
        'notes_en', coalesce(v ->> 'notes_en', ''),
        'published_at', v ->> 'published_at',
        'min_code', public.config_int('min_version_code'))
    from (select coalesce((select value from public.app_config where key = 'app_update'), '{}'::jsonb) v) s
$$;
revoke all on function private.app_update_row() from public, anon, authenticated;

create or replace function public.app_update_info() returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, public as $$
begin
    if auth.uid() is null then raise exception 'not_authenticated' using errcode = '42501'; end if;
    return private.app_update_row();
end $$;
revoke all on function public.app_update_info() from public, anon;
grant execute on function public.app_update_info() to authenticated;

-- Publishes a release. The APK must be served over HTTPS and is checked on the phone against
-- this SHA-256 and the installed app's own signing certificate before Android is asked to install.
create or replace function public.admin_publish_app_update(
    p_latest_code bigint, p_latest_name text, p_apk_url text, p_apk_sha256 text, p_apk_size bigint,
    p_notes_ar text default '', p_notes_en text default '', p_min_code bigint default null) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare
    v_name text := btrim(coalesce(p_latest_name, ''));
    v_url text := btrim(coalesce(p_apk_url, ''));
    v_sha text := lower(btrim(coalesce(p_apk_sha256, '')));
begin
    if not public.is_admin() then raise exception 'not_admin' using errcode = '42501'; end if;
    if p_latest_code is null or p_latest_code not between 1 and 2100000000
        or v_name !~ '^[A-Za-z0-9._-]{1,32}$'
        or length(v_url) > 300 or v_url !~ '^https://[A-Za-z0-9.-]+(:[0-9]+)?/[A-Za-z0-9._~%/-]+\.apk$'
        or v_sha !~ '^[0-9a-f]{64}$'
        or p_apk_size is null or p_apk_size not between 1 and 200000000
        or length(coalesce(p_notes_ar, '')) > 1000 or length(coalesce(p_notes_en, '')) > 1000
        or (p_min_code is not null and p_min_code not between 1 and p_latest_code) then
        raise exception 'invalid_release' using errcode = '22023';
    end if;
    insert into public.app_config (key, value, updated_at)
    values ('app_update', jsonb_build_object(
        'latest_code', p_latest_code, 'latest_name', v_name, 'apk_url', v_url, 'apk_sha256', v_sha,
        'apk_size', p_apk_size, 'notes_ar', coalesce(p_notes_ar, ''), 'notes_en', coalesce(p_notes_en, ''),
        'published_at', now()), now())
    on conflict (key) do update set value = excluded.value, updated_at = now();
    if p_min_code is not null then
        insert into public.app_config (key, value, updated_at) values ('min_version_code', to_jsonb(p_min_code), now())
        on conflict (key) do update set value = excluded.value, updated_at = now();
    end if;
    insert into public.audit_log (actor, action, target, detail)
    values (auth.uid(), 'publish_app_update', v_name, jsonb_build_object('code', p_latest_code, 'min_code', p_min_code, 'sha256', v_sha));
    return private.app_update_row();
end $$;
revoke all on function public.admin_publish_app_update(bigint, text, text, text, bigint, text, text, bigint) from public, anon;
grant execute on function public.admin_publish_app_update(bigint, text, text, text, bigint, text, text, bigint) to authenticated;

create or replace function public.admin_app_update() returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, public as $$
begin
    if not public.is_admin() then raise exception 'not_admin' using errcode = '42501'; end if;
    return private.app_update_row() || jsonb_build_object('adoption', coalesce((
        select jsonb_agg(jsonb_build_object('app_version', app_version, 'drivers', drivers) order by drivers desc)
        from (select app_version, count(*) drivers from (
                  select distinct on (ingested_by) ingested_by, app_version from public.offer_telemetry_events
                  where ingested_by is not null and received_at > now() - interval '3 days'
                  order by ingested_by, received_at desc) latest
              group by app_version) a), '[]'::jsonb));
end $$;
revoke all on function public.admin_app_update() from public, anon;
grant execute on function public.admin_app_update() to authenticated;

-- Public bucket for signed release APKs (Supabase only; the local test database has no storage schema).
do $$
begin
    if exists (select 1 from information_schema.tables where table_schema = 'storage' and table_name = 'buckets') then
        insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
        values ('releases', 'releases', true, 200000000, array['application/vnd.android.package-archive'])
        on conflict (id) do update set public = true, file_size_limit = excluded.file_size_limit,
            allowed_mime_types = excluded.allowed_mime_types;
    end if;
end $$;

-- 3. Version report ------------------------------------------------------------------------------
create or replace function public.admin_version_report(p_since timestamptz default now() - interval '7 days') returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, public as $$
begin
    if not public.is_admin() then raise exception 'not_admin' using errcode = '42501'; end if;
    return jsonb_build_object(
        'since', p_since,
        'versions', coalesce((select jsonb_agg(v order by v.last_seen desc) from (
            select app_version,
                   count(distinct correlation_id) devices, count(distinct ingested_by) drivers,
                   min(received_at) first_seen, max(received_at) last_seen,
                   coalesce(sum(event_count) filter (where stage = 'OFFER_DETECTED'), 0) offer_frames,
                   coalesce(sum(event_count) filter (where stage = 'DECISION_ACCEPTED'), 0) accepted,
                   coalesce(sum(event_count) filter (where stage = 'CONFIRMATION_CONFIRMED'), 0) confirmed,
                   coalesce(sum(event_count) filter (where stage = 'CONFIRMATION_TIMEOUT'), 0) unconfirmed,
                   coalesce(sum(event_count) filter (where stage = 'REJECTED'), 0) rejected_by_conditions,
                   coalesce(sum(event_count) filter (where stage = 'OFFER_NOT_PARSED'), 0) read_misses,
                   coalesce(sum(event_count) filter (where stage = 'APP_CRASH'), 0) crashes,
                   coalesce(sum(event_count) filter (where stage = 'PROCESS_EXIT' and reason_code not in ('user_requested', 'user_stopped', 'crash', 'anr', 'exit_self', 'package_updated', 'package_state', 'init_failure')), 0) system_kills,
                   percentile_disc(0.5) within group (order by latency_ms) filter (where stage = 'CLICK_RESULT' and latency_ms is not null) tap_p50_ms
            from public.offer_telemetry_events where received_at >= p_since
            group by app_version) v), '[]'::jsonb),
        'exit_reasons', coalesce((select jsonb_agg(e order by e.count desc) from (
            select app_version, reason_code, sum(event_count) count from public.offer_telemetry_events
            where received_at >= p_since and stage = 'PROCESS_EXIT' group by 1, 2) e), '[]'::jsonb),
        'manufacturers', coalesce((select jsonb_agg(m order by m.devices desc) from (
            select dev.manufacturer, count(*) devices, count(*) filter (where dev.at_risk) at_risk,
                   coalesce(sum(k.kills), 0) system_kills
            from (select correlation_id, max(substring(detail from 'mf=([a-z0-9]+)')) manufacturer,
                         bool_or(detail like '%hib=1%' or detail ~ 'bkt=[rx]') at_risk
                  from public.offer_telemetry_events
                  where received_at >= p_since and stage = 'DEVICE_INFO' and detail is not null
                  group by correlation_id) dev
            left join (select correlation_id, sum(event_count) kills from public.offer_telemetry_events
                       where received_at >= p_since and stage = 'PROCESS_EXIT'
                         and reason_code not in ('user_requested', 'user_stopped', 'crash', 'anr', 'exit_self', 'package_updated', 'package_state', 'init_failure')
                       group by correlation_id) k on k.correlation_id = dev.correlation_id
            group by dev.manufacturer) m), '[]'::jsonb));
end $$;
revoke all on function public.admin_version_report(timestamptz) from public, anon;
grant execute on function public.admin_version_report(timestamptz) to authenticated;

-- 4. Learning ------------------------------------------------------------------------------------
-- Structure of one offer screen the reader could not use. Nodes are {c,v,b,k,e,t,d}: short class,
-- view-id entry, bounds, clickable, enabled, redacted text, depth. The phone keeps only numbers,
-- known lexicon words and short button labels; every other word arrives as a letter-shape token.
create table if not exists public.screen_samples (
    id                 bigserial primary key,
    received_at        timestamptz not null default now(),
    day                date not null default (now() at time zone 'utc')::date,
    ingested_by        uuid references auth.users (id) on delete set null,
    provider           text not null check (provider ~ '^[a-z0-9_]{1,32}$'),
    target_app_version text check (target_app_version is null or target_app_version ~ '^[A-Za-z0-9._-]{1,32}$'),
    app_version        text not null check (app_version ~ '^[A-Za-z0-9._-]{1,32}$'),
    reason             text not null check (reason ~ '^[a-z0-9_]{1,32}$'),
    signature          text not null check (signature ~ '^[0-9a-f]{16}$'),
    nodes              jsonb not null check (jsonb_typeof(nodes) = 'array' and jsonb_array_length(nodes) between 1 and 150
                                             and octet_length(nodes::text) <= 30000),
    status             text not null default 'new' check (status in ('new', 'reviewed', 'learned', 'ignored'))
);
create unique index if not exists screen_samples_once_a_day on public.screen_samples (ingested_by, signature, day);
create index if not exists screen_samples_review on public.screen_samples (status, received_at desc);
alter table public.screen_samples enable row level security;
revoke all on public.screen_samples from anon, authenticated;

-- Server-side privacy guard on top of the phone's redaction: fewer than seven digits per node,
-- separators ignored (phones, IDs), no e-mail or link text, bounded text per node.
create or replace function private.screen_text_ok(p_text text) returns boolean
language sql immutable as $$
    select p_text is null or (length(p_text) <= 40
        and length(regexp_replace(p_text, '[^0-9٠-٩۰-۹]', '', 'g')) < 7
        and p_text !~ '@' and p_text !~* 'https?:|www\.')
$$;

create or replace function public.ingest_screen_sample(p_sample jsonb) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare
    v_uid uuid := auth.uid();
    v_nodes jsonb := p_sample -> 'nodes';
    c_daily_cap constant integer := 20;
    v_ok boolean;
    v_id bigint;
begin
    if v_uid is null then raise exception 'not_authenticated' using errcode = '42501'; end if;
    if jsonb_typeof(p_sample) <> 'object' or octet_length(p_sample::text) > 32000 then
        raise exception 'invalid_sample' using errcode = '22023';
    end if;
    if (select count(*) from public.screen_samples where ingested_by = v_uid and day = (now() at time zone 'utc')::date) >= c_daily_cap then
        return jsonb_build_object('stored', false, 'reason', 'daily_cap');
    end if;
    select bool_and(
               jsonb_typeof(n) = 'object'
               and (n - array['c','v','b','k','e','t','d']) = '{}'::jsonb
               and coalesce(n ->> 'c', '') ~ '^[A-Za-z0-9.]{0,40}$'
               and coalesce(n ->> 'v', '') ~ '^[a-z0-9_]{0,64}$'
               and (n -> 'b' is null or (jsonb_typeof(n -> 'b') = 'array' and jsonb_array_length(n -> 'b') = 4
                    and not exists (select 1 from jsonb_array_elements(n -> 'b') e where jsonb_typeof(e) <> 'number')))
               and coalesce(jsonb_typeof(n -> 'k'), 'boolean') = 'boolean'
               and coalesce(jsonb_typeof(n -> 'e'), 'boolean') = 'boolean'
               and (n -> 'd' is null or (jsonb_typeof(n -> 'd') = 'number' and (n ->> 'd')::numeric between 0 and 200))
               and coalesce(jsonb_typeof(n -> 't'), 'string') = 'string'
               and private.screen_text_ok(n ->> 't'))
      into v_ok
      from jsonb_array_elements(case when jsonb_typeof(v_nodes) = 'array' then v_nodes else '[]'::jsonb end) n;
    if not coalesce(v_ok, false) then raise exception 'invalid_sample' using errcode = '22023'; end if;
    insert into public.screen_samples (ingested_by, provider, target_app_version, app_version, reason, signature, nodes)
    values (v_uid, p_sample ->> 'provider', nullif(p_sample ->> 'target_app_version', ''), p_sample ->> 'app_version',
            p_sample ->> 'reason', p_sample ->> 'signature', v_nodes)
    on conflict do nothing
    returning id into v_id;
    return jsonb_build_object('stored', v_id is not null, 'reason', case when v_id is null then 'duplicate' end);
exception when check_violation or not_null_violation or invalid_text_representation then
    raise exception 'invalid_sample' using errcode = '22023';
end $$;
revoke all on function public.ingest_screen_sample(jsonb) from public, anon;
grant execute on function public.ingest_screen_sample(jsonb) to authenticated;

-- app_config 'shared_lexicon': {version, accept[], time[], currency[], noise[]}; merged by every app
-- into the driver's own extra keywords (the reference engine's extension point).
create or replace function private.shared_lexicon_row() returns jsonb
language sql stable security definer set search_path = pg_catalog, public as $$
    select jsonb_build_object(
        'version', coalesce((v ->> 'version')::int, 0),
        'accept', coalesce(v -> 'accept', '[]'::jsonb), 'time', coalesce(v -> 'time', '[]'::jsonb),
        'currency', coalesce(v -> 'currency', '[]'::jsonb), 'noise', coalesce(v -> 'noise', '[]'::jsonb))
    from (select coalesce((select value from public.app_config where key = 'shared_lexicon'), '{}'::jsonb) v) s
$$;
revoke all on function private.shared_lexicon_row() from public, anon, authenticated;

create or replace function public.shared_lexicon() returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, public as $$
begin
    if auth.uid() is null then raise exception 'not_authenticated' using errcode = '42501'; end if;
    return private.shared_lexicon_row();
end $$;
revoke all on function public.shared_lexicon() from public, anon;
grant execute on function public.shared_lexicon() to authenticated;

create or replace function private.lexicon_words_ok(p_words text[]) returns boolean
language sql immutable as $$
    select coalesce(array_length(p_words, 1), 0) <= 30
       and not exists (select 1 from unnest(coalesce(p_words, '{}')) w
                       where w is null or length(btrim(w)) not between 1 and 24 or w ~ '[0-9٠-٩,@]' or w <> btrim(w))
$$;

create or replace function public.admin_set_shared_lexicon(p_accept text[], p_time text[], p_currency text[], p_noise text[]) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v_version int;
begin
    if not public.is_admin() then raise exception 'not_admin' using errcode = '42501'; end if;
    if not (private.lexicon_words_ok(p_accept) and private.lexicon_words_ok(p_time)
            and private.lexicon_words_ok(p_currency) and private.lexicon_words_ok(p_noise)) then
        raise exception 'invalid_lexicon' using errcode = '22023';
    end if;
    v_version := (private.shared_lexicon_row() ->> 'version')::int + 1;
    insert into public.app_config (key, value, updated_at)
    values ('shared_lexicon', jsonb_build_object('version', v_version,
        'accept', to_jsonb(coalesce(p_accept, '{}')), 'time', to_jsonb(coalesce(p_time, '{}')),
        'currency', to_jsonb(coalesce(p_currency, '{}')), 'noise', to_jsonb(coalesce(p_noise, '{}'))), now())
    on conflict (key) do update set value = excluded.value, updated_at = now();
    insert into public.audit_log (actor, action, target, detail)
    values (auth.uid(), 'set_shared_lexicon', v_version::text, private.shared_lexicon_row());
    return private.shared_lexicon_row();
end $$;
revoke all on function public.admin_set_shared_lexicon(text[], text[], text[], text[]) from public, anon;
grant execute on function public.admin_set_shared_lexicon(text[], text[], text[], text[]) to authenticated;

-- What the fleet teaches: how confirmed accepts were found per ride-app version, where reading
-- fails per ride-app version, and the words seen on unread offer screens that the lexicon lacks.
create or replace function public.admin_learning_report(p_since timestamptz default now() - interval '7 days') returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, public as $$
declare v_known text[];
begin
    if not public.is_admin() then raise exception 'not_admin' using errcode = '42501'; end if;
    select coalesce(array_agg(lower(w)), '{}') into v_known from (
        select jsonb_array_elements_text(l -> k) w
        from (select private.shared_lexicon_row() l) s, unnest(array['accept','time','currency','noise']) k) x;
    return jsonb_build_object(
        'lexicon', private.shared_lexicon_row(),
        'accept_signatures', coalesce((select jsonb_agg(a order by a.count desc) from (
            select provider, target_app_version, detail, sum(event_count) count from public.offer_telemetry_events
            where received_at >= p_since and stage = 'ACCEPT_LEARNED' group by 1, 2, 3 limit 100) a), '[]'::jsonb),
        'misses', coalesce((select jsonb_agg(m order by m.count desc) from (
            select provider, target_app_version, stage, reason_code, sum(event_count) count from public.offer_telemetry_events
            where received_at >= p_since and stage in ('OFFER_NOT_PARSED', 'CONFIRMATION_TIMEOUT', 'ROOT_UNAVAILABLE', 'TREE_REJECTED')
            group by 1, 2, 3, 4 limit 100) m), '[]'::jsonb),
        'samples', jsonb_build_object(
            'new', (select count(*) from public.screen_samples where status = 'new'),
            'by_reason', coalesce((select jsonb_agg(r order by r.count desc) from (
                select provider, target_app_version, reason, count(*) count from public.screen_samples
                where received_at >= p_since group by 1, 2, 3) r), '[]'::jsonb)),
        'candidate_words', coalesce((select jsonb_agg(c order by c.drivers desc, c.count desc) from (
            select w word, bool_or(coalesce(n -> 'k' = 'true'::jsonb, false)) on_button, count(*) count, count(distinct s.ingested_by) drivers
            from public.screen_samples s, jsonb_array_elements(s.nodes) n,
                 regexp_split_to_table(coalesce(n ->> 't', ''), '\s+') w
            where s.received_at >= p_since and s.status = 'new'
              and w ~ '[A-Za-zء-ي]' and w !~ '^[اa]+$' and not (lower(w) = any (v_known))
            group by 1
            -- k-anonymity: a word is a candidate only once three different drivers saw it.
            having count(distinct s.ingested_by) >= 3
            order by 4 desc, 3 desc limit 40) c), '[]'::jsonb));
end $$;
revoke all on function public.admin_learning_report(timestamptz) from public, anon;
grant execute on function public.admin_learning_report(timestamptz) to authenticated;

create or replace function public.admin_screen_samples(p_status text default 'new', p_limit integer default 20) returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, public as $$
begin
    if not public.is_admin() then raise exception 'not_admin' using errcode = '42501'; end if;
    return coalesce((select jsonb_agg(s order by s.received_at desc) from (
        select id, received_at, provider, target_app_version, app_version, reason, nodes, status
        from public.screen_samples where status = coalesce(p_status, status)
        order by received_at desc limit least(greatest(coalesce(p_limit, 20), 1), 100)) s), '[]'::jsonb);
end $$;
revoke all on function public.admin_screen_samples(text, integer) from public, anon;
grant execute on function public.admin_screen_samples(text, integer) to authenticated;

create or replace function public.admin_mark_screen_sample(p_id bigint, p_status text) returns boolean
language plpgsql security definer set search_path = pg_catalog, public as $$
begin
    if not public.is_admin() then raise exception 'not_admin' using errcode = '42501'; end if;
    if p_status not in ('new', 'reviewed', 'learned', 'ignored') then raise exception 'invalid_status' using errcode = '22023'; end if;
    update public.screen_samples set status = p_status where id = p_id;
    return found;
end $$;
revoke all on function public.admin_mark_screen_sample(bigint, text) from public, anon;
grant execute on function public.admin_mark_screen_sample(bigint, text) to authenticated;

create or replace function public.purge_screen_samples(p_keep interval default interval '30 days') returns integer
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v_deleted integer;
begin
    delete from public.screen_samples where received_at < now() - p_keep and status <> 'learned';
    get diagnostics v_deleted = row_count;
    return v_deleted;
end $$;
revoke all on function public.purge_screen_samples(interval) from public, anon, authenticated;

do $$
begin
    if exists (select 1 from pg_extension where extname = 'pg_cron') then
        perform cron.unschedule(jobid) from cron.job where jobname = 'purge_screen_samples';
        perform cron.schedule('purge_screen_samples', '27 3 * * *', 'select public.purge_screen_samples()');
    end if;
end $$;

-- 5. Automatic engine learning -------------------------------------------------------------------
-- Every hour the server proposes words from the structure samples (time and fare words next to a
-- number on screens that missed them, short labels on buttons of screens without an accept
-- control) once three different drivers saw them. Proposals go to every app as a shadow list:
-- when a screen fails to read, the app re-reads it, read-only and without tapping, with each
-- shadow word and reports which one would have made a complete offer. A time or fare word that
-- did so five times for three drivers is adopted automatically; an accept word waits for the
-- administration. Words of the built-in vocabulary are never proposed (the app's contract test
-- keeps this list equal to its own), each list holds at most 10 shadow words, and the
-- administration can reject or revert any word.
create or replace function private.reserved_words() returns text[]
language sql immutable as $$
    select array['$','accept','arrived','at','away','cancel','card','cash','confirm','coupon','credit','discount','earn','economy','end','in','jod','km','lite','location','m','mada','mastercard','meter','mi','min','mins','mn','money','navigate','net','offer','online','picking','pickup','progress','promo','reject','ride','rule','sar','standard','take','visa','wallet','you''re','أنت','إلغاء','إلى','إنهاء','إيكونومي','ائتمان','استلام','الإتجاهات','الإقلاع','الاتجاهات','الانطلاق','التوصيل','الطلب','العرض','الغاء','القبول','المشوار','الى','انهاء','بطاقة','بعيد','تأكيد','جارٍ','خصم','د.أ','دقائ','دقائق','دقيقة','دقيقه','دوج','دينار','ر.س','رفض','ريال','ستاندرد','شرط','صافي','عرض','في','فيزا','قبول','قتصل','قريب','قواعد','كاش','كم','لايت','م','متر','متصل','محفظة','مستلام','معدل','مواعد','موافق','نقد','نقدي','نقطة','وصلت','يبعد','يبعُد','₡','⃁','﷼']::text[]
$$;

create table if not exists public.lexicon_proposals (
    id             bigserial primary key,
    category       text not null check (category in ('accept', 'time', 'currency')),
    word           text not null check (length(word) between 1 and 24 and word !~ '[0-9٠-٩,@]' and word = btrim(word)),
    status         text not null default 'shadow'
                   check (status in ('shadow', 'adopted', 'awaiting_approval', 'approved', 'rejected', 'reverted', 'expired')),
    drivers        integer not null default 0,
    samples        integer not null default 0,
    shadow_hits    integer not null default 0,
    shadow_drivers integer not null default 0,
    created_at     timestamptz not null default now(),
    decided_at     timestamptz,
    decided_by     uuid,
    unique (category, word)
);
alter table public.lexicon_proposals enable row level security;
revoke all on public.lexicon_proposals from anon, authenticated;

-- Which shadow word each (version, category, index) reported by a phone stood for.
create table if not exists public.shadow_slots (
    version     integer not null,
    category    text not null check (category in ('accept', 'time', 'currency')),
    idx         integer not null check (idx between 0 and 9),
    proposal_id bigint not null references public.lexicon_proposals (id) on delete cascade,
    primary key (version, category, idx)
);
alter table public.shadow_slots enable row level security;
revoke all on public.shadow_slots from anon, authenticated;

create or replace function private.shadow_lexicon_row() returns jsonb
language sql stable security definer set search_path = pg_catalog, public as $$
    select jsonb_build_object(
        'version', coalesce((v ->> 'version')::int, 0),
        'accept', coalesce(v -> 'accept', '[]'::jsonb), 'time', coalesce(v -> 'time', '[]'::jsonb),
        'currency', coalesce(v -> 'currency', '[]'::jsonb))
    from (select coalesce((select value from public.app_config where key = 'shadow_lexicon'), '{}'::jsonb) v) s
$$;
revoke all on function private.shadow_lexicon_row() from public, anon, authenticated;

-- Every app reads the live words and the shadow words to test.
create or replace function public.shared_lexicon() returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, public as $$
begin
    if auth.uid() is null then raise exception 'not_authenticated' using errcode = '42501'; end if;
    return private.shared_lexicon_row() || jsonb_build_object('shadow', private.shadow_lexicon_row());
end $$;
revoke all on function public.shared_lexicon() from public, anon;
grant execute on function public.shared_lexicon() to authenticated;

-- Adds or removes one live word (new lexicon version, audited).
create or replace function private.lexicon_change(p_category text, p_word text, p_add boolean, p_actor uuid) returns void
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v_row jsonb := private.shared_lexicon_row(); v_list jsonb;
begin
    v_list := coalesce(v_row -> p_category, '[]'::jsonb);
    if p_add then
        if v_list @> to_jsonb(array[p_word]) or jsonb_array_length(v_list) >= 30 then return; end if;
        v_list := v_list || to_jsonb(array[p_word]);
    else
        if not (v_list @> to_jsonb(array[p_word])) then return; end if;
        v_list := coalesce((select jsonb_agg(x) from jsonb_array_elements(v_list) x where x <> to_jsonb(p_word)), '[]'::jsonb);
    end if;
    insert into public.app_config (key, value, updated_at)
    values ('shared_lexicon', (v_row - 'version') || jsonb_build_object(p_category, v_list, 'version', (v_row ->> 'version')::int + 1), now())
    on conflict (key) do update set value = excluded.value, updated_at = now();
    insert into public.audit_log (actor, action, target, detail)
    values (p_actor, case when p_add then 'lexicon_add' else 'lexicon_remove' end, p_category,
            jsonb_build_object('word', p_word, 'version', (v_row ->> 'version')::int + 1));
end $$;
revoke all on function private.lexicon_change(text, text, boolean, uuid) from public, anon, authenticated;

-- Rebuilds the shadow list from the open proposals (10 per category, most drivers first); a
-- changed list gets a new version and its slots, so old reports never map to new words.
create or replace function private.rebuild_shadow() returns void
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v_old jsonb := private.shadow_lexicon_row(); v_new jsonb; v_version int; c text;
begin
    select jsonb_build_object(
        'accept', coalesce((select jsonb_agg(word order by drivers desc, id) from (select * from public.lexicon_proposals where status = 'shadow' and category = 'accept' order by drivers desc, id limit 10) a), '[]'::jsonb),
        'time', coalesce((select jsonb_agg(word order by drivers desc, id) from (select * from public.lexicon_proposals where status = 'shadow' and category = 'time' order by drivers desc, id limit 10) t), '[]'::jsonb),
        'currency', coalesce((select jsonb_agg(word order by drivers desc, id) from (select * from public.lexicon_proposals where status = 'shadow' and category = 'currency' order by drivers desc, id limit 10) u), '[]'::jsonb))
      into v_new;
    if v_new = (v_old - 'version') then return; end if;
    v_version := (v_old ->> 'version')::int + 1;
    insert into public.app_config (key, value, updated_at) values ('shadow_lexicon', v_new || jsonb_build_object('version', v_version), now())
    on conflict (key) do update set value = excluded.value, updated_at = now();
    foreach c in array array['accept', 'time', 'currency'] loop
        insert into public.shadow_slots (version, category, idx, proposal_id)
        select v_version, c, (w.ord - 1)::int, p.id
        from jsonb_array_elements_text(v_new -> c) with ordinality w(word, ord)
        join public.lexicon_proposals p on p.category = c and p.word = w.word;
    end loop;
end $$;
revoke all on function private.rebuild_shadow() from public, anon, authenticated;

create or replace function private.learn_from_fleet() returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare
    v_reserved text[] := private.reserved_words();
    v_live text[];
    v_proposed int := 0; v_adopted int := 0; v_awaiting int := 0; v_expired int := 0;
    r record;
begin
    select coalesce(array_agg(lower(w)), '{}') into v_live from (
        select jsonb_array_elements_text(l -> k) w from (select private.shared_lexicon_row() l) s, unnest(array['accept', 'time', 'currency', 'noise']) k) x;

    -- 1. Proposals from the samples of the last 14 days, once three drivers saw the word.
    for r in
        with toks as (
            select s.ingested_by, s.reason, btrim(n ->> 't') t, coalesce(n ->> 'c', '') c,
                   coalesce(n -> 'k' = 'true'::jsonb, false) k, coalesce(n -> 'e' = 'true'::jsonb, true) e
            from public.screen_samples s, jsonb_array_elements(s.nodes) n
            where s.received_at > now() - interval '14 days' and s.provider = 'jeeny' and n ? 't'),
        cand as (
            select 'time' category,
                   coalesce(substring(t from '^[0-9]{1,3}\s*([^\s0-9]{2,24})$'), substring(t from '^([^\s0-9]{2,24})\s*[0-9]{1,3}$')) word,
                   ingested_by
            from toks where reason = 'miss_no_minutes'
            union all
            select 'currency',
                   coalesce(substring(t from '^[0-9]+[.,][0-9]{1,2}\s*([^\s0-9.,]{1,12})$'), substring(t from '^([^\s0-9.,]{1,12})\s*[0-9]+[.,][0-9]{1,2}$')),
                   ingested_by
            from toks where reason = 'miss_no_price'
            union all
            select 'accept', t, ingested_by
            from toks where reason = 'miss_no_accept' and k and e and c ~ '(Button|Chip)$' and t !~ '[0-9٠-٩]'
              and length(t) <= 24 and array_length(regexp_split_to_array(t, '\s+'), 1) <= 3)
        select category, word, count(*)::int samples, count(distinct ingested_by)::int drivers
        from cand
        where word is not null and word ~ '[A-Za-zء-ي]' and word !~ '^[اa ]+$' and word !~ '[0-9٠-٩,@]'
          and not (lower(word) = any (v_live))
          and not exists (select 1 from unnest(regexp_split_to_array(lower(word), '\s+')) p where p = any (v_reserved))
        group by 1, 2
        having count(distinct ingested_by) >= 3
    loop
        insert into public.lexicon_proposals (category, word, drivers, samples)
        values (r.category, r.word, r.drivers, r.samples)
        on conflict (category, word) do update
            set drivers = greatest(public.lexicon_proposals.drivers, excluded.drivers),
                samples = greatest(public.lexicon_proposals.samples, excluded.samples);
        if found then v_proposed := v_proposed + 1; end if;
    end loop;

    -- 2. Shadow results reported by the phones: reason "shadow_hit:<version>:<c><i>", detail "lv=<version>,c=<a|t|c>,i=<index>".
    update public.lexicon_proposals p set shadow_hits = h.hits, shadow_drivers = h.drivers
    from (
        select sl.proposal_id, sum(e.event_count)::int hits, count(distinct e.ingested_by)::int drivers
        from public.offer_telemetry_events e
        join public.shadow_slots sl
          on sl.version = substring(e.detail from 'lv=([0-9]{1,6})')::int
         and sl.category = case substring(e.detail from 'c=([atc])') when 'a' then 'accept' when 't' then 'time' else 'currency' end
         and sl.idx = substring(e.detail from 'i=([0-9])')::int
        where e.stage = 'ACCEPT_LEARNED' and e.reason_code like 'shadow\_hit:%' and e.received_at > now() - interval '14 days'
          and e.detail ~ '^lv=[0-9]{1,6},c=[atc],i=[0-9]$'
        group by 1) h
    where p.id = h.proposal_id;

    -- 3. Proven words: time and fare words go live, accept words wait for the administration.
    for r in select * from public.lexicon_proposals where status = 'shadow' and shadow_hits >= 5 and shadow_drivers >= 3 loop
        if r.category = 'accept' then
            update public.lexicon_proposals set status = 'awaiting_approval' where id = r.id;
            v_awaiting := v_awaiting + 1;
        else
            perform private.lexicon_change(r.category, r.word, true, null);
            update public.lexicon_proposals set status = 'adopted', decided_at = now() where id = r.id;
            v_adopted := v_adopted + 1;
        end if;
    end loop;

    -- 4. A shadow word unproven after 30 days leaves the shadow list.
    update public.lexicon_proposals set status = 'expired', decided_at = now()
    where status = 'shadow' and created_at < now() - interval '30 days';
    get diagnostics v_expired = row_count;

    perform private.rebuild_shadow();
    return jsonb_build_object('proposed', v_proposed, 'adopted', v_adopted, 'awaiting_approval', v_awaiting, 'expired', v_expired,
                              'shadow', private.shadow_lexicon_row());
end $$;
revoke all on function private.learn_from_fleet() from public, anon, authenticated;

create or replace function public.admin_run_learning() returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
begin
    if not public.is_admin() then raise exception 'not_admin' using errcode = '42501'; end if;
    return private.learn_from_fleet();
end $$;
revoke all on function public.admin_run_learning() from public, anon;
grant execute on function public.admin_run_learning() to authenticated;

create or replace function public.admin_lexicon_proposals() returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, public as $$
begin
    if not public.is_admin() then raise exception 'not_admin' using errcode = '42501'; end if;
    return coalesce((select jsonb_agg(p order by p.status = 'awaiting_approval' desc, p.status = 'shadow' desc, p.shadow_hits desc, p.drivers desc) from (
        select id, category, word, status, drivers, samples, shadow_hits, shadow_drivers, created_at, decided_at
        from public.lexicon_proposals
        where status in ('shadow', 'awaiting_approval', 'adopted', 'approved') or decided_at > now() - interval '30 days'
        limit 100) p), '[]'::jsonb);
end $$;
revoke all on function public.admin_lexicon_proposals() from public, anon;
grant execute on function public.admin_lexicon_proposals() to authenticated;

-- approve: the word goes live; reject: it leaves the shadow list; revert: a live word is withdrawn.
create or replace function public.admin_decide_proposal(p_id bigint, p_decision text) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v public.lexicon_proposals;
begin
    if not public.is_admin() then raise exception 'not_admin' using errcode = '42501'; end if;
    select * into v from public.lexicon_proposals where id = p_id for update;
    if not found then raise exception 'not_found' using errcode = 'P0002'; end if;
    if p_decision = 'approve' and v.status in ('shadow', 'awaiting_approval', 'rejected', 'expired', 'reverted') then
        perform private.lexicon_change(v.category, v.word, true, auth.uid());
        update public.lexicon_proposals set status = 'approved', decided_at = now(), decided_by = auth.uid() where id = p_id;
    elsif p_decision = 'reject' and v.status in ('shadow', 'awaiting_approval') then
        update public.lexicon_proposals set status = 'rejected', decided_at = now(), decided_by = auth.uid() where id = p_id;
    elsif p_decision = 'revert' and v.status in ('adopted', 'approved') then
        perform private.lexicon_change(v.category, v.word, false, auth.uid());
        update public.lexicon_proposals set status = 'reverted', decided_at = now(), decided_by = auth.uid() where id = p_id;
    else
        raise exception 'invalid_decision' using errcode = '22023';
    end if;
    perform private.rebuild_shadow();
    return (select to_jsonb(p) from public.lexicon_proposals p where id = p_id);
end $$;
revoke all on function public.admin_decide_proposal(bigint, text) from public, anon;
grant execute on function public.admin_decide_proposal(bigint, text) to authenticated;

do $$
begin
    if exists (select 1 from pg_extension where extname = 'pg_cron') then
        perform cron.unschedule(jobid) from cron.job where jobname = 'learn_from_fleet';
        perform cron.schedule('learn_from_fleet', '41 * * * *', 'select private.learn_from_fleet()');
    end if;
end $$;
