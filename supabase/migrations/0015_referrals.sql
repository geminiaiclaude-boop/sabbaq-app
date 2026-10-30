-- Referrals and rewards. Every driver has an invite code; a new driver may attach one within
-- the claim window (default 7 days, before any paid subscription). Attaching only links the
-- two accounts: the referrer earns free days only when the referred driver pays for the first
-- time (an activation code or days granted by the administration), checked again for the same
-- device or phone at that moment. All rules run here, never only in the app.
create schema if not exists private;
revoke all on schema private from public, anon, authenticated;

insert into public.app_config (key, value)
values ('referrals', '{"enabled": true, "reward_days": 7, "claim_window_days": 7}'::jsonb)
on conflict (key) do nothing;

-- Settings with safe bounds whatever the stored JSON holds.
create or replace function private.referral_settings(out enabled boolean, out reward_days integer, out claim_window_days integer)
language sql stable security definer set search_path = pg_catalog, public as $$
    select coalesce((v ->> 'enabled')::boolean, true),
           least(greatest(coalesce((v ->> 'reward_days')::integer, 7), 1), 90),
           least(greatest(coalesce((v ->> 'claim_window_days')::integer, 7), 1), 30)
    from (select coalesce((select value from public.app_config where key = 'referrals'), '{}'::jsonb) v) s
$$;

-- SB- and eight characters without look-alikes (no 0/O, 1/I/L).
create or replace function private.new_referral_code() returns text
language plpgsql volatile security definer set search_path = pg_catalog, public as $$
declare v_chars constant text := '23456789ABCDEFGHJKMNPQRSTUVWXYZ'; v_code text;
begin
    loop
        v_code := 'SB-';
        for i in 1..8 loop
            v_code := v_code || substr(v_chars, 1 + floor(random() * length(v_chars))::integer, 1);
        end loop;
        exit when not exists (select 1 from public.drivers where referral_code = v_code);
    end loop;
    return v_code;
end;
$$;

alter table public.drivers
    add column if not exists referral_code text,
    add column if not exists referred_by uuid references public.drivers (id) on delete set null,
    add column if not exists referred_at timestamptz,
    add column if not exists has_ever_paid boolean not null default false,
    add column if not exists referral_days_earned integer not null default 0;

update public.drivers set referral_code = private.new_referral_code() where referral_code is null;
update public.drivers set has_ever_paid = true where pro_until is not null and not has_ever_paid;
alter table public.drivers alter column referral_code set default private.new_referral_code();
alter table public.drivers alter column referral_code set not null;
create unique index if not exists drivers_referral_code_key on public.drivers (referral_code);
create index if not exists drivers_referred_by on public.drivers (referred_by) where referred_by is not null;

create table if not exists public.referral_claims (
    referred_id      uuid primary key references public.drivers (id) on delete cascade,
    referrer_id      uuid not null references public.drivers (id) on delete cascade,
    status           text not null default 'awaiting_first_payment'
                     check (status in ('awaiting_first_payment', 'rewarded', 'rejected_fraud')),
    rejection_reason text,
    reward_days      integer not null default 0,
    claimed_at       timestamptz not null default now(),
    rewarded_at      timestamptz
);
create index if not exists referral_claims_referrer on public.referral_claims (referrer_id, claimed_at desc);
alter table public.referral_claims enable row level security;
revoke all on public.referral_claims from public, anon, authenticated;

-- Same device or same phone between two drivers: the bound device, any device either account
-- ever claimed a trial on, or the same verified phone.
create or replace function private.referral_same_person(p_a uuid, p_b uuid) returns text
language sql stable security definer set search_path = pg_catalog, public as $$
    select case
        when exists (select 1 from public.drivers a, public.drivers b
                     where a.id = p_a and b.id = p_b and a.phone is not null and a.phone = b.phone) then 'same_phone'
        when exists (select 1 from public.drivers a, public.drivers b
                     where a.id = p_a and b.id = p_b and a.device_id is not null and a.device_id = b.device_id) then 'same_device'
        when exists (select 1 from public.trial_device_claims ca join public.trial_device_claims cb
                         on ca.reported_device_id = cb.reported_device_id
                     where ca.account_id = p_a and cb.account_id = p_b) then 'same_device'
        when exists (select 1 from public.trial_device_claims c join public.drivers d on d.id = p_b
                     where c.account_id = p_a and c.reported_device_id = d.device_id) then 'same_device'
        when exists (select 1 from public.trial_device_claims c join public.drivers d on d.id = p_a
                     where c.account_id = p_b and c.reported_device_id = d.device_id) then 'same_device'
        else null end
$$;

