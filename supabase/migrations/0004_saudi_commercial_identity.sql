-- Saudi commercial and identity contract. This is additive: legacy driver rows,
-- activation-code subscriptions, and already-issued pro_until values remain valid.
-- It deliberately does not apply anything to a live Supabase project.

-- ---------------------------------------------------------------- identity data
-- New Google profiles can have no phone number. Existing rows (including historic
-- non-Saudi values) are retained rather than rewritten by this migration.
alter table public.drivers alter column full_name drop not null;
alter table public.drivers alter column phone drop not null;

-- 0003 created a profile from user-editable metadata. A profile is now created
-- only through ensure_driver_profile after server-side auth.users validation.
drop trigger if exists create_driver_from_signup_metadata on auth.users;
drop function if exists public.create_driver_from_signup_metadata();

-- Block a newly supplied password hash. Existing password users and their paid
-- subscriptions are not deleted or downgraded; an existing non-empty hash may be
-- rotated. GoTrue normally writes the hash on INSERT, but the UPDATE arm closes a
-- create-then-set-password path as well.
create or replace function public.reject_new_password_signup() returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
    if coalesce(nullif(new.encrypted_password, ''), '') <> ''
       and (tg_op = 'INSERT' or coalesce(nullif(old.encrypted_password, ''), '') = '') then
        raise exception 'password_signup_disabled' using errcode = '22023';
    end if;
    return new;
end;
$$;

revoke all on function public.reject_new_password_signup() from public, anon, authenticated;
drop trigger if exists reject_new_password_signup on auth.users;
create trigger reject_new_password_signup
before insert or update of encrypted_password on auth.users
for each row execute function public.reject_new_password_signup();

-- This predicate deliberately reads only server-controlled app metadata for the
-- OAuth provider. raw_user_meta_data is not used for provider/role/trial checks.
create or replace function public.is_supported_customer_identity(p_user_id uuid) returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public
as $$
    select exists (
        select 1
        from auth.users u
        where u.id = p_user_id
          and (
              coalesce(u.raw_app_meta_data ->> 'provider', '') = 'google'
              or coalesce(u.raw_app_meta_data -> 'providers', '[]'::jsonb) ? 'google'
              or (
                  u.phone_confirmed_at is not null
                  and u.phone ~ '^[+]?9665[0-9]{8}$'
              )
          )
    );
$$;

revoke all on function public.is_supported_customer_identity(uuid) from public, anon, authenticated;

-- ---------------------------------------------------------------- commercial policy
create table if not exists public.commercial_policy (
    singleton            boolean primary key default true check (singleton),
    trial_enabled        boolean not null default true,
    trial_days           integer not null default 3 check (trial_days between 1 and 365),
    price_minor          integer not null default 1875 check (price_minor between 1 and 100000000),
    currency             text not null default 'SAR' check (currency = 'SAR'),
    subscription_days    integer not null default 30 check (subscription_days between 1 and 365),
    purchase_contact_url text check (
        purchase_contact_url is null or (
            octet_length(purchase_contact_url) <= 2048
            and purchase_contact_url ~ '^https://[A-Za-z0-9][A-Za-z0-9.-]*([/?#][^[:space:]]*)?$'
        )
    ),
    updated_at           timestamptz not null default now()
);

insert into public.commercial_policy (
    singleton, trial_enabled, trial_days, price_minor, currency, subscription_days, purchase_contact_url
) values (true, true, 3, 1875, 'SAR', 30, null)
on conflict (singleton) do nothing;

create table if not exists public.trial_claims (
    account_id        uuid primary key references auth.users (id) on delete cascade,
    reported_device_id text,
    policy_trial_days integer check (policy_trial_days between 1 and 365),
    claimed_at        timestamptz not null default now(),
    trial_until       timestamptz,
    source            text not null default 'start_trial' check (source in ('start_trial', 'legacy_seed'))
);

-- Device IDs are client-provided and not attested. This ledger only provides a
-- best-effort cross-account duplicate check; the account ledger is authoritative.
create table if not exists public.trial_device_claims (
    reported_device_id text primary key check (octet_length(reported_device_id) between 16 and 256),
    account_id         uuid not null references auth.users (id) on delete cascade,
    claimed_at         timestamptz not null default now()
);

-- Do not accidentally make historic trials eligible again merely because their
-- pre-0004 grant had no ledger row. Dates are preserved exactly as they were.
insert into public.trial_claims (
    account_id, reported_device_id, policy_trial_days, claimed_at, trial_until, source
)
select d.id, d.device_id, null, d.created_at, d.trial_until, 'legacy_seed'
from public.drivers d
where d.trial_until is not null
on conflict (account_id) do nothing;

