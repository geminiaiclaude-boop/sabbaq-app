-- Offer lifecycle tracing: metadata-only, bounded and admin-readable through SECURITY DEFINER RPCs.
create table if not exists public.offer_telemetry_events (
    event_id uuid primary key,
    occurred_at timestamptz not null,
    correlation_id text not null check (correlation_id ~ '^[a-f0-9]{16,64}$'),
    app_id text not null check (app_id ~ '^[A-Za-z0-9._-]{1,64}$'),
    app_version text not null check (app_version ~ '^[A-Za-z0-9._-]{1,32}$'),
    android_api integer not null check (android_api between 26 and 99),
    provider text not null check (provider ~ '^[A-Za-z0-9._-]{1,32}$'),
    stage text not null check (stage in ('EVENT_RECEIVED','SIGNAL_FOUND','SIGNAL_MISS','FRAME_INCOMPLETE','PARSED','REJECTED','ACCEPT_NODE','ACCEPT_DISPATCHED','CLICK_RESULT','CONFIRMATION_CONFIRMED','CONFIRMATION_TIMEOUT','STALE','PAUSED','QUEUE_DROPPED','UPLOAD_REJECTED')),
    reason_code text check (reason_code is null or reason_code ~ '^[a-z0-9_.:-]{1,48}$'),
    decision_score integer check (decision_score is null or decision_score between 0 and 100),
    latency_ms bigint check (latency_ms is null or latency_ms between 0 and 86400000),
    latency_bucket text check (latency_bucket is null or latency_bucket ~ '^[A-Za-z0-9_-]{1,24}$'),
    config_version text check (config_version is null or config_version ~ '^[A-Za-z0-9._-]{1,48}$'),
    profile_version text check (profile_version is null or profile_version ~ '^[A-Za-z0-9._-]{1,48}$'),
    install_cohort text check (install_cohort is null or install_cohort ~ '^[A-Za-z0-9._-]{1,32}$'),
    received_at timestamptz not null default now(),
    constraint telemetry_event_no_payload check (reason_code is null or reason_code !~ '(address|fare|phone|email|token|http)')
);
create index if not exists offer_telemetry_correlation_time on public.offer_telemetry_events(correlation_id, occurred_at desc);
create index if not exists offer_telemetry_funnel on public.offer_telemetry_events(stage, occurred_at desc);
create index if not exists offer_telemetry_cohort on public.offer_telemetry_events(app_id, app_version, android_api, profile_version, occurred_at desc);
alter table public.offer_telemetry_events enable row level security;
revoke all on public.offer_telemetry_events from public, anon, authenticated;

create or replace function public.ingest_offer_telemetry(p_events jsonb) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v_uid uuid := auth.uid(); v_count integer;
begin
    if v_uid is null then raise exception 'not_authenticated' using errcode = '42501'; end if;
    if jsonb_typeof(p_events) <> 'array' or jsonb_array_length(p_events) < 1 or jsonb_array_length(p_events) > 50 then raise exception 'invalid_telemetry_batch' using errcode = '22023'; end if;
    if octet_length(p_events::text) > 200000 then raise exception 'telemetry_batch_too_large' using errcode = '22023'; end if;
    insert into public.offer_telemetry_events(event_id, occurred_at, correlation_id, app_id, app_version, android_api, provider, stage, reason_code, decision_score, latency_ms, latency_bucket, config_version, profile_version, install_cohort)
    select (x->>'eventId')::uuid, to_timestamp((x->>'occurredAtEpochMs')::bigint / 1000.0), x->>'correlationId', x->>'appId', x->>'appVersion', (x->>'androidApi')::integer, x->>'provider', x->>'stage', nullif(x->>'reasonCode',''), (x->>'decisionScore')::integer, (x->>'latencyMs')::bigint, x->>'latencyBucket', x->>'configVersion', x->>'profileVersion', x->>'installCohort'
    from jsonb_array_elements(p_events) x
    on conflict (event_id) do nothing;
    get diagnostics v_count = row_count;
    return jsonb_build_object('accepted', v_count, 'received', jsonb_array_length(p_events));
exception when invalid_text_representation or numeric_value_out_of_range then
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
        'events', coalesce(jsonb_agg(to_jsonb(e) - 'event_id' - 'correlation_id' order by e.occurred_at), '[]'::jsonb),
        'funnel', coalesce((select jsonb_agg(jsonb_build_object('stage', stage, 'count', count) order by stage) from (select stage, count(*) from public.offer_telemetry_events where correlation_id=p_correlation_id and occurred_at >= p_since group by stage) f), '[]'::jsonb),
        'cohorts', coalesce((select jsonb_agg(jsonb_build_object('app_id', app_id, 'app_version', app_version, 'android_api', android_api, 'profile_version', profile_version, 'count', count) order by count desc) from (select app_id, app_version, android_api, profile_version, count(*) from public.offer_telemetry_events where correlation_id=p_correlation_id and occurred_at >= p_since group by app_id, app_version, android_api, profile_version) c), '[]'::jsonb)
    ) into result from public.offer_telemetry_events e where e.correlation_id=p_correlation_id and e.occurred_at >= p_since;
    return coalesce(result, jsonb_build_object('correlation_id', p_correlation_id, 'events', '[]'::jsonb, 'funnel', '[]'::jsonb, 'cohorts', '[]'::jsonb));
end $$;
revoke all on function public.admin_offer_telemetry(text, timestamptz) from public, anon;
grant execute on function public.admin_offer_telemetry(text, timestamptz) to authenticated;

-- Operational retention; schedule this statement daily in Supabase Cron/CI.
comment on table public.offer_telemetry_events is 'Privacy-safe offer tracing. Retain 30 days; delete older rows with a scheduled privileged job.';
