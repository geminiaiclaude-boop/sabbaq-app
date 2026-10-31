-- Fixes from the engineering review of 2026-10-30, each verified against the code before fixing.
-- Functions are their latest definitions with only the named lines changed.

-- C-01: a code is used once, forever. Codes whose driver deleted the account are marked revoked.
update public.activation_codes set revoked = true where redeemed_at is not null and redeemed_by is null and not revoked;
create or replace function public.redeem_code(p_code text, p_device_id text) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare
    v_uid    uuid := auth.uid();
    v_device text;
    v_d      public.drivers%rowtype;
    v_c      public.activation_codes%rowtype;
    v_code   text;
    v_base   timestamptz;
    v_fails  integer;
begin
    if v_uid is null then
        raise exception 'not_authenticated' using errcode = '42501';
    end if;
    if p_device_id is null or octet_length(p_device_id) not between 16 and 256 then
        raise exception 'bad_device' using errcode = '22023';
    end if;
    v_device := btrim(p_device_id);
    if octet_length(v_device) not between 16 and 256 then
        raise exception 'bad_device' using errcode = '22023';
    end if;

    select * into v_d from public.drivers where id = v_uid for update;
    if not found then
        return jsonb_build_object('ok', false, 'error', 'unregistered');
    end if;
    if v_d.status in ('rejected', 'banned') then
        return jsonb_build_object('ok', false, 'error', v_d.status::text);
    end if;

    if v_d.device_id is not null and v_d.device_id <> v_device then
        return jsonb_build_object('ok', false, 'error', 'device_mismatch');
    end if;

    select count(*) into v_fails
    from public.redeem_failures
    where driver_id = v_uid and at > now() - interval '1 hour';
    if v_fails >= public.config_int('redeem_failures_per_hour') then
        return jsonb_build_object('ok', false, 'error', 'rate_limited');
    end if;

    -- Avoid regex work on oversized untrusted input while preserving the normal
    -- invalid-code response and rate-limit accounting.
    if p_code is null or octet_length(p_code) > 128 then
        insert into public.redeem_failures (driver_id) values (v_uid);
        return jsonb_build_object('ok', false, 'error', 'invalid_code');
    end if;
    v_code := upper(regexp_replace(p_code, '[[:space:]-]', '', 'g'));
    if v_code !~ '^[0-9A-HJKMNP-TV-Z]{16}$' then
        insert into public.redeem_failures (driver_id) values (v_uid);
        return jsonb_build_object('ok', false, 'error', 'invalid_code');
    end if;
    v_code := substr(v_code, 1, 4) || '-' || substr(v_code, 5, 4) || '-'
           || substr(v_code, 9, 4) || '-' || substr(v_code, 13, 4);

    select * into v_c from public.activation_codes where code = v_code for update;
    if not found or v_c.revoked or v_c.redeemed_by is not null or v_c.redeemed_at is not null then
        insert into public.redeem_failures (driver_id) values (v_uid);
        return jsonb_build_object('ok', false, 'error', 'invalid_code');
    end if;

    v_base := greatest(now(), coalesce(v_d.pro_until, now()));
    update public.activation_codes set redeemed_by = v_uid, redeemed_at = now() where code = v_code;
    update public.drivers
    set pro_until = v_base + make_interval(days => v_c.days),
        status = 'active',
        device_id = coalesce(device_id, v_device),
        device_bound_at = coalesce(device_bound_at, now())
    where id = v_uid;
    insert into public.audit_log (actor, action, target, detail)
    values (v_uid, 'redeem', v_code, jsonb_build_object('days', v_c.days));
    return jsonb_build_object('ok', true, 'pro_until', v_base + make_interval(days => v_c.days));
end $$;

create or replace function public.admin_revoke_code(p_code text) returns void
language plpgsql security definer set search_path = pg_catalog, public as $$
declare
    v_code text;
