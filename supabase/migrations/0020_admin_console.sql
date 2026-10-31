-- Admin console: the drivers table (plan state and live app state for every account, searched and
-- filtered on the server), one driver's full page, and the activation-code statistics.
--
-- Plan state, one rule everywhere: subscribed while pro_until is ahead, trial while trial_until
-- is ahead, expired when either existed, none otherwise. Live state from the app's 5-minute
-- HEARTBEAT: monitoring when the latest heartbeat is under 12 minutes old and says act=1, online
-- when it is that recent but monitoring is off, offline when older, never when there is none.

-- The latest heartbeat per driver is read for every row of the table: a partial index keeps that
-- one index probe per driver however much other telemetry a phone sends.
create index if not exists offer_telemetry_heartbeat_by_driver
    on public.offer_telemetry_events (ingested_by, received_at desc) where stage = 'HEARTBEAT';
create index if not exists offer_telemetry_accept_learned
    on public.offer_telemetry_events (received_at desc) where stage = 'ACCEPT_LEARNED';

create or replace function private.plan_state(p_trial timestamptz, p_pro timestamptz) returns text
language sql stable set search_path = pg_catalog as $$
    select case
        when p_pro is not null and p_pro > now() then 'subscribed'
        when p_trial is not null and p_trial > now() then 'trial'
        when p_pro is not null or p_trial is not null then 'expired'
        else 'none' end
$$;

create or replace function private.live_state(p_heartbeat timestamptz, p_detail text) returns text
language sql stable set search_path = pg_catalog as $$
    select case
        when p_heartbeat is null then 'never'
        when p_heartbeat < now() - interval '12 minutes' then 'offline'
        when coalesce(p_detail, '') ~ '(^|,)act=1(,|$)' then 'monitoring'
        else 'online' end
$$;

-- One row per driver with everything the table shows; the list, its counts and the page share it.
create or replace function private.admin_driver_rows() returns table (
    id uuid, full_name text, phone text, status text, created_at timestamptz,
    trial_until timestamptz, pro_until timestamptz, plan text,
    device_linked boolean, version_code integer, version_name text,
    heartbeat_at timestamptz, live text, issue text, last_seen timestamptz)
language sql stable security definer set search_path = pg_catalog, public as $$
    select d.id, d.full_name, d.phone, d.status::text, d.created_at,
           d.trial_until, d.pro_until, private.plan_state(d.trial_until, d.pro_until),
           d.device_id is not null, d.app_version, hb.app_version,
           hb.received_at, private.live_state(hb.received_at, hb.detail), hb.reason_code,
           greatest(d.last_seen_at, hb.received_at)
    from public.drivers d
    left join lateral (
        select e.received_at, e.detail, e.reason_code, e.app_version
        from public.offer_telemetry_events e
        where e.ingested_by = d.id and e.stage = 'HEARTBEAT'
        order by e.received_at desc limit 1
    ) hb on true
$$;
revoke all on function private.admin_driver_rows() from public, anon, authenticated;

create or replace function public.admin_driver_list(
    p_search text default null, p_filter text default 'all', p_offset integer default 0, p_limit integer default 50)
returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, public as $$
declare
    v_search text := nullif(btrim(coalesce(p_search, '')), '');
    v_filter text := coalesce(p_filter, 'all');
    v_items jsonb;
    v_total integer;
    v_counts jsonb;