insert into public.trial_device_claims (reported_device_id, account_id, claimed_at)
select d.device_id, d.id, d.created_at
from public.drivers d
where d.trial_until is not null
  and d.device_id is not null
  and octet_length(d.device_id) between 16 and 256
on conflict (reported_device_id) do nothing;

alter table public.commercial_policy enable row level security;
alter table public.trial_claims enable row level security;
alter table public.trial_device_claims enable row level security;
revoke all on public.commercial_policy, public.trial_claims, public.trial_device_claims
    from public, anon, authenticated;

-- Kept private: RLS uses it to stop a trial immediately after the global switch is
-- turned off, while paid pro_until is evaluated independently.
create or replace function public.current_trial_enabled() returns boolean
language sql
stable
security definer
set search_path = pg_catalog, public
as $$
    select coalesce((select p.trial_enabled from public.commercial_policy p where p.singleton), false);
$$;

revoke all on function public.current_trial_enabled() from public, anon, authenticated;
grant execute on function public.current_trial_enabled() to authenticated;

-- A capture needs a paid entitlement or a globally enabled, unexpired trial. It
-- must never be opened by a pending profile alone, a disabled trial, or stale
-- trial_until while a paid period remains unaffected.
drop policy if exists captures_insert_licensed_driver on public.calibration_captures;
create policy captures_insert_paid_or_enabled_trial on public.calibration_captures
    for insert to authenticated
    with check (
        driver_id = auth.uid()
        and exists (
            select 1
            from public.drivers d
            where d.id = auth.uid()
              and d.status = 'active'
              and (
                  d.pro_until > now()
                  or (d.trial_until > now() and public.current_trial_enabled())
              )
        )
    );

-- ---------------------------------------------------------------- profile / offer API
create or replace function public.ensure_driver_profile(p_display_name text default null) returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    v_uid          uuid := auth.uid();
    v_phone        text;
    v_phone_at     timestamptz;
    v_app_metadata jsonb;
    v_user_metadata jsonb;
    v_google       boolean;
    v_saudi_phone  boolean;
    v_name         text;
    v_profile_phone text;
    v_inserted     uuid;
begin
    if v_uid is null then
        raise exception 'not_authenticated' using errcode = '42501';
    end if;

    select u.phone, u.phone_confirmed_at, u.raw_app_meta_data, u.raw_user_meta_data
    into v_phone, v_phone_at, v_app_metadata, v_user_metadata
    from auth.users u
    where u.id = v_uid;
    if not found then
        raise exception 'auth_user_missing' using errcode = '42501';
    end if;

    v_google := coalesce(v_app_metadata ->> 'provider', '') = 'google'
                or coalesce(v_app_metadata -> 'providers', '[]'::jsonb) ? 'google';
    v_saudi_phone := coalesce(v_phone_at is not null and v_phone ~ '^[+]?9665[0-9]{8}$', false);
    if not (v_google or v_saudi_phone) then
        raise exception 'unsupported_or_unverified_identity' using errcode = '42501';
    end if;

    -- User metadata may supply a personal display name only. It never influences
    -- provider, role, status, price, or trial authorization.
    v_name := coalesce(
        p_display_name,
        v_user_metadata ->> 'full_name',
        v_user_metadata ->> 'name',
        v_user_metadata ->> 'given_name'
    );
    if v_name is not null then
        if octet_length(v_name) > 320 then
            raise exception 'bad_display_name' using errcode = '22023';
        end if;
        v_name := nullif(btrim(v_name), '');
        if v_name is not null and char_length(v_name) not between 2 and 80 then
            raise exception 'bad_display_name' using errcode = '22023';
        end if;
    end if;

    -- Never accept a client p_phone. Google-only profiles stay NULL; a phone is
    -- copied only from a confirmed Saudi auth.users.phone value.
    v_profile_phone := case when v_saudi_phone then '+' || ltrim(v_phone, '+') else null end;

    insert into public.drivers (id, full_name, phone)
    values (v_uid, v_name, v_profile_phone)
    on conflict (id) do nothing
    returning id into v_inserted;

    if v_inserted is null then
        -- Existing legacy data is retained. Only an omitted name/phone can be
        -- filled from the verified identity; no client-provided phone is written.
        update public.drivers
        set full_name = coalesce(full_name, v_name),
            phone = coalesce(phone, v_profile_phone)
        where id = v_uid;
    else
        insert into public.audit_log (actor, action, target, detail)
        values (v_uid, 'ensure_driver_profile', v_uid::text,
                jsonb_build_object('identity', case when v_google then 'google' else 'saudi_phone' end));
    end if;

    return jsonb_build_object('ok', true, 'created', v_inserted is not null);