begin
    perform public.require_admin();
    if p_code is null or octet_length(p_code) > 128 then
        raise exception 'bad_code' using errcode = '22023';
    end if;
    v_code := upper(regexp_replace(p_code, '[[:space:]-]', '', 'g'));
    if v_code !~ '^[0-9A-HJKMNP-TV-Z]{16}$' then
        raise exception 'bad_code' using errcode = '22023';
    end if;
    v_code := substr(v_code, 1, 4) || '-' || substr(v_code, 5, 4) || '-'
           || substr(v_code, 9, 4) || '-' || substr(v_code, 13, 4);
    update public.activation_codes set revoked = true where code = v_code and redeemed_by is null and redeemed_at is null;
    if not found then
        raise exception 'code_not_revocable' using errcode = '22023';
    end if;
    insert into public.audit_log (actor, action, target) values (auth.uid(), 'revoke_code', v_code);
end $$;

-- H-01: RFC 3986 percent-encoding of UTF-8 text; libcurl refuses a URL with a raw space.
create or replace function private.url_encode(p_text text) returns text
language sql immutable strict set search_path = pg_catalog as $$
    select coalesce(string_agg(
        case when b between 48 and 57 or b between 65 and 90 or b between 97 and 122 or b in (45, 46, 95, 126)
             then chr(b) else '%' || upper(lpad(to_hex(b), 2, '0')) end, '' order by i), '')
    from (select get_byte(v, i) b, i from (select convert_to(p_text, 'UTF8') v) x,
          generate_series(0, octet_length(convert_to(p_text, 'UTF8')) - 1) i) s
$$;
revoke all on function private.url_encode(text) from public, anon, authenticated;

create or replace function private.geocode_fetch(p_text text, p_bounds text, p_key text) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v_status integer; v_body text; v_json jsonb; v_points jsonb;
begin
    if not exists (select 1 from pg_extension where extname = 'http') then return null; end if;
    begin
        execute 'select extensions.http_set_curlopt(''CURLOPT_TIMEOUT_MS'', ''4000'')';
        execute format('select status, content from extensions.http_get(%L)',
            'https://maps.googleapis.com/maps/api/geocode/json?language=ar&region=sa&address='
                || private.url_encode(p_text)
                || coalesce('&bounds=' || private.url_encode(p_bounds), '') || '&key=' || private.url_encode(p_key))
            into v_status, v_body;
    exception when others then
        return null;
    end;
    if v_status is distinct from 200 then return null; end if;
    v_json := v_body::jsonb;
    if v_json ->> 'status' = 'ZERO_RESULTS' then return jsonb_build_object('points', '[]'::jsonb); end if;
    if v_json ->> 'status' is distinct from 'OK' then return null; end if;
    select coalesce(jsonb_agg(jsonb_build_object(
               'lat', (r -> 'geometry' -> 'location' ->> 'lat')::double precision,
               'lng', (r -> 'geometry' -> 'location' ->> 'lng')::double precision)), '[]'::jsonb)
      into v_points
      from (select r from jsonb_array_elements(v_json -> 'results') r limit 5) s;
    return jsonb_build_object('points', v_points);
end;
$$;

-- H-02: a driver with no phone (Google sign-in) belongs to the default country, SA.
create or replace function private.zone_country(p_phone text) returns text
language sql immutable set search_path = pg_catalog as $$
    select case
        when regexp_replace(coalesce(p_phone, ''), '[^0-9]', '', 'g') like '966%' then 'SA'
        when regexp_replace(coalesce(p_phone, ''), '[^0-9]', '', 'g') like '962%' then 'JO'
        when regexp_replace(coalesce(p_phone, ''), '[^0-9]', '', 'g') = '' then 'SA'
    end
$$;

-- H-06: only active drivers with a trial or subscription spend the paid Google quota.
create or replace function public.geocode_resolve(p_key text, p_text text, p_lat double precision default null,
                                                  p_lng double precision default null) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare
    v_uid uuid := auth.uid();
    v_key text := private.geocode_fold(p_text);
    v_points jsonb;
    v_settings private.geocode_settings;
    v_month integer;
    v_today integer;
    v_bounds text;
    v_lat double precision;
    v_lng double precision;
    v_result jsonb;
