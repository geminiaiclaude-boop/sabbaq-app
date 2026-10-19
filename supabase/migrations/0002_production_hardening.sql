-- Additive production hardening.  Keep 0001 immutable because it may already be
-- applied to customer projects.
--
-- The public API remains the same; this migration only makes the trust boundary,
-- input limits, and licensing invariants explicit.

-- ---------------------------------------------------------------- privileges
-- Start from no privileges and grant only the browser/mobile API surface below.
revoke create on schema public from public, anon, authenticated;
grant usage on schema public to authenticated;

revoke all on all tables in schema public from public, anon, authenticated;
grant select on public.drivers, public.app_config, public.ride_profiles,
    public.activation_codes, public.calibration_captures, public.audit_log to authenticated;
grant insert on public.calibration_captures to authenticated;

revoke all on all sequences in schema public from public, anon, authenticated;
grant usage on sequence public.calibration_captures_id_seq to authenticated;

-- Existing helper functions are intentionally not part of the client API.  The
-- default-privilege rule prevents a later migration (run by this role) from
-- accidentally publishing a newly created function to PUBLIC.
revoke execute on all functions in schema public from public, anon;
revoke execute on function public.config_int(text), public.require_admin() from authenticated;
-- PostgreSQL's built-in PUBLIC EXECUTE default is global.  A schema-scoped
-- REVOKE would only subtract schema additions and leaves that global default.
alter default privileges revoke execute on functions from public, anon;

-- ---------------------------------------------------------------- helpers
create or replace function public.is_admin() returns boolean
language sql stable security definer set search_path = pg_catalog, public as $$
    select exists (select 1 from public.admins where user_id = auth.uid());
$$;

