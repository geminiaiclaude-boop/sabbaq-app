-- Self-service move of an account to a new device.
--
-- An account is bound to one device. Android derives the device identifier per app
-- signing key, so a new phone, a factory reset, or switching between the direct and
-- the Play build presents a new identifier for the same person. Without a way to move
-- the account they are locked out with «الحساب مرتبط بجهاز آخر». The signed-in owner may
-- move the account themselves, at most twice per 30 days: enough for a new phone or a
-- reinstall, not enough to share one subscription across devices. The previous device
-- stops at its next license check.

create or replace function public.transfer_device(p_device_id text) returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    v_uid    uuid := auth.uid();
    v_device text;
    v_d      public.drivers%rowtype;
    v_recent integer;
    v_oldest timestamptz;
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
        return jsonb_build_object('ok', false, 'error', 'account_blocked');
    end if;

    -- Already here (or never bound): nothing to move and nothing counted.
    if v_d.device_id is null or v_d.device_id = v_device then
        update public.drivers
        set device_id = v_device, device_bound_at = coalesce(device_bound_at, now())
        where id = v_uid;
        return jsonb_build_object('ok', true, 'transferred', false);
    end if;

    select count(*), min(a.at) into v_recent, v_oldest
    from public.audit_log a
    where a.actor = v_uid and a.action = 'device_transferred' and a.at > now() - interval '30 days';
    if v_recent >= 2 then
        return jsonb_build_object('ok', false, 'error', 'transfer_limit',
                                  'retry_after', v_oldest + interval '30 days');
    end if;

    update public.drivers set device_id = v_device, device_bound_at = now() where id = v_uid;
    insert into public.audit_log (actor, action, target)
    values (v_uid, 'device_transferred', v_uid::text);
    return jsonb_build_object('ok', true, 'transferred', true);
end;
$$;

revoke all on function public.transfer_device(text) from public, anon, authenticated;
grant execute on function public.transfer_device(text) to authenticated;