begin
    if v_uid is null then raise exception 'not_authenticated' using errcode = '42501'; end if;
    if not exists (select 1 from public.drivers where id = v_uid) then raise exception 'unregistered' using errcode = '42501'; end if;
    if not exists (select 1 from public.drivers d where d.id = v_uid and d.status = 'active'
                   and private.plan_state(d.trial_until, d.pro_until) in ('subscribed', 'trial')) then
        raise exception 'not_eligible' using errcode = '42501';
    end if;
    if p_text is null or char_length(btrim(p_text)) not between 1 and 300 or char_length(v_key) not between 1 and 200 then
        raise exception 'invalid_address' using errcode = '22023';
    end if;

    select * into v_settings from private.geocode_settings where id;
    insert into private.geocode_usage (driver_id, day, lookups) values (v_uid, current_date, 1)
    on conflict (driver_id, day) do update set lookups = private.geocode_usage.lookups + 1
    returning lookups into v_today;
    if v_today > coalesce(v_settings.driver_daily_cap, 0) then
        return jsonb_build_object('status', 'unavailable', 'reason', 'driver_cap');
    end if;

    update public.geocode_cache set hits = hits + 1 where key = v_key returning points into v_points;
    if found then return jsonb_build_object('status', 'resolved', 'points', v_points); end if;

    if v_settings.id is null or not v_settings.enabled or coalesce(v_settings.google_api_key, '') = '' then
        return jsonb_build_object('status', 'unavailable', 'reason', 'disabled');
    end if;
    select coalesce(sum(calls), 0) into v_month from private.geocode_usage where day >= date_trunc('month', current_date)::date;
    if v_month >= v_settings.monthly_cap then
        return jsonb_build_object('status', 'unavailable', 'reason', 'monthly_cap');
    end if;
    update private.geocode_usage set calls = calls + 1 where driver_id = v_uid and day = current_date;

    if p_lat is not null and p_lng is not null and p_lat between -90 and 90 and p_lng between -180 and 180 then
        v_lat := round(p_lat * 2) / 2.0;
        v_lng := round(p_lng * 2) / 2.0;
        v_bounds := format('%s,%s|%s,%s', v_lat - 1, v_lng - 1, v_lat + 1, v_lng + 1);
    end if;
    -- Google is asked for exactly the folded text the key is, so one key always means one query.
    v_result := private.geocode_fetch(v_key, v_bounds, v_settings.google_api_key);
    if v_result is null then return jsonb_build_object('status', 'unavailable', 'reason', 'fetch_failed'); end if;

    insert into public.geocode_cache (key, points, source) values (v_key, v_result -> 'points', 'google')
    on conflict (key) do nothing;
    return jsonb_build_object('status', 'resolved', 'points', v_result -> 'points');
end;
$$;

create or replace function public.zone_places(p_lat double precision, p_lng double precision, p_radius integer) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare
    v_uid uuid := auth.uid();
    v_settings private.geocode_settings;
    v_today integer;
    v_month integer;
    v_cell text;
    v_places jsonb;
    v_result jsonb;