end;
$$;

-- Retain the old callable signature for deployed clients but stop trusting its
-- p_phone input. New profiles follow the verified auth.users path above.
create or replace function public.register_driver(p_full_name text, p_phone text) returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
    return public.ensure_driver_profile(p_full_name);
end;
$$;

create or replace function public.public_offer() returns jsonb
language sql
stable
security definer
set search_path = pg_catalog, public
as $$
    select jsonb_build_object(
        'trial_enabled', p.trial_enabled,
        'trial_days', p.trial_days,
        'price_minor', p.price_minor,
        'currency', p.currency,
        'subscription_days', p.subscription_days,
        'purchase_contact_url', p.purchase_contact_url
    )
    from public.commercial_policy p
    where p.singleton;
$$;

-- ---------------------------------------------------------------- trial / license API
create or replace function public.start_trial(p_device_id text, p_version_code integer) returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    v_uid             uuid := auth.uid();
    v_device          text;
    v_d               public.drivers%rowtype;
    v_policy          public.commercial_policy%rowtype;
    v_claim           public.trial_claims%rowtype;
    v_device_inserted text;
    v_device_owner    uuid;
    v_now             timestamptz := now();
    v_until           timestamptz;
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
    if p_version_code is null or p_version_code < 1 then
        raise exception 'bad_version_code' using errcode = '22023';
    end if;
    if not public.is_supported_customer_identity(v_uid) then
        raise exception 'unsupported_or_unverified_identity' using errcode = '42501';
    end if;

    -- FOR SHARE serialises the policy snapshot with an administrative UPDATE.
    select * into v_policy from public.commercial_policy where singleton for share;
    if not found then
        raise exception 'commercial_policy_missing' using errcode = '55000';
    end if;
    if not v_policy.trial_enabled then
        return jsonb_build_object('ok', false, 'created', false, 'error', 'trial_disabled');
    end if;

    -- The account-row lock makes repeated/concurrent claims idempotent.
    select * into v_d from public.drivers where id = v_uid for update;
    if not found then
        return jsonb_build_object('ok', false, 'created', false, 'error', 'unregistered');
    end if;
    if v_d.status in ('rejected', 'banned') then
        return jsonb_build_object('ok', false, 'created', false, 'error', v_d.status::text);
    end if;
    if v_d.device_id is not null and v_d.device_id <> v_device then
        return jsonb_build_object('ok', false, 'created', false, 'error', 'device_mismatch');
    end if;
    if coalesce(v_d.pro_until, '-infinity'::timestamptz) > v_now then
        return jsonb_build_object('ok', false, 'created', false, 'error', 'paid_license_active');
    end if;

    select * into v_claim from public.trial_claims where account_id = v_uid;
    if found then
        -- A retry is an idempotent acknowledgement, never a renewed trial. The
        -- client must call license_status because this acknowledgement is no lease.
        return jsonb_build_object(
            'ok', true,
            'created', false,
            'trial_until', v_claim.trial_until
        );
    end if;

    insert into public.trial_device_claims (reported_device_id, account_id)
    values (v_device, v_uid)
    on conflict (reported_device_id) do nothing
    returning reported_device_id into v_device_inserted;
    if v_device_inserted is null then
        select account_id into v_device_owner
        from public.trial_device_claims
        where reported_device_id = v_device;
        return jsonb_build_object('ok', false, 'created', false,
                                  'error', 'device_trial_already_claimed');
    end if;

    v_until := v_now + make_interval(days => v_policy.trial_days);
    insert into public.trial_claims (
        account_id, reported_device_id, policy_trial_days, claimed_at, trial_until, source
    ) values (v_uid, v_device, v_policy.trial_days, v_now, v_until, 'start_trial');

    update public.drivers
    set trial_until = v_until,
        status = 'active',
        device_id = coalesce(device_id, v_device),
        device_bound_at = coalesce(device_bound_at, v_now),
        app_version = p_version_code,
        last_seen_at = v_now
    where id = v_uid;
    if v_d.device_id is null then
        insert into public.audit_log (actor, action, target)
        values (v_uid, 'device_bound', v_uid::text);
    end if;
    insert into public.audit_log (actor, action, target, detail)
    values (v_uid, 'start_trial', v_uid::text,
            jsonb_build_object('days', v_policy.trial_days, 'trial_until', v_until));

    return jsonb_build_object('ok', true, 'created', true, 'trial_until', v_until);
