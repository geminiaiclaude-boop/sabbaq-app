-- Shared address -> point cache for destination zones. A driver's app asks the server for an
-- order address it cannot place; a miss is geocoded once with Google Geocoding (the key never
-- leaves the server) and every driver reuses the answer. A monthly cap and a per-driver daily
-- cap bound the bill; when Google is off, capped or failing the app falls back on its own.
create schema if not exists private;
revoke all on schema private from public, anon, authenticated;

create table if not exists public.geocode_cache (
    key text primary key check (char_length(key) between 1 and 200),
    points jsonb not null default '[]'::jsonb check (jsonb_typeof(points) = 'array'),
    source text not null check (source in ('google', 'manual')),
    created_at timestamptz not null default now(),
    hits integer not null default 0
);
alter table public.geocode_cache enable row level security;
revoke all on public.geocode_cache from public, anon, authenticated;

create table if not exists private.geocode_settings (
    id boolean primary key default true check (id),
    google_api_key text,
    enabled boolean not null default false,
    monthly_cap integer not null default 9000 check (monthly_cap >= 0),
    driver_daily_cap integer not null default 200 check (driver_daily_cap >= 0)
);
insert into private.geocode_settings (id) values (true) on conflict (id) do nothing;

-- calls: requests sent to Google (the monthly cap); lookups: every request this driver made,
-- answered from the cache or not (the daily cap), so the cache cannot be probed without bound.
create table if not exists private.geocode_usage (
    driver_id uuid not null references public.drivers (id) on delete cascade,
    day date not null default current_date,
    calls integer not null default 0,
    lookups integer not null default 0,
    primary key (driver_id, day)
);

-- The app's address folding (DecisionEngine.fold): Latin digits, one alef, ta marbuta as ha,
-- one ya, Persian kaf as kaf, waw hamza as waw, no diacritics/tatweel/bare hamza, lower case,
-- single spaces. The cache key is always computed here, never taken from the caller.
create or replace function private.geocode_fold(p_text text) returns text
language sql immutable set search_path = pg_catalog as $$
    select btrim(regexp_replace(lower(
        regexp_replace(
            translate(coalesce(p_text, ''), '٠١٢٣٤٥٦٧٨٩أإآٱٲٳةىئیکؤ', '0123456789اااااا' || 'هيييكو'),
            '[\u064B-\u0655\u0670\u0640\u0621]', '', 'g')), '\s+', ' ', 'g'))
$$;

-- The Google request, kept apart so it can be replaced in tests. Returns {"points":[{lat,lng}…]}
-- (empty when Google found nothing) or null when the call could not be made or was refused.
create or replace function private.geocode_fetch(p_text text, p_bounds text, p_key text) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v_status integer; v_body text; v_json jsonb; v_points jsonb;
begin
    if not exists (select 1 from pg_extension where extname = 'http') then return null; end if;
    begin
        execute 'select extensions.http_set_curlopt(''CURLOPT_TIMEOUT_MS'', ''4000'')';
        execute format('select status, content from extensions.http_get(%L)',
            'https://maps.googleapis.com/maps/api/geocode/json?language=ar&region=sa&address='
                || replace(replace(replace(p_text, '%', '%25'), '&', '%26'), '#', '%23')
                || coalesce('&bounds=' || p_bounds, '') || '&key=' || p_key)
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
revoke all on function private.geocode_fetch(text, text, text) from public, anon, authenticated;

-- Driver entry point. p_text is the address as shown; the cache key is folded here from it
-- (p_key is accepted for the app's convenience but never trusted). p_lat/p_lng: the driver's
-- position, only rounded to 0.5° to bias the search. Cached or fresh, the answer looks the same.
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
revoke all on function public.geocode_resolve(text, text, double precision, double precision) from public, anon;
grant execute on function public.geocode_resolve(text, text, double precision, double precision) to authenticated;

-- Administration: switch Google on or off, set the key and the caps, read this month's usage.
-- The key is write-only: it is never returned, only whether one is set.
create or replace function public.admin_geocode_settings(p_enabled boolean default null, p_google_api_key text default null,
                                                        p_monthly_cap integer default null, p_driver_daily_cap integer default null) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v_settings private.geocode_settings;
begin
    if not public.is_admin() then raise exception 'not_admin' using errcode = '42501'; end if;
    if p_monthly_cap is not null and p_monthly_cap < 0 or p_driver_daily_cap is not null and p_driver_daily_cap < 0 then
        raise exception 'invalid_cap' using errcode = '22023';
    end if;
    update private.geocode_settings set
        enabled = coalesce(p_enabled, enabled),
        google_api_key = case when p_google_api_key is null then google_api_key
                              when btrim(p_google_api_key) = '' then null else btrim(p_google_api_key) end,
        monthly_cap = coalesce(p_monthly_cap, monthly_cap),
        driver_daily_cap = coalesce(p_driver_daily_cap, driver_daily_cap)
    where id
    returning * into v_settings;
    return jsonb_build_object(
        'enabled', v_settings.enabled,
        'has_key', coalesce(v_settings.google_api_key, '') <> '',
        'monthly_cap', v_settings.monthly_cap,
        'driver_daily_cap', v_settings.driver_daily_cap,
        'month_calls', (select coalesce(sum(calls), 0) from private.geocode_usage where day >= date_trunc('month', current_date)::date),
        'cached_addresses', (select count(*) from public.geocode_cache));
end;
$$;
revoke all on function public.admin_geocode_settings(boolean, text, integer, integer) from public, anon;
grant execute on function public.admin_geocode_settings(boolean, text, integer, integer) to authenticated;

-- Administration: forget a wrong answer so the next lookup asks Google again.
create or replace function public.admin_geocode_forget(p_text text) returns boolean
language plpgsql security definer set search_path = pg_catalog, public as $$
begin
    if not public.is_admin() then raise exception 'not_admin' using errcode = '42501'; end if;
    delete from public.geocode_cache where key = private.geocode_fold(p_text);
    return found;
end;
$$;
revoke all on function public.admin_geocode_forget(text) from public, anon;
grant execute on function public.admin_geocode_forget(text) to authenticated;

-- Housekeeping: "not found" answers are retried after 30 days; usage rows kept 90 days.
create or replace function public.purge_geocode_cache() returns integer
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v_count integer;
begin
    delete from public.geocode_cache where points = '[]'::jsonb and created_at < now() - interval '30 days';
    get diagnostics v_count = row_count;
    delete from private.geocode_usage where day < current_date - 90;
    return v_count;
end;
$$;
revoke all on function public.purge_geocode_cache() from public, anon, authenticated;