begin
    if v_uid is null then raise exception 'not_authenticated' using errcode = '42501'; end if;
    if not exists (select 1 from public.drivers where id = v_uid) then raise exception 'unregistered' using errcode = '42501'; end if;
    if not exists (select 1 from public.drivers d where d.id = v_uid and d.status = 'active'
                   and private.plan_state(d.trial_until, d.pro_until) in ('subscribed', 'trial')) then
        raise exception 'not_eligible' using errcode = '42501';
    end if;
    if p_lat is null or p_lng is null or p_lat not between -90 and 90 or p_lng not between -180 and 180
       or p_radius is null or p_radius not between 250 and 1500 then
        raise exception 'invalid_point' using errcode = '22023';
    end if;

    select * into v_settings from private.geocode_settings where id;
    if v_settings.id is null or not v_settings.enabled or not v_settings.places_enabled
       or coalesce(v_settings.google_api_key, '') = '' then
        return jsonb_build_object('status', 'unavailable', 'reason', 'disabled');
    end if;
    insert into private.geocode_usage (driver_id, day, lookups) values (v_uid, current_date, 1)
    on conflict (driver_id, day) do update set lookups = private.geocode_usage.lookups + 1
    returning lookups into v_today;
    if v_today > v_settings.driver_daily_cap then
        return jsonb_build_object('status', 'unavailable', 'reason', 'driver_cap');
    end if;

    -- About 110 m cells: neighbouring drivers warming the same zone share one answer.
    v_cell := format('%s,%s,%s', round(p_lat::numeric, 3), round(p_lng::numeric, 3), p_radius);
    select places into v_places from private.places_cells where cell = v_cell and created_at > now() - interval '30 days';
    if found then return jsonb_build_object('status', 'resolved', 'places', v_places); end if;

    select coalesce(sum(calls), 0) into v_month from private.geocode_usage where day >= date_trunc('month', current_date)::date;
    if v_month + 2 > v_settings.monthly_cap then
        return jsonb_build_object('status', 'unavailable', 'reason', 'monthly_cap');
    end if;
    update private.geocode_usage set calls = calls + 2 where driver_id = v_uid and day = current_date;

    v_result := private.places_fetch(p_lat, p_lng, p_radius, v_settings.google_api_key);
    if v_result is null then return jsonb_build_object('status', 'unavailable', 'reason', 'fetch_failed'); end if;
    insert into private.places_cells (cell, places) values (v_cell, v_result -> 'places')
    on conflict (cell) do update set places = excluded.places, created_at = now();
    return jsonb_build_object('status', 'resolved', 'places', v_result -> 'places');
end;
$$;

-- H-07, M-11: see the comments in the function.
create or replace function private.referral_first_payment() returns trigger
language plpgsql security definer set search_path = pg_catalog, public as $$
declare
    v_settings record;
    v_claim public.referral_claims;
    v_referrer public.drivers;
    v_same text;
begin
    if coalesce(current_setting('sabbaq.referral_reward', true), '') = 'on' then return new; end if;
    if new.pro_until is null or new.pro_until <= greatest(now(), coalesce(old.pro_until, '-infinity'::timestamptz)) then return new; end if;
    if old.has_ever_paid then return new; end if;
    new.has_ever_paid := true;
    if new.referred_by is null then return new; end if;

    select * into v_claim from public.referral_claims where referred_id = new.id for update;
    if not found or v_claim.status <> 'awaiting_first_payment' then return new; end if;
    select * into v_settings from private.referral_settings();
    if not v_settings.enabled then return new; end if;
    select * into v_referrer from public.drivers where id = new.referred_by for update;
    if not found or v_referrer.status = 'banned' then return new; end if;
    v_same := case
        when new.phone is not null and new.phone = v_referrer.phone then 'same_phone'
        when new.device_id is not null and new.device_id = v_referrer.device_id then 'same_device'
        else private.referral_same_person(new.id, v_referrer.id) end;
    if v_same is not null then
        update public.referral_claims set status = 'rejected_fraud', rejection_reason = v_same || '_at_payment'
        where referred_id = new.id;
        insert into public.audit_log (actor, action, target, detail)
        values (new.id, 'referral_rejected', v_referrer.id::text, jsonb_build_object('reason', v_same));
        return new;
    end if;

    perform set_config('sabbaq.referral_reward', 'on', true);
    update public.drivers
    set pro_until = greatest(now(), coalesce(pro_until, now())) + make_interval(days => v_settings.reward_days),
        referral_days_earned = referral_days_earned + v_settings.reward_days,
        status = case when status = 'pending' then 'active'::public.driver_status else status end
    where id = v_referrer.id;
    perform set_config('sabbaq.referral_reward', 'off', true);

    update public.referral_claims set status = 'rewarded', reward_days = v_settings.reward_days, rewarded_at = now()
    where referred_id = new.id;
    insert into public.driver_notifications (driver_id, title, body, kind, action)
    values (v_referrer.id, 'حصلت على أيام مجانية 🎁',
            format('اشترك زميلك %s لأول مرة، فأُضيفت %s أيام مجانية إلى اشتراكك.', new.full_name, v_settings.reward_days),
            'success', 'open_account');
    insert into public.audit_log (actor, action, target, detail)
    values (new.id, 'referral_rewarded', v_referrer.id::text, jsonb_build_object('days', v_settings.reward_days));
    return new;
