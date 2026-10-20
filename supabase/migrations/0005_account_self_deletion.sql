-- Self-service account deletion (Google Play account-deletion requirement).
--
-- A driver deletes their own auth account; every row keyed to it cascades
-- (drivers, trial ledgers, redeem failures, calibration captures) and audit rows
-- are de-identified. Deletion must not become a way to mint a second free trial,
-- so an account that already consumed a trial leaves only one-way SHA-256 digests
-- of its verified phone/email and reported device ids. No raw identifier survives.

create table if not exists public.deleted_identity_tombstones (
    identity_hash text primary key check (identity_hash ~ '^[0-9a-f]{64}$'),
    kind          text not null check (kind in ('phone', 'email', 'device')),
    deleted_at    timestamptz not null default now()
);

alter table public.deleted_identity_tombstones enable row level security;
revoke all on public.deleted_identity_tombstones from public, anon, authenticated;

-- Normalisation matches how each identifier is compared: phones by digits only,
-- emails case-insensitively, device ids exactly as the client reports them.
create or replace function public.identity_digest(p_kind text, p_value text) returns text
language sql
immutable
set search_path = pg_catalog
as $$
    select case
        when p_value is null or btrim(p_value) = '' then null
        when p_kind = 'phone' then nullif(
            encode(sha256(convert_to('phone:' || regexp_replace(p_value, '[^0-9]', '', 'g'), 'UTF8')), 'hex'),
            encode(sha256(convert_to('phone:', 'UTF8')), 'hex'))
        when p_kind = 'email' then encode(sha256(convert_to('email:' || lower(btrim(p_value)), 'UTF8')), 'hex')
        when p_kind = 'device' then encode(sha256(convert_to('device:' || btrim(p_value), 'UTF8')), 'hex')
    end;
$$;

revoke all on function public.identity_digest(text, text) from public, anon, authenticated;

alter table public.trial_claims drop constraint if exists trial_claims_source_check;
alter table public.trial_claims add constraint trial_claims_source_check
    check (source in ('start_trial', 'legacy_seed', 'deleted_identity'));

-- A re-registered identity starts with its trial already consumed. trial_until stays
-- null, so license_status reports no trial and no eligibility, and start_trial returns
-- its idempotent acknowledgement without granting anything.
create or replace function public.carry_deleted_identity_trial() returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    v_phone text;
    v_email text;
begin
    select u.phone, u.email into v_phone, v_email from auth.users u where u.id = new.id;
    if exists (
        select 1 from public.deleted_identity_tombstones t
        where t.identity_hash in (
            public.identity_digest('phone', v_phone),
            public.identity_digest('email', v_email)
        )
    ) then
        insert into public.trial_claims (account_id, reported_device_id, policy_trial_days, trial_until, source)
        values (new.id, null, null, null, 'deleted_identity')
        on conflict (account_id) do nothing;
    end if;
    return new;
end;
$$;

revoke all on function public.carry_deleted_identity_trial() from public, anon, authenticated;

drop trigger if exists drivers_carry_deleted_identity_trial on public.drivers;
create trigger drivers_carry_deleted_identity_trial
    after insert on public.drivers
    for each row execute function public.carry_deleted_identity_trial();

-- start_trial inserts the device claim with ON CONFLICT DO NOTHING and treats a
-- skipped insert as "device_trial_already_claimed"; skipping here reuses that path.
create or replace function public.reject_deleted_device_trial() returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
    if exists (
        select 1 from public.deleted_identity_tombstones t
        where t.identity_hash = public.identity_digest('device', new.reported_device_id)
    ) then
        return null;
    end if;
    return new;
end;
$$;

revoke all on function public.reject_deleted_device_trial() from public, anon, authenticated;

drop trigger if exists trial_device_claims_reject_deleted on public.trial_device_claims;
create trigger trial_device_claims_reject_deleted
    before insert on public.trial_device_claims
    for each row execute function public.reject_deleted_device_trial();

create or replace function public.delete_my_account(p_confirmation text) returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    v_uid       uuid := auth.uid();
    v_phone     text;
    v_email     text;
    v_had_trial boolean;
begin
    if v_uid is null then
        raise exception 'not_authenticated' using errcode = '42501';
    end if;
    if p_confirmation is distinct from 'DELETE' then
        return jsonb_build_object('ok', false, 'error', 'confirmation_required');
    end if;
    -- Admin accounts own generated activation codes and the admin role itself;
    -- removing one is an operator decision, never a self-service tap.
    if exists (select 1 from public.admins a where a.user_id = v_uid) then
        return jsonb_build_object('ok', false, 'error', 'admin_account');
    end if;

    select u.phone, u.email into v_phone, v_email from auth.users u where u.id = v_uid for update;
    if not found then
        return jsonb_build_object('ok', false, 'error', 'not_found');
    end if;

    v_had_trial := exists (select 1 from public.trial_claims c where c.account_id = v_uid);
    if v_had_trial then
        insert into public.deleted_identity_tombstones (identity_hash, kind)
        select h, k from (
            values (public.identity_digest('phone', v_phone), 'phone'),
                   (public.identity_digest('email', v_email), 'email')
        ) as ids(h, k)
        where h is not null
        on conflict (identity_hash) do nothing;

        insert into public.deleted_identity_tombstones (identity_hash, kind)
        select distinct public.identity_digest('device', d), 'device'
        from (
            select c.reported_device_id from public.trial_device_claims c where c.account_id = v_uid
            union
            select c.reported_device_id from public.trial_claims c where c.account_id = v_uid
            union
            select dr.device_id from public.drivers dr where dr.id = v_uid
        ) as devices(d)
        where public.identity_digest('device', d) is not null
        on conflict (identity_hash) do nothing;
    end if;

    update public.audit_log set actor = null where actor = v_uid;
    update public.audit_log set target = null where target = v_uid::text;
    insert into public.audit_log (actor, action, target, detail)
    values (null, 'account_deleted', null, jsonb_build_object('trial_consumed', v_had_trial));

    delete from auth.users where id = v_uid;
    return jsonb_build_object('ok', true);
end;
$$;

revoke all on function public.delete_my_account(text) from public, anon, authenticated;
grant execute on function public.delete_my_account(text) to authenticated;
