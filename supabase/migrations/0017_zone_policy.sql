-- Destination zone policy published by the administration (the reference app's admin zones).
--
-- 1. Shared exclude zones per country: every driver of that country gets them as a default
--    exclude list (the app lets a driver opt out for themselves). A country can be switched off
--    while its zones stay saved. Each change bumps a version, so apps tell drivers about new zones.
-- 2. The most zones a driver may have switched on at once: one default for everyone, and an
--    override per driver. 0 means no limit.
-- 3. How far a shared zone may be from the driver and still be applied (km, 0 = everywhere).
--
-- app_config 'zone_policy': {version, default_max_zones, proximity_km,
--                            countries: {"SA": {enabled, zones: [...]}, "JO": {...}}}
-- A zone is the app's own format: {"name": text, "active": bool, "points": [[lat, lng], ...]}.

alter table public.drivers add column if not exists max_active_zones integer
    check (max_active_zones is null or max_active_zones between 0 and 100);

-- The driver's country from the phone number: +966 Saudi Arabia, +962 Jordan.
create or replace function private.zone_country(p_phone text) returns text
language sql immutable as $$
    select case
        when regexp_replace(coalesce(p_phone, ''), '[^0-9]', '', 'g') like '966%' then 'SA'
        when regexp_replace(coalesce(p_phone, ''), '[^0-9]', '', 'g') like '962%' then 'JO'
    end
$$;

-- At most 50 zones; each named (1-60 characters), with 3-100 points inside the valid range.
create or replace function private.zones_ok(p_zones jsonb) returns boolean
language plpgsql immutable as $$
declare z jsonb; p jsonb;
begin
    if p_zones is null or jsonb_typeof(p_zones) <> 'array' or jsonb_array_length(p_zones) > 50 then return false; end if;
    for z in select * from jsonb_array_elements(p_zones) loop
        if jsonb_typeof(z) <> 'object' or jsonb_typeof(z -> 'name') <> 'string'
            or length(btrim(z ->> 'name')) not between 1 and 60
            or (z ? 'active' and jsonb_typeof(z -> 'active') <> 'boolean')
            or jsonb_typeof(z -> 'points') <> 'array' or jsonb_array_length(z -> 'points') not between 3 and 100 then
            return false;
        end if;
        for p in select * from jsonb_array_elements(z -> 'points') loop
            if jsonb_typeof(p) <> 'array' or jsonb_array_length(p) <> 2
                or jsonb_typeof(p -> 0) <> 'number' or jsonb_typeof(p -> 1) <> 'number'
                or (p ->> 0)::numeric not between -90 and 90 or (p ->> 1)::numeric not between -180 and 180 then
                return false;
            end if;
        end loop;
    end loop;
    return true;
end $$;

create or replace function private.zone_policy_row() returns jsonb
language sql stable security definer set search_path = pg_catalog, public as $$
    select jsonb_build_object(
        'version', coalesce((v ->> 'version')::int, 0),
        'default_max_zones', coalesce((v ->> 'default_max_zones')::int, 0),
        'proximity_km', coalesce((v ->> 'proximity_km')::numeric, 0),
        'countries', coalesce(v -> 'countries', '{}'::jsonb))
    from (select coalesce((select value from public.app_config where key = 'zone_policy'), '{}'::jsonb) v) s
$$;
revoke all on function private.zone_policy_row() from public, anon, authenticated;

create or replace function private.save_zone_policy(p_value jsonb, p_action text, p_target text) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
begin
    insert into public.app_config (key, value, updated_at) values ('zone_policy', p_value, now())
    on conflict (key) do update set value = excluded.value, updated_at = now();
    insert into public.audit_log (actor, action, target, detail) values (auth.uid(), p_action, p_target, p_value - 'countries');
    return private.zone_policy_row();
end $$;
revoke all on function private.save_zone_policy(jsonb, text, text) from public, anon, authenticated;

-- What one driver's app applies: their country's shared zones (empty when the country is off),
-- their zone limit (their own override, else the default) and the shared-zone radius.
create or replace function public.zone_policy() returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, public as $$
declare v_row jsonb := private.zone_policy_row(); v_country text; v_own int; v_c jsonb;
begin
    if auth.uid() is null then raise exception 'not_authenticated' using errcode = '42501'; end if;
    select private.zone_country(phone), max_active_zones into v_country, v_own from public.drivers where id = auth.uid();
    v_c := case when v_country is null then null else v_row -> 'countries' -> v_country end;
    return jsonb_build_object(
        'version', v_row -> 'version',
        'country', v_country,
        'zones', case when coalesce((v_c ->> 'enabled')::boolean, false) then coalesce(v_c -> 'zones', '[]'::jsonb) else '[]'::jsonb end,
        'max_active_zones', coalesce(v_own, (v_row ->> 'default_max_zones')::int),
        'proximity_km', v_row -> 'proximity_km');
end $$;
revoke all on function public.zone_policy() from public, anon;
grant execute on function public.zone_policy() to authenticated;

create or replace function public.admin_zone_policy() returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, public as $$
begin
    if not public.is_admin() then raise exception 'not_admin' using errcode = '42501'; end if;
    return private.zone_policy_row();
end $$;
revoke all on function public.admin_zone_policy() from public, anon;
grant execute on function public.admin_zone_policy() to authenticated;

-- Replaces one country's shared zones and its on/off switch; the version goes up.
create or replace function public.admin_set_global_zones(p_country text, p_enabled boolean, p_zones jsonb) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v_row jsonb;
begin
    if not public.is_admin() then raise exception 'not_admin' using errcode = '42501'; end if;
    if p_country is null or p_country not in ('SA', 'JO') or p_enabled is null or not private.zones_ok(p_zones) then
        raise exception 'invalid_zones' using errcode = '22023';
    end if;
    v_row := private.zone_policy_row();
    v_row := jsonb_set(v_row, '{countries}', (v_row -> 'countries') || jsonb_build_object(p_country,
        jsonb_build_object('enabled', p_enabled, 'zones', p_zones)));
    v_row := jsonb_set(v_row, '{version}', to_jsonb((v_row ->> 'version')::int + 1));
    return private.save_zone_policy(v_row, 'set_global_zones',
        p_country || ':' || jsonb_array_length(p_zones) || ':' || case when p_enabled then 'on' else 'off' end);
end $$;
revoke all on function public.admin_set_global_zones(text, boolean, jsonb) from public, anon;
grant execute on function public.admin_set_global_zones(text, boolean, jsonb) to authenticated;

create or replace function public.admin_set_zone_limits(p_default_max integer, p_proximity_km numeric) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v_row jsonb;
begin
    if not public.is_admin() then raise exception 'not_admin' using errcode = '42501'; end if;
    if p_default_max is null or p_default_max not between 0 and 100
        or p_proximity_km is null or p_proximity_km not between 0 and 1000 then
        raise exception 'invalid_limits' using errcode = '22023';
    end if;
    v_row := private.zone_policy_row() || jsonb_build_object('default_max_zones', p_default_max, 'proximity_km', p_proximity_km);
    return private.save_zone_policy(v_row, 'set_zone_limits', p_default_max || ':' || p_proximity_km);
end $$;
revoke all on function public.admin_set_zone_limits(integer, numeric) from public, anon;
grant execute on function public.admin_set_zone_limits(integer, numeric) to authenticated;

-- One driver's own limit; null returns them to the default.
create or replace function public.admin_set_driver_zone_limit(p_driver uuid, p_limit integer) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
begin
    if not public.is_admin() then raise exception 'not_admin' using errcode = '42501'; end if;
    if p_limit is not null and p_limit not between 0 and 100 then raise exception 'invalid_limits' using errcode = '22023'; end if;
    update public.drivers set max_active_zones = p_limit where id = p_driver;
    if not found then raise exception 'driver_not_found' using errcode = 'P0002'; end if;
    insert into public.audit_log (actor, action, target, detail)
    values (auth.uid(), 'set_driver_zone_limit', p_driver::text, jsonb_build_object('limit', p_limit));
    return public.admin_driver_zone_limit(p_driver);
end $$;

create or replace function public.admin_driver_zone_limit(p_driver uuid) returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, public as $$
declare v_own int; v_country text;
begin
    if not public.is_admin() then raise exception 'not_admin' using errcode = '42501'; end if;
    select max_active_zones, private.zone_country(phone) into v_own, v_country from public.drivers where id = p_driver;
    if not found then raise exception 'driver_not_found' using errcode = 'P0002'; end if;
    return jsonb_build_object('own', v_own, 'default', (private.zone_policy_row() ->> 'default_max_zones')::int, 'country', v_country);
end $$;
revoke all on function public.admin_driver_zone_limit(uuid) from public, anon;
grant execute on function public.admin_driver_zone_limit(uuid) to authenticated;
revoke all on function public.admin_set_driver_zone_limit(uuid, integer) from public, anon;
grant execute on function public.admin_set_driver_zone_limit(uuid, integer) to authenticated;
