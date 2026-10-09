-- دالة للمشرف لفك ربط جهاز السائق
create or replace function public.admin_reset_device(p_driver_id uuid) returns void
language plpgsql security definer set search_path = public as $$
begin
    perform public.require_admin();
    update public.drivers set device_id = null where id = p_driver_id;
    insert into public.audit_log (actor, action, target) values (auth.uid(), 'reset_device', p_driver_id::text);
end $$;

-- دالة للسائق لربط جهازه إذا كان حسابه فعالاً وليس لديه جهاز
create or replace function public.bind_device(p_device_id text) returns boolean
language plpgsql security definer set search_path = public as $$
declare
    v_uid uuid := auth.uid();
    v_d public.drivers%rowtype;
begin
    if v_uid is null then raise exception 'not_authenticated' using errcode = '42501'; end if;
    select * into v_d from public.drivers where id = v_uid for update;
    
    if not found then return false; end if;
    if v_d.status not in ('active', 'pending') then return false; end if;
    
    if v_d.device_id is null then
        update public.drivers set device_id = p_device_id, device_bound_at = now() where id = v_uid;
        return true;
    end if;
    
    return v_d.device_id = p_device_id;
end $$;