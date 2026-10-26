-- Offer visibility telemetry v2: aggregated counters, machine-only detail, target app version,
-- per-account ingestion bound, fleet overview and an executable retention job.
alter table public.offer_telemetry_events
    add column if not exists event_count integer not null default 1,
    add column if not exists detail text,
    add column if not exists target_app_version text,
    add column if not exists ingested_by uuid references auth.users (id) on delete set null;

alter table public.offer_telemetry_events drop constraint if exists offer_telemetry_events_event_count_check;
alter table public.offer_telemetry_events add constraint offer_telemetry_events_event_count_check
    check (event_count between 1 and 1000000);
alter table public.offer_telemetry_events drop constraint if exists offer_telemetry_events_detail_check;
alter table public.offer_telemetry_events add constraint offer_telemetry_events_detail_check
    check (detail is null or (detail ~ '^[a-z0-9_.:=,-]{1,96}$' and detail !~ '(address|fare|phone|email|token|http)'));
alter table public.offer_telemetry_events drop constraint if exists offer_telemetry_events_target_app_version_check;
alter table public.offer_telemetry_events add constraint offer_telemetry_events_target_app_version_check
    check (target_app_version is null or target_app_version ~ '^[A-Za-z0-9._-]{1,32}$');
alter table public.offer_telemetry_events drop constraint if exists offer_telemetry_events_stage_check;
alter table public.offer_telemetry_events add constraint offer_telemetry_events_stage_check check (stage in (
    'EVENT_RECEIVED','SIGNAL_FOUND','SIGNAL_MISS','FRAME_INCOMPLETE','PARSED','REJECTED','ACCEPT_NODE',
    'ACCEPT_DISPATCHED','CLICK_RESULT','CONFIRMATION_CONFIRMED','CONFIRMATION_TIMEOUT','STALE','PAUSED',
    'QUEUE_DROPPED','UPLOAD_REJECTED','SERVICE_CONNECTED','SERVICE_INTERRUPTED','SERVICE_DESTROYED',
    'GATE_BLOCKED','PACKAGE_SEEN','ROOT_UNAVAILABLE','TREE_REJECTED','OFFER_NOT_PARSED','OFFER_DETECTED',
    'DECISION_ACCEPTED','HEARTBEAT','APP_CRASH'));

create index if not exists offer_telemetry_ingested_by_time on public.offer_telemetry_events(ingested_by, received_at desc);
create index if not exists offer_telemetry_received on public.offer_telemetry_events(received_at desc);

-- Rows over the hourly bound are acknowledged but not stored, so a misbehaving client cannot
-- grow the table while a well-behaved device (a few hundred rows an hour) is never affected.
create or replace function public.ingest_offer_telemetry(p_events jsonb) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare
    v_uid uuid := auth.uid();
    v_count integer;
    v_recent integer;
    v_room integer;
    c_hourly_limit constant integer := 3000;
begin
    if v_uid is null then raise exception 'not_authenticated' using errcode = '42501'; end if;
    if jsonb_typeof(p_events) <> 'array' or jsonb_array_length(p_events) < 1 or jsonb_array_length(p_events) > 50 then raise exception 'invalid_telemetry_batch' using errcode = '22023'; end if;
    if octet_length(p_events::text) > 200000 then raise exception 'telemetry_batch_too_large' using errcode = '22023'; end if;
    select count(*) into v_recent from public.offer_telemetry_events
        where ingested_by = v_uid and received_at > now() - interval '1 hour';
    v_room := greatest(c_hourly_limit - v_recent, 0);
    if v_room = 0 then
        return jsonb_build_object('accepted', 0, 'received', jsonb_array_length(p_events), 'rate_limited', true);
    end if;
    insert into public.offer_telemetry_events(event_id, occurred_at, correlation_id, app_id, app_version, android_api, provider, stage, reason_code, decision_score, latency_ms, latency_bucket, config_version, profile_version, install_cohort, event_count, detail, target_app_version, ingested_by)
    select (x->>'eventId')::uuid, to_timestamp((x->>'occurredAtEpochMs')::bigint / 1000.0), x->>'correlationId', x->>'appId', x->>'appVersion', (x->>'androidApi')::integer, x->>'provider', x->>'stage', nullif(x->>'reasonCode',''), (x->>'decisionScore')::integer, (x->>'latencyMs')::bigint, x->>'latencyBucket', x->>'configVersion', x->>'profileVersion', x->>'installCohort',
        coalesce((x->>'eventCount')::integer, 1), nullif(x->>'detail',''), nullif(x->>'targetAppVersion',''), v_uid
    from (select x from jsonb_array_elements(p_events) x limit v_room) batch
    on conflict (event_id) do nothing;
    get diagnostics v_count = row_count;
    return jsonb_build_object('accepted', v_count, 'received', jsonb_array_length(p_events), 'rate_limited', v_room < jsonb_array_length(p_events));
exception when invalid_text_representation or numeric_value_out_of_range or check_violation or not_null_violation then
    raise exception 'invalid_telemetry_event' using errcode = '22023';
end $$;
revoke all on function public.ingest_offer_telemetry(jsonb) from public, anon;
grant execute on function public.ingest_offer_telemetry(jsonb) to authenticated;

