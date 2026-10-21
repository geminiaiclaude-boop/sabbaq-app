-- Subscription purchase over WhatsApp.
--
-- Drivers obtain an activation code by messaging the administration on WhatsApp
-- from inside the app. The number is part of the commercial policy, so the owner
-- changes it from the admin panel without a new app release.

-- Digits only, international format without "+" or leading zeros (wa.me format),
-- e.g. 9665XXXXXXXX. 8 to 15 digits per E.164.
alter table public.commercial_policy
    add column if not exists admin_whatsapp text
        check (admin_whatsapp is null or admin_whatsapp ~ '^[1-9][0-9]{7,14}$');

create or replace function public.admin_set_whatsapp(p_number text) returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    v_number text;
begin
    perform public.require_admin();
    if p_number is not null and octet_length(p_number) > 64 then
        raise exception 'bad_whatsapp_number' using errcode = '22023';
    end if;
    -- Accept what an admin naturally types (+966 5x xxx xxxx, 05xxxxxxxx) and store wa.me digits.
    v_number := regexp_replace(coalesce(p_number, ''), '[[:space:]()+-]', '', 'g');
    if v_number ~ '^05[0-9]{8}$' then
        v_number := '966' || substr(v_number, 2);
    elsif v_number ~ '^00[1-9]' then
        v_number := substr(v_number, 3);
    end if;
    v_number := nullif(v_number, '');
    if v_number is not null and v_number !~ '^[1-9][0-9]{7,14}$' then
        raise exception 'bad_whatsapp_number' using errcode = '22023';
    end if;

    update public.commercial_policy set admin_whatsapp = v_number, updated_at = now() where singleton;
    if not found then
        raise exception 'commercial_policy_missing' using errcode = '55000';
    end if;
    insert into public.audit_log (actor, action, target, detail)
    values (auth.uid(), 'set_admin_whatsapp', 'singleton', jsonb_build_object('set', v_number is not null));
    return jsonb_build_object('ok', true, 'admin_whatsapp', v_number);
end;
$$;

revoke all on function public.admin_set_whatsapp(text) from public, anon, authenticated;
grant execute on function public.admin_set_whatsapp(text) to authenticated;

-- The offer and the admin view both carry the number.
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
        'purchase_contact_url', p.purchase_contact_url,
        'admin_whatsapp', p.admin_whatsapp
    )
    from public.commercial_policy p
    where p.singleton;
$$;

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
        'admin_whatsapp', v_policy.admin_whatsapp,
        'updated_at', v_policy.updated_at
    );
end;
$$;