end;
$$;

-- H-05: only paying or trial drivers teach the fleet, and no report counts for more than 3.
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
            from public.screen_samples s join public.drivers dr on dr.id = s.ingested_by, jsonb_array_elements(s.nodes) n
            where s.received_at > now() - interval '14 days' and s.provider = 'jeeny' and n ? 't'
              and dr.status = 'active' and private.plan_state(dr.trial_until, dr.pro_until) in ('subscribed', 'trial')),
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
        select sl.proposal_id, sum(least(e.event_count, 3))::int hits, count(distinct e.ingested_by)::int drivers
        from public.offer_telemetry_events e
        join public.drivers dr on dr.id = e.ingested_by and dr.status = 'active'
             and private.plan_state(dr.trial_until, dr.pro_until) in ('subscribed', 'trial')
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

-- M-12: private tables and helpers are closed to the API roles; app_config only for administrators.
alter table if exists private.geocode_settings enable row level security;
alter table if exists private.geocode_usage enable row level security;
revoke all on all tables in schema private from public, anon, authenticated;
do $$
declare f record;
begin
    for f in select p.oid::regprocedure sig from pg_proc p join pg_namespace n on n.oid = p.pronamespace where n.nspname = 'private' loop
        execute format('revoke all on function %s from public, anon, authenticated', f.sig);
    end loop;
end $$;
drop policy if exists config_read on public.app_config;
create policy config_admin_read on public.app_config for select to authenticated using (public.is_admin());

-- M-13: the unused calibration table takes no more rows; hot admin filters get indexes; the geocode
-- cache is purged daily.
drop policy if exists captures_insert_own on public.calibration_captures;
revoke insert on public.calibration_captures from authenticated;
create index if not exists offer_telemetry_driver_occurred on public.offer_telemetry_events (ingested_by, occurred_at desc);
create index if not exists offer_telemetry_occurred on public.offer_telemetry_events (occurred_at desc);
create index if not exists audit_log_actor_at on public.audit_log (actor, at desc);
create index if not exists audit_log_target on public.audit_log (target);
do $$
begin
    if exists (select 1 from pg_extension where extname = 'pg_cron') then
        perform cron.unschedule(jobid) from cron.job where jobname = 'purge_geocode_cache';
        perform cron.schedule('purge_geocode_cache', '47 3 * * *', 'select public.purge_geocode_cache()');
    end if;
end $$;

-- M-14: every administrative change to these tables is audited with the acting administrator,
-- whatever function made it.
create or replace function private.audit_admin_change() returns trigger
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v_row jsonb := to_jsonb(coalesce(new, old));
begin
    if auth.uid() is null or not public.is_admin() then return coalesce(new, old); end if;
    insert into public.audit_log (actor, action, target, detail)
    values (auth.uid(), lower(tg_table_name) || '_' || lower(tg_op),
            coalesce(v_row ->> 'id', v_row ->> 'driver_id', v_row ->> 'key', v_row ->> 'text_key'),
            v_row - 'body' - 'value' - 'google_api_key' - 'places_api_key' - 'nodes');
    return coalesce(new, old);
end $$;
revoke all on function private.audit_admin_change() from public, anon, authenticated;
do $$
declare t text;
begin
    foreach t in array array['public.driver_notifications', 'public.support_messages', 'public.support_threads',
                             'public.screen_samples', 'public.app_config', 'private.geocode_settings', 'public.geocode_cache'] loop
        if to_regclass(t) is not null then
            execute format('drop trigger if exists audit_admin_change on %s', t);
            execute format('create trigger audit_admin_change after insert or update or delete on %s for each row execute function private.audit_admin_change()', t);
        end if;
    end loop;
end $$;
