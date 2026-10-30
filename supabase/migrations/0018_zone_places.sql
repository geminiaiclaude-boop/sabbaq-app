-- Paid Google Places for zone warming (the reference's `warm_places`, off until the
-- administration switches it on): the named places Google knows around one grid point inside a
-- zone being warmed. The key never leaves the server; each cell is fetched once and shared by
-- every driver for 30 days; each Google request counts against the geocoding monthly cap and
-- each lookup against the driver's daily cap.
alter table private.geocode_settings add column if not exists places_enabled boolean not null default false;

create table if not exists private.places_cells (
    cell text primary key check (char_length(cell) between 1 and 60),
    places jsonb not null default '[]'::jsonb check (jsonb_typeof(places) = 'array'),
    created_at timestamptz not null default now()
);

-- The Google request (`places:searchNearby`, Arabic then English, 20 each, nearest first), kept
-- apart so it can be replaced in tests. Returns {"places":[{name,lat,lng}…]} or null when no
-- request succeeded.
create or replace function private.places_fetch(p_lat double precision, p_lng double precision, p_radius integer,
                                                p_key text) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v_status integer; v_body text; v_lang text; v_any boolean := false; v_out jsonb := '[]'::jsonb; v_seen text[] := '{}';
        r jsonb; v_name text;
begin
    if not exists (select 1 from pg_extension where extname = 'http') then return null; end if;
    foreach v_lang in array array['ar', 'en'] loop
        begin
            execute 'select extensions.http_set_curlopt(''CURLOPT_TIMEOUT_MS'', ''6000'')';
            execute 'select status, content from extensions.http((''POST'', $1, array[extensions.http_header(''X-Goog-Api-Key'', $2), '
                 || 'extensions.http_header(''X-Goog-FieldMask'', ''places.displayName,places.location'')], ''application/json'', $3)::extensions.http_request)'
               into v_status, v_body
              using 'https://places.googleapis.com/v1/places:searchNearby', p_key,
                    jsonb_build_object('languageCode', v_lang, 'maxResultCount', 20, 'rankPreference', 'DISTANCE',
                        'locationRestriction', jsonb_build_object('circle', jsonb_build_object(
                            'center', jsonb_build_object('latitude', p_lat, 'longitude', p_lng), 'radius', p_radius)))::text;
        exception when others then
            continue;
        end;
        if v_status is distinct from 200 then continue; end if;
        v_any := true;
        for r in select x from jsonb_array_elements(coalesce(v_body::jsonb -> 'places', '[]'::jsonb)) x limit 20 loop
            v_name := btrim(coalesce(r -> 'displayName' ->> 'text', ''));
            if v_name = '' or r -> 'location' -> 'latitude' is null or lower(v_name) = any (v_seen) then continue; end if;
            v_seen := v_seen || lower(v_name);
            v_out := v_out || jsonb_build_array(jsonb_build_object('name', left(v_name, 120),
                'lat', (r -> 'location' ->> 'latitude')::double precision,
                'lng', (r -> 'location' ->> 'longitude')::double precision));
        end loop;
    end loop;
    if not v_any then return null; end if;
    return jsonb_build_object('places', v_out);
end;
$$;
revoke all on function private.places_fetch(double precision, double precision, integer, text) from public, anon, authenticated;

-- Driver entry point: one grid point inside a zone the driver is warming. p_radius is the
-- reference's search radius (250–1500 m). Cached or fresh, the answer looks the same.
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
revoke all on function public.zone_places(double precision, double precision, integer) from public, anon;
grant execute on function public.zone_places(double precision, double precision, integer) to authenticated;

-- Administration: switch the Places step on or off (Google itself is switched in
-- admin_geocode_settings); read whether it is on and how many cells are stored.
create or replace function public.admin_places_settings(p_enabled boolean default null) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v_on boolean;
begin
    if not public.is_admin() then raise exception 'not_admin' using errcode = '42501'; end if;
    update private.geocode_settings set places_enabled = coalesce(p_enabled, places_enabled) where id
    returning places_enabled into v_on;
    if p_enabled is not null then
        insert into public.audit_log (actor, action, target, detail)
        values (auth.uid(), 'set_places_enabled', 'geocode_settings', jsonb_build_object('places_enabled', v_on));
    end if;
    return jsonb_build_object('places_enabled', coalesce(v_on, false),
        'cells', (select count(*) from private.places_cells));
end;
$$;
revoke all on function public.admin_places_settings(boolean) from public, anon;
grant execute on function public.admin_places_settings(boolean) to authenticated;

-- Housekeeping as 0014, plus Places cells older than 30 days.
create or replace function public.purge_geocode_cache() returns integer
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v_count integer;
begin
    delete from public.geocode_cache where points = '[]'::jsonb and created_at < now() - interval '30 days';
    get diagnostics v_count = row_count;
    delete from private.geocode_usage where day < current_date - 90;
    delete from private.places_cells where created_at < now() - interval '30 days';
    return v_count;
end;
$$;
revoke all on function public.purge_geocode_cache() from public, anon, authenticated;
