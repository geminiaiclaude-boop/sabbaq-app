-- Qannas backend: driver accounts, admin activation, sold activation codes,
-- single-device binding, remotely updatable ride-app profiles.
-- Every write a driver can make goes through a SECURITY DEFINER function;
-- tables expose read access only through row-level security.

create schema if not exists extensions;
create extension if not exists pgcrypto with schema extensions;

do $$ begin
    create type public.driver_status as enum ('pending', 'active', 'rejected', 'banned');
exception when duplicate_object then null; end $$;

create table if not exists public.drivers (
    id               uuid primary key references auth.users (id) on delete cascade,
    full_name        text not null check (char_length(btrim(full_name)) between 2 and 80),
    phone            text not null check (phone ~ '^\+?[0-9]{7,15}$'),
    status           public.driver_status not null default 'pending',
    trial_until      timestamptz,
    pro_until        timestamptz,
    device_id        text,
    device_bound_at  timestamptz,
    last_seen_at     timestamptz,
    app_version      integer,
    note             text,
    created_at       timestamptz not null default now()
);

create table if not exists public.admins (
    user_id uuid primary key references auth.users (id) on delete cascade
);

create table if not exists public.app_config (
    key        text primary key,
    value      jsonb not null,
    updated_at timestamptz not null default now()
);

create table if not exists public.ride_profiles (
    id         text primary key check (id ~ '^[a-z0-9_]{2,32}$'),
    body       jsonb not null,
    version    integer not null check (version >= 1),
    enabled    boolean not null default true,
    updated_at timestamptz not null default now(),
    constraint body_matches_id check (body ->> 'id' = id),
    constraint body_matches_version check ((body ->> 'version')::int = version)
);

create table if not exists public.activation_codes (
    code        text primary key check (code ~ '^[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){3}$'),
    days        integer not null check (days between 1 and 3660),
    batch       text,
    created_by  uuid references auth.users (id),
    created_at  timestamptz not null default now(),
    redeemed_by uuid references public.drivers (id) on delete set null,
    redeemed_at timestamptz,
    revoked     boolean not null default false
);

create table if not exists public.redeem_failures (
    id        bigserial primary key,
    driver_id uuid not null references public.drivers (id) on delete cascade,
    at        timestamptz not null default now()
);
create index if not exists redeem_failures_driver_at on public.redeem_failures (driver_id, at);

create table if not exists public.calibration_captures (
    id          bigserial primary key,
    driver_id   uuid not null default auth.uid() references public.drivers (id) on delete cascade,
    package     text not null check (char_length(package) between 3 and 200),
    app_version integer,
    tree        jsonb not null check (octet_length(tree::text) <= 600000),
    note        text check (char_length(note) <= 500),
    created_at  timestamptz not null default now()
);

create table if not exists public.audit_log (
    id     bigserial primary key,
    actor  uuid,
    action text not null,
    target text,
    detail jsonb,
    at     timestamptz not null default now()
);

insert into public.app_config (key, value) values
    ('trial_days', '0'::jsonb),
    ('offline_grace_hours', '24'::jsonb),
    ('min_version_code', '1'::jsonb),
    ('announcement', '""'::jsonb),
    ('redeem_failures_per_hour', '10'::jsonb)
on conflict (key) do nothing;

-- ---------------------------------------------------------------- helpers

create or replace function public.is_admin() returns boolean
language sql stable security definer set search_path = public as $$
    select exists (select 1 from public.admins where user_id = auth.uid());
$$;