end;
$$;

create or replace function public.license_status(p_device_id text, p_version_code integer default null) returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    v_uid            uuid := auth.uid();
    v_device         text;
    v_d              public.drivers%rowtype;
    v_policy         public.commercial_policy%rowtype;
    v_state          text;
    v_valid_until    timestamptz;
    v_paid_active    boolean := false;
    v_trial_active   boolean := false;
    v_trial_eligible boolean := false;
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

    select * into v_policy from public.commercial_policy where singleton;
    if not found then
        raise exception 'commercial_policy_missing' using errcode = '55000';
    end if;
    select * into v_d from public.drivers where id = v_uid for update;
    if not found then
        v_state := 'unregistered';
    elsif v_d.status in ('rejected', 'banned') then
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

        if v_d.device_id <> v_device then
            v_state := 'device_mismatch';
        else
            v_paid_active := coalesce(v_d.pro_until, '-infinity'::timestamptz) > now();
            v_trial_active := v_policy.trial_enabled
                              and coalesce(v_d.trial_until, '-infinity'::timestamptz) > now();
            if v_d.status = 'pending' then
                v_state := 'pending';
            elsif v_paid_active then
                v_state := 'active';
                v_valid_until := v_d.pro_until;
            elsif v_trial_active then
                v_state := 'trial';
                v_valid_until := v_d.trial_until;
            else
                v_state := 'expired';
            end if;
        end if;

        v_trial_eligible := v_d.status not in ('rejected', 'banned')
                            and v_d.device_id = v_device
                            and not v_paid_active
                            and public.is_supported_customer_identity(v_uid)
                            and not exists (
                                select 1 from public.trial_claims c where c.account_id = v_uid
                            )
                            and not exists (
                                select 1 from public.trial_device_claims c
                                where c.reported_device_id = v_device and c.account_id <> v_uid
                            );
    end if;

    if v_d.id is not null then
        update public.drivers
        set last_seen_at = now(), app_version = coalesce(p_version_code, app_version)
        where id = v_uid;
    end if;

    return jsonb_build_object(
        'state', v_state,
        -- valid_until is a safe lease boundary, not merely the stored trial date.
        'valid_until', v_valid_until,
        'server_time', now(),
        'grace_hours', public.config_int('offline_grace_hours'),
        'min_version_code', public.config_int('min_version_code'),
        'full_name', v_d.full_name,
        'announcement', (select value #>> '{}' from public.app_config where key = 'announcement'),
        'trial_until', v_d.trial_until,
        'pro_until', v_d.pro_until,
        'trial_eligible', v_trial_eligible,
        'trial_enabled', v_policy.trial_enabled,
        'trial_days', v_policy.trial_days
    );
end;
$$;

-- ---------------------------------------------------------------- admin policy API
create or replace function public.admin_commercial_policy() returns jsonb
language plpgsql
stable
security definer
set search_path = pg_catalog, public
as $$
declare
    v_policy public.commercial_policy%rowtype;
begin
    perform public.require_admin();
    select * into v_policy from public.commercial_policy where singleton;
    if not found then
        raise exception 'commercial_policy_missing' using errcode = '55000';
    end if;
    return jsonb_build_object(
        'trial_enabled', v_policy.trial_enabled,
        'trial_days', v_policy.trial_days,
        'price_minor', v_policy.price_minor,
        'currency', v_policy.currency,
        'subscription_days', v_policy.subscription_days,
        'purchase_contact_url', v_policy.purchase_contact_url,
        'updated_at', v_policy.updated_at
    );
end;
$$;

create or replace function public.admin_set_commercial_policy(
    p_trial_enabled boolean,
    p_trial_days integer,
    p_price_minor integer,
    p_subscription_days integer,
    p_purchase_contact_url text default null
) returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    v_contact text;
    v_policy  public.commercial_policy%rowtype;
begin
    perform public.require_admin();
    if p_trial_enabled is null
       or p_trial_days is null or p_trial_days not between 1 and 365
       or p_price_minor is null or p_price_minor not between 1 and 100000000
       or p_subscription_days is null or p_subscription_days not between 1 and 365 then
        raise exception 'bad_commercial_policy' using errcode = '22023';
    end if;
    if p_purchase_contact_url is not null and octet_length(p_purchase_contact_url) > 2048 then
        raise exception 'bad_purchase_contact_url' using errcode = '22023';
    end if;
    v_contact := nullif(btrim(p_purchase_contact_url), '');
    if v_contact is not null and v_contact !~ '^https://[A-Za-z0-9][A-Za-z0-9.-]*([/?#][^[:space:]]*)?$' then
        raise exception 'bad_purchase_contact_url' using errcode = '22023';
    end if;

    -- One UPSERT changes the entire singleton policy; no partial set_config state
    -- can be observed by a caller.
    insert into public.commercial_policy (
        singleton, trial_enabled, trial_days, price_minor, currency, subscription_days, purchase_contact_url, updated_at
    ) values (true, p_trial_enabled, p_trial_days, p_price_minor, 'SAR', p_subscription_days, v_contact, now())
    on conflict (singleton) do update
    set trial_enabled = excluded.trial_enabled,
        trial_days = excluded.trial_days,
        price_minor = excluded.price_minor,
        currency = excluded.currency,
        subscription_days = excluded.subscription_days,
        purchase_contact_url = excluded.purchase_contact_url,
        updated_at = excluded.updated_at
    returning * into v_policy;

    insert into public.audit_log (actor, action, target, detail)
    values (
        auth.uid(),
        'set_commercial_policy',
        'singleton',
        jsonb_build_object(
            'trial_enabled', v_policy.trial_enabled,
            'trial_days', v_policy.trial_days,
            'price_minor', v_policy.price_minor,
            'currency', v_policy.currency,
            'subscription_days', v_policy.subscription_days,
            'purchase_contact_url', v_policy.purchase_contact_url
        )
    );

    return jsonb_build_object(
        'trial_enabled', v_policy.trial_enabled,
        'trial_days', v_policy.trial_days,
        'price_minor', v_policy.price_minor,
        'currency', v_policy.currency,
        'subscription_days', v_policy.subscription_days,
        'purchase_contact_url', v_policy.purchase_contact_url,
        'updated_at', v_policy.updated_at
    );
end;
$$;

-- Status administration must never mint a trial. A trial is granted only by the
-- user-initiated start_trial path and its append-only account ledger.
create or replace function public.admin_set_status(p_driver uuid, p_status public.driver_status, p_note text default null)
returns void
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    v_note text;
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
        note = coalesce(v_note, note)
    where id = p_driver;
    if not found then
        raise exception 'no_driver' using errcode = '22023';
    end if;
    insert into public.audit_log (actor, action, target, detail)
    values (auth.uid(), 'set_status', p_driver::text, jsonb_build_object('status', p_status, 'note', v_note));
end;
$$;

-- trial_days is intentionally no longer a mutable app_config key: commercial
-- parameters must move together through admin_set_commercial_policy.
create or replace function public.admin_set_config(p_key text, p_value jsonb) returns void
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    v_number numeric;
begin
    perform public.require_admin();
    if p_key = 'trial_days' then
        raise exception 'commercial_policy_required' using errcode = '22023';
    end if;
    if p_key is null or p_key not in ('offline_grace_hours', 'min_version_code', 'announcement', 'redeem_failures_per_hour') then
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
        if (p_key = 'offline_grace_hours' and v_number not between 0 and 168)
           or (p_key = 'min_version_code' and v_number not between 1 and 1000000000)
           or (p_key = 'redeem_failures_per_hour' and v_number not between 1 and 100) then
            raise exception 'bad_value' using errcode = '22023';
        end if;
    end if;

    insert into public.app_config (key, value, updated_at) values (p_key, p_value, now())
    on conflict (key) do update set value = excluded.value, updated_at = now();
    insert into public.audit_log (actor, action, target, detail) values (auth.uid(), 'set_config', p_key, p_value);
end;
$$;

-- ---------------------------------------------------------------- executable surface
revoke all on function
    public.ensure_driver_profile(text),
    public.register_driver(text, text),
    public.public_offer(),
    public.start_trial(text, integer),
    public.license_status(text, integer),
    public.admin_commercial_policy(),
    public.admin_set_commercial_policy(boolean, integer, integer, integer, text),
    public.admin_set_status(uuid, public.driver_status, text),
    public.admin_set_config(text, jsonb)
from public, anon;

grant usage on schema public to anon, authenticated;
grant execute on function public.public_offer() to anon, authenticated;
grant execute on function
    public.ensure_driver_profile(text),
    public.register_driver(text, text),
    public.start_trial(text, integer),
    public.license_status(text, integer),
    public.admin_commercial_policy(),
    public.admin_set_commercial_policy(boolean, integer, integer, integer, text),
    public.admin_set_status(uuid, public.driver_status, text),
    public.admin_set_config(text, jsonb)
to authenticated;