-- Configuration is operator-controlled, but a malformed historic value must not
-- turn every licence check into a database error.  The fallback values match the
-- initial configuration in 0001.
create or replace function public.config_int(p_key text) returns integer
language sql stable security definer set search_path = pg_catalog, public as $$
    select coalesce(
        (
            select case
                when jsonb_typeof(c.value) = 'number'
                 and (c.value #>> '{}') ~ '^(0|[1-9][0-9]{0,9})$'
                 and (c.value #>> '{}')::numeric <= 2147483647
                then (c.value #>> '{}')::integer
            end
            from public.app_config c
            where c.key = p_key
        ),
        case p_key
            when 'trial_days' then 0
            when 'offline_grace_hours' then 24
            when 'min_version_code' then 1
            when 'redeem_failures_per_hour' then 10
        end
    );
$$;

create or replace function public.require_admin() returns void
language plpgsql stable security definer set search_path = pg_catalog, public as $$
begin
    if not public.is_admin() then
        raise exception 'not_admin' using errcode = '42501';
    end if;
end $$;

-- ---------------------------------------------------------------- RLS
-- Captures can be large.  Restrict direct ingestion to a currently licensed
-- driver rather than every registered (including banned/pending) account.
drop policy if exists captures_insert_own on public.calibration_captures;
create policy captures_insert_licensed_driver on public.calibration_captures
    for insert to authenticated
    with check (
        driver_id = auth.uid()
        and exists (
            select 1
            from public.drivers d
            where d.id = auth.uid()
              and d.status = 'active'
              and greatest(
                    coalesce(d.trial_until, '-infinity'::timestamptz),
                    coalesce(d.pro_until, '-infinity'::timestamptz)
                  ) > now()
        )
    );

-- ---------------------------------------------------------------- driver API
create or replace function public.register_driver(p_full_name text, p_phone text) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare
    v_uid      uuid := auth.uid();
    v_name     text;
    v_phone    text;
    v_inserted uuid;
begin
    if v_uid is null then
        raise exception 'not_authenticated' using errcode = '42501';
    end if;
    if p_full_name is null or octet_length(p_full_name) > 320 then
        raise exception 'bad_full_name' using errcode = '22023';
    end if;
    v_name := btrim(p_full_name);
    if char_length(v_name) not between 2 and 80 then
        raise exception 'bad_full_name' using errcode = '22023';
    end if;
    if p_phone is null or octet_length(p_phone) > 64 then
        raise exception 'bad_phone' using errcode = '22023';
    end if;
    v_phone := regexp_replace(p_phone, '[[:space:]-]', '', 'g');
    if v_phone !~ '^[+]?[0-9]{7,15}$' then
        raise exception 'bad_phone' using errcode = '22023';
    end if;

    insert into public.drivers (id, full_name, phone)
    values (v_uid, v_name, v_phone)
    on conflict (id) do nothing
    returning id into v_inserted;

    -- Idempotent registration must not become an unbounded audit-log write.
    if v_inserted is not null then
        insert into public.audit_log (actor, action, target)
        values (v_uid, 'register', v_uid::text);
    end if;
    return jsonb_build_object('ok', true, 'created', v_inserted is not null);
end $$;

create or replace function public.license_status(p_device_id text, p_version_code integer default null) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare
    v_uid    uuid := auth.uid();
    v_device text;
    v_d      public.drivers%rowtype;
    v_until  timestamptz;
    v_state  text;
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
    if p_version_code is not null and p_version_code < 1 then
        raise exception 'bad_version_code' using errcode = '22023';
    end if;

    select * into v_d from public.drivers where id = v_uid for update;
    if not found then
        v_state := 'unregistered';
    elsif v_d.status in ('pending', 'rejected', 'banned') then
        v_state := v_d.status::text;
    else
        if v_d.device_id is null then
            update public.drivers
            set device_id = v_device, device_bound_at = now()
            where id = v_uid;
            insert into public.audit_log (actor, action, target)
            values (v_uid, 'device_bound', v_uid::text);
            v_d.device_id := v_device;
        end if;
        v_until := greatest(coalesce(v_d.trial_until, '-infinity'), coalesce(v_d.pro_until, '-infinity'));
        if v_d.device_id <> v_device then
            v_state := 'device_mismatch';
        elsif v_until > now() then
            v_state := case when coalesce(v_d.pro_until, '-infinity') > now() then 'active' else 'trial' end;
        else
            v_state := 'expired';
        end if;
    end if;

    if v_d.id is not null then
        update public.drivers
        set last_seen_at = now(), app_version = coalesce(p_version_code, app_version)
        where id = v_uid;
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
    if not found or v_c.revoked or v_c.redeemed_by is not null then
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

-- ---------------------------------------------------------------- admin API
create or replace function public.admin_generate_codes(p_days integer, p_count integer, p_batch text default null)
returns setof text
language plpgsql security definer set search_path = pg_catalog, public, extensions as $$
declare
    alphabet constant text := '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
    v_bytes bytea;
    v_code  text;
    v_batch text;
    i       integer;
    j       integer;
begin
    perform public.require_admin();
    if p_days is null or p_days not between 1 and 3660 then
        raise exception 'bad_days' using errcode = '22023';
    end if;
    if p_count is null or p_count not between 1 and 500 then
        raise exception 'bad_count' using errcode = '22023';
    end if;
    if p_batch is not null and octet_length(p_batch) > 400 then
        raise exception 'bad_batch' using errcode = '22023';
    end if;
    v_batch := nullif(btrim(p_batch), '');
    if char_length(v_batch) > 100 then
        raise exception 'bad_batch' using errcode = '22023';
    end if;

    for i in 1..p_count loop
        loop
            v_bytes := extensions.gen_random_bytes(16);
            v_code := '';
            for j in 0..15 loop
                v_code := v_code || substr(alphabet, (get_byte(v_bytes, j) % 32) + 1, 1);
                if j in (3, 7, 11) then v_code := v_code || '-'; end if;
            end loop;
            begin
                insert into public.activation_codes (code, days, batch, created_by)
                values (v_code, p_days, v_batch, auth.uid());
                exit;
            exception when unique_violation then
            end;
        end loop;
        return next v_code;
    end loop;
    insert into public.audit_log (actor, action, detail)
    values (auth.uid(), 'generate_codes', jsonb_build_object('days', p_days, 'count', p_count, 'batch', v_batch));
end $$;

create or replace function public.admin_set_status(p_driver uuid, p_status public.driver_status, p_note text default null)
returns void
language plpgsql security definer set search_path = pg_catalog, public as $$
declare
    v_trial integer := coalesce(public.config_int('trial_days'), 0);
    v_note  text;
begin
    perform public.require_admin();
    if p_status is null then
        raise exception 'bad_status' using errcode = '22023';
    end if;
    if p_note is not null and octet_length(p_note) > 2000 then
        raise exception 'bad_note' using errcode = '22023';
    end if;
    v_note := nullif(btrim(p_note), '');
    if char_length(v_note) > 500 then
        raise exception 'bad_note' using errcode = '22023';
    end if;
    update public.drivers
    set status = p_status,
        note = coalesce(v_note, note),
        trial_until = case
            when p_status = 'active' and trial_until is null and v_trial > 0 then now() + make_interval(days => v_trial)
            else trial_until
        end
    where id = p_driver;
    if not found then
        raise exception 'no_driver' using errcode = '22023';
    end if;
    insert into public.audit_log (actor, action, target, detail)
    values (auth.uid(), 'set_status', p_driver::text, jsonb_build_object('status', p_status, 'note', v_note));
end $$;

create or replace function public.admin_grant_days(p_driver uuid, p_days integer) returns void
language plpgsql security definer set search_path = pg_catalog, public as $$
begin
    perform public.require_admin();
    if p_days is null or p_days not between -3660 and 3660 or p_days = 0 then
        raise exception 'bad_days' using errcode = '22023';
    end if;
    update public.drivers
    set pro_until = greatest(now(), coalesce(pro_until, now())) + make_interval(days => p_days)
    where id = p_driver;
    if not found then
        raise exception 'no_driver' using errcode = '22023';
    end if;
    insert into public.audit_log (actor, action, target, detail)
    values (auth.uid(), 'grant_days', p_driver::text, jsonb_build_object('days', p_days));
end $$;

create or replace function public.admin_reset_device(p_driver uuid) returns void
language plpgsql security definer set search_path = pg_catalog, public as $$
begin
    perform public.require_admin();
    update public.drivers set device_id = null, device_bound_at = null where id = p_driver;
    if not found then
        raise exception 'no_driver' using errcode = '22023';
    end if;
    insert into public.audit_log (actor, action, target)
    values (auth.uid(), 'reset_device', p_driver::text);
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
    update public.activation_codes set revoked = true where code = v_code and redeemed_by is null;
    if not found then
        raise exception 'code_not_revocable' using errcode = '22023';
    end if;
    insert into public.audit_log (actor, action, target) values (auth.uid(), 'revoke_code', v_code);
end $$;

create or replace function public.admin_set_config(p_key text, p_value jsonb) returns void
language plpgsql security definer set search_path = pg_catalog, public as $$
declare
    v_number numeric;
begin
    perform public.require_admin();
    if p_key is null or p_key not in ('trial_days', 'offline_grace_hours', 'min_version_code', 'announcement', 'redeem_failures_per_hour') then
        raise exception 'unknown_key' using errcode = '22023';
    end if;

    if p_key = 'announcement' then
        if p_value is null or jsonb_typeof(p_value) <> 'string'
           or octet_length(p_value #>> '{}') > 4000
           or char_length(p_value #>> '{}') > 1000 then
            raise exception 'bad_value' using errcode = '22023';
        end if;
    else
        if p_value is null or jsonb_typeof(p_value) <> 'number'
           or (p_value #>> '{}') !~ '^(0|[1-9][0-9]{0,9})$' then
            raise exception 'bad_value' using errcode = '22023';
        end if;
        v_number := (p_value #>> '{}')::numeric;
        if (p_key = 'trial_days' and v_number not between 0 and 3660)
           or (p_key = 'offline_grace_hours' and v_number not between 0 and 168)
           or (p_key = 'min_version_code' and v_number not between 1 and 1000000000)
           or (p_key = 'redeem_failures_per_hour' and v_number not between 1 and 100) then
            raise exception 'bad_value' using errcode = '22023';
        end if;
    end if;

    insert into public.app_config (key, value, updated_at) values (p_key, p_value, now())
    on conflict (key) do update set value = excluded.value, updated_at = now();
    insert into public.audit_log (actor, action, target, detail) values (auth.uid(), 'set_config', p_key, p_value);
end $$;

create or replace function public.admin_upsert_profile(p_body jsonb, p_enabled boolean default true) returns void
language plpgsql security definer set search_path = pg_catalog, public as $$
declare
    v_id           text;
    v_version_text text;
    v_version      integer;
    v_current      integer;
begin
    perform public.require_admin();
    if p_body is null or jsonb_typeof(p_body) <> 'object'
       or octet_length(p_body::text) > 600000
       or coalesce(jsonb_typeof(p_body -> 'id'), '') <> 'string'
       or coalesce(jsonb_typeof(p_body -> 'version'), '') <> 'number'
       or coalesce(jsonb_typeof(p_body -> 'packages'), '') <> 'array'
       or coalesce(jsonb_typeof(p_body -> 'acceptLabels'), '') <> 'array'
       or coalesce(jsonb_typeof(p_body -> 'currencyTokens'), '') <> 'array'
       or p_enabled is null then
        raise exception 'bad_profile' using errcode = '22023';
    end if;

    v_id := p_body ->> 'id';
    v_version_text := p_body ->> 'version';
    if char_length(v_id) not between 2 and 32 or v_id !~ '^[a-z0-9_]{2,32}$'
       or v_version_text !~ '^[1-9][0-9]{0,9}$'
       or v_version_text::numeric > 2147483647 then
        raise exception 'bad_profile' using errcode = '22023';
    end if;
    v_version := v_version_text::integer;

    -- Serialise writers for this profile, including the first insert, so two
    -- administrators cannot publish different bodies at the same version.
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(v_id, 0));
    select version into v_current from public.ride_profiles where id = v_id for update;
    if v_current is not null and v_version <= v_current then
        raise exception 'version_must_increase' using errcode = '22023';
    end if;

    insert into public.ride_profiles (id, body, version, enabled, updated_at)
    values (v_id, p_body, v_version, p_enabled, now())
    on conflict (id) do update
    set body = excluded.body, version = excluded.version, enabled = excluded.enabled, updated_at = now();
    insert into public.audit_log (actor, action, target, detail)
    values (auth.uid(), 'upsert_profile', v_id, jsonb_build_object('version', v_version));
end $$;

-- Reassert only the intended remote-callable functions after replacing them.
revoke execute on function
    public.is_admin(), public.register_driver(text, text), public.license_status(text, integer), public.redeem_code(text, text),
    public.admin_generate_codes(integer, integer, text), public.admin_set_status(uuid, public.driver_status, text),
    public.admin_grant_days(uuid, integer), public.admin_reset_device(uuid), public.admin_revoke_code(text),
    public.admin_set_config(text, jsonb), public.admin_upsert_profile(jsonb, boolean)
    from public, anon;
grant execute on function
    public.is_admin(), public.register_driver(text, text), public.license_status(text, integer), public.redeem_code(text, text),
    public.admin_generate_codes(integer, integer, text), public.admin_set_status(uuid, public.driver_status, text),
    public.admin_grant_days(uuid, integer), public.admin_reset_device(uuid), public.admin_revoke_code(text),
    public.admin_set_config(text, jsonb), public.admin_upsert_profile(jsonb, boolean)
    to authenticated;