begin
    perform public.require_admin();
    if v_filter not in ('all', 'subscribed', 'trial', 'expired', 'none', 'monitoring', 'online', 'offline', 'pending', 'banned') then
        raise exception 'bad_filter' using errcode = '22023';
    end if;
    if p_offset is null or p_offset < 0 or p_limit is null or p_limit not between 1 and 100 then
        raise exception 'bad_page' using errcode = '22023';
    end if;
    if v_search is not null and char_length(v_search) > 40 then raise exception 'bad_search' using errcode = '22023'; end if;

    with rows as materialized (select * from private.admin_driver_rows()),
    picked as (
        select * from rows r
        where (v_search is null
               or r.full_name ilike '%' || replace(replace(v_search, '%', ''), '_', '') || '%'
               or (regexp_replace(v_search, '[^0-9]', '', 'g') <> ''
                   and r.phone like '%' || regexp_replace(v_search, '[^0-9]', '', 'g') || '%'))
          and case v_filter
                when 'all' then true
                when 'subscribed' then r.plan = 'subscribed'
                when 'trial' then r.plan = 'trial'
                when 'expired' then r.plan = 'expired'
                when 'none' then r.plan = 'none'
                when 'monitoring' then r.live = 'monitoring'
                when 'online' then r.live in ('monitoring', 'online')
                when 'offline' then r.live in ('offline', 'never')
                when 'pending' then r.status = 'pending'
                when 'banned' then r.status = 'banned'
              end
    )
    select
        (select jsonb_build_object(
            'all', count(*),
            'subscribed', count(*) filter (where plan = 'subscribed'),
            'trial', count(*) filter (where plan = 'trial'),
            'expired', count(*) filter (where plan = 'expired'),
            'none', count(*) filter (where plan = 'none'),
            'monitoring', count(*) filter (where live = 'monitoring'),
            'online', count(*) filter (where live in ('monitoring', 'online')),
            'offline', count(*) filter (where live in ('offline', 'never')),
            'pending', count(*) filter (where status = 'pending'),
            'banned', count(*) filter (where status = 'banned')) from rows),
        (select count(*) from picked),
        coalesce((select jsonb_agg(to_jsonb(p) order by p.last_seen desc nulls last, p.created_at desc, p.id)
                  from (select * from picked order by last_seen desc nulls last, created_at desc, id
                        offset p_offset limit p_limit) p), '[]'::jsonb)
    into v_counts, v_total, v_items;

    return jsonb_build_object('items', v_items, 'total', v_total, 'counts', v_counts,
                              'has_more', p_offset + p_limit < v_total);
end $$;
revoke all on function public.admin_driver_list(text, text, integer, integer) from public, anon;
grant execute on function public.admin_driver_list(text, text, integer, integer) to authenticated;

-- Everything about one driver: account, plan and the codes that built it, the app on the phone
-- now, the last day's and week's offers, referrals, support and notifications.
create or replace function public.admin_driver_detail(p_driver uuid) returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, public as $$
declare
    d public.drivers%rowtype;
    v_row record;
    v_email text;
begin
    perform public.require_admin();
    select * into d from public.drivers where id = p_driver;
    if not found then raise exception 'driver_not_found' using errcode = 'P0002'; end if;
    select * into v_row from private.admin_driver_rows() r where r.id = p_driver;
    select u.email into v_email from auth.users u where u.id = p_driver;

    return jsonb_build_object(
        'account', jsonb_build_object(
            'id', d.id, 'full_name', d.full_name, 'phone', d.phone, 'email', v_email,
            'status', d.status, 'note', d.note, 'created_at', d.created_at,
            'device_id', d.device_id, 'device_bound_at', d.device_bound_at,
            'referral_code', d.referral_code, 'max_active_zones', d.max_active_zones),
        'plan', jsonb_build_object(
            'state', v_row.plan, 'trial_until', d.trial_until, 'pro_until', d.pro_until,
            'codes', coalesce((select jsonb_agg(jsonb_build_object('code', c.code, 'days', c.days, 'batch', c.batch, 'redeemed_at', c.redeemed_at)
                                                order by c.redeemed_at desc)
                               from public.activation_codes c where c.redeemed_by = d.id), '[]'::jsonb)),
        'live', jsonb_build_object(
            'state', v_row.live, 'heartbeat_at', v_row.heartbeat_at, 'issue', v_row.issue,
            'last_seen', v_row.last_seen, 'licence_check_at', d.last_seen_at,
            'version_code', d.app_version, 'version_name', v_row.version_name,
            'flags', (select e.detail from public.offer_telemetry_events e
                      where e.ingested_by = d.id and e.stage = 'HEARTBEAT' order by e.received_at desc limit 1),
            'device', (select e.detail from public.offer_telemetry_events e
                       where e.ingested_by = d.id and e.stage = 'DEVICE_INFO' order by e.received_at desc limit 1),
            'android_api', (select e.android_api from public.offer_telemetry_events e
                            where e.ingested_by = d.id order by e.received_at desc limit 1),
            'ride_app_version', (select e.target_app_version from public.offer_telemetry_events e
                                 where e.ingested_by = d.id and e.target_app_version is not null order by e.received_at desc limit 1)),
        'activity', jsonb_build_object(
            'day', (select jsonb_build_object(
                        'offers', coalesce(sum(event_count) filter (where stage = 'OFFER_DETECTED'), 0),
                        'matched', coalesce(sum(event_count) filter (where stage = 'DECISION_ACCEPTED'), 0),
                        'rejected', coalesce(sum(event_count) filter (where stage = 'REJECTED'), 0),
                        'taps', coalesce(sum(event_count) filter (where stage = 'CLICK_RESULT'), 0),
                        'confirmed', coalesce(sum(event_count) filter (where stage = 'CONFIRMATION_CONFIRMED'), 0))
                    from public.offer_telemetry_events where ingested_by = d.id and received_at >= now() - interval '24 hours'),
            'week', (select jsonb_build_object(
                        'offers', coalesce(sum(event_count) filter (where stage = 'OFFER_DETECTED'), 0),
                        'confirmed', coalesce(sum(event_count) filter (where stage = 'CONFIRMATION_CONFIRMED'), 0))
                     from public.offer_telemetry_events where ingested_by = d.id and received_at >= now() - interval '7 days'),
            'reject_reasons', coalesce((select jsonb_agg(jsonb_build_object('reason', reason_code, 'count', total) order by total desc)
                                        from (select reason_code, sum(event_count) total from public.offer_telemetry_events
                                              where ingested_by = d.id and stage = 'REJECTED' and received_at >= now() - interval '7 days'
                                                and reason_code is not null
                                              group by reason_code order by sum(event_count) desc limit 5) r), '[]'::jsonb)),
        'referrals', jsonb_build_object(
            'referred_by', (select jsonb_build_object('id', r.id, 'full_name', r.full_name) from public.drivers r where r.id = d.referred_by),
            'invited', (select count(*) from public.referral_claims where referrer_id = d.id),
            'rewarded', (select count(*) from public.referral_claims where referrer_id = d.id and status = 'rewarded')),
        'support', (select jsonb_build_object('status', t.status, 'admin_unread', t.admin_unread, 'last_message_at', t.last_message_at,
                                              'messages', (select count(*) from public.support_messages m where m.driver_id = t.driver_id))
                    from public.support_threads t where t.driver_id = d.id),
        'notifications_30d', (select count(*) from public.driver_notifications n
                              where n.driver_id = d.id and n.created_at >= now() - interval '30 days'));