-- Attach an invite code to the calling driver. Returns {"ok":true,"status":"awaiting_first_payment"}
-- or {"ok":false,"code":<reason>}; the app shows the reason in the driver's language.
create or replace function public.claim_referral_code(p_code text) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare
    v_uid uuid := auth.uid();
    v_code text := upper(btrim(coalesce(p_code, '')));
    v_me public.drivers;
    v_referrer public.drivers;
    v_settings record;
    v_same text;
begin
    if v_uid is null then raise exception 'not_authenticated' using errcode = '42501'; end if;
    if v_code !~ '^[A-Z0-9-]{4,20}$' then return jsonb_build_object('ok', false, 'code', 'invalid_code'); end if;
    select * into v_settings from private.referral_settings();
    if not v_settings.enabled then return jsonb_build_object('ok', false, 'code', 'referrals_paused'); end if;

    select * into v_me from public.drivers where id = v_uid for update;
    if not found then raise exception 'unregistered' using errcode = '42501'; end if;
    if v_me.referred_by is not null then return jsonb_build_object('ok', false, 'code', 'already_claimed'); end if;
    if v_me.has_ever_paid then return jsonb_build_object('ok', false, 'code', 'already_paid'); end if;
    if now() > v_me.created_at + make_interval(days => v_settings.claim_window_days) then
        return jsonb_build_object('ok', false, 'code', 'claim_window_expired');
    end if;

    select * into v_referrer from public.drivers where referral_code = v_code;
    if not found or v_referrer.status = 'banned' then return jsonb_build_object('ok', false, 'code', 'invalid_code'); end if;
    if v_referrer.id = v_me.id then return jsonb_build_object('ok', false, 'code', 'self_referral'); end if;
    v_same := private.referral_same_person(v_me.id, v_referrer.id);
    if v_same = 'same_phone' then return jsonb_build_object('ok', false, 'code', 'self_referral'); end if;
    if v_same = 'same_device' then return jsonb_build_object('ok', false, 'code', 'same_device'); end if;

    update public.drivers set referred_by = v_referrer.id, referred_at = now() where id = v_me.id;
    insert into public.referral_claims (referred_id, referrer_id) values (v_me.id, v_referrer.id)
    on conflict (referred_id) do nothing;
    insert into public.audit_log (actor, action, target, detail)
    values (v_me.id, 'referral_claimed', v_referrer.id::text, jsonb_build_object('code', v_code));
    return jsonb_build_object('ok', true, 'status', 'awaiting_first_payment', 'referrer_name', v_referrer.full_name);
end;
$$;
revoke all on function public.claim_referral_code(text) from public, anon;
grant execute on function public.claim_referral_code(text) to authenticated;

-- First paid subscription: pro_until rises for a driver who never paid. Runs inside the same
-- transaction as redeem_code / admin_grant_days. The referrer's own reward also raises
-- pro_until; a transaction-local flag keeps that from counting as the referrer "paying".
create or replace function private.referral_first_payment() returns trigger
language plpgsql security definer set search_path = pg_catalog, public as $$
declare
    v_settings record;
    v_claim public.referral_claims;
    v_referrer public.drivers;
    v_same text;
begin
    if coalesce(current_setting('sabbaq.referral_reward', true), '') = 'on' then return new; end if;
    if new.pro_until is null or new.pro_until <= coalesce(old.pro_until, '-infinity'::timestamptz) then return new; end if;
    if old.has_ever_paid then return new; end if;
    new.has_ever_paid := true;
    if new.referred_by is null then return new; end if;

    select * into v_claim from public.referral_claims where referred_id = new.id for update;
    if not found or v_claim.status <> 'awaiting_first_payment' then return new; end if;
    select * into v_settings from private.referral_settings();
    if not v_settings.enabled then return new; end if;
    select * into v_referrer from public.drivers where id = new.referred_by for update;
    if not found or v_referrer.status = 'banned' then return new; end if;
    v_same := private.referral_same_person(new.id, v_referrer.id);
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
        referral_days_earned = referral_days_earned + v_settings.reward_days
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
drop trigger if exists drivers_referral_first_payment on public.drivers;
create trigger drivers_referral_first_payment before update of pro_until on public.drivers
for each row execute function private.referral_first_payment();