create or replace function public.admin_offer_telemetry(p_correlation_id text, p_since timestamptz default now() - interval '7 days') returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare result jsonb;
begin
    perform public.require_admin();
    if p_correlation_id is null or p_correlation_id !~ '^[a-f0-9]{16,64}$' then raise exception 'invalid_correlation_id' using errcode = '22023'; end if;
    select jsonb_build_object(
        'correlation_id', p_correlation_id,
        'events', coalesce((select jsonb_agg(to_jsonb(e) - 'event_id' - 'correlation_id' - 'ingested_by' order by e.occurred_at desc)
            from (select * from public.offer_telemetry_events where correlation_id = p_correlation_id and occurred_at >= p_since
                  order by occurred_at desc limit 500) e), '[]'::jsonb),
        'funnel', coalesce((select jsonb_agg(jsonb_build_object('stage', stage, 'reason_code', reason_code, 'count', total, 'last_at', last_at, 'detail', detail) order by total desc)
            from (select stage, reason_code, sum(event_count) total, max(occurred_at) last_at,
                         (array_agg(detail order by occurred_at desc) filter (where detail is not null))[1] detail
                  from public.offer_telemetry_events where correlation_id = p_correlation_id and occurred_at >= p_since
                  group by stage, reason_code) f), '[]'::jsonb),
        'cohorts', coalesce((select jsonb_agg(jsonb_build_object('app_id', app_id, 'app_version', app_version, 'android_api', android_api, 'target_app_version', target_app_version, 'count', total) order by total desc)
            from (select app_id, app_version, android_api, target_app_version, sum(event_count) total
                  from public.offer_telemetry_events where correlation_id = p_correlation_id and occurred_at >= p_since
                  group by app_id, app_version, android_api, target_app_version) c), '[]'::jsonb)
    ) into result;
    return result;
end $$;
revoke all on function public.admin_offer_telemetry(text, timestamptz) from public, anon;
grant execute on function public.admin_offer_telemetry(text, timestamptz) to authenticated;

-- Complaint entry point from the drivers list: the admin does not need the driver to find and
-- copy a support id. Uses the device that most recently reported for that account.
create or replace function public.admin_offer_telemetry_for_driver(p_driver uuid, p_since timestamptz default now() - interval '7 days') returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v_correlation text;
begin
    perform public.require_admin();
    if p_driver is null then raise exception 'invalid_driver' using errcode = '22023'; end if;
    select correlation_id into v_correlation from public.offer_telemetry_events
        where ingested_by = p_driver and occurred_at >= p_since order by occurred_at desc limit 1;
    if v_correlation is null then
        return jsonb_build_object('driver_has_telemetry', false, 'events', '[]'::jsonb, 'funnel', '[]'::jsonb, 'cohorts', '[]'::jsonb);
    end if;
    return public.admin_offer_telemetry(v_correlation, p_since) || jsonb_build_object('driver_has_telemetry', true);
end $$;
revoke all on function public.admin_offer_telemetry_for_driver(uuid, timestamptz) from public, anon;
grant execute on function public.admin_offer_telemetry_for_driver(uuid, timestamptz) to authenticated;

-- Fleet view: where offers stop across all devices, and which devices reported most recently.
create or replace function public.admin_offer_telemetry_overview(p_since timestamptz default now() - interval '24 hours') returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
begin
    perform public.require_admin();
    return jsonb_build_object(
        'since', p_since,
        'funnel', coalesce((select jsonb_agg(jsonb_build_object('stage', stage, 'reason_code', reason_code, 'count', total, 'devices', devices) order by total desc)
            from (select stage, reason_code, sum(event_count) total, count(distinct correlation_id) devices
                  from public.offer_telemetry_events where occurred_at >= p_since
                  group by stage, reason_code order by sum(event_count) desc limit 100) f), '[]'::jsonb),
        'devices', coalesce((select jsonb_agg(d order by d.last_seen desc)
            from (select correlation_id, max(occurred_at) last_seen, max(app_version) app_version,
                         max(target_app_version) target_app_version, sum(event_count) events,
                         (array_agg(detail order by occurred_at desc) filter (where stage = 'HEARTBEAT'))[1] last_heartbeat
                  from public.offer_telemetry_events where occurred_at >= p_since
                  group by correlation_id order by max(occurred_at) desc limit 50) d), '[]'::jsonb)
    );
end $$;
revoke all on function public.admin_offer_telemetry_overview(timestamptz) from public, anon;
grant execute on function public.admin_offer_telemetry_overview(timestamptz) to authenticated;

-- Retention: 30 days. Callable only by privileged roles (service role / cron), never by clients.
create or replace function public.purge_offer_telemetry(p_keep interval default interval '30 days') returns integer
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v_deleted integer;
begin
    delete from public.offer_telemetry_events where received_at < now() - p_keep;
    get diagnostics v_deleted = row_count;
    return v_deleted;
end $$;
revoke all on function public.purge_offer_telemetry(interval) from public, anon, authenticated;

do $$
begin
    if exists (select 1 from pg_extension where extname = 'pg_cron') then
        perform cron.unschedule(jobid) from cron.job where jobname = 'purge_offer_telemetry';
        perform cron.schedule('purge_offer_telemetry', '17 3 * * *', 'select public.purge_offer_telemetry()');
    end if;
end $$;

comment on table public.offer_telemetry_events is 'Privacy-safe offer tracing. Retained 30 days by purge_offer_telemetry() (pg_cron job purge_offer_telemetry when available).';