end $$;
revoke all on function public.admin_driver_detail(uuid) from public, anon;
grant execute on function public.admin_driver_detail(uuid) to authenticated;

-- Activation codes at a glance: what is left to sell, what was used and when, per batch and length.
create or replace function public.admin_code_stats() returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, public as $$
begin
    perform public.require_admin();
    return jsonb_build_object(
        'total', (select count(*) from public.activation_codes),
        'available', (select count(*) from public.activation_codes where redeemed_by is null and redeemed_at is null and not revoked),
        'redeemed', (select count(*) from public.activation_codes where redeemed_at is not null),
        'revoked', (select count(*) from public.activation_codes where revoked),
        'redeemed_7d', (select count(*) from public.activation_codes where redeemed_at >= now() - interval '7 days'),
        'redeemed_30d', (select count(*) from public.activation_codes where redeemed_at >= now() - interval '30 days'),
        'by_days', coalesce((select jsonb_agg(jsonb_build_object('days', days, 'total', total, 'available', available) order by days)
                             from (select days, count(*) total,
                                          count(*) filter (where redeemed_at is null and not revoked) available
                                   from public.activation_codes group by days) x), '[]'::jsonb),
        'by_batch', coalesce((select jsonb_agg(jsonb_build_object('batch', batch, 'total', total, 'redeemed', redeemed, 'available', available, 'created_at', created)
                                               order by created desc)
                              from (select coalesce(batch, '') batch, count(*) total,
                                           count(*) filter (where redeemed_at is not null) redeemed,
                                           count(*) filter (where redeemed_at is null and not revoked) available,
                                           max(created_at) created
                                    from public.activation_codes group by coalesce(batch, '')
                                    order by max(created_at) desc limit 20) b), '[]'::jsonb));
end $$;
revoke all on function public.admin_code_stats() from public, anon;
grant execute on function public.admin_code_stats() to authenticated;

-- Learning from confirmed accepts. A phone reports ACCEPT_LEARNED "confirmed:<hash>" with detail
-- "aw=<word>,vid=<button id>,n=<nodes>" each time Android confirms an accept it tapped. Button ids
-- confirmed by at least 3 active, subscribed or trial drivers in 5 accept reports over 30 days
-- become candidates (each report counts at most 3, whatever count a phone claims). Like accept
-- words, a candidate reaches the phones only when the administration approves it
-- (admin_set_accept_id), so a handful of accounts can never steer every phone's tap.
create or replace function private.shared_lexicon_row() returns jsonb
language sql stable security definer set search_path = pg_catalog, public as $$
    select jsonb_build_object(
        'version', coalesce((v ->> 'version')::int, 0),
        'accept', coalesce(v -> 'accept', '[]'::jsonb), 'time', coalesce(v -> 'time', '[]'::jsonb),
        'currency', coalesce(v -> 'currency', '[]'::jsonb), 'noise', coalesce(v -> 'noise', '[]'::jsonb),
        'accept_ids', coalesce(v -> 'accept_ids', '[]'::jsonb))
    from (select coalesce((select value from public.app_config where key = 'shared_lexicon'), '{}'::jsonb) v) s