-- The calling driver's referral page: own code, whether a code may still be entered, totals
-- and the drivers who used the code with their state. A phone is shown only when the referred
-- driver has a verified one (for the "encourage on WhatsApp" button).
create or replace function public.my_referrals() returns jsonb
language plpgsql stable security definer set search_path = pg_catalog, public as $$
declare v_uid uuid := auth.uid(); v_me public.drivers; v_settings record;
begin
    if v_uid is null then raise exception 'not_authenticated' using errcode = '42501'; end if;
    select * into v_me from public.drivers where id = v_uid;
    if not found then raise exception 'unregistered' using errcode = '42501'; end if;
    select * into v_settings from private.referral_settings();
    return jsonb_build_object(
        'enabled', v_settings.enabled,
        'my_code', v_me.referral_code,
        'reward_days', v_settings.reward_days,
        'claim_window_days', v_settings.claim_window_days,
        'already_claimed', v_me.referred_by is not null,
        'can_claim', v_settings.enabled and v_me.referred_by is null and not v_me.has_ever_paid
                     and now() <= v_me.created_at + make_interval(days => v_settings.claim_window_days),
        'days_earned', v_me.referral_days_earned,
        'invited', (select count(*) from public.referral_claims where referrer_id = v_uid),
        'rewarded', (select count(*) from public.referral_claims where referrer_id = v_uid and status = 'rewarded'),
        'referrals', coalesce((
            select jsonb_agg(jsonb_build_object(
                'name', d.full_name,
                'phone', d.phone,
                'claimed_at', c.claimed_at,
                'status', c.status,
                'reward_days', c.reward_days,
                'subscription', case
                    when not d.has_ever_paid then 'awaiting_first_payment'
                    when d.pro_until > now() then 'active' else 'expired' end,
                'pro_until', d.pro_until) order by c.claimed_at desc)
            from public.referral_claims c join public.drivers d on d.id = c.referred_id
            where c.referrer_id = v_uid), '[]'::jsonb));
end;
$$;
revoke all on function public.my_referrals() from public, anon;
grant execute on function public.my_referrals() to authenticated;

-- Administration: switch referrals on or off, set reward and claim window, read totals and the
-- top referrers (for agent codes).
create or replace function public.admin_referral_settings(p_enabled boolean default null, p_reward_days integer default null,
                                                         p_claim_window_days integer default null) returns jsonb
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v_current jsonb; v_settings record;
begin
    if not public.is_admin() then raise exception 'not_admin' using errcode = '42501'; end if;
    if p_reward_days is not null and p_reward_days not between 1 and 90
        or p_claim_window_days is not null and p_claim_window_days not between 1 and 30 then
        raise exception 'invalid_setting' using errcode = '22023';
    end if;
    v_current := coalesce((select value from public.app_config where key = 'referrals'), '{}'::jsonb);
    insert into public.app_config (key, value, updated_at)
    values ('referrals', v_current
        || jsonb_strip_nulls(jsonb_build_object('enabled', p_enabled, 'reward_days', p_reward_days, 'claim_window_days', p_claim_window_days)), now())
    on conflict (key) do update set value = excluded.value, updated_at = now();
    select * into v_settings from private.referral_settings();
    return jsonb_build_object(
        'enabled', v_settings.enabled, 'reward_days', v_settings.reward_days, 'claim_window_days', v_settings.claim_window_days,
        'claims', (select count(*) from public.referral_claims),
        'rewarded', (select count(*) from public.referral_claims where status = 'rewarded'),
        'rejected', (select count(*) from public.referral_claims where status = 'rejected_fraud'),
        'top', coalesce((select jsonb_agg(t order by t.rewarded desc, t.invited desc) from (
            select d.full_name as name, d.referral_code as code,
                   count(*) as invited, count(*) filter (where c.status = 'rewarded') as rewarded
            from public.referral_claims c join public.drivers d on d.id = c.referrer_id
            group by d.id order by 4 desc, 3 desc limit 20) t), '[]'::jsonb));
end;
$$;
revoke all on function public.admin_referral_settings(boolean, integer, integer) from public, anon;
grant execute on function public.admin_referral_settings(boolean, integer, integer) to authenticated;

-- Administration: give an agent or marketer a memorable code (e.g. SB-AGENT01).
create or replace function public.admin_set_referral_code(p_driver uuid, p_code text) returns text
language plpgsql security definer set search_path = pg_catalog, public as $$
declare v_code text := upper(btrim(coalesce(p_code, '')));
begin
    if not public.is_admin() then raise exception 'not_admin' using errcode = '42501'; end if;
    if v_code !~ '^[A-Z0-9-]{4,20}$' then raise exception 'invalid_code' using errcode = '22023'; end if;
    if exists (select 1 from public.drivers where referral_code = v_code and id <> p_driver) then
        raise exception 'code_taken' using errcode = '23505';
    end if;
    update public.drivers set referral_code = v_code where id = p_driver;
    if not found then raise exception 'driver_not_found' using errcode = 'P0002'; end if;
    insert into public.audit_log (actor, action, target, detail)
    values (auth.uid(), 'admin_set_referral_code', p_driver::text, jsonb_build_object('code', v_code));
    return v_code;
end;
$$;
revoke all on function public.admin_set_referral_code(uuid, text) from public, anon;
grant execute on function public.admin_set_referral_code(uuid, text) to authenticated;