create or replace function public.config_int(p_key text) returns integer
language sql stable security definer set search_path = public as $$
    select (value #>> '{}')::integer from public.app_config where key = p_key;
$$;

create or replace function public.require_admin() returns void
language plpgsql stable security definer set search_path = public as $$
begin
    if not public.is_admin() then
        raise exception 'not_admin' using errcode = '42501';
    end if;
end $$;

-- ---------------------------------------------------------------- RLS

alter table public.drivers enable row level security;
alter table public.admins enable row level security;
alter table public.app_config enable row level security;
alter table public.ride_profiles enable row level security;
alter table public.activation_codes enable row level security;
alter table public.redeem_failures enable row level security;
alter table public.calibration_captures enable row level security;
alter table public.audit_log enable row level security;

drop policy if exists drivers_read on public.drivers;
create policy drivers_read on public.drivers for select to authenticated
    using (id = auth.uid() or public.is_admin());

drop policy if exists config_read on public.app_config;
create policy config_read on public.app_config for select to authenticated using (true);

drop policy if exists profiles_read on public.ride_profiles;
create policy profiles_read on public.ride_profiles for select to authenticated
    using (enabled or public.is_admin());

drop policy if exists codes_admin_read on public.activation_codes;
create policy codes_admin_read on public.activation_codes for select to authenticated
    using (public.is_admin());

drop policy if exists captures_insert_own on public.calibration_captures;
create policy captures_insert_own on public.calibration_captures for insert to authenticated
    with check (driver_id = auth.uid() and exists (select 1 from public.drivers d where d.id = auth.uid()));

drop policy if exists captures_admin_read on public.calibration_captures;
create policy captures_admin_read on public.calibration_captures for select to authenticated
    using (public.is_admin());

drop policy if exists audit_admin_read on public.audit_log;
create policy audit_admin_read on public.audit_log for select to authenticated
    using (public.is_admin());

revoke all on all tables in schema public from anon;
grant select on public.drivers, public.app_config, public.ride_profiles, public.activation_codes,
    public.calibration_captures, public.audit_log to authenticated;
grant insert on public.calibration_captures to authenticated;
grant usage on sequence public.calibration_captures_id_seq to authenticated;

-- ---------------------------------------------------------------- driver API

create or replace function public.register_driver(p_full_name text, p_phone text) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
    v_uid uuid := auth.uid();
begin
    if v_uid is null then raise exception 'not_authenticated' using errcode = '42501'; end if;
    insert into public.drivers (id, full_name, phone)
    values (v_uid, btrim(p_full_name), regexp_replace(p_phone, '[\s-]', '', 'g'))
    on conflict (id) do nothing;
    insert into public.audit_log (actor, action, target) values (v_uid, 'register', v_uid::text);
    return jsonb_build_object('ok', true);
end $$;

create or replace function public.license_status(p_device_id text, p_version_code integer default null) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
    v_uid   uuid := auth.uid();
    v_d     public.drivers%rowtype;
    v_until timestamptz;
    v_state text;
begin
    if v_uid is null then raise exception 'not_authenticated' using errcode = '42501'; end if;
    if p_device_id is null or char_length(p_device_id) < 16 then raise exception 'bad_device' using errcode = '22023'; end if;

    select * into v_d from public.drivers where id = v_uid for update;
    if not found then
        v_state := 'unregistered';
    elsif v_d.status in ('pending', 'rejected', 'banned') then
        v_state := v_d.status::text;
    else
        if v_d.device_id is null then
            update public.drivers set device_id = p_device_id, device_bound_at = now() where id = v_uid;
            insert into public.audit_log (actor, action, target) values (v_uid, 'device_bound', v_uid::text);
            v_d.device_id := p_device_id;
        end if;
        v_until := greatest(coalesce(v_d.trial_until, '-infinity'), coalesce(v_d.pro_until, '-infinity'));
        if v_d.device_id <> p_device_id then
            v_state := 'device_mismatch';
        elsif v_until > now() then
            v_state := case when coalesce(v_d.pro_until, '-infinity') > now() then 'active' else 'trial' end;
        else
            v_state := 'expired';
        end if;
    end if;

    if v_d.id is not null then
        update public.drivers set last_seen_at = now(), app_version = coalesce(p_version_code, app_version) where id = v_uid;
    end if;

    return jsonb_build_object(
        'state', v_state,
        'valid_until', case when v_until is null or v_until = '-infinity' then null else v_until end,
        'server_time', now(),
        'grace_hours', public.config_int('offline_grace_hours'),
        'min_version_code', public.config_int('min_version_code'),
        'full_name', v_d.full_name,
        'announcement', (select value #>> '{}' from public.app_config where key = 'announcement')
    );
end $$;

create or replace function public.redeem_code(p_code text, p_device_id text) returns jsonb
language plpgsql security definer set search_path = public as $$
declare
    v_uid   uuid := auth.uid();
    v_d     public.drivers%rowtype;
    v_c     public.activation_codes%rowtype;
    v_code  text := upper(regexp_replace(coalesce(p_code, ''), '[\s]', '', 'g'));
    v_base  timestamptz;
    v_fails integer;
begin
    if v_uid is null then raise exception 'not_authenticated' using errcode = '42501'; end if;
    select * into v_d from public.drivers where id = v_uid for update;
    if not found then return jsonb_build_object('ok', false, 'error', 'unregistered'); end if;
    if v_d.status in ('rejected', 'banned') then return jsonb_build_object('ok', false, 'error', v_d.status::text); end if;
    if v_d.device_id is not null and v_d.device_id <> p_device_id then
        return jsonb_build_object('ok', false, 'error', 'device_mismatch');
    end if;

    select count(*) into v_fails from public.redeem_failures where driver_id = v_uid and at > now() - interval '1 hour';
    if v_fails >= public.config_int('redeem_failures_per_hour') then
        return jsonb_build_object('ok', false, 'error', 'rate_limited');
    end if;

    if char_length(v_code) = 16 then
        v_code := substr(v_code, 1, 4) || '-' || substr(v_code, 5, 4) || '-' || substr(v_code, 9, 4) || '-' || substr(v_code, 13, 4);
    end if;

    select * into v_c from public.activation_codes where code = v_code for update;
    if not found or v_c.revoked or v_c.redeemed_by is not null then
        insert into public.redeem_failures (driver_id) values (v_uid);
        return jsonb_build_object('ok', false, 'error', 'invalid_code');
    end if;

    v_base := greatest(now(), coalesce(v_d.pro_until, now()));
    update public.activation_codes set redeemed_by = v_uid, redeemed_at = now() where code = v_code;
    update public.drivers
        set pro_until = v_base + make_interval(days => v_c.days),
            status = 'active',
            device_id = coalesce(device_id, p_device_id),
            device_bound_at = coalesce(device_bound_at, now())
        where id = v_uid;
    insert into public.audit_log (actor, action, target, detail)
        values (v_uid, 'redeem', v_code, jsonb_build_object('days', v_c.days));
    return jsonb_build_object('ok', true, 'pro_until', v_base + make_interval(days => v_c.days));
end $$;

-- ---------------------------------------------------------------- admin API

create or replace function public.admin_generate_codes(p_days integer, p_count integer, p_batch text default null)
returns setof text
language plpgsql security definer set search_path = public, extensions as $$
declare
    alphabet constant text := '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
    v_bytes bytea;
    v_code  text;
    i       integer;
    j       integer;
begin
    perform public.require_admin();
    if p_count not between 1 and 500 then raise exception 'bad_count' using errcode = '22023'; end if;
    for i in 1..p_count loop
        loop
            v_bytes := gen_random_bytes(16);
            v_code := '';
            for j in 0..15 loop
                v_code := v_code || substr(alphabet, (get_byte(v_bytes, j) % 32) + 1, 1);
                if j in (3, 7, 11) then v_code := v_code || '-'; end if;
            end loop;
            begin
                insert into public.activation_codes (code, days, batch, created_by) values (v_code, p_days, p_batch, auth.uid());
                exit;
            exception when unique_violation then
            end;
        end loop;
        return next v_code;
    end loop;
    insert into public.audit_log (actor, action, detail)
        values (auth.uid(), 'generate_codes', jsonb_build_object('days', p_days, 'count', p_count, 'batch', p_batch));
end $$;

create or replace function public.admin_set_status(p_driver uuid, p_status public.driver_status, p_note text default null)
returns void
language plpgsql security definer set search_path = public as $$
declare
    v_trial integer := coalesce(public.config_int('trial_days'), 0);
begin
    perform public.require_admin();
    update public.drivers
        set status = p_status,
            note = coalesce(p_note, note),
            trial_until = case
                when p_status = 'active' and trial_until is null and v_trial > 0 then now() + make_interval(days => v_trial)
                else trial_until end
        where id = p_driver;
    if not found then raise exception 'no_driver' using errcode = '22023'; end if;
    insert into public.audit_log (actor, action, target, detail)
        values (auth.uid(), 'set_status', p_driver::text, jsonb_build_object('status', p_status, 'note', p_note));
end $$;

create or replace function public.admin_grant_days(p_driver uuid, p_days integer) returns void
language plpgsql security definer set search_path = public as $$
begin
    perform public.require_admin();
    if p_days not between -3660 and 3660 or p_days = 0 then raise exception 'bad_days' using errcode = '22023'; end if;
    update public.drivers
        set pro_until = greatest(now(), coalesce(pro_until, now())) + make_interval(days => p_days)
        where id = p_driver;
    if not found then raise exception 'no_driver' using errcode = '22023'; end if;
    insert into public.audit_log (actor, action, target, detail)
        values (auth.uid(), 'grant_days', p_driver::text, jsonb_build_object('days', p_days));
end $$;

create or replace function public.admin_reset_device(p_driver uuid) returns void
language plpgsql security definer set search_path = public as $$
begin
    perform public.require_admin();
    update public.drivers set device_id = null, device_bound_at = null where id = p_driver;
    if not found then raise exception 'no_driver' using errcode = '22023'; end if;
    insert into public.audit_log (actor, action, target) values (auth.uid(), 'reset_device', p_driver::text);
end $$;

create or replace function public.admin_revoke_code(p_code text) returns void
language plpgsql security definer set search_path = public as $$
begin
    perform public.require_admin();
    update public.activation_codes set revoked = true where code = p_code and redeemed_by is null;
    if not found then raise exception 'code_not_revocable' using errcode = '22023'; end if;
    insert into public.audit_log (actor, action, target) values (auth.uid(), 'revoke_code', p_code);
end $$;

create or replace function public.admin_set_config(p_key text, p_value jsonb) returns void
language plpgsql security definer set search_path = public as $$
begin
    perform public.require_admin();
    if p_key not in ('trial_days', 'offline_grace_hours', 'min_version_code', 'announcement', 'redeem_failures_per_hour') then
        raise exception 'unknown_key' using errcode = '22023';
    end if;
    if p_key <> 'announcement' and (jsonb_typeof(p_value) <> 'number' or (p_value #>> '{}')::numeric < 0) then
        raise exception 'bad_value' using errcode = '22023';
    end if;
    insert into public.app_config (key, value, updated_at) values (p_key, p_value, now())
        on conflict (key) do update set value = excluded.value, updated_at = now();
    insert into public.audit_log (actor, action, target, detail) values (auth.uid(), 'set_config', p_key, p_value);
end $$;

create or replace function public.admin_upsert_profile(p_body jsonb, p_enabled boolean default true) returns void
language plpgsql security definer set search_path = public as $$
declare
    v_id      text := p_body ->> 'id';
    v_version integer := (p_body ->> 'version')::integer;
    v_current integer;
begin
    perform public.require_admin();
    if v_id is null or v_version is null or jsonb_typeof(p_body -> 'packages') <> 'array'
       or jsonb_typeof(p_body -> 'acceptLabels') <> 'array' or jsonb_typeof(p_body -> 'currencyTokens') <> 'array' then
        raise exception 'bad_profile' using errcode = '22023';
    end if;
    select version into v_current from public.ride_profiles where id = v_id;
    if v_current is not null and v_version <= v_current then
        raise exception 'version_must_increase' using errcode = '22023';
    end if;
    insert into public.ride_profiles (id, body, version, enabled, updated_at) values (v_id, p_body, v_version, p_enabled, now())
        on conflict (id) do update set body = excluded.body, version = excluded.version, enabled = excluded.enabled, updated_at = now();
    insert into public.audit_log (actor, action, target, detail) values (auth.uid(), 'upsert_profile', v_id, jsonb_build_object('version', v_version));
end $$;

revoke execute on all functions in schema public from public, anon;
grant execute on function
    public.is_admin(), public.register_driver(text, text), public.license_status(text, integer), public.redeem_code(text, text),
    public.admin_generate_codes(integer, integer, text), public.admin_set_status(uuid, public.driver_status, text),
    public.admin_grant_days(uuid, integer), public.admin_reset_device(uuid), public.admin_revoke_code(text),
    public.admin_set_config(text, jsonb), public.admin_upsert_profile(jsonb, boolean)
    to authenticated;