$$;
revoke all on function private.shared_lexicon_row() from public, anon, authenticated;

create or replace function private.accept_id_candidates() returns table (view_id text, accepts bigint, drivers bigint, last_at timestamptz)
language sql stable security definer set search_path = pg_catalog, public as $$
    select lower(substring(e.detail from '(?:^|,)vid=([a-z0-9_]{2,32})(?:,|$)')) vid,
           sum(least(e.event_count, 3)), count(distinct e.ingested_by), max(e.received_at)
    from public.offer_telemetry_events e
    join public.drivers d on d.id = e.ingested_by
    where e.stage = 'ACCEPT_LEARNED' and e.reason_code like 'confirmed:%'
      and e.received_at > now() - interval '30 days'
      and d.status = 'active' and private.plan_state(d.trial_until, d.pro_until) in ('subscribed', 'trial')
      and substring(e.detail from '(?:^|,)vid=([a-z0-9_]{2,32})(?:,|$)') is not null
      and substring(e.detail from '(?:^|,)vid=([a-z0-9_]{2,32})(?:,|$)') <> 'none'
    group by 1
$$;
revoke all on function private.accept_id_candidates() from public, anon, authenticated;

-- The administration publishes or withdraws one accept-button id (new lexicon version, audited).
-- Publishing needs the id to be a proven candidate; withdrawing is always allowed.
create or replace function public.admin_set_accept_id(p_view_id text, p_publish boolean) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare
    v_row jsonb;
    v_ids jsonb;
    v_id text := lower(btrim(coalesce(p_view_id, '')));
begin
    perform public.require_admin();
    if v_id !~ '^[a-z0-9_]{2,32}$' or v_id = 'none' or p_publish is null then raise exception 'bad_view_id' using errcode = '22023'; end if;
    v_row := private.shared_lexicon_row();
    v_ids := coalesce(v_row -> 'accept_ids', '[]'::jsonb);
    if p_publish then
        if v_ids @> to_jsonb(array[v_id]) then return v_ids; end if;
        if not exists (select 1 from private.accept_id_candidates() c where c.view_id = v_id and c.accepts >= 5 and c.drivers >= 3) then
            raise exception 'not_proven' using errcode = '22023';
        end if;
        if jsonb_array_length(v_ids) >= 10 then raise exception 'too_many_ids' using errcode = '22023'; end if;
        v_ids := v_ids || to_jsonb(array[v_id]);
    else
        if not (v_ids @> to_jsonb(array[v_id])) then return v_ids; end if;
        v_ids := coalesce((select jsonb_agg(x) from jsonb_array_elements(v_ids) x where x <> to_jsonb(v_id)), '[]'::jsonb);
    end if;
    insert into public.app_config (key, value, updated_at)
    values ('shared_lexicon', (v_row - 'version') || jsonb_build_object('accept_ids', v_ids, 'version', (v_row ->> 'version')::int + 1), now())
    on conflict (key) do update set value = excluded.value, updated_at = now();
    insert into public.audit_log (actor, action, target, detail)
    values (auth.uid(), case when p_publish then 'accept_id_publish' else 'accept_id_withdraw' end, v_id,
            jsonb_build_object('ids', v_ids, 'version', (v_row ->> 'version')::int + 1));
    return v_ids;
end $$;
revoke all on function public.admin_set_accept_id(text, boolean) from public, anon;
grant execute on function public.admin_set_accept_id(text, boolean) to authenticated;

-- What the fleet learned from confirmed accepts, for the administration's learning view.
create or replace function public.admin_accept_learning() returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, public as $$
begin
    perform public.require_admin();
    return jsonb_build_object(
        'live', coalesce(private.shared_lexicon_row() -> 'accept_ids', '[]'::jsonb),
        'confirmed_30d', (select coalesce(sum(event_count), 0) from public.offer_telemetry_events
                          where stage = 'ACCEPT_LEARNED' and reason_code like 'confirmed:%' and received_at > now() - interval '30 days'),
        'candidates', coalesce((select jsonb_agg(jsonb_build_object('view_id', view_id, 'accepts', accepts, 'drivers', drivers, 'last_at', last_at,
                                                                    'proven', accepts >= 5 and drivers >= 3)
                                                 order by drivers desc, accepts desc)
                                from (select * from private.accept_id_candidates() order by drivers desc, accepts desc limit 20) c), '[]'::jsonb));
end $$;
revoke all on function public.admin_accept_learning() from public, anon;
grant execute on function public.admin_accept_learning() to authenticated;
